public struct Rope: Sendable {
	private var root: RopeNode
	private var repeatedASCII: RepeatedASCII?

	public init(_ string: String = "") {
		if let repeatedASCII = RepeatedASCII(string) {
			root = RopeNode(text: "")
			self.repeatedASCII = repeatedASCII
		} else {
			root = RopeNode.build(from: string)
			repeatedASCII = nil
		}
	}

	public var length: Int {
		if let repeatedASCII {
			return repeatedASCII.count
		}
		return root.summary.utf8Bytes
	}

	public var lineCount: Int {
		if let repeatedASCII {
			return repeatedASCII.lineCount
		}
		return root.summary.lines + 1
	}

	public var graphemeCount: Int {
		if let repeatedASCII {
			return repeatedASCII.count
		}
		return root.summary.graphemes
	}

	public mutating func insert(_ string: String, at offset: Int) {
		precondition((0 ... length).contains(offset), "insert offset out of bounds")
		guard !string.isEmpty else {
			return
		}
		if repeatedASCII == nil, root.summary.utf8Bytes == 0, let byte = string.singleASCIIByte {
			repeatedASCII = RepeatedASCII(byte: byte, count: 1)
			return
		}
		if let byte = string.singleASCIIByte, repeatedASCII?.byte == byte {
			repeatedASCII?.count += 1
			return
		}
		materializeRepeatedASCII()
		root = RopeNode.buildTree(from: root.inserting(string, at: offset))
	}

	public mutating func remove(_ range: Range<Int>) {
		precondition(range.lowerBound >= 0 && range.upperBound <= length, "remove range out of bounds")
		guard !range.isEmpty else {
			return
		}
		if repeatedASCII != nil {
			repeatedASCII?.count -= range.count
			if repeatedASCII?.count == 0 {
				repeatedASCII = nil
				root = RopeNode(text: "")
			}
			return
		}
		let nodes = root.removing(range)
		root = nodes.isEmpty ? RopeNode(text: "") : RopeNode.buildTree(from: nodes)
	}

	public func slice(_ range: Range<Int>) -> String {
		precondition(range.lowerBound >= 0 && range.upperBound <= length, "slice range out of bounds")
		if let repeatedASCII {
			return repeatedASCII.string(count: range.count)
		}
		var result = ""
		result.reserveCapacity(range.count)
		root.appendSlice(range, into: &result)
		return result
	}

	public func chunk(at offset: Int, maxBytes: Int) -> String {
		precondition((0 ... length).contains(offset), "chunk offset out of bounds")
		precondition(maxBytes > 0, "chunk size must be positive")
		guard offset < length else {
			return ""
		}
		if let repeatedASCII {
			return repeatedASCII.string(count: min(maxBytes, repeatedASCII.count - offset))
		}
		var chunk = ""
		chunk.reserveCapacity(maxBytes)
		_ = root.appendChunk(at: offset, maxBytes: maxBytes, into: &chunk)
		return chunk
	}

	public func copyUTF8Chunk(at offset: Int, maxBytes: Int, into buffer: UnsafeMutablePointer<UInt8>) -> Int {
		precondition((0 ... length).contains(offset), "chunk offset out of bounds")
		precondition(maxBytes > 0, "chunk size must be positive")
		guard offset < length else {
			return 0
		}
		if let repeatedASCII {
			let count = min(maxBytes, repeatedASCII.count - offset)
			buffer.initialize(repeating: repeatedASCII.byte, count: count)
			return count
		}
		return root.copyUTF8Chunk(at: offset, maxBytes: maxBytes, into: buffer)
	}

	public func lineRange(_ index: Int) -> Range<Int> {
		precondition(index >= 0, "line index out of bounds")
		guard index < lineCount else {
			return length ..< length
		}
		if let repeatedASCII {
			return repeatedASCII.lineRange(index)
		}
		let start = offset(forLine: index)
		let next = index + 1 < lineCount ? offset(forLine: index + 1) : length
		let end = index + 1 < lineCount ? max(start, next - 1) : next
		return start ..< end
	}

	public func offset(forLine line: Int) -> Int {
		precondition(line >= 0, "line index out of bounds")
		guard line < lineCount else {
			return length
		}
		if let repeatedASCII {
			return repeatedASCII.offset(forLine: line)
		}
		return root.offset(forLine: line)
	}

