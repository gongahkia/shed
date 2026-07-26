import ItsyEditor
import Testing

@Test func editorNormalizesSelectionOffsetsAtGraphemeBoundaries() {
	for storage in [EditorStorageKind.rope, .pieceTree] {
		var editor = Editor(text: "a👩‍💻b", storage: storage)
		editor.setSelection(SelectionSet(primary: Selection(anchor: 2, head: 2, affinity: .upstream)))
		#expect(editor.selections.primary == Selection(anchor: 1, head: 1, affinity: .upstream))

		editor.setSelection(SelectionSet(primary: Selection(anchor: 2, head: 99, affinity: .downstream)))
		#expect(editor.selections.primary == Selection(anchor: 12, head: 13, affinity: .downstream))

		editor.setSelection(SelectionSet(
			primary: Selection(anchor: 1, head: 12),
			secondaries: [Selection(anchor: 2, head: 99, affinity: .downstream)]
		))
		#expect(editor.selections == SelectionSet(primary: Selection(anchor: 1, head: 13)))
		assertSelectionInvariants(editor)
	}
}

@Test func editorNormalizesEveryMultiSelectionAfterTextStorageReplacement() {
	var editor = Editor(text: "ab", storage: .rope)
	editor.setSelection(SelectionSet(
		primary: Selection(anchor: 1, head: 1, affinity: .upstream),
		secondaries: [Selection(anchor: 2, head: 2, affinity: .downstream)]
	))
	editor.rope = Rope("a\u{301}b")

	#expect(editor.selections == SelectionSet(
		primary: Selection(anchor: 0, head: 0, affinity: .upstream),
		secondaries: [Selection(anchor: 3, head: 3, affinity: .downstream)]
	))
	assertSelectionInvariants(editor)
}

@Test func editorSelectionInvariantsSurviveRandomEditsAndHistory() {
	for storage in [EditorStorageKind.rope, .pieceTree] {
		var rng = SelectionInvariantRNG(0x51EC710)
		var editor = Editor(text: "a👩‍💻e\u{301}\n", storage: storage)
		for _ in 0 ..< 100 {
			let upperBound = editor.textStorage.length + 7
			let first = rng.nextInt(upperBound) - 3
			let second = rng.nextInt(upperBound) - 3
			let affinity: Affinity = rng.nextInt(2) == 0 ? .upstream : .downstream
			editor.setSelection(SelectionSet(
				primary: Selection(anchor: first, head: second, affinity: affinity),
				secondaries: [Selection(anchor: second - 1, head: first + 1, affinity: affinity)]
			))
			switch rng.nextInt(4) {
			case 0:
				editor.insert(["x", "👩‍💻", "e\u{301}", "\n"][rng.nextInt(4)])
			case 1:
				editor.deleteBackward()
			case 2:
				editor.moveCursor(.charForward)
			default:
				editor.moveCursor(.charBackward)
			}
			assertSelectionInvariants(editor)
		}
		editor.undo()
		assertSelectionInvariants(editor)
		editor.redo()
		assertSelectionInvariants(editor)
	}
}

private func assertSelectionInvariants(_ editor: Editor) {
	let selections = [editor.selections.primary] + editor.selections.secondaries
	#expect(selections.allSatisfy {
		editor.textStorage.isGraphemeBoundary($0.anchor) && editor.textStorage.isGraphemeBoundary($0.head)
	}, "invalid selection for \(editor.textStorage.kind): \(editorTextForSelectionInvariant(editor)); \(editor.selections)")
	for (previous, current) in zip(selections, selections.dropFirst()) {
		#expect(previous.range.upperBound < current.range.lowerBound)
	}
}

private func editorTextForSelectionInvariant(_ editor: Editor) -> String {
	editor.textStorage.substring(0 ..< editor.textStorage.length)
}

private struct SelectionInvariantRNG {
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
