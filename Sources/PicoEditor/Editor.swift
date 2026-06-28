import Foundation

public enum Motion: Sendable, Equatable {
	case charForward
	case charBackward
	case wordForward
	case wordBackward
	case lineStart
	case lineEnd
	case visualLineStart
	case visualLineEnd
	case bufferStart
	case bufferEnd
	case pageDown
	case pageUp
}

public struct UndoStack: Sendable, Equatable {
	public private(set) var edits: [UndoEntry] = []
	private var redoEdits: [UndoEntry] = []

	public init() {}

	mutating func record(_ edit: Edit, selectionAfter: SelectionSet, textBefore: String) {
		let snapshot = edits.count.isMultiple(of: 32) ? textBefore : nil
		edits.append(UndoEntry(edit: edit, selectionAfter: selectionAfter, snapshotBefore: snapshot))
		redoEdits.removeAll()
	}

	mutating func popUndo() -> UndoEntry? {
		guard let entry = edits.popLast() else {
			return nil
		}
		redoEdits.append(entry)
		return entry
	}

	mutating func popRedo() -> UndoEntry? {
		guard let entry = redoEdits.popLast() else {
			return nil
		}
		edits.append(entry)
		return entry
	}
}

public struct UndoEntry: Sendable, Equatable {
	public var edit: Edit
	public var selectionAfter: SelectionSet
	public var snapshotBefore: String?
}

public struct Editor: Sendable {
	public var rope: Rope
	public var selections: SelectionSet
	public var history: UndoStack
	public var pageLineCount = 40

	public init(text: String = "") {
		rope = Rope(text)
		selections = SelectionSet()
		history = UndoStack()
	}

	public var text: String {
		rope.slice(0 ..< rope.length)
	}

	public mutating func insert(_ string: String) {
		replaceSelections(with: string)
	}

	public mutating func deleteBackward() {
		let ranges = selectionsForEdit().map { selection -> Range<Int> in
			if !selection.isCaret {
				return selection.range
			}
			return previousCharacterRange(before: selection.head)
		}
		replace(ranges: ranges, with: "")
	}

	public mutating func deleteForward() {
		let ranges = selectionsForEdit().map { selection -> Range<Int> in
			if !selection.isCaret {
				return selection.range
			}
			return nextCharacterRange(after: selection.head)
		}
		replace(ranges: ranges, with: "")
	}

	public mutating func moveCursor(_ motion: Motion) {
		let moved = selectionsForEdit().map { selection -> Selection in
			let offset: Int
			switch motion {
			case .charForward:
				offset = nextCharacterRange(after: selection.head).upperBound
			case .charBackward:
				offset = previousCharacterRange(before: selection.head).lowerBound
			case .wordForward:
				offset = wordForward(from: selection.head)
			case .wordBackward:
				offset = wordBackward(from: selection.head)
			case .lineStart, .visualLineStart:
				offset = rope.offset(forLine: rope.line(forOffset: selection.head))
			case .lineEnd, .visualLineEnd:
				offset = rope.lineRange(rope.line(forOffset: selection.head)).upperBound
			case .bufferStart:
				offset = 0
			case .bufferEnd:
				offset = rope.length
			case .pageDown:
				let line = min(rope.line(forOffset: selection.head) + pageLineCount, max(0, rope.lineCount - 1))
				offset = rope.offset(forLine: line)
			case .pageUp:
				let line = max(rope.line(forOffset: selection.head) - pageLineCount, 0)
				offset = rope.offset(forLine: line)
			}
			return Selection(anchor: offset, head: offset, affinity: selection.affinity)
		}
		selections = SelectionSet(primary: moved[0], secondaries: Array(moved.dropFirst()))
	}

	public mutating func setSelection(_ selectionSet: SelectionSet) {
		selections = selectionSet
		selections.merge()
	}

	public mutating func undo() {
		guard let entry = history.popUndo() else {
			return
		}
		if let snapshot = entry.snapshotBefore {
			rope = Rope(snapshot)
		} else {
			applyInverse(entry.edit)
		}
		selections = entry.edit.selectionBefore
	}

	public mutating func redo() {
		guard let entry = history.popRedo() else {
			return
		}
		apply(entry.edit)
		selections = entry.selectionAfter
	}

	private mutating func replaceSelections(with string: String) {
		let ranges = selectionsForEdit().map(\.range)
		replace(ranges: ranges, with: string)
	}

