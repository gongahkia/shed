public struct Rope: Sendable {
	private var root: RopeNode

	public init(_ string: String = "") {
		root = RopeNode.build(from: string)
	}

	public var length: Int {
		root.summary.utf8Bytes
	}

	public var lineCount: Int {
		root.summary.lines + 1
	}

	public var graphemeCount: Int {
		root.summary.graphemes
	}

	public mutating func insert(_ string: String, at offset: Int) {
		precondition((0 ... length).contains(offset), "insert offset out of bounds")
		var text = root.text
		text.insert(contentsOf: string, at: text.index(atUTF8Offset: offset))
		root = RopeNode.build(from: text)
	}

	public mutating func remove(_ range: Range<Int>) {
		precondition(range.lowerBound >= 0 && range.upperBound <= length, "remove range out of bounds")
		var text = root.text
		let lower = text.index(atUTF8Offset: range.lowerBound)
		let upper = text.index(atUTF8Offset: range.upperBound)
		text.removeSubrange(lower ..< upper)
		root = RopeNode.build(from: text)
	}

	public func slice(_ range: Range<Int>) -> String {
		precondition(range.lowerBound >= 0 && range.upperBound <= length, "slice range out of bounds")
		let text = root.text
		let lower = text.index(atUTF8Offset: range.lowerBound)
		let upper = text.index(atUTF8Offset: range.upperBound)
		return String(text[lower ..< upper])
	}

	public func lineRange(_ index: Int) -> Range<Int> {
		precondition(index >= 0, "line index out of bounds")
		guard index < lineCount else {
			return length ..< length
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
		return root.offset(forLine: line)
	}

	public func line(forOffset offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "utf8 offset out of bounds")
		return root.line(forOffset: offset)
	}

	public func graphemeIndex(forOffset offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "utf8 offset out of bounds")
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
		root.validate()
	}
}

private final class RopeNode: @unchecked Sendable {
	let children: [RopeNode]
	let leafText: String?
	let summary: RopeSummary

	var text: String {
		if let leafText {
			return leafText
		}
		return children.map(\.text).joined()
	}

	private init(text: String) {
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
		var leaves: [RopeNode] = []
		var chunk = ""
		var chunkBytes = 0
		for character in text {
			let bytes = String(character).utf8.count
			if chunkBytes > 0, chunkBytes + bytes > 1024 {
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
		var level = leaves
		while level.count > 1 {
			var next: [RopeNode] = []
			var index = 0
			while index < level.count {
				let end = min(index + 8, level.count)
				next.append(RopeNode(children: Array(level[index ..< end])))
				index = end
			}
			level = next
		}
		return level[0]
	}

	func validate() -> Bool {
		if let leafText {
			return leafText.utf8.count <= 1024 && summary == RopeSummary(leafText)
		}
		guard (1 ... 8).contains(children.count) else {
			return false
		}
		return summary == children.reduce(.zero) { $0 + $1.summary } && children.allSatisfy { $0.validate() }
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
}
