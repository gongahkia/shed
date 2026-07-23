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
	private let dismissButton = NSButton(title: L10n.string("Dismiss (session)"), target: nil, action: nil)
	private var missingBinary: LSPServerRegistry.MissingBinary?
	private var unavailableLanguage: LSPServerRegistry.UnsupportedLanguage?

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		nil
	}

	func show(missingBinary: LSPServerRegistry.MissingBinary) {
		self.missingBinary = missingBinary
		unavailableLanguage = nil
		copyButton.isHidden = false
		label.stringValue = L10n.string("LSP server \(missingBinary.command) unavailable. \(missingBinary.hint)")
		isHidden = false
	}

	func show(unavailableLanguage: LSPServerRegistry.UnsupportedLanguage) {
		missingBinary = nil
		self.unavailableLanguage = unavailableLanguage
		copyButton.isHidden = true
		label.stringValue = L10n.string("LSP unavailable for \(unavailableLanguage.languageID). \(unavailableLanguage.message)")
		isHidden = false
	}

	func hide() {
		missingBinary = nil
		unavailableLanguage = nil
		copyButton.isHidden = false
		isHidden = true
	}

	func applyTheme(_ palette: AppThemePalette) {
		layer?.backgroundColor = palette.bannerBackground.cgColor
		label.textColor = palette.bannerForeground
		copyButton.contentTintColor = palette.buttonForeground
		supportButton.contentTintColor = palette.buttonForeground
		dismissButton.contentTintColor = palette.buttonForeground
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.controlAccentColor.withAlphaComponent(0.14).cgColor
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

		let stack = NSStackView(views: [label, copyButton, supportButton, dismissButton])
		stack.orientation = .horizontal
		stack.alignment = .centerY
		stack.spacing = 8
		stack.edgeInsets = NSEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
		stack.translatesAutoresizingMaskIntoConstraints = false
		addSubview(stack)

		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: trailingAnchor),
			stack.topAnchor.constraint(equalTo: topAnchor),
			stack.bottomAnchor.constraint(equalTo: bottomAnchor),
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

	@objc private func dismiss(_ sender: NSButton) {
		if let missingBinary {
			dismissRequested?(missingBinary)
		} else if let unavailableLanguage {
			unavailableDismissRequested?(unavailableLanguage)
		}
	}
}
