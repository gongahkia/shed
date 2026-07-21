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

@Test func uax29ASCIIWithoutCRFastPathMatchesByteOffsets() {
	let bytes = Array("abc\nxyz".utf8)
	bytes.withUnsafeBufferPointer { buffer in
		#expect(UAX29GraphemeIterator.boundaries(in: buffer) == Array(0 ... bytes.count))
		#expect(UAX29GraphemeIterator.graphemeCount(in: buffer) == bytes.count)
		for offset in 0 ... bytes.count {
			#expect(UAX29GraphemeIterator.graphemeIndex(in: buffer, before: offset) == offset)
		}
	}
}

@Test func uax29GraphemeIteratorPassesUnicodeBreakTest() throws {
	let url = try graphemeBreakFixtureURL()
	let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n", omittingEmptySubsequences: false)
	var caseCount = 0
	for (index, line) in lines.enumerated() {
		let trimmed = line.trimmingCharacters(in: .whitespaces)
		guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else {
			continue
		}
		let test = try parseGraphemeBreakTestLine(String(line))
		let actual = test.bytes.withUnsafeBufferPointer {
			UAX29GraphemeIterator.boundaries(in: $0)
		}
		caseCount += 1
		#expect(actual == test.boundaries, "line \(index + 1): \(codepointList(test.codepoints))")
		assertStoragePathsMatchFixture(test, line: index + 1)
	}
	#expect(caseCount > 0)
}

private func boundaries(_ text: String) -> [Int] {
	Array(text.utf8).withUnsafeBufferPointer {
		UAX29GraphemeIterator.boundaries(in: $0)
	}
}

private func graphemeBreakFixtureURL() throws -> URL {
	return try #require(Bundle.module.url(
		forResource: "GraphemeBreakTest",
		withExtension: "txt",
		subdirectory: "Fixtures/UCD"
	))
}

private func assertStoragePathsMatchFixture(_ test: (bytes: [UInt8], boundaries: [Int], codepoints: [UInt32]), line: Int) {
	let text = String(decoding: test.bytes, as: UTF8.self)
	func expectedText(afterDeleting range: Range<Int>) -> String {
		var bytes = test.bytes
		bytes.removeSubrange(range)
		return String(decoding: bytes, as: UTF8.self)
	}
	let context = "line \(line): \(codepointList(test.codepoints))"
	for storage in [EditorStorageKind.rope, .pieceTree] {
		var editor = Editor(text: text, storage: storage)
		let actual = (0 ... editor.textStorage.length).filter { editor.textStorage.isGraphemeBoundary($0) }
		#expect(actual == test.boundaries, "\(storage): \(context)")
		for (lower, upper) in zip(test.boundaries, test.boundaries.dropFirst()) {
			editor.setSelection(SelectionSet(primary: Selection(anchor: lower, head: lower)))
			editor.moveCursor(.charForward)
			#expect(editor.selections.primary.head == upper, "forward \(storage): \(context)")
			editor.moveCursor(.charBackward)
			#expect(editor.selections.primary.head == lower, "backward \(storage): \(context)")

			var deletionEditor = Editor(text: text, storage: storage)
			deletionEditor.setSelection(SelectionSet(primary: Selection(anchor: upper, head: upper)))
			deletionEditor.deleteBackward()
			#expect(deletionEditor.textStorage.substring(0 ..< deletionEditor.textStorage.length) == expectedText(afterDeleting: lower ..< upper), "delete \(storage): \(context)")
		}
	}
}

private func parseGraphemeBreakTestLine(_ line: String) throws -> (bytes: [UInt8], boundaries: [Int], codepoints: [UInt32]) {
	let data = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
	let tokens = data.split(whereSeparator: { $0 == " " || $0 == "\t" })
	var bytes: [UInt8] = []
	var boundaries: [Int] = []
	var codepoints: [UInt32] = []
	for token in tokens {
		if token == "÷" {
			boundaries.append(bytes.count)
		} else if token == "×" {
			continue
		} else {
			let value = try #require(UInt32(token, radix: 16), "bad codepoint \(token)")
			let scalar = try #require(UnicodeScalar(value), "bad scalar \(token)")
			codepoints.append(value)
			bytes.append(contentsOf: String(scalar).utf8)
		}
	}
	return (bytes, boundaries, codepoints)
}

private func codepointList(_ values: [UInt32]) -> String {
	values.map { String(format: "U+%04X", $0) }.joined(separator: " ")
}