	public func line(forOffset offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "utf8 offset out of bounds")
		if let repeatedASCII {
			return repeatedASCII.line(forOffset: offset)
		}
		return root.line(forOffset: offset)
	}

	public func graphemeIndex(forOffset offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "utf8 offset out of bounds")
		if repeatedASCII != nil {
			return offset
		}
		var bytes = 0
		var graphemes = 0
		for character in root.text {
			let next = bytes + String(character).utf8.count
			if offset < next {
				return graphemes
			}
			bytes = next
			graphemes += 1
		}
		return graphemes
	}

	func validateInvariants() -> Bool {
		if repeatedASCII != nil {
			return true
		}
		return root.validate()
	}

	private mutating func materializeRepeatedASCII() {
		guard let repeatedASCII else {
			return
		}
		root = RopeNode.build(from: repeatedASCII.string(count: repeatedASCII.count))
		self.repeatedASCII = nil
	}
}

private struct RepeatedASCII: Sendable, Equatable {
	var byte: UInt8
	var count: Int

	init?( _ string: String) {
		guard let byte = string.repeatedASCIIByte else {
			return nil
		}
		self.byte = byte
		count = string.utf8.count
	}

	init(byte: UInt8, count: Int) {
		self.byte = byte
		self.count = count
	}

	var lineCount: Int {
		byte == 10 ? count + 1 : 1
	}

	func string(count: Int) -> String {
		guard count > 0 else {
			return ""
		}
		return String(repeating: String(decoding: [byte], as: UTF8.self), count: count)
	}

	func lineRange(_ index: Int) -> Range<Int> {
		if byte == 10 {
			let offset = min(index, count)
			return offset ..< offset
		}
		return index == 0 ? 0 ..< count : count ..< count
	}

	func offset(forLine line: Int) -> Int {
		byte == 10 ? min(line, count) : (line == 0 ? 0 : count)
	}

	func line(forOffset offset: Int) -> Int {
		byte == 10 ? offset : 0
	}
}

private final class RopeNode: @unchecked Sendable {
	private static let maxLeafBytes = 1024
	private static let maxChildren = 8

	let children: [RopeNode]
	let leafText: String?
	let summary: RopeSummary

	var text: String {
		if let leafText {
			return leafText
		}
		return children.map(\.text).joined()
	}

	init(text: String) {
		children = []
		leafText = text
		summary = RopeSummary(text)
	}

	private init(children: [RopeNode]) {
		self.children = children
		leafText = nil
		summary = children.reduce(.zero) { $0 + $1.summary }
	}

	static func build(from text: String) -> RopeNode {
		buildTree(from: leaves(from: text))
	}

	static func buildTree(from nodes: [RopeNode]) -> RopeNode {
		guard !nodes.isEmpty else {
			return RopeNode(text: "")
		}
		var level = nodes
		while level.count > 1 {
			level = packLevel(level)
		}
		return level[0]
	}

	private static func leaves(from text: String) -> [RopeNode] {
		var leaves: [RopeNode] = []
		var chunk = ""
		var chunkBytes = 0
		for character in text {
			let bytes = String(character).utf8.count
			if chunkBytes > 0, chunkBytes + bytes > maxLeafBytes {
				leaves.append(RopeNode(text: chunk))
				chunk = ""
				chunkBytes = 0
			}
			chunk.append(character)
			chunkBytes += bytes
		}
		if !chunk.isEmpty || leaves.isEmpty {
			leaves.append(RopeNode(text: chunk))
		}
		return leaves
	}

	private static func packLevel(_ nodes: [RopeNode]) -> [RopeNode] {
		var packed: [RopeNode] = []
		var index = 0
		while index < nodes.count {
			let end = min(index + maxChildren, nodes.count)
			packed.append(RopeNode(children: Array(nodes[index ..< end])))
			index = end
		}
		return packed
	}

	private static func packChildren(_ nodes: [RopeNode]) -> [RopeNode] {
		guard !nodes.isEmpty else {
			return []
		}
		return packLevel(nodes)
	}

