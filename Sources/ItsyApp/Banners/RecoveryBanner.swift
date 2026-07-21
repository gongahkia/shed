import AppKit

final class RecoveryBanner: NSView {
	var dismissRequested: (() -> Void)?

	private let label = NSTextField(labelWithString: "")
	private let dismissButton = NSButton(title: L10n.string("Dismiss"), target: nil, action: nil)

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		nil
	}

	func show(fileURL: URL) {
		label.stringValue = L10n.string("Recovered unsaved edits for \(fileURL.lastPathComponent). Review and save when ready.")
		isHidden = false
	}

	func hide() {
		isHidden = true
	}

	func applyTheme(_ palette: AppThemePalette) {
		layer?.backgroundColor = palette.bannerBackground.cgColor
		label.textColor = palette.bannerForeground
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
		dismissButton.target = self
		dismissButton.action = #selector(dismiss(_:))
		dismissButton.bezelStyle = .rounded
		dismissButton.controlSize = .small
		dismissButton.setContentCompressionResistancePriority(.required, for: .horizontal)
		dismissButton.setAccessibilityLabel(L10n.string("Dismiss recovery notice"))
		let stack = NSStackView(views: [label, dismissButton])
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

	@objc private func dismiss(_: NSButton) {
		hide()
		dismissRequested?()
	}
}
