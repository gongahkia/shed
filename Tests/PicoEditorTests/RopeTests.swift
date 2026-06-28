@testable import PicoEditor
import Testing

@Test func ropeTracksLengthLinesAndSlices() {
	let rope = Rope("alpha\nbeta\ngamma")
	#expect(rope.length == "alpha\nbeta\ngamma".utf8.count)
	#expect(rope.lineCount == 3)
	#expect(rope.slice(0 ..< 5) == "alpha")
	#expect(rope.lineRange(1) == 6 ..< 10)
	#expect(rope.validateInvariants())
}

@Test func ropeInsertsAndRemovesByUTF8Offset() {
	var rope = Rope("hello world")
	rope.insert(" tiny", at: 5)
	#expect(rope.slice(0 ..< rope.length) == "hello tiny world")
	rope.remove(5 ..< 10)
	#expect(rope.slice(0 ..< rope.length) == "hello world")
	#expect(rope.validateInvariants())
}

@Test func ropeBuildsEightWayTreeWithSmallLeaves() {
	let text = String(repeating: "0123456789abcdef\n", count: 1_000)
	let rope = Rope(text)
	#expect(rope.length == text.utf8.count)
	#expect(rope.lineCount == 1_001)
	#expect(rope.validateInvariants())
}

@Test func ropeTracksGraphemeClustersByUTF8Offset() {
	let text = "aé🇸🇬e\u{301}"
	let rope = Rope(text)
	#expect(rope.graphemeCount == text.count)
	#expect(rope.graphemeIndex(forOffset: 0) == 0)
	var offset = 0
	for (index, character) in text.enumerated() {
		offset += String(character).utf8.count
		#expect(rope.graphemeIndex(forOffset: offset) == index + 1)
	}
	#expect(rope.validateInvariants())
}

@Test(arguments: [0xC0FFEE, 0xBAD5EED, 0x51A7E, 0xBEEFED])
func ropeRandomEditsMatchCharacterArray(seed: UInt64) {
	var rng = SeededRNG(seed)
	var rope = Rope("")
	var reference: [Character] = []
	let inserts = ["a", "b", "c", "\n", "é", "🇸🇬", "e\u{301}"]
	for _ in 0 ..< 2_500 {
		let shouldInsert = reference.isEmpty || (reference.count < 300 && rng.nextInt(100) < 60)
		if shouldInsert {
			let text = inserts[rng.nextInt(inserts.count)]
			let charIndex = rng.nextInt(reference.count + 1)
			let offset = utf8Offset(forCharacterIndex: charIndex, in: reference)
			rope.insert(text, at: offset)
			reference.insert(contentsOf: Array(text), at: charIndex)
		} else {
			let start = rng.nextInt(reference.count)
			let maxLength = min(5, reference.count - start)
			let count = rng.nextInt(maxLength) + 1
			let lower = utf8Offset(forCharacterIndex: start, in: reference)
			let upper = utf8Offset(forCharacterIndex: start + count, in: reference)
			rope.remove(lower ..< upper)
			reference.removeSubrange(start ..< start + count)
		}
		let expected = String(reference)
		#expect(rope.length == expected.utf8.count)
		#expect(rope.graphemeCount == reference.count)
		#expect(rope.slice(0 ..< rope.length) == expected)
		#expect(rope.validateInvariants())
	}
}

@Test func ropeBoundaryVectors() {
	let vectors = [
		"",
		"\n",
		"a\n",
		String(repeating: "x", count: 1024),
		String(repeating: "x", count: 1025),
		"αβγ\n🇸🇬\ne\u{301}",
	]
	for text in vectors {
		var rope = Rope(text)
		#expect(rope.slice(0 ..< rope.length) == text)
		rope.insert("Z", at: 0)
		#expect(rope.slice(0 ..< 1) == "Z")
		rope.remove(0 ..< 1)
		#expect(rope.slice(0 ..< rope.length) == text)
		#expect(rope.validateInvariants())
	}
}

