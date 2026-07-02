import Foundation
import ItsyConfig

public enum Motion: Sendable, Equatable {
	case charForward
	case charBackward
	case lineDown
	case lineUp
	case wordForward
	case wordBackward
	case wordEnd
	case bigWordForward
	case bigWordBackward
	case bigWordEnd
	case lineStart
	case lineEnd
	case visualLineStart
	case visualLineEnd
	case bufferStart
	case bufferEnd
	case paragraphForward
	case paragraphBackward
	case pageDown
	case pageUp
}

public struct UndoStack: Sendable, Equatable {
	public var maxEditCount: Int
	public var maxTotalRemovedBytes: Int
	public private(set) var edits: [UndoEntry] = []
	private var redoEdits: [UndoEntry] = []
	private var activeGroupID: Int?
	private var nextGroupID = 1

	public init(maxEditCount: Int = 10_000, maxTotalRemovedBytes: Int = 64 * 1024 * 1024) {
		precondition(maxEditCount > 0, "maxEditCount must be positive")
		precondition(maxTotalRemovedBytes >= 0, "maxTotalRemovedBytes must be non-negative")
		self.maxEditCount = maxEditCount
		self.maxTotalRemovedBytes = maxTotalRemovedBytes
	}

	mutating func record(_ edit: Edit, reverse: Edit, selectionBefore: SelectionSet, selectionAfter: SelectionSet) {
		edits.append(UndoEntry(
			edit: edit,
			reverse: reverse,
			selectionAfter: selectionAfter,
			selectionBefore: selectionBefore,
			groupID: activeGroupID
		))
		redoEdits.removeAll()
		trimUndoHistory()
	}

	mutating func beginGroup() {
		guard activeGroupID == nil else {
			return
		}
		activeGroupID = nextGroupID
		nextGroupID += 1
	}

	mutating func endGroup() {
		activeGroupID = nil
	}

	mutating func popUndo() -> [UndoEntry]? {
		guard let entry = edits.popLast() else {
			return nil
		}
		var entries = [entry]
		if let groupID = entry.groupID {
			while edits.last?.groupID == groupID, let groupedEntry = edits.popLast() {
				entries.append(groupedEntry)
			}
		}
		redoEdits.append(contentsOf: entries)
		return entries
	}

	mutating func popRedo() -> [UndoEntry]? {
		guard let entry = redoEdits.popLast() else {
			return nil
		}
		var entries = [entry]
		if let groupID = entry.groupID {
			while redoEdits.last?.groupID == groupID, let groupedEntry = redoEdits.popLast() {
				entries.append(groupedEntry)
			}
		}
		edits.append(contentsOf: entries)
		return entries
	}

	private mutating func trimUndoHistory() {
		while edits.count + redoEdits.count > maxEditCount || retainedRemovedBytes > maxTotalRemovedBytes {
			guard !edits.isEmpty else {
				redoEdits.removeAll()
				return
			}
			dropOldestUndoGroup()
		}
	}

	private var retainedRemovedBytes: Int {
		retainedRemovedBytes(in: edits) + retainedRemovedBytes(in: redoEdits)
	}

	private func retainedRemovedBytes(in entries: [UndoEntry]) -> Int {
		entries.reduce(0) { $0 + $1.edit.removed.count }
	}

	private mutating func dropOldestUndoGroup() {
		guard let first = edits.first else {
			return
		}
		let groupID = first.groupID
		edits.removeFirst()
		guard let groupID else {
			return
		}
		while edits.first?.groupID == groupID {
			edits.removeFirst()
		}
	}
}

public struct UndoEntry: Sendable, Equatable {
	public var edit: Edit
	public var reverse: Edit
	public var selectionAfter: SelectionSet
	public var selectionBefore: SelectionSet
	public var groupID: Int?
}

public enum EditorStorageKind: String, Sendable, Equatable {
	case rope
	case pieceTree = "piecetree"

	init(_ setting: ItsySettings.EditorStorage) {
		switch setting {
		case .rope:
			self = .rope
		case .pieceTree:
			self = .pieceTree
		}
	}
}

public enum EditorTextStorage: Sendable {
	case rope(Rope)
	case pieceTree(PieceTree)

	public var kind: EditorStorageKind {
		switch self {
		case .rope:
			return .rope
		case .pieceTree:
			return .pieceTree
		}
	}

	public var length: Int {
		switch self {
		case let .rope(rope):
			return rope.length
		case let .pieceTree(pieceTree):
			return pieceTree.length
		}
	}

	public var lineCount: Int {
		switch self {
		case let .rope(rope):
			return rope.lineCount
		case let .pieceTree(pieceTree):
			return pieceTree.lineCount
		}
	}

