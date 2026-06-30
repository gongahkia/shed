import AppKit
import Foundation

struct ChangelogVersionStore {
    static let key = "olly.lastShownChangelogVersion"

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func shouldShow(version: String) -> Bool {
        defaults.string(forKey: Self.key) != version
    }

    func markShown(version: String) {
        defaults.set(version, forKey: Self.key)
    }
}

enum ChangelogResources {
    static func loadMarkdown(bundle: Bundle = .module) -> String? {
        guard let url = bundle.url(forResource: "CHANGELOG", withExtension: "md") else {
            return nil
        }
        return try? String(contentsOf: url, encoding: .utf8)
    }
}

enum ChangelogMarkdownRenderer {
    static func render(_ markdown: String) -> NSAttributedString {
        do {
            let attributed = try AttributedString(markdown: markdown)
            return NSAttributedString(attributed)
        } catch {
            return NSAttributedString(string: markdown)
        }
    }
}

final class ChangelogWindowController: NSWindowController, NSWindowDelegate {
    var onClose: (() -> Void)?

    private var didClose = false

    init(markdown: String, version: String) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 660, height: 560),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = L10n.s("Olly Changelog", "changelog window title")
        window.level = .modalPanel
        super.init(window: window)
        window.delegate = self
        window.contentView = makeContent(markdown: markdown, version: version)
        window.center()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func showModal() {
        guard let window else {
            return
        }
        showWindow(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
        NSApplication.shared.runModal(for: window)
    }

    func windowWillClose(_ notification: Notification) {
        finish()
    }

    private func makeContent(markdown: String, version: String) -> NSView {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 12
        root.edgeInsets = NSEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)

        let title = NSTextField(labelWithString: L10n.s("What's New in Olly", "changelog title"))
        title.font = .systemFont(ofSize: 20, weight: .semibold)
        root.addArrangedSubview(title)

        let subtitle = NSTextField(labelWithString: L10n.f("Version %@", "changelog version", version))
        subtitle.textColor = .secondaryLabelColor
        subtitle.font = .systemFont(ofSize: 12, weight: .medium)
        root.addArrangedSubview(subtitle)
        root.addArrangedSubview(scrollView(markdown: markdown))
        root.addArrangedSubview(buttonRow())
        return root
    }

    private func scrollView(markdown: String) -> NSScrollView {
        let textView = NSTextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.drawsBackground = false
        textView.textContainerInset = NSSize(width: 0, height: 8)
        textView.textStorage?.setAttributedString(ChangelogMarkdownRenderer.render(markdown))

        let scrollView = NSScrollView()
        scrollView.hasVerticalScroller = true
        scrollView.documentView = textView
        scrollView.heightAnchor.constraint(equalToConstant: 420).isActive = true
        return scrollView
    }

    private func buttonRow() -> NSView {
        let row = NSStackView()
        row.orientation = .horizontal
        row.addArrangedSubview(NSView())
        let button = NSButton(
            title: L10n.s("Close", "changelog close button"),
            target: self,
            action: #selector(closeFromButton)
        )
        button.bezelStyle = .rounded
        row.addArrangedSubview(button)
        return row
    }

    @objc private func closeFromButton() {
        close()
    }

    private func finish() {
        guard !didClose else {
            return
        }
        didClose = true
        NSApplication.shared.stopModal()
        onClose?()
    }
}
