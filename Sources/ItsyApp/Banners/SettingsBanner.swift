import AppKit

final class SettingsBanner: NSView {
	var openSettingsRequested: (() -> Void)?
	private let label = NSTextField(labelWithString: "")
	private let openButton = NSButton(title: L10n.string("Open Settings"), target: nil, action: nil)
	private let dismissButton = NSButton(title: L10n.string("Dismiss"), target: nil, action: nil)
	private var hideWorkItem: DispatchWorkItem?

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		nil
	}

	func show(message: String, isError: Bool) {
		hideWorkItem?.cancel()
		label.stringValue = message
		label.textColor = isError ? .systemRed : .labelColor
		openButton.isHidden = !isError
		isHidden = false
		setAccessibilityLabel(message)
		guard !isError else { return }
		let workItem = DispatchWorkItem { [weak self] in self?.hide() }
		hideWorkItem = workItem
		DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: workItem)
	}

	func hide() {
		hideWorkItem?.cancel()
		hideWorkItem = nil
		isHidden = true
	}

	func applyTheme(_ palette: AppThemePalette) {
		layer?.backgroundColor = palette.bannerBackground.withAlphaComponent(1).cgColor
		if label.textColor != .systemRed {
			label.textColor = palette.bannerForeground
		}
		openButton.contentTintColor = palette.buttonForeground
		dismissButton.contentTintColor = palette.buttonForeground
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.controlAccentColor.cgColor
		isHidden = true
		label.font = .systemFont(ofSize: 12)
		label.lineBreakMode = .byTruncatingMiddle
		label.setContentHuggingPriority(.defaultLow, for: .horizontal)
		label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
		openButton.target = self
		openButton.action = #selector(openSettings(_:))
		openButton.bezelStyle = .rounded
		openButton.controlSize = .small
		openButton.setAccessibilityLabel(L10n.string("Open settings to resolve configuration error"))
		dismissButton.target = self
		dismissButton.action = #selector(dismiss(_:))
		dismissButton.bezelStyle = .rounded
		dismissButton.controlSize = .small
		dismissButton.setAccessibilityLabel(L10n.string("Dismiss settings notice"))
		let stack = NSStackView(views: [label, openButton, dismissButton])
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

	@objc private func openSettings(_: Any?) {
		openSettingsRequested?()
	}

	@objc private func dismiss(_: Any?) {
		hide()
	}
}
