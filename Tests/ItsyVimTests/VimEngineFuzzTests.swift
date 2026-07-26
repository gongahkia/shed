import ItsyVim
import Testing

@Test(arguments: Array(0 ..< 16))
func vimEngineFuzzRandomKeySequencesPreserveState(seedIndex: Int) {
	var rng = VimFuzzRNG(UInt64(seedIndex) &* 0x9E37_79B9_7F4A_7C15 &+ 0x51A7E)
	var buffer = VimFuzzBuffer(text: VimFuzzBuffer.randomText(rng: &rng))
	var selection = VimFuzzSelection(anchor: 0, head: 0)
	var engine = VimEngine()
	for _ in 0 ..< 500 {
		let key = vimFuzzKeys[rng.nextInt(vimFuzzKeys.count)]
		let actions = engine.handle(key, buffer: buffer)
		apply(actions: actions, buffer: &buffer, selection: &selection, engine: engine)
		#expect(buffer.isValid)
		#expect(selection.isValid(in: buffer.length))
		#expect(engine.pendingOperatorCount >= 1 && engine.pendingOperatorCount <= 9_999)
		if let pendingCount = engine.pendingCount {
			#expect((0 ... 9_999).contains(pendingCount))
		}
	}
}

private func apply(actions: [VimAction], buffer: inout VimFuzzBuffer, selection: inout VimFuzzSelection, engine: VimEngine) {
	for action in actions {
		switch action {
		case .move(let motion):
			let next = buffer.movedOffset(selection.head, motion: motion)
			selection = VimFuzzSelection(anchor: next, head: next)
		case .delete(let range):
			let clamped = buffer.clamped(range)
			buffer.replace(clamped, with: "")
			selection = VimFuzzSelection(anchor: clamped.lowerBound, head: clamped.lowerBound)
		case .insert(let text, let offset):
			let clamped = min(max(offset, 0), buffer.length)
			buffer.replace(clamped ..< clamped, with: text)
			let head = clamped + text.utf8.count
			selection = VimFuzzSelection(anchor: head, head: head)
		case .jumpToMark(let name):
			if let position = engine.marks[name] {
				let head = min(max(position.offset, 0), buffer.length)
				selection = VimFuzzSelection(anchor: head, head: head)
			}
		default:
			break
		}
	}
}

private let vimFuzzKeys: [Key] = [
	Key("h"),
	Key("j"),
	Key("k"),
	Key("l"),
	Key("left"),
	Key("right"),
	Key("up"),
	Key("down"),
	Key("w"),
	Key("b"),
	Key("e"),
	Key("w", modifiers: .shift),
	Key("b", modifiers: .shift),
	Key("e", modifiers: .shift),
	Key("0"),
	Key("6", modifiers: .shift),
	Key("4", modifiers: .shift),
	Key("g"),
	Key("g", modifiers: .shift),
	Key("{", modifiers: .shift),
	Key("}", modifiers: .shift),
	Key("f", modifiers: .control),
	Key("b", modifiers: .control),
	Key("d"),
	Key("c"),
	Key("y"),
	Key("\""),
	Key("+"),
	Key("*"),
	Key("_"),
	Key("m"),
	Key("'"),
	Key("q"),
	Key("@"),
	Key("p"),
	Key("p", modifiers: .shift),
	Key("/"),
	Key("/", modifiers: .shift),
	Key("n"),
	Key("n", modifiers: .shift),
	Key(";"),
	Key("v"),
	Key("v", modifiers: .shift),
	Key("v", modifiers: .control),
	Key("i"),
	Key("a"),
	Key("return"),
	Key("escape"),
	Key("."),
	Key("`", modifiers: .shift),
	Key(".", modifiers: .shift),
	Key(",", modifiers: .shift),
] + (0 ... 9).map { Key(String($0)) } + Array("abcdefghijklmnopqrstuvwxyz").map { Key(String($0)) }

private struct VimFuzzSelection {
	var anchor: Int
	var head: Int

	func isValid(in length: Int) -> Bool {
		(0 ... length).contains(anchor) && (0 ... length).contains(head)
	}
}

private struct VimFuzzBuffer: BufferQuery {
	var text: String

	var length: Int {
		text.utf8.count
	}

	var isValid: Bool {
		length == text.utf8.count
	}

	func line(forOffset offset: Int) -> Int {
		text.utf8.prefix(max(0, min(offset, length))).filter { $0 == 10 }.count
	}

	func substring(_ range: Range<Int>) -> String {
		let clampedRange = clamped(range)
		let lower = text.utf8.index(text.utf8.startIndex, offsetBy: clampedRange.lowerBound)
		let upper = text.utf8.index(text.utf8.startIndex, offsetBy: clampedRange.upperBound)
		return String(decoding: text.utf8[lower ..< upper], as: UTF8.self)
	}

	func graphemeBoundary(after offset: Int) -> Int {
		min(length, max(0, offset) + 1)
	}

	func clamped(_ range: Range<Int>) -> Range<Int> {
		let lower = min(max(range.lowerBound, 0), length)
		let upper = min(max(range.upperBound, lower), length)
		return lower ..< upper
	}

	func movedOffset(_ offset: Int, motion: Motion) -> Int {
		let clamped = min(max(offset, 0), length)
		switch motion {
		case .charForward, .wordForward, .wordEnd, .bigWordForward, .bigWordEnd:
			return min(length, clamped + 1)
		case .charBackward, .wordBackward, .bigWordBackward:
			return max(0, clamped - 1)
		case .lineDown:
			return min(length, lineEnd(for: clamped) + 1)
		case .lineUp:
			return max(0, lineStart(for: clamped) - 1)
		case .lineStart:
			return lineStart(for: clamped)
		case .lineEnd:
			return lineEnd(for: clamped)
		case .bufferStart:
			return 0
		case .bufferEnd:
			return length
		case .paragraphForward, .pageDown:
			return min(length, clamped + 8)
		case .paragraphBackward, .pageUp:
			return max(0, clamped - 8)
		}
	}

	mutating func replace(_ range: Range<Int>, with newText: String) {
		let clampedRange = clamped(range)
		let lowerUTF8 = text.utf8.index(text.utf8.startIndex, offsetBy: clampedRange.lowerBound)
		let upperUTF8 = text.utf8.index(text.utf8.startIndex, offsetBy: clampedRange.upperBound)
		let lower = String.Index(lowerUTF8, within: text) ?? text.startIndex
		let upper = String.Index(upperUTF8, within: text) ?? text.endIndex
		text.replaceSubrange(lower ..< upper, with: newText)
	}

	private func lineStart(for offset: Int) -> Int {
		let bytes = Array(text.utf8)
		var cursor = min(max(offset, 0), bytes.count)
		while cursor > 0, bytes[cursor - 1] != 10 {
			cursor -= 1
		}
		return cursor
	}

	private func lineEnd(for offset: Int) -> Int {
		let bytes = Array(text.utf8)
		var cursor = min(max(offset, 0), bytes.count)
		while cursor < bytes.count, bytes[cursor] != 10 {
			cursor += 1
		}
		return cursor
	}

	static func randomText(rng: inout VimFuzzRNG) -> String {
		let parts = ["alpha", "beta", "gamma", "delta", "x", "42", "\n", " "]
		return (0 ..< 80).map { _ in parts[rng.nextInt(parts.count)] }.joined()
	}
}

private struct VimFuzzRNG {
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
