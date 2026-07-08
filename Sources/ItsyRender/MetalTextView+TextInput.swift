import AppKit
import ItsyEditor

extension MetalTextView: @MainActor NSTextInputClient {
	@available(macOS 15.0, *)
	@objc public var writingToolsBehavior: NSWritingToolsBehavior {
		get { .none }
		set {}
	}

	@available(macOS 15.0, *)
	@objc public var allowedWritingToolsResultOptions: NSWritingToolsResultOptions {
		get { [] }
		set {}
	}

	public func insertText(_ string: Any, replacementRange: NSRange) {
		lastYankRange = nil
		let text = plainString(from: string)
		let range = replacementUTF8Range(replacementRange) ?? markedRangeUTF8 ?? editor.selections.primary.range
		replace(range: range, with: text)
		markedRangeUTF8 = nil
		syncEditorState()
		editorDidChange?(editor)
	}

	public override func doCommand(by selector: Selector) {
		lastYankRange = nil
		var didEdit = false
		switch selector {
		case #selector(NSResponder.deleteBackward(_:)):
			editor.deleteBackward()
			didEdit = true
		case #selector(NSResponder.deleteForward(_:)):
			editor.deleteForward()
			didEdit = true
		case #selector(NSResponder.moveLeft(_:)):
			editor.moveCursor(.charBackward)
		case #selector(NSResponder.moveRight(_:)):
			editor.moveCursor(.charForward)
		case #selector(NSResponder.moveToBeginningOfLine(_:)):
			editor.moveCursor(.lineStart)
		case #selector(NSResponder.moveToEndOfLine(_:)):
			editor.moveCursor(.lineEnd)
		case #selector(NSResponder.insertNewline(_:)):
			editor.insert(newlineInsertionTextProvider?(editor) ?? "\n")
			didEdit = true
		default:
			_ = tryToPerform(selector, with: nil)
		}
		markedRangeUTF8 = nil
		syncEditorState()
		if didEdit {
			editorDidChange?(editor)
		}
	}

