import AppKit
import Foundation
import ollyDSL

final class ConfigPlaygroundWindowController: NSWindowController {
    var onClose: (() -> Void)?

    private let sourceURL: URL
    private let moduleSearchPaths: [URL]
    private let fileManager: FileManager
    private let compileQueue = DispatchQueue(label: "dev.olly.app.config-playground.compile", qos: .userInitiated)
    private let sourceTextView = NSTextView()
    private let diagnosticTextView = NSTextView()
    private let validateButton = NSButton(title: L10n.s("Validate", "validate"), target: nil, action: nil)

    init(
        sourceURL: URL,
        moduleSearchPaths: [URL],
        fileManager: FileManager = .default
    ) {
        self.sourceURL = sourceURL
        self.moduleSearchPaths = moduleSearchPaths
        self.fileManager = fileManager
        let window = NSWindow(
            contentRect: CGRect(x: 0, y: 0, width: 780, height: 620),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        super.init(window: window)
        configureWindow(window)
        configureContent()
        loadSource()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override func close() {
        super.close()
        onClose?()
    }

    private func configureWindow(_ window: NSWindow) {
        window.title = L10n.s("Config Playground", "config playground window title")
        window.isReleasedWhenClosed = false
    }

    private func configureContent() {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 10
        root.edgeInsets = NSEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        root.translatesAutoresizingMaskIntoConstraints = false

        let buttonRow = NSStackView()
        buttonRow.orientation = .horizontal
        buttonRow.spacing = 8
        validateButton.target = self
        validateButton.action = #selector(validate)
        buttonRow.addArrangedSubview(validateButton)
        buttonRow.addArrangedSubview(button(L10n.s("Close", "config playground close button"), #selector(closeSheet)))
        buttonRow.addArrangedSubview(NSView())

        let sourceScrollView = makeSourceScrollView()
        let diagnosticScrollView = makeDiagnosticScrollView()
        root.addArrangedSubview(label(L10n.s("Config.swift Playground", "title"), size: 18, weight: .semibold))
        root.addArrangedSubview(sourceScrollView)
        root.addArrangedSubview(buttonRow)
        root.addArrangedSubview(diagnosticScrollView)
        window?.contentView = root

        NSLayoutConstraint.activate([
            root.widthAnchor.constraint(greaterThanOrEqualToConstant: 720),
            sourceScrollView.heightAnchor.constraint(equalToConstant: 330),
            diagnosticScrollView.heightAnchor.constraint(equalToConstant: 160)
        ])
    }

    private func makeSourceScrollView() -> NSScrollView {
        sourceTextView.isEditable = true
        sourceTextView.isSelectable = true
        sourceTextView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        sourceTextView.isAutomaticQuoteSubstitutionEnabled = false
        sourceTextView.isAutomaticDashSubstitutionEnabled = false
        return scrollView(for: sourceTextView)
    }

    private func makeDiagnosticScrollView() -> NSScrollView {
        diagnosticTextView.isEditable = false
        diagnosticTextView.isSelectable = true
        diagnosticTextView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        diagnosticTextView.textColor = .secondaryLabelColor
        diagnosticTextView.string = L10n.s("Validation output appears here.", "config playground output placeholder")
        return scrollView(for: diagnosticTextView)
    }

    private func scrollView(for textView: NSTextView) -> NSScrollView {
        let scrollView = NSScrollView()
        scrollView.documentView = textView
        scrollView.hasVerticalScroller = true
        scrollView.hasHorizontalScroller = true
        scrollView.borderType = .bezelBorder
        return scrollView
    }

    private func label(_ text: String, size: CGFloat, weight: NSFont.Weight) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: size, weight: weight)
        return label
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    private func loadSource() {
        if let source = try? String(contentsOf: sourceURL, encoding: .utf8) {
            sourceTextView.string = source
        } else {
            sourceTextView.string = Self.defaultSource
        }
    }

    @objc private func validate() {
        validateButton.isEnabled = false
        diagnosticTextView.textColor = .secondaryLabelColor
        diagnosticTextView.string = L10n.s("Validating...", "config playground validating status")
        let source = sourceTextView.string
        compileQueue.async { [weak self] in
            let result = self?.validateSource(source) ?? L10n.s("Playground closed", "config playground closed status")
            DispatchQueue.main.async {
                self?.validateButton.isEnabled = true
                self?.diagnosticTextView.textColor = .labelColor
                self?.diagnosticTextView.string = result
            }
        }
    }

    @objc private func closeSheet() {
        if let sheetParent = window?.sheetParent, let window {
            sheetParent.endSheet(window)
        }
        close()
    }

    private func validateSource(_ source: String) -> String {
        let directory = fileManager.temporaryDirectory
            .appendingPathComponent("olly-playground-\(UUID().uuidString)", isDirectory: true)
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            defer { try? fileManager.removeItem(at: directory) }
            let sourceURL = directory.appendingPathComponent("Config.swift")
            try source.write(to: sourceURL, atomically: true, encoding: .utf8)
            let loader = ConfigLoader(
                sourceURL: sourceURL,
                cacheDirectory: directory.appendingPathComponent("cache", isDirectory: true),
                moduleSearchPaths: moduleSearchPaths
            )
            _ = try loader.load()
            return L10n.s("Config compiled and loaded.", "config playground success status")
        } catch let ConfigLoaderError.compileFailed(_, _, output) {
            return ConfigDiagnosticFormatter.render(compilerOutput: output, source: source)
        } catch {
            return String(describing: error)
        }
    }

    private static let defaultSource = """
    import ollyDSL

    public func ollyConfig() -> Config {
        Config {}
    }
    """
}
