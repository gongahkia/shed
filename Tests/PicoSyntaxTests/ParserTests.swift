import Dispatch
import PicoEditor
import PicoSyntax
import Testing

@Test func parserParsesTypeScriptProgram() throws {
	let rope = Rope("const answer: number = 42;\n")
	let parser = try Parser(language: .typescript)
	let tree = try parser.parse(rope)
	let root = tree.rootNode
	#expect(root.type == "program")
	#expect(root.byteRange == 0 ..< rope.length)
	#expect(!root.hasError)
}

@Test func parserParsesLargeTypeScriptAndIncrementalEdit() throws {
	var rope = Rope(largeTypeScriptLineSet())
	let parser = try Parser(language: .typescript)
	let initialStart = DispatchTime.now().uptimeNanoseconds
	let tree = try parser.parse(rope)
	let initialEnd = DispatchTime.now().uptimeNanoseconds
	#expect(!tree.rootNode.hasError)
	#if !DEBUG
	#expect(milliseconds(initialEnd - initialStart) < 300)
	#endif

	let editLine = 100_000
	let editOffset = rope.lineRange(editLine).upperBound
	let editColumn = editOffset - rope.offset(forLine: editLine)
	let editText = " "
	let edit = InputEdit(
		startByte: editOffset,
		oldEndByte: editOffset,
		newEndByte: editOffset + editText.utf8.count,
		startPoint: Point(row: editLine, column: editColumn),
		oldEndPoint: Point(row: editLine, column: editColumn),
		newEndPoint: Point(row: editLine, column: editColumn + editText.utf8.count)
	)
	rope.insert(editText, at: editOffset)
	tree.edit(edit)
	let incrementalStart = DispatchTime.now().uptimeNanoseconds
	let newTree = try parser.parse(rope, oldTree: tree)
	let incrementalEnd = DispatchTime.now().uptimeNanoseconds
	#expect(!newTree.rootNode.hasError)
	#expect(newTree.rootNode.byteRange.upperBound == rope.length)
	#if !DEBUG
	#expect(milliseconds(incrementalEnd - incrementalStart) < 5)
	#endif
}

@Test func highlightQueryCapturesTypeScriptBasics() throws {
	let text = "// note\nconst value = \"ok\";\n"
	let rope = Rope(text)
	let parser = try Parser(language: .typescript)
	let tree = try parser.parse(rope)
	let query = try HighlightQuery(language: .typescript)
	let highlights = try query.highlights(in: tree)
	let captures = Set(highlights.map(\.capture))
	#expect(captures.contains("comment"))
	#expect(captures.contains("keyword"))
	#expect(captures.contains("string"))
}

@Test func syntaxThemeLoadsBundledAndFallsBackByCapturePrefix() throws {
	let theme = try SyntaxTheme.loadDefaultDark()
	#expect(theme.color(for: "keyword") != nil)
	#expect(theme.color(for: "type.builtin") == theme.color(for: "type"))
	let parsed = try SyntaxTheme.parse(#"""
"keyword" = "#112233"
"variable.parameter" = "#445566cc"
"""#)
	let keyword = try SyntaxColor(hex: "#112233")
	let parameter = try SyntaxColor(hex: "#445566cc")
	#expect(parsed.color(for: "keyword") == keyword)
	#expect(parsed.color(for: "variable.parameter") == parameter)
}

private func largeTypeScriptLineSet() -> String {
	String(repeating: "\n", count: 100_000) + "const done: boolean = true;\n"
}

private func milliseconds(_ nanoseconds: UInt64) -> Double {
	Double(nanoseconds) / 1_000_000
}
