import AppKit
import Foundation
import ollyKit

final class AXOnboardingWindowController: NSWindowController, NSWindowDelegate {
    var onPermissionGranted: (() -> Void)?

    private let statusLabel = NSTextField(labelWithString: "")
    private var didGrantPermission = false

    init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 520, height: 340),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = L10n.s("Olly Setup", "AX onboarding window title")
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.delegate = self
        window.contentView = makeContentView()
        window.center()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func showWindow(_ sender: Any?) {
        super.showWindow(sender)
        AXPermission.requestSystemPrompt()
        refreshStatus()
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if AXPermission.status(prompt: false) == .trusted {
            didGrantPermission = true
            onPermissionGranted?()
            return true
        }

        NSApplication.shared.terminate(nil)
        return false
    }

    private func makeContentView() -> NSView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.edgeInsets = NSEdgeInsets(top: 24, left: 24, bottom: 24, right: 24)

        stack.addArrangedSubview(titleLabel())
        stack.addArrangedSubview(bodyLabel())
        stack.addArrangedSubview(deepLinkGroup())
        stack.addArrangedSubview(statusLabel)
        stack.addArrangedSubview(buttonRow())

        statusLabel.font = .systemFont(ofSize: NSFont.systemFontSize)
        return stack
    }

    private func titleLabel() -> NSTextField {
        let label = NSTextField(labelWithString: L10n.s(
            "Grant Accessibility permission",
            "AX onboarding title"
        ))
        label.font = .boldSystemFont(ofSize: 20)
        return label
    }

    private func bodyLabel() -> NSTextField {
        let label = NSTextField(wrappingLabelWithString: L10n.s("""
        Olly needs Accessibility permission before it can inspect, focus, move, or resize windows. Open \
        System Settings, enable Olly in Privacy & Security > Accessibility, then return and refresh.
        """, "AX onboarding body"))
        label.preferredMaxLayoutWidth = 460
        return label
    }

    private func deepLinkGroup() -> NSView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 6

        let caption = NSTextField(labelWithString: L10n.s("Deeplink", "AX deep link label"))
        caption.font = .boldSystemFont(ofSize: NSFont.smallSystemFontSize)

        let field = NSTextField(labelWithString: AXPermission.accessibilitySettingsDeepLink)
        field.isSelectable = true
        field.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        field.lineBreakMode = .byTruncatingMiddle
        field.preferredMaxLayoutWidth = 460

        stack.addArrangedSubview(caption)
        stack.addArrangedSubview(field)
        return stack
    }

    private func buttonRow() -> NSView {
        let stack = NSStackView()
        stack.orientation = .horizontal
        stack.alignment = .centerY
        stack.spacing = 10

        stack.addArrangedSubview(button(L10n.s("Open System Settings", "AX settings button"), #selector(openSettings)))
        stack.addArrangedSubview(button(L10n.s("Copy Deeplink", "AX copy deep link button"), #selector(copyDeepLink)))
        stack.addArrangedSubview(button(L10n.s("Refresh", "AX refresh button"), #selector(refreshStatus)))
        stack.addArrangedSubview(button(L10n.s("Quit", "AX quit button"), #selector(quit)))
        return stack
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    @objc private func openSettings() {
        AXPermission.openAccessibilitySettings()
    }

    @objc private func copyDeepLink() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(AXPermission.accessibilitySettingsDeepLink, forType: .string)
    }

    @objc private func refreshStatus() {
        if AXPermission.status(prompt: false) == .trusted {
            complete()
        } else {
            statusLabel.stringValue = L10n.s(
                "Waiting for Accessibility permission...",
                "AX permission pending status"
            )
        }
    }

    @objc private func quit() {
        NSApplication.shared.terminate(nil)
    }

    private func complete() {
        didGrantPermission = true
        statusLabel.stringValue = L10n.s("Accessibility permission granted.", "AX permission granted status")
        onPermissionGranted?()
        close()
    }
}
