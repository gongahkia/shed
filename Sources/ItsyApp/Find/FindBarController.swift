import AppKit
import Foundation
import ItsyRender

@MainActor final class FindBarController: NSObject, NSTextFieldDelegate {
	let view = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 38))
	private let queryField = NSTextField(frame: .zero)
	private let replaceField = NSTextField(frame: .zero)
	private let regexButton = NSButton(checkboxWithTitle: L10n.string("Regex"), target: nil, action: nil)
	private let caseButton = NSButton(checkboxWithTitle: L10n.string("Case"), target: nil, action: nil)
	private let wholeWordButton = NSButton(checkboxWithTitle: L10n.string("Word"), target: nil, action: nil)
	private let closeButton = NSButton(title: L10n.string("X"), target: nil, action: nil)
	private weak var window: NSWindow?
	private var keyMonitor: Any?
	private var matches: [Range<Int>] = []
	private var selectedMatchIndex: Int?
	private var incrementalDirection: Int?
	var currentEditorView: () -> MetalTextView? = { nil }
	var focusEditor: () -> Void = {}
	var visibilityDidChange: () -> Void = {}

	override init() {
		super.init()
		configureView()
		installActions()
		view.isHidden = true
	}

	deinit {
		MainActor.assumeIsolated {
			if let keyMonitor {
				NSEvent.removeMonitor(keyMonitor)
			}
		}
	}

	func attach(to window: NSWindow) {
		self.window = window
	}

	var focusableViews: [NSView] {
		[queryField, replaceField, regexButton, caseButton, wholeWordButton, closeButton]
	}

	func applyTheme(_ palette: AppThemePalette) {
		view.layer?.backgroundColor = palette.panelBackground.cgColor
		view.layer?.borderColor = palette.border.cgColor
		for field in [queryField, replaceField] {
			field.textColor = palette.inputForeground
			field.backgroundColor = palette.inputBackground
			field.placeholderAttributedString = NSAttributedString(
				string: field.placeholderString ?? "",
				attributes: [.foregroundColor: palette.inputPlaceholder]
			)
		}
		for button in [regexButton, caseButton, wholeWordButton, closeButton] {
			button.contentTintColor = palette.foreground
		}
	}

	func toggle() {
		setVisible(view.isHidden)
	}

	func findNext() {
		selectMatch(direction: 1)
	}

	func findPrevious() {
		selectMatch(direction: -1)
	}

	func startIncrementalSearch(direction: Int) {
		incrementalDirection = direction
		setVisible(true)
		selectMatch(direction: direction, focusEditorAfterSelection: false)
	}

	func selectAllMatches() {
		guard !view.isHidden else {
			setVisible(true)
			return
		}
		refreshMatches()
		guard !matches.isEmpty, let editorView = currentEditorView() else {
			return
		}
		selectedMatchIndex = nil
		editorView.selectUTF8Ranges(matches)
		focusEditor()
	}

	private func configureView() {
		view.wantsLayer = true
		view.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		view.layer?.borderWidth = 1
		view.layer?.borderColor = NSColor.separatorColor.cgColor

		queryField.placeholderString = L10n.string("Find")
		replaceField.placeholderString = L10n.string("Replace")
		for field in [queryField, replaceField] {
			field.font = .systemFont(ofSize: 12)
			field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
		}
		for button in [regexButton, caseButton, wholeWordButton] {
			button.font = .systemFont(ofSize: 11)
			button.setContentCompressionResistancePriority(.required, for: .horizontal)
		}
		closeButton.isBordered = false
		closeButton.font = .systemFont(ofSize: 11)
		closeButton.toolTip = L10n.string("Close")
		closeButton.setContentCompressionResistancePriority(.required, for: .horizontal)

		let stack = NSStackView()
		stack.orientation = .horizontal
		stack.alignment = .centerY
		stack.spacing = 8
		stack.edgeInsets = NSEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
		stack.translatesAutoresizingMaskIntoConstraints = false
		view.addSubview(stack)

		queryField.widthAnchor.constraint(greaterThanOrEqualToConstant: 180).isActive = true
		replaceField.widthAnchor.constraint(greaterThanOrEqualToConstant: 180).isActive = true
		stack.addArrangedSubview(queryField)
		stack.addArrangedSubview(replaceField)
		stack.addArrangedSubview(regexButton)
		stack.addArrangedSubview(caseButton)
		stack.addArrangedSubview(wholeWordButton)
		stack.addArrangedSubview(closeButton)

		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
			stack.topAnchor.constraint(equalTo: view.topAnchor),
			stack.bottomAnchor.constraint(equalTo: view.bottomAnchor),
			view.heightAnchor.constraint(equalToConstant: 38),
		])
	}

	private func installActions() {
		for field in [queryField, replaceField] {
			field.delegate = self
		}
		keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
			guard let self,
			      self.window?.isKeyWindow == true,
			      self.isEditingFindField,
			      event.modifierFlags.contains(.control)
			else {
				return event
			}
			if event.charactersIgnoringModifiers == "s" {
				self.selectMatchFromFindBar(direction: 1)
				return nil
			}
			if event.charactersIgnoringModifiers == "r" {
				self.selectMatchFromFindBar(direction: -1)
				return nil
			}
			return event
		}
		for button in [regexButton, caseButton, wholeWordButton] {
			button.target = self
			button.action = #selector(findOptionChanged(_:))
		}
		closeButton.target = self
		closeButton.action = #selector(closeFindBar(_:))
	}

	private var isEditingFindField: Bool {
		guard let firstResponder = window?.firstResponder else {
			return false
		}
		return firstResponder === queryField.currentEditor() || firstResponder === replaceField.currentEditor()
	}

	private var findState: FindBarState {
		FindBarState(
			query: queryField.stringValue,
			replacement: replaceField.stringValue,
			usesRegex: regexButton.state == .on,
			isCaseSensitive: caseButton.state == .on,
			matchesWholeWord: wholeWordButton.state == .on
		)
	}

	private func setVisible(_ visible: Bool) {
		view.isHidden = !visible
		currentEditorView()?.topContentInset = visible ? 38 : 0
		if visible {
			refreshMatches()
			focusQuery()
		} else {
			incrementalDirection = nil
			matches = []
			selectedMatchIndex = nil
			currentEditorView()?.setFindMatchRanges([])
			focusEditor()
		}
		visibilityDidChange()
	}

	private func focusQuery() {
		window?.makeFirstResponder(queryField)
		queryField.currentEditor()?.selectAll(nil)
	}

	private func findStateDidChange() {
		let expression = findRegularExpression()
		queryField.textColor = findState.query.isEmpty || expression != nil ? AppTheme.palette.inputForeground : AppTheme.palette.errorForeground
		refreshMatches()
		if let incrementalDirection {
			selectMatch(direction: incrementalDirection, refreshBeforeSelecting: false, focusEditorAfterSelection: false)
		}
	}

	private func findRegularExpression() -> NSRegularExpression? {
		let state = findState
		guard !state.query.isEmpty else {
			return nil
		}
		let basePattern = state.usesRegex ? state.query : NSRegularExpression.escapedPattern(for: state.query)
		let pattern = state.matchesWholeWord ? "\\b(?:\(basePattern))\\b" : basePattern
		let options: NSRegularExpression.Options = state.isCaseSensitive ? [] : [.caseInsensitive]
		return try? NSRegularExpression(pattern: pattern, options: options)
	}

	private func refreshMatches() {
		guard !view.isHidden, let expression = findRegularExpression(), let editorView = currentEditorView() else {
			matches = []
			selectedMatchIndex = nil
			currentEditorView()?.setFindMatchRanges([])
			return
		}
		let text = editorStorageString(editorView.editor)
		let fullRange = NSRange(text.startIndex ..< text.endIndex, in: text)
		matches = expression.matches(in: text, range: fullRange).compactMap { result in
			guard result.range.length > 0, let range = Range(result.range, in: text) else {
				return nil
			}
			return utf8Range(range, in: text)
		}
		selectedMatchIndex = nil
		editorView.setFindMatchRanges(matches)
	}

	private func selectMatchFromFindBar(direction: Int) {
		selectMatch(direction: direction, focusEditorAfterSelection: false)
	}

	private func selectMatch(direction: Int, refreshBeforeSelecting: Bool = true, focusEditorAfterSelection: Bool = true) {
		guard !view.isHidden else {
			setVisible(true)
			return
		}
		if refreshBeforeSelecting {
			refreshMatches()
		}
		guard !matches.isEmpty, let editorView = currentEditorView() else {
			return
		}
		let selectedIndex: Int
		if let selectedMatchIndex {
			selectedIndex = wrappedIndex(selectedMatchIndex + direction, count: matches.count)
		} else if direction >= 0 {
			let cursor = editorView.editor.selections.primary.head
			selectedIndex = matches.firstIndex { $0.lowerBound >= cursor } ?? 0
		} else {
			let cursor = editorView.editor.selections.primary.head
			selectedIndex = matches.lastIndex { $0.upperBound <= cursor } ?? matches.count - 1
		}
		selectedMatchIndex = selectedIndex
		editorView.selectUTF8Range(matches[selectedIndex])
		if focusEditorAfterSelection {
			focusEditor()
		}
	}

	private func wrappedIndex(_ index: Int, count: Int) -> Int {
		(index % count + count) % count
	}

	private func utf8Range(_ range: Range<String.Index>, in text: String) -> Range<Int>? {
		guard
			let lowerIndex = range.lowerBound.samePosition(in: text.utf8),
			let upperIndex = range.upperBound.samePosition(in: text.utf8)
		else {
			return nil
		}
		let lower = text.utf8.distance(from: text.utf8.startIndex, to: lowerIndex)
		let upper = text.utf8.distance(from: text.utf8.startIndex, to: upperIndex)
		return lower ..< upper
	}

	@objc private func closeFindBar(_ sender: Any?) {
		setVisible(false)
	}

	@objc private func findOptionChanged(_ sender: Any?) {
		findStateDidChange()
	}

	func controlTextDidChange(_ notification: Notification) {
		guard let field = notification.object as? NSTextField, field === queryField || field === replaceField else {
			return
		}
		findStateDidChange()
	}

	func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		guard control === queryField || control === replaceField else {
			return false
		}
		switch commandSelector {
		case #selector(NSResponder.insertNewline(_:)):
			selectMatchFromFindBar(direction: 1)
			return true
		case #selector(NSResponder.cancelOperation(_:)):
			setVisible(false)
			return true
		default:
			return false
		}
	}
}
