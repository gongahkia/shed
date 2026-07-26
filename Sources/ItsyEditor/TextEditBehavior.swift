import Foundation

public struct TextEditBehaviorConfiguration: Equatable, Sendable {
	public var autoPairs: Bool
	public var smartIndent: Bool
	public var indentationUnit: String
	public var detectIndentation: Bool

	public init(autoPairs: Bool = true, smartIndent: Bool = true, indentationUnit: String = "\t", detectIndentation: Bool = false) {
		self.autoPairs = autoPairs
		self.smartIndent = smartIndent
		self.indentationUnit = indentationUnit
		self.detectIndentation = detectIndentation
	}
}

public enum TextEditOperation: Equatable, Sendable {
	case replace(range: Range<Int>, text: String, selection: Range<Int>?)
	case select(Range<Int>)
}

public enum TextEditBehavior {
	public static func insertion(
		text: String,
		content: String,
		range: Range<Int>,
		configuration: TextEditBehaviorConfiguration
	) -> TextEditOperation? {
		guard configuration.autoPairs, let bytes = validBytes(content, range: range), text.utf8.count == 1, let input = text.utf8.first else {
			return nil
		}
		if range.isEmpty, isClosingPair(input), byte(at: range.lowerBound, in: bytes) == input {
			return .select(range.lowerBound + 1 ..< range.lowerBound + 1)
		}
		if let closing = closingPair(for: input) {
			if range.isEmpty, isQuote(input), !shouldPairQuote(bytes, at: range.lowerBound, quote: input) {
				return nil
			}
			let selected = String(decoding: bytes[range], as: UTF8.self)
			let replacement = String(UnicodeScalar(input)) + selected + String(UnicodeScalar(closing))
			let selection = range.lowerBound + 1 ..< range.upperBound + 1
			return .replace(range: range, text: replacement, selection: selection)
		}
		return nil
	}

	public static func deleteBackward(
		content: String,
		range: Range<Int>,
		configuration: TextEditBehaviorConfiguration
	) -> TextEditOperation? {
		guard configuration.autoPairs, range.isEmpty, let bytes = validBytes(content, range: range), range.lowerBound > 0 else {
			return nil
		}
		let opening = bytes[range.lowerBound - 1]
		guard let closing = closingPair(for: opening), byte(at: range.lowerBound, in: bytes) == closing else {
			return nil
		}
		let caret = range.lowerBound - 1
		return .replace(range: caret ..< range.lowerBound + 1, text: "", selection: caret ..< caret)
	}

	public static func newline(
		content: String,
		range: Range<Int>,
		providedText: String?,
		configuration: TextEditBehaviorConfiguration
	) -> TextEditOperation? {
		guard configuration.smartIndent, range.isEmpty, let bytes = validBytes(content, range: range) else {
			return nil
		}
		let baseIndentation = indentation(in: bytes, before: range.lowerBound)
		let indentationUnit = configuration.detectIndentation
			? IndentationDetector.indentationUnit(in: content, fallback: configuration.indentationUnit)
			: configuration.indentationUnit
		if let opening = byte(at: range.lowerBound - 1, in: bytes),
		   let closing = closingPair(for: opening),
		   byte(at: range.lowerBound, in: bytes) == closing
		{
			let innerIndentation = baseIndentation + indentationUnit
			let text = "\n\(innerIndentation)\n\(baseIndentation)"
			let caret = range.lowerBound + 1 + innerIndentation.utf8.count
			return .replace(range: range, text: text, selection: caret ..< caret)
		}
		let text = providedText ?? "\n\(baseIndentation)"
		guard text != "\n" else {
			return nil
		}
		return .replace(range: range, text: text, selection: nil)
	}

	private static func validBytes(_ content: String, range: Range<Int>) -> [UInt8]? {
		let bytes = Array(content.utf8)
		guard range.lowerBound >= 0, range.upperBound >= range.lowerBound, range.upperBound <= bytes.count else {
			return nil
		}
		return bytes
	}

	private static func closingPair(for opening: UInt8) -> UInt8? {
		switch opening {
		case 40: 41
		case 91: 93
		case 123: 125
		case 34: 34
		case 39: 39
		default: nil
		}
	}

	private static func isClosingPair(_ value: UInt8) -> Bool {
		value == 41 || value == 93 || value == 125 || value == 34 || value == 39
	}

	private static func isQuote(_ value: UInt8) -> Bool {
		value == 34 || value == 39
	}

	private static func shouldPairQuote(_ bytes: [UInt8], at offset: Int, quote: UInt8) -> Bool {
		guard byte(at: offset, in: bytes) != quote else {
			return false
		}
		var backslashCount = 0
		var index = offset
		while index > 0, bytes[index - 1] == 92 {
			backslashCount += 1
			index -= 1
		}
		guard backslashCount.isMultiple(of: 2) else {
			return false
		}
		return !isWordByte(byte(at: offset - 1, in: bytes)) && !isWordByte(byte(at: offset, in: bytes))
	}

	private static func indentation(in bytes: [UInt8], before offset: Int) -> String {
		let limit = min(max(offset, 0), bytes.count)
		var lineStart = 0
		for index in 0 ..< limit where bytes[index] == 10 {
			lineStart = index + 1
		}
		let line = bytes[lineStart ..< min(max(offset, lineStart), bytes.count)]
		let prefix = line.prefix { $0 == 9 || $0 == 32 }
		return String(decoding: prefix, as: UTF8.self)
	}

	private static func byte(at offset: Int, in bytes: [UInt8]) -> UInt8? {
		guard bytes.indices.contains(offset) else {
			return nil
		}
		return bytes[offset]
	}

	private static func isWordByte(_ value: UInt8?) -> Bool {
		guard let value else {
			return false
		}
		return value == 95 || (48 ... 57).contains(value) || (65 ... 90).contains(value) || (97 ... 122).contains(value)
	}
}
