import ApplicationServices
import AppKit

public enum AXPermissionStatus: Equatable, Sendable {
    case trusted
    case missing
}

public enum AXPermission {
    public static func status(prompt: Bool = false) -> AXPermissionStatus {
        checkIsTrusted(prompt: prompt) ? .trusted : .missing
    }

    public static var isTrusted: Bool {
        status(prompt: false) == .trusted
    }

    @discardableResult
    public static func requestSystemPrompt() -> AXPermissionStatus {
        status(prompt: true)
    }

    public static func refresh() async -> AXPermissionStatus {
        await Task.yield()
        return status(prompt: false)
    }

    @MainActor
    @discardableResult
    public static func presentOnboardingSheetIfNeeded(
        attachedTo parentWindow: NSWindow? = nil
    ) async -> AXPermissionStatus {
        let currentStatus = requestSystemPrompt()
        guard currentStatus == .missing else {
            return currentStatus
        }

        let targetWindow = parentWindow ?? NSApplication.shared.keyWindow
        let response = await makeOnboardingAlert().ollyResponse(attachedTo: targetWindow)
        if response == .alertFirstButtonReturn {
            openAccessibilitySettings()
        }
        return await refresh()
    }

    private static func checkIsTrusted(prompt: Bool) -> Bool {
        let promptKey = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        let options = [promptKey: prompt] as CFDictionary
        return AXSignpost.interval("ax.permission.status") {
            AXIsProcessTrustedWithOptions(options)
        }
    }

    @MainActor
    private static func makeOnboardingAlert() -> NSAlert {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "Accessibility Permission Required"
        alert.informativeText = """
        Olly needs Accessibility permission to inspect, move, and resize windows. Grant access in System \
        Settings, then return to Olly and refresh.
        """
        alert.addButton(withTitle: "Open System Settings")
        alert.addButton(withTitle: "Not Now")
        return alert
    }

    @MainActor
    private static func openAccessibilitySettings() {
        let settingsURL = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
        guard let url = URL(string: settingsURL) else {
            return
        }
        NSWorkspace.shared.open(url)
    }
}

private extension NSAlert {
    @MainActor
    func ollyResponse(attachedTo parentWindow: NSWindow?) async -> NSApplication.ModalResponse {
        guard let parentWindow else {
            return runModal()
        }

        return await withCheckedContinuation { continuation in
            beginSheetModal(for: parentWindow) { response in
                continuation.resume(returning: response)
            }
        }
    }
}