	func validate() -> Bool {
		if let leafText {
			return leafText.utf8.count <= Self.maxLeafBytes && summary == RopeSummary(leafText)
		}
		guard (1 ... Self.maxChildren).contains(children.count) else {
			return false
		}
		return summary == children.reduce(.zero) { $0 + $1.summary } && children.allSatisfy { $0.validate() }
	}

	func inserting(_ string: String, at target: Int) -> [RopeNode] {
		if let leafText {
			var text = leafText
			text.insert(contentsOf: string, at: text.index(atUTF8Offset: target))
			return Self.leaves(from: text)
		}
		var offset = 0
		var inserted = false
		var next: [RopeNode] = []
		for child in children {
			let childEnd = offset + child.summary.utf8Bytes
			if !inserted, target <= childEnd {
				next += child.inserting(string, at: target - offset)
				inserted = true
			} else {
				next.append(child)
			}
			offset = childEnd
		}
		return Self.packChildren(next)
	}

	func removing(_ range: Range<Int>) -> [RopeNode] {
		if let leafText {
			var text = leafText
			let lower = text.index(atUTF8Offset: range.lowerBound)
			let upper = text.index(atUTF8Offset: range.upperBound)
			text.removeSubrange(lower ..< upper)
			return text.isEmpty ? [] : Self.leaves(from: text)
		}
		var offset = 0
		var next: [RopeNode] = []
		for child in children {
			let childStart = offset
			let childEnd = offset + child.summary.utf8Bytes
			if range.upperBound <= childStart || range.lowerBound >= childEnd {
				next.append(child)
			} else {
				let lower = max(0, range.lowerBound - childStart)
				let upper = min(child.summary.utf8Bytes, range.upperBound - childStart)
				next += child.removing(lower ..< upper)
			}
			offset = childEnd
		}
		return Self.packChildren(next)
	}

	func offset(forLine line: Int) -> Int {
		if let leafText {
			return leafText.offset(forLine: line)
		}
		var remaining = line
		var offset = 0
		for child in children {
			if remaining <= child.summary.lines {
				return offset + child.offset(forLine: remaining)
			}
			remaining -= child.summary.lines
			offset += child.summary.utf8Bytes
		}
		return summary.utf8Bytes
	}

	func line(forOffset target: Int) -> Int {
		if let leafText {
			return leafText.line(forOffset: target)
		}
		var offset = 0
		var line = 0
		for child in children {
			let next = offset + child.summary.utf8Bytes
			if target <= next {
				return line + child.line(forOffset: target - offset)
			}
			offset = next
			line += child.summary.lines
		}
		return line
	}

	func appendChunk(at target: Int, maxBytes: Int, into chunk: inout String) -> Int {
		if let leafText {
			let text = leafText.chunk(at: target, maxBytes: maxBytes)
			chunk += text
			return text.utf8.count
		}
		var skipped = target
		var bytes = 0
		for child in children {
			if skipped >= child.summary.utf8Bytes {
				skipped -= child.summary.utf8Bytes
				continue
			}
			bytes += child.appendChunk(at: skipped, maxBytes: maxBytes - bytes, into: &chunk)
			if bytes >= maxBytes {
				break
			}
			skipped = 0
		}
		return bytes
	}

	func appendSlice(_ range: Range<Int>, into result: inout String) {
		guard !range.isEmpty else {
			return
		}
		if let leafText {
			let lower = leafText.index(atUTF8Offset: range.lowerBound)
			let upper = leafText.index(atUTF8Offset: range.upperBound)
			result += leafText[lower ..< upper]
			return
		}
		var offset = 0
		for child in children {
			let childStart = offset
			let childEnd = offset + child.summary.utf8Bytes
			if range.upperBound <= childStart {
				break
			}
			if range.lowerBound < childEnd {
				let lower = max(0, range.lowerBound - childStart)
				let upper = min(child.summary.utf8Bytes, range.upperBound - childStart)
				child.appendSlice(lower ..< upper, into: &result)
			}
			offset = childEnd
		}
	}

	func copyUTF8Chunk(at target: Int, maxBytes: Int, into buffer: UnsafeMutablePointer<UInt8>) -> Int {
		if let leafText {
			return leafText.copyUTF8Chunk(at: target, maxBytes: maxBytes, into: buffer)
		}
		var skipped = target
		var bytes = 0
		for child in children {
			if skipped >= child.summary.utf8Bytes {
				skipped -= child.summary.utf8Bytes
				continue
			}
			bytes += child.copyUTF8Chunk(at: skipped, maxBytes: maxBytes - bytes, into: buffer.advanced(by: bytes))
			if bytes >= maxBytes {
				break
			}
			skipped = 0
		}
		return bytes
	}
}

