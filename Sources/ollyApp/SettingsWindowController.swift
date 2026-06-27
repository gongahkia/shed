import AppKit
import Foundation
import ollyDSL

final class SettingsWindowController: NSWindowController {
    private let sourceURL: URL
    private let reloader: ConfigReloader
    private let fileManager: FileManager
    private let reloadQueue = DispatchQueue(label: "dev.olly.app.settings.reload", qos: .userInitiated)
    private let pathLabel = NSTextField(labelWithString: "")
    private let statusLabel = NSTextField(labelWithString: "")
    private let errorTextView = NSTextView()
    private let reloadButton = NSButton(title: "Reload", target: nil, action: nil)
    private var playgroundController: ConfigPlaygroundWindowController?

    init(
        sourceURL: URL = ConfigLoader.defaultSourceURL(),
        reloader: ConfigReloader? = nil,
        fileManager: FileManager = .default
    ) {
        self.sourceURL = sourceURL
        self.fileManager = fileManager
        self.reloader = reloader ?? ConfigReloader(loader: Self.defaultLoader(sourceURL: sourceURL))
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 620, height: 420),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        super.init(window: window)
        configureWindow(window)
        configureContent()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    func show() {
        pathLabel.stringValue = sourceURL.path
        window?.center()
        window?.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    private static func defaultLoader(sourceURL: URL) -> ConfigLoader {
        ConfigLoader(sourceURL: sourceURL, moduleSearchPaths: defaultModuleSearchPaths())
    }

    private static func defaultModuleSearchPaths() -> [URL] {
        let modulesURL = Bundle.main.bundleURL
            .deletingLastPathComponent()
            .appendingPathComponent("Modules", isDirectory: true)
        return [modulesURL]
    }

    private func configureWindow(_ window: NSWindow) {
        window.title = "Olly Settings"
        window.isReleasedWhenClosed = false
    }

    private func configureContent() {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 12
        root.edgeInsets = NSEdgeInsets(top: 18, left: 18, bottom: 18, right: 18)
        root.translatesAutoresizingMaskIntoConstraints = false

        let titleLabel = NSTextField(labelWithString: "Config.swift")
        titleLabel.font = .systemFont(ofSize: 20, weight: .semibold)
        pathLabel.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        pathLabel.textColor = .secondaryLabelColor
        pathLabel.lineBreakMode = .byTruncatingMiddle
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.stringValue = "No reload run yet"

        let buttonRow = NSStackView()
        buttonRow.orientation = .horizontal
        buttonRow.spacing = 8
        buttonRow.addArrangedSubview(button("Open in Editor", #selector(openConfig)))
        reloadButton.target = self
        reloadButton.action = #selector(reloadConfig)
        buttonRow.addArrangedSubview(reloadButton)
        buttonRow.addArrangedSubview(button("Playground...", #selector(openPlayground)))
        buttonRow.addArrangedSubview(NSView())

        let errorScrollView = makeErrorScrollView()
        root.addArrangedSubview(titleLabel)
        root.addArrangedSubview(pathLabel)
        root.addArrangedSubview(buttonRow)
        root.addArrangedSubview(statusLabel)
        root.addArrangedSubview(errorScrollView)
        window?.contentView = root

        NSLayoutConstraint.activate([
            root.widthAnchor.constraint(greaterThanOrEqualToConstant: 560),
            errorScrollView.heightAnchor.constraint(equalToConstant: 240)
        ])
    }

    private func makeErrorScrollView() -> NSScrollView {
        errorTextView.isEditable = false
        errorTextView.isSelectable = true
        errorTextView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        errorTextView.string = "Last compile error appears here."
        errorTextView.textColor = .secondaryLabelColor
        errorTextView.drawsBackground = false

        let scrollView = NSScrollView()
        scrollView.documentView = errorTextView
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder
        return scrollView
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    @objc private func openConfig() {
        do {
            try ensureConfigExists()
            NSWorkspace.shared.open(sourceURL)
            statusLabel.stringValue = "Opened Config.swift"
        } catch {
            showError(error)
        }
    }

    @objc private func reloadConfig() {
        reloadButton.isEnabled = false
        statusLabel.stringValue = "Reloading..."
        reloadQueue.async { [weak self] in
            guard let self else {
                return
            }
            let event = reloader.reloadNow()
            DispatchQueue.main.async {
                self.reloadButton.isEnabled = true
                self.handleReloadEvent(event)
            }
        }
    }

    @objc private func openPlayground() {
        let controller = ConfigPlaygroundWindowController(
            sourceURL: sourceURL,
            moduleSearchPaths: Self.defaultModuleSearchPaths()
        )
        controller.onClose = { [weak self] in
            self?.playgroundController = nil
        }
        playgroundController = controller
        guard let sheet = controller.window, let window else {
            controller.showWindow(nil)
            return
        }
        window.beginSheet(sheet)
    }

    private func handleReloadEvent(_ event: ConfigReloadEvent) {
        switch event {
        case let .reloaded(config):
            let source = config.didCompile ? "compiled" : "cache"
            statusLabel.stringValue = "Reloaded from \(source)"
            errorTextView.string = ""
        case let .failed(failure):
            statusLabel.stringValue = "Reload failed"
            errorTextView.textColor = .labelColor
            errorTextView.string = failure.message
        }
    }

    private func ensureConfigExists() throws {
        try fileManager.createDirectory(at: sourceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard !fileManager.fileExists(atPath: sourceURL.path) else {
            return
        }
        try Self.defaultConfigSource.write(to: sourceURL, atomically: true, encoding: .utf8)
    }

    private func showError(_ error: Error) {
        statusLabel.stringValue = "Settings action failed"
        errorTextView.textColor = .labelColor
        errorTextView.string = String(describing: error)
    }

    private static let defaultConfigSource = """
    import ollyDSL

    public func ollyConfig() -> Config {
        Config {}
    }
    """
}
