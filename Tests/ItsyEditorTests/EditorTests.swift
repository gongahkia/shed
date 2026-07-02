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

@Test func editorInitializesSelectedTextStorageWithoutChangingRopePath() {
	let pieceTreeEditor = Editor(text: "abc", storage: .pieceTree)
	#expect(pieceTreeEditor.textStorage.kind == .pieceTree)
	#expect(pieceTreeEditor.text == "abc")

	var ropeEditor = Editor(text: "a", storage: .rope)
	ropeEditor.insert("b")
	#expect(ropeEditor.textStorage.kind == .rope)
	if case let .rope(rope) = ropeEditor.textStorage {
		#expect(rope.slice(0 ..< rope.length) == "ba")
	}
}

@Test func editorInsertsAndDeletesText() {
	var editor = Editor()
	editor.insert("hello")
	#expect(editor.text == "hello")
	#expect(editor.lastEditBatch == [Edit(range: 0 ..< 0, oldText: "", newText: "hello")])
	#expect(editor.selections.primary.head == 5)
	editor.deleteBackward()
	#expect(editor.text == "hell")
	#expect(editor.lastEditBatch == [Edit(range: 4 ..< 5, oldText: "o", newText: "", selectionBefore: SelectionSet(primary: Selection(anchor: 5, head: 5)))])
	editor.deleteForward()
	#expect(editor.text == "hell")
	editor.moveCursor(.bufferStart)
	editor.deleteForward()
	#expect(editor.text == "ell")
}

@Test func editorAppliesInsertToEverySelection() {
	var editor = Editor(text: "alpha beta")
	editor.setSelection(SelectionSet(
		primary: Selection(anchor: 0, head: 5),
		secondaries: [Selection(anchor: 6, head: 10)]
	))
	editor.insert("x")
	#expect(editor.text == "x x")
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
	#expect(editor.text == expected)
	for _ in 0 ..< 1_000 {
		editor.undo()
	}
	#expect(editor.text == "")
	for _ in 0 ..< 1_000 {
		editor.redo()
	}
	#expect(editor.text == expected)
}

@Test func editorUndoRedoRestoresGroupedEditTranscript() {
	var editor = Editor()
	editor.beginUndoGroup()
	editor.insert("a")
	editor.insert("b")
	editor.insert("c")
	editor.endUndoGroup()
	#expect(editor.text == "abc")
	editor.undo()
	#expect(editor.text == "")
	editor.redo()
	#expect(editor.text == "abc")
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