@Test func ropeLineOffsetWalkMatchesLinearScanOnLargeBuffer() {
	let text = String(repeating: "abc\n", count: 100_000)
	let rope = Rope(text)
	let lines = [0, 1, 2, 99, 1_024, 65_535, 100_000]
	for line in lines {
		#expect(rope.offset(forLine: line) == linearOffset(forLine: line, in: text))
	}
	let offsets = [0, 1, 3, 4, 4_096, 262_144, text.utf8.count]
	for offset in offsets {
		#expect(rope.line(forOffset: offset) == linearLine(forOffset: offset, in: text))
	}
	#expect(rope.lineRange(65_535) == linearLineRange(65_535, in: text))
}

@Test func ropeChunkReadsDoNotMaterializeWholeBuffer() {
	let text = String(repeating: "abc\n", count: 2_000) + "é🇸🇬tail"
	let rope = Rope(text)
	var offset = 0
	var rebuilt = ""
	while offset < rope.length {
		let chunk = rope.chunk(at: offset, maxBytes: 257)
		#expect(!chunk.isEmpty)
		#expect(chunk.utf8.count <= 257 || chunk.count == 1)
		rebuilt += chunk
		offset += chunk.utf8.count
	}
	#expect(rebuilt == text)
}

@Test func ropeRepeatedASCIIFastPathPreservesCoreQueries() {
	var rope = Rope()
	for _ in 0 ..< 1_000 {
		rope.insert("a", at: rope.length / 2)
	}
	#expect(rope.length == 1_000)
	#expect(rope.graphemeCount == 1_000)
	#expect(rope.lineCount == 1)
	#expect(rope.slice(10 ..< 42) == String(repeating: "a", count: 32))
	#expect(rope.chunk(at: 990, maxBytes: 20) == String(repeating: "a", count: 10))
	var buffer = [UInt8](repeating: 0, count: 8)
	let copied = buffer.withUnsafeMutableBufferPointer { pointer in
		rope.copyUTF8Chunk(at: 3, maxBytes: 8, into: pointer.baseAddress!)
	}
	#expect(copied == 8)
	#expect(buffer == [UInt8](repeating: 97, count: 8))
	rope.remove(100 ..< 200)
	#expect(rope.length == 900)
	rope.insert("b", at: 450)
	#expect(rope.slice(448 ..< 453) == "aabaa")
	#expect(rope.validateInvariants())
}

@Test func ropeRepeatedNewlineFastPathPreservesLineQueries() {
	let rope = Rope(String(repeating: "\n", count: 4))
	#expect(rope.lineCount == 5)
	#expect(rope.lineRange(0) == 0 ..< 0)
	#expect(rope.lineRange(4) == 4 ..< 4)
	#expect(rope.offset(forLine: 3) == 3)
	#expect(rope.line(forOffset: 3) == 3)
}

private struct SeededRNG {
	private var state: UInt64

	init(_ seed: UInt64) {
		state = seed
	}

	mutating func nextInt(_ upperBound: Int) -> Int {
		state = state &* 6364136223846793005 &+ 1442695040888963407
		return Int(state % UInt64(upperBound))
	}
}

private func utf8Offset(forCharacterIndex index: Int, in chars: [Character]) -> Int {
	chars.prefix(index).reduce(0) { $0 + String($1).utf8.count }
}

private func linearOffset(forLine target: Int, in text: String) -> Int {
	guard target > 0 else {
		return 0
	}
	var line = 0
	for (offset, byte) in text.utf8.enumerated() where byte == 10 {
		line += 1
		if line == target {
			return offset + 1
		}
	}
	return text.utf8.count
}

private func linearLine(forOffset offset: Int, in text: String) -> Int {
	text.utf8.prefix(offset).reduce(0) { $1 == 10 ? $0 + 1 : $0 }
}

private func linearLineRange(_ line: Int, in text: String) -> Range<Int> {
	let start = linearOffset(forLine: line, in: text)
	let next = linearOffset(forLine: line + 1, in: text)
	return start ..< max(start, next - 1)
}