	public func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
		let text = plainString(from: string)
		let range = replacementUTF8Range(replacementRange) ?? markedRangeUTF8 ?? editor.selections.primary.range
		replace(range: range, with: text)
		let lower = range.lowerBound
		let upper = lower + text.utf8.count
		markedRangeUTF8 = lower ..< upper
		let selection = utf8Range(in: text, forUTF16Range: selectedRange, baseUTF8Offset: lower) ?? upper ..< upper
		editor.setSelection(SelectionSet(primary: Selection(anchor: selection.lowerBound, head: selection.upperBound)))
		syncEditorState()
		editorDidChange?(editor)
	}

	public func unmarkText() {
		markedRangeUTF8 = nil
		markDirty()
	}

	public func selectedRange() -> NSRange {
		nsRange(forUTF8Range: editor.selections.primary.range)
	}

	public func markedRange() -> NSRange {
		guard let markedRangeUTF8 else {
			return NSRange(location: NSNotFound, length: 0)
		}
		return nsRange(forUTF8Range: markedRangeUTF8)
	}

	public func hasMarkedText() -> Bool {
		markedRangeUTF8 != nil
	}

	public func attributedSubstring(forProposedRange range: NSRange, actualRange: NSRangePointer?) -> NSAttributedString? {
		guard let utf8Range = utf8Range(forNSRange: range) else {
			return nil
		}
		actualRange?.pointee = nsRange(forUTF8Range: utf8Range)
		return NSAttributedString(
			string: editor.rope.slice(utf8Range),
			attributes: [.font: NSFont.monospacedSystemFont(ofSize: 14, weight: .regular)]
		)
	}

	public func validAttributesForMarkedText() -> [NSAttributedString.Key] {
		[.underlineStyle, .foregroundColor, .backgroundColor]
	}

	public func firstRect(forCharacterRange range: NSRange, actualRange: NSRangePointer?) -> NSRect {
		let utf8Range = utf8Range(forNSRange: range) ?? editor.selections.primary.range
		actualRange?.pointee = nsRange(forUTF8Range: utf8Range)
		let rect = rectForUTF8Offset(utf8Range.lowerBound)
		guard let window else {
			return convert(rect, to: nil)
		}
		return window.convertToScreen(convert(rect, to: nil))
	}

	public func characterIndex(for point: NSPoint) -> Int {
		let windowPoint = window?.convertPoint(fromScreen: point) ?? point
		let local = convert(windowPoint, from: nil)
		let line = min(max(Int((local.y - textInset.y) / max(lineHeight, 1)) + topLineIndex, 0), max(0, editor.rope.lineCount - 1))
		let lineRange = editor.rope.lineRange(line)
		let lineText = editor.rope.slice(lineRange)
		let targetX = local.x - textInset.x + xOffset
		var offset = lineRange.lowerBound
		var bestUTF16 = 0
		let baseUTF16 = nsRange(forUTF8Range: lineRange.lowerBound ..< lineRange.lowerBound).location
		for character in lineText {
			let next = offset + String(character).utf8.count
			let width = typographicWidth(editor.rope.slice(lineRange.lowerBound ..< next))
			if width > targetX {
				break
			}
			offset = next
			bestUTF16 += String(character).utf16.count
		}
		return baseUTF16 + bestUTF16
	}

	func utf8Offset(forMouseEvent event: NSEvent) -> Int {
		utf8Offset(forLocalPoint: convert(event.locationInWindow, from: nil))
	}

	func utf8Offset(forLocalPoint local: NSPoint) -> Int {
		let line = min(max(Int((local.y - topContentInset - textInset.y) / max(lineHeight, 1)) + topLineIndex, 0), max(0, editor.rope.lineCount - 1))
		let lineRange = editor.rope.lineRange(line)
		let lineText = editor.rope.slice(lineRange)
		let targetX = local.x - textInset.x + xOffset
		var offset = lineRange.lowerBound
		for character in lineText {
			let next = offset + String(character).utf8.count
			let width = typographicWidth(editor.rope.slice(lineRange.lowerBound ..< next))
			if width > targetX {
				break
			}
			offset = next
		}
		return offset
	}

	private func plainString(from value: Any) -> String {
		if let attributed = value as? NSAttributedString {
			return attributed.string
		}
		return String(describing: value)
	}

	private func replacementUTF8Range(_ range: NSRange) -> Range<Int>? {
		range.location == NSNotFound ? nil : utf8Range(forNSRange: range)
	}

	func replace(range: Range<Int>, with text: String) {
		editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.upperBound)))
		editor.insert(text)
	}

	private func nsRange(forUTF8Range range: Range<Int>) -> NSRange {
		let text = editorStorageString(editor)
		let lower = utf16Offset(in: text, forUTF8Offset: range.lowerBound)
		let upper = utf16Offset(in: text, forUTF8Offset: range.upperBound)
		return NSRange(location: lower, length: upper - lower)
	}

	private func utf8Range(forNSRange range: NSRange) -> Range<Int>? {
		utf8Range(in: editorStorageString(editor), forUTF16Range: range, baseUTF8Offset: 0)
	}

	private func utf8Range(in text: String, forUTF16Range range: NSRange, baseUTF8Offset: Int) -> Range<Int>? {
		guard range.location != NSNotFound else {
			return nil
		}
		let lower = utf8Offset(in: text, forUTF16Offset: range.location)
		let upper = utf8Offset(in: text, forUTF16Offset: range.location + range.length)
		return baseUTF8Offset + lower ..< baseUTF8Offset + upper
	}

	private func utf8Offset(in text: String, forUTF16Offset target: Int) -> Int {
		var utf8 = 0
		var utf16 = 0
		for character in text {
			if utf16 >= target {
				break
			}
			utf8 += String(character).utf8.count
			utf16 += String(character).utf16.count
		}
		return utf8
	}

	private func utf16Offset(in text: String, forUTF8Offset target: Int) -> Int {
		var utf8 = 0
		var utf16 = 0
		for character in text {
			if utf8 >= target {
				break
			}
			utf8 += String(character).utf8.count
			utf16 += String(character).utf16.count
		}
		return utf16
	}

	func rectForUTF8Offset(_ offset: Int) -> NSRect {
		let line = editor.rope.line(forOffset: min(max(offset, 0), editor.rope.length))
		let lineRange = editor.rope.lineRange(line)
		let prefix = editor.rope.slice(lineRange.lowerBound ..< min(offset, lineRange.upperBound))
		let row = visibleRow(for: line) ?? line - topLineIndex
		return NSRect(
			x: textInset.x + typographicWidth(prefix) - xOffset,
			y: topContentInset + textInset.y + CGFloat(row) * lineHeight,
			width: 2,
			height: lineHeight
		)
	}
}