	public var graphemeCount: Int {
		switch self {
		case let .rope(rope):
			return rope.graphemeCount
		case let .pieceTree(pieceTree):
			return pieceTree.graphemeCount
		}
	}

	public func substring(_ range: Range<Int>) -> String {
		switch self {
		case let .rope(rope):
			return rope.substring(range)
		case let .pieceTree(pieceTree):
			return pieceTree.substring(range)
		}
	}

	public func line(forOffset offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.line(forOffset: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.line(forOffset: offset)
		}
	}

	public func offset(forLine line: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.offset(forLine: line)
		case let .pieceTree(pieceTree):
			return pieceTree.offset(forLine: line)
		}
	}

	public func lineRange(_ line: Int) -> Range<Int> {
		switch self {
		case let .rope(rope):
			return rope.lineRange(line)
		case let .pieceTree(pieceTree):
			return pieceTree.lineRange(line)
		}
	}

	public func graphemeIndex(forOffset offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.graphemeIndex(forOffset: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.graphemeIndex(forOffset: offset)
		}
	}

	public func previousGraphemeBoundary(before offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.previousGraphemeBoundary(before: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.previousGraphemeBoundary(before: offset)
		}
	}

	public func nextGraphemeBoundary(after offset: Int) -> Int {
		switch self {
		case let .rope(rope):
			return rope.nextGraphemeBoundary(after: offset)
		case let .pieceTree(pieceTree):
			return pieceTree.nextGraphemeBoundary(after: offset)
		}
	}

	public mutating func insert(_ string: String, at offset: Int) {
		switch self {
		case var .rope(rope):
			rope.insert(string, at: offset)
			self = .rope(rope)
		case var .pieceTree(pieceTree):
			pieceTree.insert(string, at: offset)
			self = .pieceTree(pieceTree)
		}
	}

	public mutating func remove(_ range: Range<Int>) {
		switch self {
		case var .rope(rope):
			rope.remove(range)
			self = .rope(rope)
		case var .pieceTree(pieceTree):
			pieceTree.remove(range)
			self = .pieceTree(pieceTree)
		}
	}

	@discardableResult
	public mutating func replace(_ range: Range<Int>, with string: String) -> Edit {
		replace(range, with: Data(string.utf8))
	}

	@discardableResult
	public mutating func replace(_ range: Range<Int>, with data: Data) -> Edit {
		switch self {
		case var .rope(rope):
			let oldText = rope.slice(range)
			if !range.isEmpty {
				rope.remove(range)
			}
			if !data.isEmpty {
				rope.insert(String(decoding: data, as: UTF8.self), at: range.lowerBound)
			}
			self = .rope(rope)
			return Edit(
				range: range.lowerBound ..< range.lowerBound + data.count,
				removed: data,
				inserted: Data(oldText.utf8)
			)
		case var .pieceTree(pieceTree):
			let reverse = pieceTree.replace(range, with: data)
			self = .pieceTree(pieceTree)
			return reverse
		}
	}
}

public struct Editor: Sendable {
	public var rope: Rope {
		get {
			switch textStorage {
			case let .rope(rope):
				return rope
			case let .pieceTree(pieceTree):
				return Rope(pieceTree.substring(0 ..< pieceTree.length))
			}
		}
		set {
			textStorage = .rope(newValue)
		}
	}
	public private(set) var textStorage: EditorTextStorage
	public var selections: SelectionSet
	public var history: UndoStack
	public private(set) var lastEditBatch: [Edit] = []
	public var pageLineCount = 40

	public init(text: String = "") {
		self.init(text: text, storage: Self.configuredStorage)
	}

	public init(text: String, storage: EditorStorageKind) {
		switch storage {
		case .rope:
			textStorage = .rope(Rope(text))
		case .pieceTree:
			textStorage = .pieceTree(PieceTree(text))
		}
		selections = SelectionSet()
		history = UndoStack()
	}

	public init(pieceTree: PieceTree) {
		textStorage = .pieceTree(pieceTree)
		selections = SelectionSet()
		history = UndoStack()
	}

	public static func resolveStorage(environment: [String: String], settings: ItsySettings) -> EditorStorageKind {
		if let rawStorage = environment["ITSY_EDITOR_STORAGE"]?.lowercased(), let storage = EditorStorageKind(rawValue: rawStorage) {
			return storage
		}
		return EditorStorageKind(settings.editor.experimental.storage)
	}

	private static let configuredStorage = resolveStorage(
		environment: ProcessInfo.processInfo.environment,
		settings: ItsySettingsStore().load().settings
	)

	public mutating func beginUndoGroup() {
		history.beginGroup()
	}

