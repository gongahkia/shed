import PicoEditor
import Testing

@Test func editorInsertsAndDeletesText() {
	var editor = Editor()
	editor.insert("hello")
	#expect(editor.text == "hello")
	#expect(editor.selections.primary.head == 5)
	editor.deleteBackward()
	#expect(editor.text == "hell")
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
