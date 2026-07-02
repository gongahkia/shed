import ItsyEditor
import Testing

@Test func multiCursorInsertDeletePreservesComplexGraphemeText() {
	let cases = [
		"👨‍👩‍👧‍👦",
		"\u{0915}\u{094D}\u{0915}",
		"🇸🇬🇯🇵",
		"1️⃣",
		"👋🏽",
		"👩‍🚒",
	]
	for (index, cluster) in cases.enumerated() {
		let text = Array(repeating: cluster, count: 6).joined(separator: "|")
		let offsets = selectedOffsets(in: text, seed: UInt64(index + 1), count: 5)
		var editor = Editor(text: text, storage: .pieceTree)
		editor.setSelection(selectionSet(for: offsets))
		editor.insert("#")
		#expect(editorText(editor) == insertingMarker(in: text, at: offsets))
		editor.deleteBackward()
		#expect(editorText(editor) == text)
		#expect(editor.lastEditBatch.count == offsets.count)
		#expect(editor.lastEditBatch.allSatisfy { $0.oldText == "#" && $0.newText.isEmpty })
	}
}

private func selectedOffsets(in text: String, seed: UInt64, count: Int) -> [Int] {
	let boundaries = Array(text.utf8).withUnsafeBufferPointer {
		UAX29GraphemeIterator.boundaries(in: $0)
	}
	var rng = MultiCursorGraphemeRNG(seed)
	var selected = Set<Int>()
	while selected.count < min(count, boundaries.count) {
		selected.insert(boundaries[rng.nextInt(boundaries.count)])
	}
	return selected.sorted()
}

private func selectionSet(for offsets: [Int]) -> SelectionSet {
	SelectionSet(
		primary: Selection(anchor: offsets[0], head: offsets[0]),
		secondaries: offsets.dropFirst().map { Selection(anchor: $0, head: $0) }
	)
}

private func insertingMarker(in text: String, at offsets: [Int]) -> String {
	var bytes = Array(text.utf8)
	for offset in offsets.sorted(by: >) {
		bytes.insert(35, at: offset)
	}
	return String(decoding: bytes, as: UTF8.self)
}

private func editorText(_ editor: Editor) -> String {
	switch editor.textStorage {
	case let .pieceTree(pieceTree):
		return pieceTree.substring(0 ..< pieceTree.length)
	case let .rope(rope):
		return rope.slice(0 ..< rope.length)
	}
}

private struct MultiCursorGraphemeRNG {
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
