import Dispatch
import Foundation
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

@Test func highlightSpanMapsThroughEditorEdit() throws {
	let edit = Edit(range: 5 ..< 7, oldText: "bc", newText: "XYZ")
	#expect(HighlightSpan(range: 0 ..< 3, capture: "keyword").mapped(through: edit)?.range == 0 ..< 3)
	#expect(HighlightSpan(range: 10 ..< 14, capture: "string").mapped(through: edit)?.range == 11 ..< 15)
	#expect(HighlightSpan(range: 6 ..< 7, capture: "variable").mapped(through: edit) == nil)
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

@Test func syntaxThemeListsAndLoadsSelectedBundledChoice() throws {
	let choices = SyntaxTheme.availableChoices()
	#expect(choices.contains(SyntaxThemeChoice(id: "bundled:default-dark", displayName: "Default Dark")))
	#expect(choices.contains(SyntaxThemeChoice(id: "bundled:default-light", displayName: "Default Light")))

	let suiteName = "dev.pico.editor.tests.theme.\(UUID().uuidString)"
	let defaults = try #require(UserDefaults(suiteName: suiteName))
	defer { defaults.removePersistentDomain(forName: suiteName) }
	defaults.set("bundled:default-light", forKey: SyntaxTheme.selectedThemeDefaultsKey)

	let selected = try SyntaxTheme.loadSelectedOrDefault(defaults: defaults)
	#expect(selected == (try SyntaxTheme.loadDefaultLight()))
}

@Test func syntaxPipelineDetectsLanguageAndAllocatesParserLazily() throws {
	let url = URL(fileURLWithPath: "/tmp/example.ts")
	let language = try #require(SyntaxPipeline.language(forFileURL: url))
	#expect(language == .typescript)
	let pipeline = SyntaxPipeline(language: language)
	#expect(!pipeline.didAllocateParser)
	let tree = try pipeline.parse(Rope("const value = 1;\n"))
	#expect(pipeline.didAllocateParser)
	#expect(!tree.rootNode.hasError)
}

@Test func syntaxPipelineIncrementalMiddleEditFitsFrameBudget() throws {
	var editor = Editor(text: largeTypeScriptLineSetWithMiddleLine())
	let pipeline = SyntaxPipeline(language: .typescript)
	let tree = try pipeline.parse(editor.rope)
	_ = try pipeline.highlights(in: tree, byteRange: editor.rope.lineRange(50_000))
	let oldRope = editor.rope
	let editOffset = oldRope.lineRange(50_000).upperBound
	editor.setSelection(SelectionSet(primary: Selection(anchor: editOffset, head: editOffset)))
	editor.insert(" ")
	let edit = try #require(editor.lastEditBatch.first)
	let inputEdit = InputEdit(edit: edit, oldRope: oldRope, newRope: editor.rope)
	tree.edit(inputEdit)
	let dirtyRange = editor.rope.lineRange(50_000)
	let start = DispatchTime.now().uptimeNanoseconds
	let newTree = try pipeline.parse(editor.rope, oldTree: tree)
	let dirtySpans = try pipeline.highlights(in: newTree, byteRange: dirtyRange)
	let end = DispatchTime.now().uptimeNanoseconds
	#expect(!newTree.rootNode.hasError)
	#expect(!dirtySpans.isEmpty)
	#if !DEBUG
	#expect(milliseconds(end - start) < 16)
	#endif
}

@Test func inputEditBuildsTreeSitterEditFromEditorEdit() throws {
	let oldRope = Rope("const value = 1;\n")
	var editor = Editor(text: oldRope.slice(0 ..< oldRope.length))
	editor.setSelection(SelectionSet(primary: Selection(anchor: 14, head: 14)))
	editor.insert("0")
	let edit = try #require(editor.lastEditBatch.first)
	let inputEdit = InputEdit(edit: edit, oldRope: oldRope, newRope: editor.rope)
	#expect(inputEdit.startByte == 14)
	#expect(inputEdit.oldEndByte == 14)
	#expect(inputEdit.newEndByte == 15)
	#expect(inputEdit.startPoint == Point(row: 0, column: 14))
	#expect(inputEdit.newEndPoint == Point(row: 0, column: 15))
}

private func largeTypeScriptLineSet() -> String {
	String(repeating: "\n", count: 100_000) + "const done: boolean = true;\n"
}

private func largeTypeScriptLineSetWithMiddleLine() -> String {
	String(repeating: "\n", count: 50_000) + "const done: boolean = true;\n" + String(repeating: "\n", count: 50_000)
}

private func milliseconds(_ nanoseconds: UInt64) -> Double {
	Double(nanoseconds) / 1_000_000
}