	private mutating func replace(ranges: [Range<Int>], with string: String) {
		let normalized = merge(ranges.filter { !$0.isEmpty || !string.isEmpty })
		guard !normalized.isEmpty else {
			return
		}
		let textBefore = text
		let selectionBefore = selections
		var recordedEdits: [Edit] = []
		var carets: [Selection] = []
		for range in normalized.sorted(by: { $0.lowerBound > $1.lowerBound }) {
			let oldText = rope.slice(range)
			if !range.isEmpty {
				rope.remove(range)
			}
			if !string.isEmpty {
				rope.insert(string, at: range.lowerBound)
			}
			recordedEdits.append(Edit(range: range, oldText: oldText, newText: string, selectionBefore: selectionBefore))
			let caret = range.lowerBound + string.utf8.count
			carets.append(Selection(anchor: caret, head: caret))
		}
		carets.reverse()
		selections = SelectionSet(primary: carets[0], secondaries: Array(carets.dropFirst()))
		for edit in recordedEdits.reversed() {
			history.record(edit, selectionAfter: selections, textBefore: textBefore)
		}
	}

	private func selectionsForEdit() -> [Selection] {
		var set = selections
		set.merge()
		return [set.primary] + set.secondaries
	}

	private func previousCharacterRange(before offset: Int) -> Range<Int> {
		guard offset > 0 else {
			return 0 ..< 0
		}
		let text = text
		let index = text.index(atUTF8Offset: offset)
		let previous = text.index(before: index)
		guard let utf8Previous = previous.samePosition(in: text.utf8) else {
			preconditionFailure("previous character must align with utf8")
		}
		let lower = text.utf8.distance(from: text.utf8.startIndex, to: utf8Previous)
		return lower ..< offset
	}

	private func nextCharacterRange(after offset: Int) -> Range<Int> {
		guard offset < rope.length else {
			return rope.length ..< rope.length
		}
		let text = text
		let index = text.index(atUTF8Offset: offset)
		let next = text.index(after: index)
		guard let utf8Next = next.samePosition(in: text.utf8) else {
			preconditionFailure("next character must align with utf8")
		}
		let upper = text.utf8.distance(from: text.utf8.startIndex, to: utf8Next)
		return offset ..< upper
	}

	private func wordForward(from offset: Int) -> Int {
		let chars = characterOffsets()
		guard let index = chars.firstIndex(where: { $0.offset >= offset }), index < chars.count else {
			return rope.length
		}
		let current = chars[index].character
		if isAlphaNumeric(current) {
			var cursor = index
			while cursor < chars.count, isAlphaNumeric(chars[cursor].character) {
				cursor += 1
			}
			return cursor < chars.count ? chars[cursor].offset : rope.length
		}
		return index + 1 < chars.count ? chars[index + 1].offset : rope.length
	}

	private func wordBackward(from offset: Int) -> Int {
		let chars = characterOffsets()
		guard !chars.isEmpty, offset > 0 else {
			return 0
		}
		var index = chars.lastIndex(where: { $0.offset < offset }) ?? 0
		if isAlphaNumeric(chars[index].character) {
			while index > 0, isAlphaNumeric(chars[index - 1].character) {
				index -= 1
			}
			return chars[index].offset
		}
		return chars[index].offset
	}

	private func characterOffsets() -> [(offset: Int, character: Character)] {
		var offset = 0
		return text.map { character in
			defer { offset += String(character).utf8.count }
			return (offset, character)
		}
	}

	private mutating func apply(_ edit: Edit) {
		if !edit.range.isEmpty {
			rope.remove(edit.range)
		}
		if !edit.newText.isEmpty {
			rope.insert(edit.newText, at: edit.range.lowerBound)
		}
	}

	private mutating func applyInverse(_ edit: Edit) {
		let range = edit.range.lowerBound ..< edit.range.lowerBound + edit.newText.utf8.count
		if !range.isEmpty {
			rope.remove(range)
		}
		if !edit.oldText.isEmpty {
			rope.insert(edit.oldText, at: edit.range.lowerBound)
		}
	}
}

private func merge(_ ranges: [Range<Int>]) -> [Range<Int>] {
	let sorted = ranges.sorted { lhs, rhs in
		if lhs.lowerBound == rhs.lowerBound {
			return lhs.upperBound < rhs.upperBound
		}
		return lhs.lowerBound < rhs.lowerBound
	}
	guard var current = sorted.first else {
		return []
	}
	var merged: [Range<Int>] = []
	for range in sorted.dropFirst() {
		if range.lowerBound <= current.upperBound {
			current = current.lowerBound ..< max(current.upperBound, range.upperBound)
		} else {
			merged.append(current)
			current = range
		}
	}
	merged.append(current)
	return merged
}

private func isAlphaNumeric(_ character: Character) -> Bool {
	!character.unicodeScalars.isEmpty && character.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) }
}

private extension String {
	func index(atUTF8Offset offset: Int) -> String.Index {
		let utf8Index = utf8.index(utf8.startIndex, offsetBy: offset)
		guard let index = String.Index(utf8Index, within: self) else {
			preconditionFailure("utf8 offset must be a character boundary")
		}
		return index
	}
}
