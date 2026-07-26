import ItsyEditor
import Testing

@Test func multiCursorInsertIsIndependentOfCursorOrder() {
	for storage in [EditorStorageKind.rope, .pieceTree] {
		var rng = MultiCursorDeterminismRNG(0xC0DEC0DE)
		for _ in 0 ..< 100 {
			let source = "abcdefghij"
			let count = rng.nextInt(4) + 1
			let offsets = (0 ..< count).map { _ in rng.nextInt(source.utf8.count + 1) }
			let selections = offsets.map { Selection(anchor: $0, head: $0, affinity: $0 % 2 == 0 ? .upstream : .downstream) }
			let forward = applyMultiCursorInsert(selections, to: source, storage: storage)
			let reverse = applyMultiCursorInsert(Array(selections.reversed()), to: source, storage: storage)
			let rotated = applyMultiCursorInsert(Array(selections.dropFirst()) + Array(selections.prefix(1)), to: source, storage: storage)
			#expect(forward.text == reverse.text)
			#expect(forward.selections == reverse.selections)
			#expect(forward.text == rotated.text)
			#expect(forward.selections == rotated.selections)
		}
	}
}

@Test func multiCursorEditUndoRedoRestoresTextAndSelections() {
	for storage in [EditorStorageKind.rope, .pieceTree] {
		var editor = Editor(text: "abcdefgh", storage: storage)
		editor.setSelection(SelectionSet(
			primary: Selection(anchor: 5, head: 5),
			secondaries: [
				Selection(anchor: 1, head: 1),
				Selection(anchor: 5, head: 5),
			]
		))
		let beforeText = multiCursorText(editor)
		let beforeSelections = editor.selections

		editor.insert("X")
		let afterText = multiCursorText(editor)
		let afterSelections = editor.selections
		#expect(editor.history.edits.count == 1)

		editor.undo()
		#expect(multiCursorText(editor) == beforeText)
		#expect(editor.selections == beforeSelections)

		editor.redo()
		#expect(multiCursorText(editor) == afterText)
		#expect(editor.selections == afterSelections)
	}
}

private func applyMultiCursorInsert(_ selections: [Selection], to text: String, storage: EditorStorageKind) -> (text: String, selections: SelectionSet) {
	var editor = Editor(text: text, storage: storage)
	editor.setSelection(SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst())))
	editor.insert("X")
	return (multiCursorText(editor), editor.selections)
}

private func multiCursorText(_ editor: Editor) -> String {
	editor.textStorage.substring(0 ..< editor.textStorage.length)
}

private struct MultiCursorDeterminismRNG {
	private var state: UInt64

	init(_ seed: UInt64) {
		state = seed
	}

	mutating func nextInt(_ upperBound: Int) -> Int {
		precondition(upperBound > 0)
		state = state &* 6364136223846793005 &+ 1442695040888963407
		return Int(state % UInt64(upperBound))
	}
}
