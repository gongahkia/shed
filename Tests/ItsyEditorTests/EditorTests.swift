import Foundation
import ItsyConfig
import ItsyEditor
import Testing

@Test func editorStorageFlagResolvesEnvironmentBeforeSettings() {
	let settings = ItsySettings(editor: .init(experimental: .init(storage: .rope)))
	#expect(Editor.resolveStorage(environment: ["ITSY_EDITOR_STORAGE": "piecetree"], settings: settings) == .pieceTree)
	#expect(Editor.resolveStorage(environment: ["ITSY_EDITOR_STORAGE": "rope"], settings: settings) == .rope)
}

@Test func editorStorageFlagFallsBackToSettings() {
	let settings = ItsySettings(editor: .init(experimental: .init(storage: .pieceTree)))
	#expect(Editor.resolveStorage(environment: [:], settings: settings) == .pieceTree)
	#expect(Editor.resolveStorage(environment: ["ITSY_EDITOR_STORAGE": "invalid"], settings: settings) == .pieceTree)
}

@Test func editorStorageDefaultsToPieceTreeWithRopeEnvironmentFallback() {
	let settings = ItsySettings()
	#expect(Editor.resolveStorage(environment: [:], settings: settings) == .pieceTree)
	#expect(Editor.resolveStorage(environment: ["ITSY_EDITOR_STORAGE": "rope"], settings: settings) == .rope)
}

@Test func editorInitializesSelectedTextStorageWithoutChangingRopePath() {
	let pieceTreeEditor = Editor(text: "abc", storage: .pieceTree)
	#expect(pieceTreeEditor.textStorage.kind == .pieceTree)
	#expect(editorTextStorageString(pieceTreeEditor) == "abc")

	var ropeEditor = Editor(text: "a", storage: .rope)
	ropeEditor.insert("b")
	#expect(ropeEditor.textStorage.kind == .rope)
	if case let .rope(rope) = ropeEditor.textStorage {
		#expect(rope.slice(0 ..< rope.length) == "ba")
	}
}

@Test func editorPieceTreeStorageRoutesEditsUndoRedoAndKeepsTreeCurrent() {
	var editor = Editor(text: "alpha\nbeta", storage: .pieceTree)
	editor.setSelection(SelectionSet(primary: Selection(anchor: 5, head: 5)))
	editor.insert("!")
	#expect(editorTextStorageString(editor) == "alpha!\nbeta")

	editor.deleteBackward()
	#expect(editorTextStorageString(editor) == "alpha\nbeta")

	editor.setSelection(SelectionSet(primary: Selection(anchor: 6, head: 10)))
	editor.insert("B")
	#expect(editorTextStorageString(editor) == "alpha\nB")
	#expect(editor.lastEditBatch.first?.oldText == "beta")

	editor.undo()
	#expect(editorTextStorageString(editor) == "alpha\nbeta")
	#expect(editorTextStorageKind(editor) == .pieceTree)

	editor.redo()
	#expect(editorTextStorageString(editor) == "alpha\nB")
}

@Test func editorUndoEntryStoresForwardReverseEditsAndSelections() throws {
	var editor = Editor(text: "alpha", storage: .pieceTree)
	let selectionBefore = SelectionSet(primary: Selection(anchor: 5, head: 5))
	editor.setSelection(selectionBefore)
	editor.insert("!")
	let entry = try #require(editor.history.edits.last)
	#expect(entry.edit == Edit(range: 5 ..< 5, removed: Data(), inserted: Data("!".utf8), selectionBefore: selectionBefore))
	#expect(entry.reverse == Edit(range: 5 ..< 6, removed: Data("!".utf8), inserted: Data()))
	#expect(entry.selectionBefore == selectionBefore)
	#expect(entry.selectionAfter == editor.selections)
}

@Test func editorInsertsAndDeletesText() {
	var editor = Editor()
	editor.insert("hello")
	#expect(editorTextStorageString(editor) == "hello")
	#expect(editor.lastEditBatch == [Edit(range: 0 ..< 0, oldText: "", newText: "hello")])
	#expect(editor.selections.primary.head == 5)
	editor.deleteBackward()
	#expect(editorTextStorageString(editor) == "hell")
	#expect(editor.lastEditBatch == [Edit(range: 4 ..< 5, oldText: "o", newText: "", selectionBefore: SelectionSet(primary: Selection(anchor: 5, head: 5)))])
	editor.deleteForward()
	#expect(editorTextStorageString(editor) == "hell")
	editor.moveCursor(.bufferStart)
	editor.deleteForward()
	#expect(editorTextStorageString(editor) == "ell")
}

