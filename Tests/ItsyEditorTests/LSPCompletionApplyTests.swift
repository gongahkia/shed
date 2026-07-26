import ItsyEditor
import ItsyLSP
import Testing

@Test func snippetExpansionExtractsFirstTabStops() {
	let expansion = LSPSnippetExpander.expand("print(${1:value}, $1)$0")

	#expect(expansion.text == "print(value, )")
	#expect(expansion.tabStops[1] == [6 ..< 11, 13 ..< 13])
	#expect(expansion.tabStops[0] == [14 ..< 14])
	#expect(expansion.firstTabStopRanges == [6 ..< 11, 13 ..< 13])
}

@Test func snippetExpansionSupportsNestedPlaceholdersChoicesAndEscapes() {
	let expansion = LSPSnippetExpander.expand("${1:foo${2:bar}} ${3|one\\,two,three|} \\$4")

	#expect(expansion.text == "foobar one,two $4")
	#expect(expansion.tabStops[1] == [0 ..< 6])
	#expect(expansion.tabStops[2] == [3 ..< 6])
	#expect(expansion.tabStops[3] == [7 ..< 14])
	#expect(expansion.firstTabStopRanges == [0 ..< 6])
}

@Test func completionApplyUsesTextEditAndSnippetSelections() throws {
	let text = "let value = pri\n"
	let item = LSPCompletionItem(
		label: "print",
		insertTextFormat: .snippet,
		textEdit: LSPTextEdit(
			range: LSPRange(
				start: LSPPosition(line: 0, character: 12),
				end: LSPPosition(line: 0, character: 15)
			),
			newText: "print(${1:value})"
		)
	)

	let application = try #require(LSPCompletionApply.application(for: item, in: text, cursorOffset: text.utf8.count))

	#expect(application.replacementRange == 12 ..< 15)
	#expect(application.replacementText == "print(value)")
	#expect(application.transactionRange == 12 ..< 15)
	#expect(application.transactionText == "print(value)")
	#expect(application.selectionRanges == [18 ..< 23])
	#expect(application.tabStopRanges[1] == [18 ..< 23])
}

@Test func completionApplyIncludesResolvedAdditionalTextEditsInOneTransaction() throws {
	let text = "use Old\npri"
	let item = LSPCompletionItem(
		label: "print",
		insertTextFormat: .snippet,
		textEdit: LSPTextEdit(
			range: LSPRange(start: LSPPosition(line: 1, character: 0), end: LSPPosition(line: 1, character: 3)),
			newText: "print(${1:value})"
		),
		additionalTextEdits: [
			LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 4), end: LSPPosition(line: 0, character: 7)),
				newText: "Foundation"
			),
		]
	)

	let application = try #require(LSPCompletionApply.application(for: item, in: text, cursorOffset: text.utf8.count))

	#expect(application.replacementRange == 8 ..< 11)
	#expect(application.transactionRange == 4 ..< 11)
	#expect(application.transactionText == "Foundation\nprint(value)")
	#expect(application.selectionRanges == [21 ..< 26])
	#expect(application.tabStopRanges[1] == [21 ..< 26])
}

@Test func completionApplyRejectsOverlappingAdditionalTextEdits() {
	let item = LSPCompletionItem(
		label: "print",
		textEdit: LSPTextEdit(
			range: LSPRange(start: LSPPosition(line: 0, character: 0), end: LSPPosition(line: 0, character: 3)),
			newText: "print"
		),
		additionalTextEdits: [
			LSPTextEdit(
				range: LSPRange(start: LSPPosition(line: 0, character: 1), end: LSPPosition(line: 0, character: 2)),
				newText: "x"
			),
		]
	)

	#expect(LSPCompletionApply.application(for: item, in: "pri", cursorOffset: 3) == nil)
}

@Test func completionApplyReplacesCurrentPrefixWithoutTextEdit() throws {
	let text = "let value = pri"
	let item = LSPCompletionItem(label: "print")

	let application = try #require(LSPCompletionApply.application(for: item, in: text, cursorOffset: text.utf8.count))

	#expect(application.replacementRange == 12 ..< 15)
	#expect(application.replacementText == "print")
	#expect(application.selectionRanges == [17 ..< 17])
}

@Test func utf16PositionCountsLinesAndSurrogates() {
	let text = "a\n😀x"
	let offset = "a\n😀".utf8.count

	#expect(LSPTextEditApply.utf16Position(forUTF8Offset: offset, in: text) == LSPPosition(line: 1, character: 2))
}
