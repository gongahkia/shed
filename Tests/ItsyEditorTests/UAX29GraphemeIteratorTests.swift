@testable import ItsyEditor
import Foundation
import Testing

@Test func uax29GraphemeIteratorHandlesFocusedSequences() {
	#expect(boundaries("abc") == [0, 1, 2, 3])
	#expect(boundaries("a\r\nb") == [0, 1, 3, 4])
	#expect(boundaries("e\u{301}") == [0, 3])
	#expect(boundaries("🇸🇬🇺🇸") == [0, 8, 16])
	#expect(boundaries("👩‍💻") == [0, 11])
	#expect(boundaries("\u{0915}\u{094D}\u{0915}") == [0, 9])
}

@Test func uax29GraphemeIteratorPassesUnicodeBreakTest() throws {
	let url = try #require(Bundle.module.url(
		forResource: "GraphemeBreakTest",
		withExtension: "txt",
		subdirectory: "Fixtures/UCD"
	))
	let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n", omittingEmptySubsequences: false)
	for (index, line) in lines.enumerated() {
		let trimmed = line.trimmingCharacters(in: .whitespaces)
		guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else {
			continue
		}
		let test = try parseGraphemeBreakTestLine(String(line))
		let actual = test.bytes.withUnsafeBufferPointer {
			UAX29GraphemeIterator.boundaries(in: $0)
		}
		#expect(actual == test.boundaries, "line \(index + 1)")
	}
}

private func boundaries(_ text: String) -> [Int] {
	Array(text.utf8).withUnsafeBufferPointer {
		UAX29GraphemeIterator.boundaries(in: $0)
	}
}

private func parseGraphemeBreakTestLine(_ line: String) throws -> (bytes: [UInt8], boundaries: [Int]) {
	let data = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
	let tokens = data.split(whereSeparator: { $0 == " " || $0 == "\t" })
	var bytes: [UInt8] = []
	var boundaries: [Int] = []
	for token in tokens {
		if token == "÷" {
			boundaries.append(bytes.count)
		} else if token == "×" {
			continue
		} else {
			let value = try #require(UInt32(token, radix: 16), "bad codepoint \(token)")
			let scalar = try #require(UnicodeScalar(value), "bad scalar \(token)")
			bytes.append(contentsOf: String(scalar).utf8)
		}
	}
	return (bytes, boundaries)
}