@Test func editorAppliesInsertToEverySelection() {
	var editor = Editor(text: "alpha beta")
	editor.setSelection(SelectionSet(
		primary: Selection(anchor: 0, head: 5),
		secondaries: [Selection(anchor: 6, head: 10)]
	))
	editor.insert("x")
	#expect(editorTextStorageString(editor) == "x x")
	#expect(editor.history.edits.count == 2)
}

@Test func editorUndoRedoRestoresThousandEditTranscript() {
	var editor = Editor()
	var expected = ""
	for index in 0 ..< 1_000 {
		let char = String(UnicodeScalar(97 + (index % 26))!)
		editor.insert(char)
		expected += char
	}
	#expect(editorTextStorageString(editor) == expected)
	for _ in 0 ..< 1_000 {
		editor.undo()
	}
	#expect(editorTextStorageString(editor) == "")
	for _ in 0 ..< 1_000 {
		editor.redo()
	}
	#expect(editorTextStorageString(editor) == expected)
}

@Test func editorUndoRedoRestoresGroupedEditTranscript() {
	var editor = Editor()
	editor.beginUndoGroup()
	editor.insert("a")
	editor.insert("b")
	editor.insert("c")
	editor.endUndoGroup()
	#expect(editorTextStorageString(editor) == "abc")
	editor.undo()
	#expect(editorTextStorageString(editor) == "")
	editor.redo()
	#expect(editorTextStorageString(editor) == "abc")
}

@Test func killRingStoresSixtyEntriesAndRotates() {
	var ring = KillRing()
	for index in 0 ..< 61 {
		ring.push("entry-\(index)")
	}
	#expect(ring.current == "entry-60")
	for _ in 0 ..< 59 {
		_ = ring.rotate()
	}
	#expect(ring.current == "entry-1")
	#expect(ring.rotate() == "entry-60")
}

@Test func editorMotionPrimitivesMoveCursor() {
	var editor = Editor(text: "abc, de\nsecond line\nthird")
	editor.pageLineCount = 1
	editor.moveCursor(.charForward)
	#expect(editor.selections.primary.head == 1)
	editor.moveCursor(.charBackward)
	#expect(editor.selections.primary.head == 0)
	editor.moveCursor(.wordForward)
	#expect(editor.selections.primary.head == 3)
	editor.moveCursor(.wordForward)
	#expect(editor.selections.primary.head == 4)
	editor.moveCursor(.wordForward)
	#expect(editor.selections.primary.head == 5)
	editor.moveCursor(.wordForward)
	#expect(editor.selections.primary.head == 8)
	editor.moveCursor(.wordBackward)
	#expect(editor.selections.primary.head == 5)
	editor.moveCursor(.lineEnd)
	#expect(editor.selections.primary.head == 7)
	editor.moveCursor(.visualLineStart)
	#expect(editor.selections.primary.head == 0)
	editor.moveCursor(.visualLineEnd)
	#expect(editor.selections.primary.head == 7)
	editor.moveCursor(.pageDown)
	#expect(editor.selections.primary.head == 8)
	editor.moveCursor(.lineEnd)
	#expect(editor.selections.primary.head == 19)
	editor.moveCursor(.pageUp)
	#expect(editor.selections.primary.head == 0)
	editor.moveCursor(.bufferEnd)
	#expect(editor.selections.primary.head == editor.rope.length)
	editor.moveCursor(.bufferStart)
	#expect(editor.selections.primary.head == 0)
	editor.moveCursor(.lineDown)
	#expect(editor.selections.primary.head == 8)
	editor.moveCursor(.lineUp)
	#expect(editor.selections.primary.head == 0)
	editor.moveCursor(.bigWordForward)
	#expect(editor.selections.primary.head == 5)
	editor.moveCursor(.bigWordEnd)
	#expect(editor.selections.primary.head == 6)
}

private func editorTextStorageKind(_ value: Editor) -> EditorStorageKind {
	value.textStorage.kind
}

private func editorTextStorageString(_ value: Editor) -> String {
	switch value.textStorage {
	case let .rope(rope):
		return rope.slice(0 ..< rope.length)
	case let .pieceTree(pieceTree):
		return pieceTree.substring(0 ..< pieceTree.length)
	}
}
