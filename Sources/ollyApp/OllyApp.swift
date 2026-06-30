import AppKit
import Foundation
import ollyCore
import ollyKit
import ollyLayouts
import ollyRuntime

@main
enum OllyApp {
    private static let delegate = OllyAppDelegate()

    static func main() {
        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)
        application.delegate = delegate
        application.run()
    }
}

final class OllyAppDelegate: NSObject, NSApplicationDelegate {
    private var statusController: OllyStatusMenuController?
    private var onboardingController: AXOnboardingWindowController?
    private let overviewController = OverviewModeController()
    private let commandPaletteController = CommandPaletteController()
    private let hotKeyDiagnostics = HotKeyStartupDiagnostics()
    private let runtime = OllyRuntime()
    private lazy var focusRingController = FocusRingController(runtime: runtime)
    private lazy var dragSnapOverlayController = DragSnapOverlayController(runtime: runtime)
    private lazy var gridOverlayController = GridOverlayController(runtime: runtime)
    private lazy var settingsWindowController = SettingsWindowController(runtime: runtime)
    private lazy var runtimeEventStatusController = RuntimeEventStatusController(
        runtime: runtime
    ) { [weak self] snapshot in
        self?.renderRuntimeSnapshot(snapshot)
    }
    private var overviewKeyMonitor: OverviewKeyHoldMonitor?
    private var isTerminating = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusController = OllyStatusMenuController(
            onOpenSettings: { [weak self] in
                self?.settingsWindowController.show()
            },
            onOpenCommandPalette: { [weak self] in
                self?.commandPaletteController.show()
            },
            onRefreshStatus: { [weak self] in
                self?.refreshStatusFromRuntime()
            }
        )
        statusController?.install()
        installOverviewMode()
        focusRingController.start()
        dragSnapOverlayController.start()
        gridOverlayController.start()
        runtimeEventStatusController.start()
        hotKeyDiagnostics.run()
        showOnboardingIfNeeded()
        startRuntime()
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        _ = sender
        guard !isTerminating else {
            return .terminateNow
        }
        isTerminating = true
        focusRingController.stop()
        dragSnapOverlayController.stop()
        gridOverlayController.stop()
        runtimeEventStatusController.stop()
        overviewKeyMonitor?.remove()
        Task { [runtime, weak self] in
            await runtime.stop()
            await MainActor.run {
                self?.statusController?.remove()
                NSApplication.shared.reply(toApplicationShouldTerminate: true)
            }
        }
        return .terminateLater
    }

    func applicationWillTerminate(_ notification: Notification) {
        focusRingController.stop()
        dragSnapOverlayController.stop()
        gridOverlayController.stop()
        runtimeEventStatusController.stop()
        overviewKeyMonitor?.remove()
        statusController?.remove()
    }

    private func installOverviewMode() {
        let monitor = OverviewKeyHoldMonitor(
            onActivate: { [weak overviewController] in
                Task { @MainActor in
                    overviewController?.show()
                }
            },
            onDeactivate: { [weak overviewController] in
                Task { @MainActor in
                    overviewController?.hide()
                }
            }
        )
        monitor.install()
        overviewKeyMonitor = monitor
    }

    private func showOnboardingIfNeeded() {
        guard AXPermission.status(prompt: false) == .missing else {
            return
        }

        let controller = AXOnboardingWindowController()
        controller.onPermissionGranted = { [weak self] in
            self?.refreshStatusFromRuntime()
            self?.onboardingController = nil
        }
        onboardingController = controller
        controller.showWindow(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    private func startRuntime() {
        Task { [runtime, weak self] in
            do {
                try await runtime.start()
                let snapshot = await runtime.menuSnapshot()
                await self?.renderRuntimeSnapshot(snapshot)
            } catch {
                await self?.renderRuntimeError(String(describing: error))
            }
        }
    }

    private func refreshStatusFromRuntime() {
        Task { [runtime, weak self] in
            let snapshot = await runtime.menuSnapshot()
            await self?.renderRuntimeSnapshot(snapshot)
        }
    }

    @MainActor private func renderRuntimeSnapshot(_ snapshot: OllyRuntimeMenuSnapshot) {
        statusController?.apply(snapshot: snapshot)
    }

    @MainActor private func renderRuntimeError(_ message: String) {
        statusController?.apply(error: message)
    }
}

final class OllyStatusMenuController: NSObject {
    private let displayMonitor: DisplayMonitor
    private let statusItem: NSStatusItem
    private let onOpenSettings: () -> Void
    private let onOpenCommandPalette: () -> Void
    private let onRefreshStatus: () -> Void
    private var state = OllyMenuState.default

    init(
        displayMonitor: DisplayMonitor = DisplayMonitor(),
        statusItem: NSStatusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength),
        onOpenSettings: @escaping () -> Void = {},
        onOpenCommandPalette: @escaping () -> Void = {},
        onRefreshStatus: @escaping () -> Void = {}
    ) {
        self.displayMonitor = displayMonitor
        self.statusItem = statusItem
        self.onOpenSettings = onOpenSettings
        self.onOpenCommandPalette = onOpenCommandPalette
        self.onRefreshStatus = onRefreshStatus
        super.init()
    }

    func install() {
        configureButton()
        refreshState()
    }

    func remove() {
        NSStatusBar.system.removeStatusItem(statusItem)
    }

    private func configureButton() {
        if let button = statusItem.button {
            button.image = NSImage(
                systemSymbolName: "rectangle.3.group",
                accessibilityDescription: "Olly"
            )
            button.imagePosition = .imageOnly
            button.toolTip = "Olly"
        } else {
            statusItem.button?.title = "Olly"
        }
    }

    func refreshState() {
        state = makeState()
        rebuildMenu()
    }

    func apply(snapshot: OllyRuntimeMenuSnapshot) {
        state = OllyMenuState(snapshot: snapshot)
        rebuildMenu()
    }

    func apply(error: String) {
        state = state.with(lastError: error, isIPCServerRunning: false)
        rebuildMenu()
    }

    private func makeState() -> OllyMenuState {
        let displays = displayMonitor.displays()
        let activeDisplay = displays.first(where: \.isMain) ?? displays.first
        return OllyMenuState(
            displayName: activeDisplay?.localizedName ?? "No display",
            displayID: activeDisplay?.id,
            activeTags: [0],
            currentEngineID: FloatingLayoutEngine.engineID,
            axStatus: AXPermission.status(prompt: false),
            isIPCServerRunning: false,
            lastError: nil
        )
    }

    private func rebuildMenu() {
        let menu = NSMenu()
        menu.autoenablesItems = false
        menu.addItem(disabledItem("Display: \(state.displayLabel)"))
        menu.addItem(disabledItem("Tags: \(state.tagLabel)"))
        menu.addItem(disabledItem("Engine: \(state.currentEngineID.rawValue)"))
        menu.addItem(disabledItem("AX: \(state.axLabel)"))
        menu.addItem(disabledItem("IPC: \(state.ipcLabel)"))
        if let error = state.lastErrorLabel {
            menu.addItem(disabledItem("Error: \(error)"))
        }
        menu.addItem(.separator())
        menu.addItem(actionItem("Refresh Status", #selector(refreshStatus)))
        menu.addItem(actionItem("Settings...", #selector(openSettings)))
        menu.addItem(actionItem("Command Palette...", #selector(openCommandPalette)))
        menu.addItem(actionItem("Open Config.swift", #selector(openConfig)))
        menu.addItem(actionItem("Copy `ollyctl state`", #selector(copyStateCommand)))
        menu.addItem(.separator())
        menu.addItem(actionItem("Quit Olly", #selector(quit)))
        statusItem.menu = menu
    }

    private func disabledItem(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func actionItem(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        item.isEnabled = true
        return item
    }

    @objc private func refreshStatus() {
        onRefreshStatus()
    }

    @objc private func openSettings() {
        onOpenSettings()
    }

    @objc private func openCommandPalette() {
        onOpenCommandPalette()
    }

    @objc private func openConfig() {
        let url = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config", isDirectory: true)
            .appendingPathComponent("olly", isDirectory: true)
            .appendingPathComponent("Config.swift")
        if FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.open(url)
        } else {
            NSWorkspace.shared.activateFileViewerSelecting([url.deletingLastPathComponent()])
        }
    }

    @objc private func copyStateCommand() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString("ollyctl state", forType: .string)
    }

    @objc private func quit() {
        NSApplication.shared.terminate(nil)
    }
}

struct OllyMenuState: Equatable {
    let displayName: String
    let displayID: DisplayID?
    let activeTags: [UInt8]
    let currentEngineID: LayoutEngineID
    let axStatus: AXPermissionStatus
    let isIPCServerRunning: Bool
    let lastError: String?

    init(
        displayName: String,
        displayID: DisplayID?,
        activeTags: [UInt8],
        currentEngineID: LayoutEngineID,
        axStatus: AXPermissionStatus,
        isIPCServerRunning: Bool,
        lastError: String?
    ) {
        self.displayName = displayName
        self.displayID = displayID
        self.activeTags = activeTags
        self.currentEngineID = currentEngineID
        self.axStatus = axStatus
        self.isIPCServerRunning = isIPCServerRunning
        self.lastError = lastError
    }

    static let `default` = OllyMenuState(
        displayName: "No display",
        displayID: nil,
        activeTags: [0],
        currentEngineID: FloatingLayoutEngine.engineID,
        axStatus: .missing,
        isIPCServerRunning: false,
        lastError: nil
    )

    init(snapshot: OllyRuntimeMenuSnapshot) {
        self.init(
            displayName: snapshot.displayName,
            displayID: snapshot.displayID,
            activeTags: snapshot.activeTags,
            currentEngineID: snapshot.currentEngineID,
            axStatus: snapshot.axStatus,
            isIPCServerRunning: snapshot.isIPCServerRunning,
            lastError: snapshot.lastError
        )
    }

    func with(lastError: String?, isIPCServerRunning: Bool) -> OllyMenuState {
        OllyMenuState(
            displayName: displayName,
            displayID: displayID,
            activeTags: activeTags,
            currentEngineID: currentEngineID,
            axStatus: axStatus,
            isIPCServerRunning: isIPCServerRunning,
            lastError: lastError
        )
    }

    var displayLabel: String {
        if let displayID {
            return "\(displayName) (\(displayID))"
        }
        return displayName
    }

    var tagLabel: String {
        activeTags.map { String(Int($0) + 1) }.joined(separator: ", ")
    }

    var axLabel: String {
        switch axStatus {
        case .trusted:
            return "Trusted"
        case .missing:
            return "Missing"
        }
    }

    var ipcLabel: String {
        isIPCServerRunning ? "Running" : "Stopped"
    }

    var lastErrorLabel: String? {
        guard let lastError, !lastError.isEmpty else {
            return nil
        }
        let maxLength = 120
        guard lastError.count > maxLength else {
            return lastError
        }
        return "\(lastError.prefix(maxLength))..."
    }
}
