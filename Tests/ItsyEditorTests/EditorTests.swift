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
	let selectionAfter = editor.selections
	#expect(entry.edit == Edit(range: 5 ..< 5, removed: Data(), inserted: Data("!".utf8), selectionBefore: selectionBefore))
	#expect(entry.reverse == Edit(range: 5 ..< 6, removed: Data("!".utf8), inserted: Data(), selectionBefore: selectionAfter))
	#expect(entry.selectionBefore == selectionBefore)
	#expect(entry.selectionAfter == selectionAfter)

	editor.undo()
	#expect(editor.selections == selectionBefore)
	editor.redo()
	#expect(editor.selections == selectionAfter)
}

@Test func editorUndoTreeBranchesAndRestoresSnapshots() throws {
	var editor = Editor(text: "a", storage: .pieceTree)
	editor.setSelection(SelectionSet(primary: Selection(anchor: 1, head: 1)))
	editor.insert("b")
	let firstID = try #require(editor.history.tree.currentNode?.id)
	editor.insert("c")
	let secondID = try #require(editor.history.tree.currentNode?.id)
	editor.undo()
	editor.setSelection(SelectionSet(primary: Selection(anchor: 2, head: 2)))
	editor.insert("d")
	let branchID = try #require(editor.history.tree.currentNode?.id)

	let first = try #require(editor.history.tree.node(id: firstID))
	#expect(Set(first.childIDs) == Set([secondID, branchID]))
	#expect(editorTextStorageString(editor) == "abd")

	let restored = editor.restoreUndoTreeNode(id: secondID)
	#expect(restored)
	#expect(editorTextStorageString(editor) == "abc")
	#expect(editor.history.tree.currentID == secondID)
	editor.undo()
	#expect(editorTextStorageString(editor) == "ab")
}

@Test func undoHistoryStorePersistsCappedUndoTree() throws {
	var editor = Editor(text: "a", storage: .pieceTree)
	editor.setSelection(SelectionSet(primary: Selection(anchor: 1, head: 1)))
	editor.insert("b")
	editor.insert("c")
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
	let workspace = directory.appendingPathComponent("workspace", isDirectory: true)
	let fileURL = workspace.appendingPathComponent("file.txt")
	defer { try? FileManager.default.removeItem(at: directory) }
	let store = UndoHistoryStore(maxNodeCount: 2, maxTotalTextBytes: 16)

	try store.save(editor.history.tree, fileURL: fileURL, workspaceRoot: workspace)
	let loaded = try #require(store.load(fileURL: fileURL, workspaceRoot: workspace))

	#expect(store.historyURL(fileURL: fileURL, workspaceRoot: workspace).path.contains("/.itsy/undo/"))
	#expect(loaded.currentNode?.text == Data("abc".utf8))
	#expect(loaded.nodes.count <= 2 || loaded.path(to: loaded.currentID).count > 2)
	for node in loaded.orderedNodes where node.id != loaded.rootID {
		#expect(node.parentID.flatMap { loaded.node(id: $0) } != nil)
	}

	var restored = Editor(text: "abc", storage: .pieceTree)
	restored.history.replaceTree(loaded)
	restored.undo()
	#expect(editorTextStorageString(restored) == "ab")
}

@Test func undoStackDropsOldestEntriesByCount() {
	var editor = Editor(text: "", storage: .pieceTree)
	editor.history = UndoStack(maxEditCount: 2, maxTotalRemovedBytes: Int.max)
	for text in ["a", "b", "c"] {
		editor.insert(text)
	}
	#expect(editor.history.edits.map(\.edit.inserted) == [Data("b".utf8), Data("c".utf8)])
}

