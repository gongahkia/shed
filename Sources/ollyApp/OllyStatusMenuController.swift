import AppKit
import Foundation
import ollyCore
import ollyKit
import ollyLayouts
import ollyRuntime

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
                accessibilityDescription: L10n.s("Olly", "status bar item accessibility label")
            )
            button.imagePosition = .imageOnly
            button.toolTip = L10n.s("Olly", "status bar item tooltip")
        } else {
            statusItem.button?.title = L10n.s("Olly", "status bar item fallback title")
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
            displayName: activeDisplay?.localizedName ?? L10n.s("No display", "status menu missing display"),
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
        menu.addItem(disabledItem(L10n.f("Display: %@", "status menu display row", state.displayLabel)))
        menu.addItem(disabledItem(L10n.f("Tags: %@", "status menu tags row", state.tagLabel)))
        menu.addItem(disabledItem(L10n.f("Engine: %@", "status menu engine row", state.currentEngineID.rawValue)))
        menu.addItem(disabledItem(L10n.f("AX: %@", "status menu AX row", state.axLabel)))
        menu.addItem(disabledItem(L10n.f("IPC: %@", "status menu IPC row", state.ipcLabel)))
        if let error = state.lastErrorLabel {
            menu.addItem(disabledItem(L10n.f("Error: %@", "status menu error row", error)))
        }
        menu.addItem(.separator())
        menu.addItem(actionItem(L10n.s("Refresh Status", "status menu refresh item"), #selector(refreshStatus)))
        menu.addItem(actionItem(L10n.s("Settings...", "status menu settings item"), #selector(openSettings)))
        menu.addItem(actionItem(L10n.s("Command Palette...", "palette item"), #selector(openCommandPalette)))
        menu.addItem(actionItem(L10n.s("Open Config.swift", "status menu open config item"), #selector(openConfig)))
        menu.addItem(actionItem(L10n.s("Copy `ollyctl state`", "copy state item"), #selector(copyStateCommand)))
        menu.addItem(.separator())
        menu.addItem(actionItem(L10n.s("Quit Olly", "status menu quit item"), #selector(quit)))
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
        displayName: L10n.s("No display", "status menu missing display"),
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
            return L10n.s("Trusted", "AX trusted status")
        case .missing:
            return L10n.s("Missing", "AX missing status")
        }
    }

    var ipcLabel: String {
        isIPCServerRunning ? L10n.s("Running", "IPC running status") : L10n.s("Stopped", "IPC stopped status")
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