private struct RopeSummary: Equatable, Sendable {
	var utf8Bytes: Int
	var lines: Int
	var scalars: Int
	var graphemes: Int

	static let zero = RopeSummary(utf8Bytes: 0, lines: 0, scalars: 0, graphemes: 0)

	init(utf8Bytes: Int, lines: Int, scalars: Int, graphemes: Int) {
		self.utf8Bytes = utf8Bytes
		self.lines = lines
		self.scalars = scalars
		self.graphemes = graphemes
	}

	init(_ text: String) {
		utf8Bytes = text.utf8.count
		lines = text.utf8.reduce(0) { $1 == 10 ? $0 + 1 : $0 }
		scalars = text.unicodeScalars.count
		graphemes = text.count
	}

	static func + (lhs: RopeSummary, rhs: RopeSummary) -> RopeSummary {
		RopeSummary(
			utf8Bytes: lhs.utf8Bytes + rhs.utf8Bytes,
			lines: lhs.lines + rhs.lines,
			scalars: lhs.scalars + rhs.scalars,
			graphemes: lhs.graphemes + rhs.graphemes
		)
	}
}

private extension String {
	var singleASCIIByte: UInt8? {
		guard utf8.count == 1, let byte = utf8.first, byte < 128 else {
			return nil
		}
		return byte
	}

	var repeatedASCIIByte: UInt8? {
		guard let first = utf8.first, first < 128, utf8.allSatisfy({ $0 == first }) else {
			return nil
		}
		return first
	}

	func index(atUTF8Offset offset: Int) -> String.Index {
		precondition((0 ... utf8.count).contains(offset), "utf8 offset out of bounds")
		let utf8Index = utf8.index(utf8.startIndex, offsetBy: offset)
		guard let index = String.Index(utf8Index, within: self) else {
			preconditionFailure("utf8 offset must be a character boundary")
		}
		return index
	}

	func offset(forLine line: Int) -> Int {
		guard line > 0 else {
			return 0
		}
		var currentLine = 0
		for (offset, byte) in utf8.enumerated() {
			if byte == 10 {
				currentLine += 1
				if currentLine == line {
					return offset + 1
				}
			}
		}
		return utf8.count
	}

	func line(forOffset offset: Int) -> Int {
		guard offset > 0 else {
			return 0
		}
		var line = 0
		for byte in utf8.prefix(offset) where byte == 10 {
			line += 1
		}
		return line
	}

	func chunk(at offset: Int, maxBytes: Int) -> String {
		guard offset < utf8.count else {
			return ""
		}
		let start = index(atUTF8Offset: offset)
		var end = start
		var bytes = 0
		while end < endIndex {
			let next = index(after: end)
			let byteCount = self[end ..< next].utf8.count
			if bytes > 0, bytes + byteCount > maxBytes {
				break
			}
			bytes += byteCount
			end = next
			if bytes >= maxBytes {
				break
			}
		}
		return String(self[start ..< end])
	}

	func copyUTF8Chunk(at offset: Int, maxBytes: Int, into buffer: UnsafeMutablePointer<UInt8>) -> Int {
		guard offset < utf8.count else {
			return 0
		}
		let view = utf8
		let start = view.index(view.startIndex, offsetBy: offset)
		var end = view.index(start, offsetBy: min(maxBytes, view.distance(from: start, to: view.endIndex)))
		while end > start, end < view.endIndex, view[end].isUTF8Continuation {
			end = view.index(before: end)
		}
		if end == start {
			end = view.index(after: start)
			while end < view.endIndex, view[end].isUTF8Continuation {
				end = view.index(after: end)
			}
		}
		var index = start
		var bytes = 0
		while index < end {
			buffer[bytes] = view[index]
			bytes += 1
			index = view.index(after: index)
		}
		return bytes
	}
}

private extension UInt8 {
	var isUTF8Continuation: Bool {
		(self & 0b1100_0000) == 0b1000_0000
	}
}