@Test func undoStackDropsOldestEntriesByRemovedByteBudget() {
	var editor = Editor(text: "abcd", storage: .pieceTree)
	editor.history = UndoStack(maxEditCount: 10, maxTotalRemovedBytes: 3)
	editor.setSelection(SelectionSet(primary: Selection(anchor: 0, head: 2)))
	editor.deleteForward()
	editor.setSelection(SelectionSet(primary: Selection(anchor: 0, head: 2)))
	editor.deleteForward()
	#expect(editor.history.edits.map(\.edit.removed) == [Data("cd".utf8)])
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

@Test func editorTextMutationsCommitOneTransactionAndUndoEntry() throws {
	var editor = Editor(text: "alpha beta", storage: .pieceTree)
	let selectionBefore = SelectionSet(
		primary: Selection(anchor: 0, head: 5),
		secondaries: [Selection(anchor: 6, head: 10)]
	)
	editor.setSelection(selectionBefore)
	let mutation = editor.insert("x")
	let transaction = try #require(mutation)

	#expect(editorTextStorageString(editor) == "x x")
	#expect(transaction.edits == editor.lastEditBatch)
	#expect(transaction.selectionBefore == selectionBefore)
	#expect(transaction.selectionAfter == editor.selections)
	#expect(editor.history.edits.count == 1)
	editor.undo()
	#expect(editorTextStorageString(editor) == "alpha beta")
}

@Test func editorNoOpTextMutationDoesNotCommitTransaction() {
	var editor = Editor(text: "", storage: .pieceTree)
	let backwardDelete = editor.deleteBackward()
	#expect(backwardDelete == nil)
	#expect(editorTextStorageString(editor) == "")
	#expect(editor.lastEditBatch.isEmpty)
	#expect(editor.history.edits.isEmpty)

	let emptyInsert = editor.insert("")
	#expect(emptyInsert == nil)
	#expect(editorTextStorageString(editor) == "")
	#expect(editor.lastEditBatch.isEmpty)
	#expect(editor.history.edits.isEmpty)
}

@Test func editorPieceTreeDeletesWholeGraphemeClusters() {
	let text = "a👩‍💻b"
	var backward = Editor(text: text, storage: .pieceTree)
	backward.setSelection(SelectionSet(primary: Selection(anchor: 12, head: 12)))
	backward.deleteBackward()
	#expect(editorTextStorageString(backward) == "ab")

	var forward = Editor(text: text, storage: .pieceTree)
	forward.setSelection(SelectionSet(primary: Selection(anchor: 1, head: 1)))
	forward.deleteForward()
	#expect(editorTextStorageString(forward) == "ab")
}

@Test func editorAppliesInsertToEverySelection() {
	var editor = Editor(text: "alpha beta")
	editor.setSelection(SelectionSet(
		primary: Selection(anchor: 0, head: 5),
		secondaries: [Selection(anchor: 6, head: 10)]
	))
	editor.insert("x")
	#expect(editorTextStorageString(editor) == "x x")
	#expect(editor.history.edits.count == 1)
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

@Test func editorUndoRedoRandomSequencesMatchCycledPieceTreeRuns() {
	var rng = EditorUndoFuzzRNG(0x9440_0001)
	var forward = Editor(text: "seed\n", storage: .pieceTree)
	var cycled = Editor(text: "seed\n", storage: .pieceTree)
	let inserts = ["a", "b", "Z", "\n", "  ", "=", "{}", "let ", "return "]

	for _ in 0 ..< 10_000 {
		let forwardSnapshot = editorTextStorageString(forward)
		let cycledSnapshot = editorTextStorageString(cycled)
		#expect(cycledSnapshot == forwardSnapshot)
		guard cycledSnapshot == forwardSnapshot else {
			return
		}

		let length = forward.textStorage.length
		let roll = rng.nextInt(100)
		if roll < 45 || length == 0 {
			let offset = rng.nextInt(length + 1)
			let text = inserts[rng.nextInt(inserts.count)]
			editorInsert(text, at: offset, in: &forward)
			editorInsert(text, at: offset, in: &cycled)
			cycleUndoRedo(&cycled)
		} else if roll < 75 {
			let lower = rng.nextInt(length)
			let upper = lower + 1 + rng.nextInt(min(16, length - lower))
			editorDelete(lower ..< upper, in: &forward)
			editorDelete(lower ..< upper, in: &cycled)
			cycleUndoRedo(&cycled)
		} else if roll < 88 {
			forward.undo()
			cycled.undo()
		} else {
			forward.redo()
			cycled.redo()
		}
	}

	#expect(editorTextStorageString(cycled) == editorTextStorageString(forward))
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

private func editorInsert(_ text: String, at offset: Int, in editor: inout Editor) {
	editor.setSelection(SelectionSet(primary: Selection(anchor: offset, head: offset)))
	editor.insert(text)
}

private func editorDelete(_ range: Range<Int>, in editor: inout Editor) {
	editor.setSelection(SelectionSet(primary: Selection(anchor: range.lowerBound, head: range.upperBound)))
	editor.deleteForward()
}

private func cycleUndoRedo(_ editor: inout Editor) {
	editor.undo()
	editor.redo()
	editor.undo()
	editor.redo()
}

private struct EditorUndoFuzzRNG {
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
