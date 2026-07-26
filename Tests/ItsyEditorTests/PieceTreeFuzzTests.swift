@testable import ItsyEditor
import Testing

@Test func pieceTreeFuzzOneMillionRandomOpsMatchByteOracle() {
	var rng = PieceTreeFuzzRNG(0x9220_0001)
	var tree = PieceTree()
	var oracle = PieceTreeByteOracle()
	let inserts = ["a", "b", "Z", "\n", "\r\n", "é", "e\u{301}", "中", "🇸🇬", "👋🏽"]
	let maxSegments = 512

	for operation in 0 ..< 1_000_000 {
		if oracle.segmentCount == 0 || (oracle.segmentCount < maxSegments && rng.nextInt(100) < 45) {
			let text = inserts[rng.nextInt(inserts.count)]
			let index = rng.nextInt(oracle.segmentCount + 1)
			let offset = oracle.insert(text, atSegment: index)
			tree.insert(text, at: offset)
		} else if rng.nextInt(100) < 55 {
			let start = rng.nextInt(oracle.segmentCount)
			let count = min(1 + rng.nextInt(8), oracle.segmentCount - start)
			let range = oracle.removeSegments(start ..< start + count)
			tree.remove(range)
		} else {
			let start = rng.nextInt(oracle.segmentCount + 1)
			let end = start + rng.nextInt(oracle.segmentCount - start + 1)
			let range = oracle.byteRange(forSegments: start ..< end)
			#expect(tree.substring(range) == oracle.substring(range))
		}
		if operation % 25_000 == 0 {
			expectPieceTree(tree, matches: oracle)
		}
	}
	expectPieceTree(tree, matches: oracle)
}

private struct PieceTreeByteOracle {
	private var segments: [String] = []
	private var bytes: [UInt8] = []

	var segmentCount: Int {
		segments.count
	}

	mutating func insert(_ text: String, atSegment index: Int) -> Int {
		let offset = byteOffset(forSegment: index)
		let inserted = Array(text.utf8)
		segments.insert(text, at: index)
		bytes.insert(contentsOf: inserted, at: offset)
		return offset
	}

	mutating func removeSegments(_ range: Range<Int>) -> Range<Int> {
		let lower = byteOffset(forSegment: range.lowerBound)
		let upper = byteOffset(forSegment: range.upperBound)
		segments.removeSubrange(range)
		bytes.removeSubrange(lower ..< upper)
		return lower ..< upper
	}

	func byteRange(forSegments range: Range<Int>) -> Range<Int> {
		byteOffset(forSegment: range.lowerBound) ..< byteOffset(forSegment: range.upperBound)
	}

	func substring(_ range: Range<Int>) -> String {
		String(decoding: bytes[range], as: UTF8.self)
	}

	func snapshot() -> PieceTreeByteSnapshot {
		PieceTreeByteSnapshot(bytes: bytes)
	}

	private func byteOffset(forSegment index: Int) -> Int {
		segments.prefix(index).reduce(0) { $0 + $1.utf8.count }
	}
}

private struct PieceTreeByteSnapshot {
	var bytes: [UInt8]

	var lineCount: Int {
		bytes.reduce(1) { $1 == 10 ? $0 + 1 : $0 }
	}

	var string: String {
		String(decoding: bytes, as: UTF8.self)
	}
}

private func expectPieceTree(_ tree: PieceTree, matches oracle: PieceTreeByteOracle) {
	let snapshot = oracle.snapshot()
	#expect(tree.length == snapshot.bytes.count)
	#expect(tree.lineCount == snapshot.lineCount)
	#expect(tree.substring(0 ..< tree.length) == snapshot.string)
	expectGraphemeIndexes(in: tree, match: snapshot.string)
}

private func expectGraphemeIndexes(in tree: PieceTree, match text: String) {
	let expected = expectedGraphemeIndexes(in: text)
	#expect(tree.graphemeCount == expected[expected.count - 1])
	var previous = 0
	for offset in 0 ..< expected.count {
		let actual = tree.graphemeIndex(forOffset: offset)
		#expect(actual >= previous)
		#expect(actual == expected[offset])
		previous = actual
	}
}

private func expectedGraphemeIndexes(in text: String) -> [Int] {
	var result = Array(repeating: 0, count: text.utf8.count + 1)
	var offset = 0
	var grapheme = 0
	for character in text {
		let next = offset + String(character).utf8.count
		for byteOffset in offset ..< next {
			result[byteOffset] = grapheme
		}
		grapheme += 1
		result[next] = grapheme
		offset = next
	}
	return result
}

private struct PieceTreeFuzzRNG {
	private var state: UInt64

	init(_ seed: UInt64) {
		state = seed
	}

	mutating func nextInt(_ upperBound: Int) -> Int {
		precondition(upperBound > 0)
		state = state &* 2862933555777941757 &+ 3037000493
		return Int(state % UInt64(upperBound))
	}
}
