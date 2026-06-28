import AppKit
import Foundation

struct FindBarState: Equatable {
	var query: String
	var replacement: String
	var usesRegex: Bool
	var isCaseSensitive: Bool
	var matchesWholeWord: Bool
}

final class FindBarView: NSView {
	var onDismiss: (() -> Void)?
	var onStateChange: ((FindBarState) -> Void)?
	var onFindNext: (() -> Void)?
	var onFindPrevious: (() -> Void)?
	private let queryField = FindBarTextField(frame: .zero)
	private let replaceField = FindBarTextField(frame: .zero)
	private let regexButton = NSButton(checkboxWithTitle: "Regex", target: nil, action: nil)
	private let caseButton = NSButton(checkboxWithTitle: "Case", target: nil, action: nil)
	private let wholeWordButton = NSButton(checkboxWithTitle: "Word", target: nil, action: nil)

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		configure()
	}

	var state: FindBarState {
		FindBarState(
			query: queryField.stringValue,
			replacement: replaceField.stringValue,
			usesRegex: regexButton.state == .on,
			isCaseSensitive: caseButton.state == .on,
			matchesWholeWord: wholeWordButton.state == .on
		)
	}

	func focusQuery() {
		window?.makeFirstResponder(queryField)
		queryField.currentEditor()?.selectAll(nil)
	}

	func regularExpression() -> NSRegularExpression? {
		let state = state
		guard !state.query.isEmpty else {
			return nil
		}
		let basePattern = state.usesRegex ? state.query : NSRegularExpression.escapedPattern(for: state.query)
		let pattern = state.matchesWholeWord ? "\\b(?:\(basePattern))\\b" : basePattern
		let options: NSRegularExpression.Options = state.isCaseSensitive ? [] : [.caseInsensitive]
		return try? NSRegularExpression(pattern: pattern, options: options)
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		layer?.borderWidth = 1
		layer?.borderColor = NSColor.separatorColor.cgColor

		queryField.placeholderString = "Find"
		replaceField.placeholderString = "Replace"
		for field in [queryField, replaceField] {
			field.font = .systemFont(ofSize: 12)
			field.onCancel = { [weak self] in self?.onDismiss?() }
			field.onConfirm = { [weak self] in self?.onFindNext?() }
			field.onFindPrevious = { [weak self] in self?.onFindPrevious?() }
			field.delegate = self
		}
		for button in [regexButton, caseButton, wholeWordButton] {
			button.target = self
			button.action = #selector(optionChanged(_:))
			button.font = .systemFont(ofSize: 11)
			button.setContentCompressionResistancePriority(.required, for: .horizontal)
		}

		let closeButton = NSButton(title: "X", target: self, action: #selector(close(_:)))
		closeButton.isBordered = false
		closeButton.font = .systemFont(ofSize: 11)
		closeButton.toolTip = "Close"
		closeButton.setContentCompressionResistancePriority(.required, for: .horizontal)

		let stack = NSStackView()
		stack.orientation = .horizontal
		stack.alignment = .centerY
		stack.spacing = 8
		stack.edgeInsets = NSEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
		stack.translatesAutoresizingMaskIntoConstraints = false
		addSubview(stack)

		queryField.widthAnchor.constraint(greaterThanOrEqualToConstant: 180).isActive = true
		replaceField.widthAnchor.constraint(greaterThanOrEqualToConstant: 180).isActive = true
		stack.addArrangedSubview(queryField)
		stack.addArrangedSubview(replaceField)
		stack.addArrangedSubview(regexButton)
		stack.addArrangedSubview(caseButton)
		stack.addArrangedSubview(wholeWordButton)
		stack.addArrangedSubview(closeButton)

		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: trailingAnchor),
			stack.topAnchor.constraint(equalTo: topAnchor),
			stack.bottomAnchor.constraint(equalTo: bottomAnchor),
			heightAnchor.constraint(equalToConstant: 38),
		])
	}

	@objc private func close(_ sender: Any?) {
		onDismiss?()
	}

	@objc private func optionChanged(_ sender: Any?) {
		stateDidChange()
	}

	private func stateDidChange() {
		let expression = regularExpression()
		queryField.textColor = state.query.isEmpty || expression != nil ? .labelColor : .systemRed
		onStateChange?(state)
	}
}

extension FindBarView: NSTextFieldDelegate {
	func controlTextDidChange(_ notification: Notification) {
		stateDidChange()
	}
}

final class FindBarTextField: NSTextField {
	var onCancel: (() -> Void)?
	var onConfirm: (() -> Void)?
	var onFindPrevious: (() -> Void)?

	override func keyDown(with event: NSEvent) {
		if event.modifierFlags.contains(.control), event.charactersIgnoringModifiers == "s" {
			onConfirm?()
			return
		}
		if event.modifierFlags.contains(.control), event.charactersIgnoringModifiers == "r" {
			onFindPrevious?()
			return
		}
		switch event.keyCode {
		case 36:
			onConfirm?()
			return
		case 53:
			onCancel?()
			return
		default:
			break
		}
		super.keyDown(with: event)
	}
}
