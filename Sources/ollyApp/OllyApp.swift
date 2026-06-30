import AppKit
import Foundation
import ollyCore
import ollyDSL
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
    private var firstRunController: FirstRunWindowController?
    private let overviewController = OverviewModeController()
    private let commandPaletteController = CommandPaletteController()
    private let hotKeyDiagnostics = HotKeyStartupDiagnostics()
    private let runtime = OllyRuntime()
    private lazy var focusRingController = FocusRingController(runtime: runtime)
    private lazy var dragSnapOverlayController = DragSnapOverlayController(runtime: runtime)
    private lazy var gridOverlayController = GridOverlayController(runtime: runtime)
    private lazy var cheatsheetController = CheatsheetController(runtime: runtime)
    private lazy var altTabSwitcherController = AltTabSwitcherController(runtime: runtime)
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
        cheatsheetController.start()
        altTabSwitcherController.start()
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
        cheatsheetController.stop()
        altTabSwitcherController.stop()
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
        cheatsheetController.stop()
        altTabSwitcherController.stop()
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
        if !FileManager.default.fileExists(atPath: ConfigLoader.defaultSourceURL().path) {
            showFirstRun()
            return
        }
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

    private func showFirstRun() {
        let controller = FirstRunWindowController()
        controller.onComplete = { [weak self] in
            self?.firstRunController = nil
            self?.reloadRuntimeAfterFirstRun()
        }
        firstRunController = controller
        controller.showWindow(nil)
    }

    private func reloadRuntimeAfterFirstRun() {
        Task { [runtime, weak self] in
            try? await runtime.reloadConfig()
            let snapshot = await runtime.menuSnapshot()
            await self?.renderRuntimeSnapshot(snapshot)
        }
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