	public mutating func endUndoGroup() {
		history.endGroup()
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
		lastEditBatch = []
		let moved = selectionsForEdit().map { selection -> Selection in
			let offset: Int
			switch motion {
			case .charForward:
				offset = nextCharacterRange(after: selection.head).upperBound
			case .charBackward:
				offset = previousCharacterRange(before: selection.head).lowerBound
			case .lineDown:
				offset = verticalLineOffset(from: selection.head, delta: 1)
			case .lineUp:
				offset = verticalLineOffset(from: selection.head, delta: -1)
			case .wordForward:
				offset = wordForward(from: selection.head)
			case .wordBackward:
				offset = wordBackward(from: selection.head)
			case .wordEnd:
				offset = wordEnd(from: selection.head, isWordCharacter: isAlphaNumeric)
			case .bigWordForward:
				offset = wordForward(from: selection.head, isWordCharacter: { !$0.isWhitespace })
			case .bigWordBackward:
				offset = wordBackward(from: selection.head, isWordCharacter: { !$0.isWhitespace })
			case .bigWordEnd:
				offset = wordEnd(from: selection.head, isWordCharacter: { !$0.isWhitespace })
			case .lineStart, .visualLineStart:
				offset = textStorage.offset(forLine: textStorage.line(forOffset: selection.head))
			case .lineEnd, .visualLineEnd:
				offset = textStorage.lineRange(textStorage.line(forOffset: selection.head)).upperBound
			case .bufferStart:
				offset = 0
			case .bufferEnd:
				offset = textStorage.length
			case .paragraphForward:
				offset = paragraphForward(from: selection.head)
			case .paragraphBackward:
				offset = paragraphBackward(from: selection.head)
			case .pageDown:
				let line = min(textStorage.line(forOffset: selection.head) + pageLineCount, max(0, textStorage.lineCount - 1))
				offset = textStorage.offset(forLine: line)
			case .pageUp:
				let line = max(textStorage.line(forOffset: selection.head) - pageLineCount, 0)
				offset = textStorage.offset(forLine: line)
			}
			return Selection(anchor: offset, head: offset, affinity: selection.affinity)
		}
		selections = SelectionSet(primary: moved[0], secondaries: Array(moved.dropFirst()))
	}

	public mutating func setSelection(_ selectionSet: SelectionSet) {
		lastEditBatch = []
		selections = selectionSet
		selections.merge()
		#if DEBUG
		if case let .pieceTree(pieceTree) = textStorage {
			selections.validate("Editor.setSelection", against: pieceTree)
		}
		#endif
	}

	public mutating func undo() {
		lastEditBatch = []
		guard let entries = history.popUndo() else {
			return
		}
		for entry in entries {
			apply(entry.reverse)
		}
		selections = entries.last?.selectionBefore ?? selections
	}

