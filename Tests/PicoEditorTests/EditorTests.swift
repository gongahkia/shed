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
