@testable import PicoEditor
import Testing

@Test(arguments: Array(0 ..< 16))
func ropeManualFuzzRandomOpsPreserveInvariants(seedIndex: Int) {
	var rng = FuzzRNG(UInt64(seedIndex) &* 0x9E37_79B9_7F4A_7C15 &+ 0xC0FFEE)
	var rope = Rope()
	var reference: [Character] = []
	let inserts = ["a", "β", "中", "\n", "🇸🇬", "e\u{301}", "0123456789"]
	for _ in 0 ..< 1_000 {
		switch rng.nextInt(4) {
		case 0, 1:
			let text = inserts[rng.nextInt(inserts.count)]
			let index = rng.nextInt(reference.count + 1)
			let offset = utf8Offset(forCharacterIndex: index, in: reference)
			rope.insert(text, at: offset)
			reference.insert(contentsOf: text, at: index)
		case 2 where !reference.isEmpty:
			let start = rng.nextInt(reference.count)
			let count = min(reference.count - start, rng.nextInt(8) + 1)
			let lower = utf8Offset(forCharacterIndex: start, in: reference)
			let upper = utf8Offset(forCharacterIndex: start + count, in: reference)
			rope.remove(lower ..< upper)
			reference.removeSubrange(start ..< start + count)
		default:
			let start = rng.nextInt(reference.count + 1)
			let end = start + rng.nextInt(reference.count - start + 1)
			let lower = utf8Offset(forCharacterIndex: start, in: reference)
			let upper = utf8Offset(forCharacterIndex: end, in: reference)
			#expect(rope.slice(lower ..< upper) == String(reference[start ..< end]))
		}
		let expected = String(reference)
		#expect(rope.length == expected.utf8.count)
		#expect(rope.lineCount == expected.utf8.reduce(1) { $1 == 10 ? $0 + 1 : $0 })
		#expect(rope.slice(0 ..< rope.length) == expected)
		#expect(rope.validateInvariants())
		if !reference.isEmpty {
			let index = rng.nextInt(reference.count + 1)
			let offset = utf8Offset(forCharacterIndex: index, in: reference)
			#expect(rope.line(forOffset: offset) == expected.utf8.prefix(offset).reduce(0) { $1 == 10 ? $0 + 1 : $0 })
		}
	}
}

private struct FuzzRNG {
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

private func utf8Offset(forCharacterIndex index: Int, in chars: [Character]) -> Int {
	chars.prefix(index).reduce(0) { $0 + String($1).utf8.count }
}
