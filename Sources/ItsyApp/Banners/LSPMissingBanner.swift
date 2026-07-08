import AppKit
import ItsyEditor

final class LSPMissingBanner: NSView {
	var copyRequested: ((LSPServerRegistry.MissingBinary) -> Void)?
	var dismissRequested: ((LSPServerRegistry.MissingBinary) -> Void)?

	private let label = NSTextField(labelWithString: "")
	private let copyButton = NSButton(title: L10n.string("Copy command"), target: nil, action: nil)
	private let dismissButton = NSButton(title: L10n.string("Dismiss (session)"), target: nil, action: nil)
	private var missingBinary: LSPServerRegistry.MissingBinary?

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		nil
	}

	func show(missingBinary: LSPServerRegistry.MissingBinary) {
		self.missingBinary = missingBinary
		label.stringValue = L10n.string("LSP server \(missingBinary.command) unavailable. \(missingBinary.hint)")
		isHidden = false
	}

	func hide() {
		missingBinary = nil
		isHidden = true
	}

	func applyTheme(_ palette: AppThemePalette) {
		layer?.backgroundColor = palette.bannerBackground.cgColor
		label.textColor = palette.bannerForeground
		copyButton.contentTintColor = palette.buttonForeground
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
		label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

		copyButton.target = self
		copyButton.action = #selector(copyInstallCommand(_:))
		copyButton.bezelStyle = .rounded
		copyButton.controlSize = .small

		dismissButton.target = self
		dismissButton.action = #selector(dismiss(_:))
		dismissButton.bezelStyle = .rounded
		dismissButton.controlSize = .small

		let stack = NSStackView(views: [label, copyButton, dismissButton])
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

	@objc private func dismiss(_ sender: NSButton) {
		guard let missingBinary else {
			return
		}
		dismissRequested?(missingBinary)
	}
}
