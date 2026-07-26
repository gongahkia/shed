import Foundation
import ItsyEditor
import ItsyLSP
import Testing

@Test func textEditApplyHandlesInsertAtBeginning() throws {
	let edit = LSPTextEdit(
		range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 0)),
		newText: "// header\n"
	)
	let result = try LSPTextEditApply.apply([edit], to: "let x = 1\n")
	#expect(result == "// header\nlet x = 1\n")
}

@Test func textEditApplyReplacesRangeAcrossLines() throws {
	let source = "abc\ndef\nghi\n"
	let edit = LSPTextEdit(
		range: LSPRange(start: LSPPosition(line: 0, character: 1), end: LSPPosition(line: 2, character: 1)),
		newText: "Z"
	)
	let result = try LSPTextEditApply.apply([edit], to: source)
	#expect(result == "aZhi\n")
}

@Test func textEditApplyAppliesEditsInReverseOffsetOrder() throws {
	let source = "abcdef\n"
	let edits = [
		LSPTextEdit(
			range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 1)),
			newText: "A"
		),
		LSPTextEdit(
			range: LSPRange(start: LSPPosition(line: 0, character: 3), end: LSPPosition(line: 0, character: 4)),
			newText: "DDDD"
		),
	]
	let result = try LSPTextEditApply.apply(edits, to: source)
	#expect(result == "AbcDDDDef\n")
}

@Test func textEditApplyRejectsOverlappingEdits() {
	let source = "abcdef\n"
	let edits = [
		LSPTextEdit(
			range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 3)),
			newText: "X"
		),
		LSPTextEdit(
			range: LSPRange(start: LSPPosition(line: 0, character: 2), end: LSPPosition(line: 0, character: 5)),
			newText: "Y"
		),
	]
	do {
		_ = try LSPTextEditApply.apply(edits, to: source)
		Issue.record("expected overlappingEdits")
	} catch let error as LSPTextEditApplyError {
		#expect(error == .overlappingEdits)
	} catch {
		Issue.record("unexpected error \(error)")
	}
}

@Test func textEditApplyRejectsOutOfBoundsPosition() {
	let source = "abc\n"
	let edit = LSPTextEdit(
		range: LSPRange(start: LSPPosition(line: 5, character: 0), end: LSPPosition(line: 5, character: 0)),
		newText: "x"
	)
	do {
		_ = try LSPTextEditApply.apply([edit], to: source)
		Issue.record("expected outOfBoundsRange")
	} catch let error as LSPTextEditApplyError {
		#expect(error == .outOfBoundsRange)
	} catch {
		Issue.record("unexpected error \(error)")
	}
}

@Test func textEditApplyHandlesUTF16SurrogatePairsInColumns() throws {
	let source = "a😀b\n"
	let edit = LSPTextEdit(
		range: LSPRange(start: LSPPosition(line: 0, character: 1), end: LSPPosition(line: 0, character: 3)),
		newText: ""
	)
	let result = try LSPTextEditApply.apply([edit], to: source)
	#expect(result == "ab\n")
}

@Test func textEditApplyPreservesCRLFNewlines() throws {
	let source = "abc\r\ndef\r\n"
	let edit = LSPTextEdit(
		range: LSPRange(start: LSPPosition(line: 1, character: 0), end: LSPPosition(line: 1, character: 3)),
		newText: "XXX"
	)
	let result = try LSPTextEditApply.apply([edit], to: source)
	#expect(result == "abc\r\nXXX\r\n")
}
