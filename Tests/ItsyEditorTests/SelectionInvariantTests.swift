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

@Test(arguments: [0x51EC_0001, 0x51EC_0002, 0x51EC_0003, 0x51EC_0004])
func editorSelectionAndUndoFuzzMatchesAcrossStorage(seed: UInt64) {
	var rng = SelectionInvariantRNG(seed)
	var rope = Editor(text: "a👩‍💻e\u{301}\n", storage: .rope)
	var pieceTree = Editor(text: "a👩‍💻e\u{301}\n", storage: .pieceTree)
	let inserts = ["x", "👩‍💻", "e\u{301}", "\n", "[]"]
	let motions: [Motion] = [.charForward, .charBackward, .wordForward, .wordBackward, .lineStart, .lineEnd, .bufferStart, .bufferEnd]

	for _ in 0 ..< 250 {
		switch rng.nextInt(7) {
		case 0:
			let selectionSet = randomSelectionSet(length: rope.textStorage.length, rng: &rng)
			rope.setSelection(selectionSet)
			pieceTree.setSelection(selectionSet)
		case 1:
			let inserted = inserts[rng.nextInt(inserts.count)]
			rope.insert(inserted)
			pieceTree.insert(inserted)
		case 2:
			rope.deleteBackward()
			pieceTree.deleteBackward()
		case 3:
			rope.deleteForward()
			pieceTree.deleteForward()
		case 4:
			let motion = motions[rng.nextInt(motions.count)]
			rope.moveCursor(motion)
			pieceTree.moveCursor(motion)
		case 5:
			rope.undo()
			pieceTree.undo()
		default:
			rope.redo()
			pieceTree.redo()
		}
		assertEquivalentEditorState(rope, pieceTree)
	}

	for _ in 0 ..< 250 {
		rope.redo()
		pieceTree.redo()
		assertEquivalentEditorState(rope, pieceTree)
	}
	let expectedText = editorTextForSelectionInvariant(rope)
	let expectedSelections = rope.selections
	for _ in 0 ..< 250 {
		rope.undo()
		pieceTree.undo()
		assertEquivalentEditorState(rope, pieceTree)
	}
	for _ in 0 ..< 250 {
		rope.redo()
		pieceTree.redo()
		assertEquivalentEditorState(rope, pieceTree)
	}
	#expect(editorTextForSelectionInvariant(rope) == expectedText)
	#expect(rope.selections == expectedSelections)
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

private func randomSelectionSet(length: Int, rng: inout SelectionInvariantRNG) -> SelectionSet {
	let count = 1 + rng.nextInt(4)
	let selections = (0 ..< count).map { _ in
		Selection(
			anchor: rng.nextInt(length + 17) - 8,
			head: rng.nextInt(length + 17) - 8,
			affinity: rng.nextInt(2) == 0 ? .upstream : .downstream
		)
	}
	return SelectionSet(primary: selections[0], secondaries: Array(selections.dropFirst()))
}

private func assertEquivalentEditorState(_ rope: Editor, _ pieceTree: Editor) {
	#expect(editorTextForSelectionInvariant(rope) == editorTextForSelectionInvariant(pieceTree))
	#expect(rope.selections == pieceTree.selections)
	#expect(rope.history.edits == pieceTree.history.edits)
	assertSelectionInvariants(rope)
	assertSelectionInvariants(pieceTree)
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
