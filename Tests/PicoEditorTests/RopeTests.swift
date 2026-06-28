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
