import AppKit
import ItsyEditor

final class LSPMissingBanner: NSView {
	var copyRequested: ((LSPServerRegistry.MissingBinary) -> Void)?
	var supportRequested: ((String?) -> Void)?
	var dismissRequested: ((LSPServerRegistry.MissingBinary) -> Void)?
	var unavailableDismissRequested: ((LSPServerRegistry.UnsupportedLanguage) -> Void)?

	private let label = NSTextField(labelWithString: "")
	private let copyButton = NSButton(title: L10n.string("Copy command"), target: nil, action: nil)
	private let supportButton = NSButton(title: L10n.string("Manage support"), target: nil, action: nil)
	private let detailsButton = NSButton(title: L10n.string("Details"), target: nil, action: nil)
	private let dismissButton = NSButton(title: L10n.string("Dismiss (session)"), target: nil, action: nil)
	private let detailsLabel = NSTextField(wrappingLabelWithString: "")
	private let copyDiagnosticsButton = NSButton(title: L10n.string("Copy diagnostics"), target: nil, action: nil)
	private let detailsContainer = NSView()
	private let detailsStack = NSStackView()
	private var missingBinary: LSPServerRegistry.MissingBinary?
	private var unavailableLanguage: LSPServerRegistry.UnsupportedLanguage?
	private var fileURL: URL?

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		nil
	}

	func show(missingBinary: LSPServerRegistry.MissingBinary, fileURL: URL? = nil) {
		self.missingBinary = missingBinary
		unavailableLanguage = nil
		self.fileURL = fileURL ?? self.fileURL
		copyButton.isHidden = false
		label.stringValue = L10n.string("LSP server \(missingBinary.command) unavailable")
		updateDetails()
		setDetailsExpanded(false)
		isHidden = false
	}

	func show(unavailableLanguage: LSPServerRegistry.UnsupportedLanguage, fileURL: URL? = nil) {
		missingBinary = nil
		self.unavailableLanguage = unavailableLanguage
		self.fileURL = fileURL ?? self.fileURL
		copyButton.isHidden = true
		label.stringValue = L10n.string("LSP unavailable for \(unavailableLanguage.languageID)")
		updateDetails()
		setDetailsExpanded(false)
		isHidden = false
	}

	func hide() {
		missingBinary = nil
		unavailableLanguage = nil
		fileURL = nil
		copyButton.isHidden = false
		setDetailsExpanded(false)
		isHidden = true
	}

	func applyTheme(_ palette: AppThemePalette) {
		layer?.backgroundColor = palette.bannerBackground.withAlphaComponent(1).cgColor
		label.textColor = palette.bannerForeground
		copyButton.contentTintColor = palette.buttonForeground
		supportButton.contentTintColor = palette.buttonForeground
		detailsButton.contentTintColor = palette.buttonForeground
		dismissButton.contentTintColor = palette.buttonForeground
		detailsLabel.textColor = palette.bannerForeground
		copyDiagnosticsButton.contentTintColor = palette.buttonForeground
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.controlAccentColor.cgColor
		setContentHuggingPriority(.required, for: .vertical)
		setContentCompressionResistancePriority(.required, for: .vertical)
		isHidden = true

		label.font = .systemFont(ofSize: 12)
		label.textColor = .labelColor
		label.lineBreakMode = .byTruncatingMiddle
		label.setContentHuggingPriority(.defaultLow, for: .horizontal)
		label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

		copyButton.target = self
		copyButton.action = #selector(copyInstallCommand(_:))
		copyButton.bezelStyle = .rounded
		copyButton.controlSize = .small
		copyButton.setContentCompressionResistancePriority(.required, for: .horizontal)

		supportButton.target = self
		supportButton.action = #selector(requestSupport(_:))
		supportButton.bezelStyle = .rounded
		supportButton.controlSize = .small
		supportButton.setContentCompressionResistancePriority(.required, for: .horizontal)

		dismissButton.target = self
		dismissButton.action = #selector(dismiss(_:))
		dismissButton.bezelStyle = .rounded
		dismissButton.controlSize = .small
		dismissButton.setContentCompressionResistancePriority(.required, for: .horizontal)

		detailsButton.target = self
		detailsButton.action = #selector(toggleDetails(_:))
		detailsButton.bezelStyle = .rounded
		detailsButton.controlSize = .small
		detailsButton.setContentCompressionResistancePriority(.required, for: .horizontal)
		detailsButton.setAccessibilityLabel(L10n.string("Show LSP diagnostic details"))

		detailsLabel.font = .systemFont(ofSize: 12)
		detailsLabel.alignment = .left
		detailsLabel.lineBreakMode = .byWordWrapping
		detailsLabel.setContentCompressionResistancePriority(.required, for: .vertical)

		copyDiagnosticsButton.target = self
		copyDiagnosticsButton.action = #selector(copyDiagnostics(_:))
		copyDiagnosticsButton.bezelStyle = .rounded
		copyDiagnosticsButton.controlSize = .small
		copyDiagnosticsButton.setContentCompressionResistancePriority(.required, for: .horizontal)
		copyDiagnosticsButton.setAccessibilityLabel(L10n.string("Copy LSP diagnostics"))

		let summaryStack = NSStackView(views: [label, copyButton, supportButton, detailsButton, dismissButton])
		summaryStack.orientation = .horizontal
		summaryStack.alignment = .centerY
		summaryStack.spacing = 8

		detailsStack.orientation = .vertical
		detailsStack.alignment = .leading
		detailsStack.spacing = 8
		detailsStack.addArrangedSubview(detailsLabel)
		detailsStack.addArrangedSubview(copyDiagnosticsButton)
		detailsStack.translatesAutoresizingMaskIntoConstraints = false
		detailsContainer.addSubview(detailsStack)
		detailsContainer.isHidden = true
		NSLayoutConstraint.activate([
			detailsStack.leadingAnchor.constraint(equalTo: detailsContainer.leadingAnchor),
			detailsStack.trailingAnchor.constraint(equalTo: detailsContainer.trailingAnchor),
			detailsStack.topAnchor.constraint(equalTo: detailsContainer.topAnchor),
			detailsStack.bottomAnchor.constraint(equalTo: detailsContainer.bottomAnchor),
			detailsLabel.widthAnchor.constraint(equalTo: detailsStack.widthAnchor),
		])

		let contentStack = NSStackView(views: [summaryStack, detailsContainer])
		contentStack.orientation = .vertical
		contentStack.alignment = .width
		contentStack.spacing = 8
		contentStack.detachesHiddenViews = true
		contentStack.edgeInsets = NSEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
		contentStack.translatesAutoresizingMaskIntoConstraints = false
		addSubview(contentStack)

		NSLayoutConstraint.activate([
			contentStack.leadingAnchor.constraint(equalTo: leadingAnchor),
			contentStack.trailingAnchor.constraint(equalTo: trailingAnchor),
			contentStack.topAnchor.constraint(equalTo: topAnchor),
			contentStack.bottomAnchor.constraint(equalTo: bottomAnchor),
			heightAnchor.constraint(greaterThanOrEqualToConstant: 34),
		])
	}

	@objc private func copyInstallCommand(_ sender: NSButton) {
		guard let missingBinary else {
			return
		}
		copyRequested?(missingBinary)
	}

	@objc private func requestSupport(_: NSButton) {
		supportRequested?(missingBinary?.command)
	}

	@objc private func toggleDetails(_: NSButton) {
		setDetailsExpanded(detailsContainer.isHidden)
	}

	@objc private func copyDiagnostics(_: NSButton) {
		guard missingBinary != nil || unavailableLanguage != nil else { return }
		let pasteboard = NSPasteboard.general
		pasteboard.clearContents()
		pasteboard.setString(diagnosticReport(), forType: .string)
	}

	@objc private func dismiss(_ sender: NSButton) {
		if let missingBinary {
			dismissRequested?(missingBinary)
		} else if let unavailableLanguage {
			unavailableDismissRequested?(unavailableLanguage)
		}
	}

	private func setDetailsExpanded(_ expanded: Bool) {
		detailsContainer.isHidden = !expanded
		detailsButton.title = L10n.string(expanded ? "Hide details" : "Details")
		detailsButton.setAccessibilityLabel(L10n.string(expanded ? "Hide LSP diagnostic details" : "Show LSP diagnostic details"))
		superview?.needsLayout = true
	}

	private func updateDetails() {
		detailsLabel.stringValue = diagnosticReport()
	}

	private func diagnosticReport() -> String {
		var lines = ["Itsy LSP diagnostic", "status: unavailable"]
		if let missingBinary {
			lines += [
				"language: \(missingBinary.languageID)",
				"server command: \(missingBinary.command)",
				"remediation: \(missingBinary.hint)",
				"process log: unavailable; the server process did not start",
			]
		} else if let unavailableLanguage {
			lines += [
				"language: \(unavailableLanguage.languageID)",
				"reason: \(unavailableLanguage.message)",
				"process log: unavailable; no language server is configured",
			]
		}
		lines.append("file: \(fileURL?.path ?? "unavailable")")
		return lines.joined(separator: "\n")
	}
}