	public mutating func redo() {
		lastEditBatch = []
		guard let entries = history.popRedo() else {
			return
		}
		for entry in entries {
			apply(entry.edit)
		}
		selections = entries.last?.selectionAfter ?? selections
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
		let selectionBefore = selections
		var recordedEdits: [Edit] = []
		var reverseEdits: [Edit] = []
		var carets: [Selection] = []
		for range in normalized.sorted(by: { $0.lowerBound > $1.lowerBound }) {
			let reverse = textStorage.replace(range, with: string)
			let edit = Edit(range: range, removed: reverse.inserted, inserted: reverse.removed, selectionBefore: selectionBefore)
			recordedEdits.append(edit)
			reverseEdits.append(reverse)
			let caret = range.lowerBound + string.utf8.count
			carets.append(Selection(anchor: caret, head: caret))
		}
		carets.reverse()
		selections = SelectionSet(primary: carets[0], secondaries: Array(carets.dropFirst()))
		lastEditBatch = Array(recordedEdits.reversed())
		for (edit, reverse) in zip(recordedEdits.reversed(), reverseEdits.reversed()) {
			history.record(edit, reverse: reverse, selectionBefore: selectionBefore, selectionAfter: selections)
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
		return textStorage.previousGraphemeBoundary(before: offset) ..< offset
	}

	private func nextCharacterRange(after offset: Int) -> Range<Int> {
		guard offset < textStorage.length else {
			return textStorage.length ..< textStorage.length
		}
		return offset ..< textStorage.nextGraphemeBoundary(after: offset)
	}

	private func verticalLineOffset(from offset: Int, delta: Int) -> Int {
		let line = textStorage.line(forOffset: offset)
		let lineRange = textStorage.lineRange(line)
		let column = offset - lineRange.lowerBound
		let targetLine = min(max(line + delta, 0), max(0, textStorage.lineCount - 1))
		let targetRange = textStorage.lineRange(targetLine)
		return min(targetRange.lowerBound + column, targetRange.upperBound)
	}

	private func wordForward(from offset: Int) -> Int {
		wordForward(from: offset, isWordCharacter: isAlphaNumeric)
	}

	private func wordForward(from offset: Int, isWordCharacter: (Character) -> Bool) -> Int {
		let chars = characterOffsets()
		guard let index = chars.firstIndex(where: { $0.offset >= offset }), index < chars.count else {
			return textStorage.length
		}
		let current = chars[index].character
		if isWordCharacter(current) {
			var cursor = index
			while cursor < chars.count, isWordCharacter(chars[cursor].character) {
				cursor += 1
			}
			while cursor < chars.count, chars[cursor].character.isWhitespace {
				cursor += 1
			}
			return cursor < chars.count ? chars[cursor].offset : textStorage.length
		}
		return index + 1 < chars.count ? chars[index + 1].offset : textStorage.length
	}

	private func wordBackward(from offset: Int) -> Int {
		wordBackward(from: offset, isWordCharacter: isAlphaNumeric)
	}

	private func wordBackward(from offset: Int, isWordCharacter: (Character) -> Bool) -> Int {
		let chars = characterOffsets()
		guard !chars.isEmpty, offset > 0 else {
			return 0
		}
		var index = chars.lastIndex(where: { $0.offset < offset }) ?? 0
		while index > 0, chars[index].character.isWhitespace {
			index -= 1
		}
		while index > 0, isWordCharacter(chars[index - 1].character) == isWordCharacter(chars[index].character), !chars[index - 1].character.isWhitespace {
			index -= 1
		}
		return chars[index].offset
	}

	private func wordEnd(from offset: Int, isWordCharacter: (Character) -> Bool) -> Int {
		let chars = characterOffsets()
		guard let index = chars.firstIndex(where: { $0.offset >= offset }), index < chars.count else {
			return textStorage.length
		}
		var cursor = index
		if cursor < chars.count, chars[cursor].character.isWhitespace {
			while cursor < chars.count, chars[cursor].character.isWhitespace {
				cursor += 1
			}
		}
		guard cursor < chars.count else {
			return textStorage.length
		}
		let wordState = isWordCharacter(chars[cursor].character)
		while cursor + 1 < chars.count, isWordCharacter(chars[cursor + 1].character) == wordState, !chars[cursor + 1].character.isWhitespace {
			cursor += 1
		}
		return chars[cursor].offset
	}

	private func paragraphForward(from offset: Int) -> Int {
		let currentLine = textStorage.line(forOffset: offset)
		guard currentLine + 1 < textStorage.lineCount else {
			return textStorage.length
		}
		for line in (currentLine + 1) ..< textStorage.lineCount {
			if textStorage.substring(textStorage.lineRange(line)).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				return textStorage.offset(forLine: min(line + 1, textStorage.lineCount - 1))
			}
		}
		return textStorage.length
	}

	private func paragraphBackward(from offset: Int) -> Int {
		let currentLine = textStorage.line(forOffset: offset)
		guard currentLine > 0 else {
			return 0
		}
		for line in stride(from: currentLine - 1, through: 0, by: -1) {
			if textStorage.substring(textStorage.lineRange(line)).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
				return textStorage.offset(forLine: min(line + 1, textStorage.lineCount - 1))
			}
		}
		return 0
	}

	private func characterOffsets() -> [(offset: Int, character: Character)] {
		var offset = 0
		return textStorage.substring(0 ..< textStorage.length).map { character in
			defer { offset += String(character).utf8.count }
			return (offset, character)
		}
	}

	private mutating func apply(_ edit: Edit) {
		textStorage.replace(edit.range, with: edit.inserted)
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

private extension Rope {
	func substring(_ range: Range<Int>) -> String {
		slice(range)
	}

	func previousGraphemeBoundary(before offset: Int) -> Int {
		guard offset > 0 else {
			return 0
		}
		let text = slice(0 ..< length)
		let index = text.index(atUTF8Offset: offset)
		let previous = text.index(before: index)
		guard let utf8Previous = previous.samePosition(in: text.utf8) else {
			preconditionFailure("previous character must align with utf8")
		}
		return text.utf8.distance(from: text.utf8.startIndex, to: utf8Previous)
	}

	func nextGraphemeBoundary(after offset: Int) -> Int {
		guard offset < length else {
			return length
		}
		let text = slice(0 ..< length)
		let index = text.index(atUTF8Offset: offset)
		let next = text.index(after: index)
		guard let utf8Next = next.samePosition(in: text.utf8) else {
			preconditionFailure("next character must align with utf8")
		}
		return text.utf8.distance(from: text.utf8.startIndex, to: utf8Next)
	}
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
