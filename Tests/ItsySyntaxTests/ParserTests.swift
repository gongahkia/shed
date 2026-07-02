import Dispatch
import Foundation
import ItsyEditor
@testable import ItsySyntax
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

@Test func parserParsesPieceTreeInputAcrossChunkBoundary() throws {
	var pieceTree = PieceTree("// " + String(repeating: "a", count: 4_092) + "é\n")
	pieceTree.insert("const answer: number = 42;\n", at: pieceTree.length)
	var pipeline = SyntaxPipeline(language: .typescript)
	let tree = try pipeline.parse(pieceTree)
	let root = tree.rootNode
	#expect(pipeline.didAllocateParser)
	#expect(root.type == "program")
	#expect(root.byteRange == 0 ..< pieceTree.length)
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

@Test func syntaxThemeCoversStandardCaptureSetWithFallbacks() throws {
	let required: Set<String> = [
		"keyword.control", "keyword.function", "keyword.operator", "keyword.return",
		"type.builtin", "type.parameter", "function.builtin", "function.macro", "function.method",
		"variable.builtin", "variable.member", "constant.builtin", "constant.macro",
		"string.escape", "string.regexp", "string.special", "number.float", "boolean",
		"character", "character.special", "comment.documentation", "punctuation.bracket",
		"punctuation.delimiter", "punctuation.special", "operator", "attribute", "tag",
		"label", "namespace", "module", "property", "field", "parameter", "error",
		"diff.plus", "diff.minus", "markup.heading", "markup.link", "markup.list",
		"markup.bold", "markup.italic", "markup.raw", "markup.quote",
	]
	#expect(Set(SyntaxTheme.standardCaptures).isSuperset(of: required))
	let standardCaptures = Set(SyntaxTheme.standardCaptures)
	for choice in SyntaxTheme.bundledChoices {
		let theme = try SyntaxTheme.loadChoice(id: choice.id)
		#expect(Set(theme.colors.keys).isSuperset(of: standardCaptures))
		for capture in SyntaxTheme.standardCaptures {
			#expect(theme.color(for: capture) != nil)
		}
	}

	let control = try SyntaxColor(hex: "#222222")
	let constant = try SyntaxColor(hex: "#333333")
	let member = try SyntaxColor(hex: "#555555")
	let heading = try SyntaxColor(hex: "#777777")
	let parsed = try SyntaxTheme.parse(#"""
"keyword" = "#111111"
"keyword.control" = "#222222"
"constant" = "#333333"
"variable" = "#444444"
"variable.member" = "#555555"
"string" = "#666666"
"markup.heading" = "#777777"
"""#)
	#expect(parsed.color(for: "keyword.return") == control)
	#expect(parsed.color(for: "boolean") == constant)
	#expect(parsed.color(for: "field") == member)
	#expect(parsed.color(for: "text.title") == heading)
}

@Test func syntaxThemeListsAndLoadsSelectedBundledChoice() throws {
	let choices = SyntaxTheme.availableChoices()
	for choice in SyntaxTheme.bundledChoices {
		#expect(choices.contains(choice))
		_ = try SyntaxTheme.loadChoice(id: choice.id)
	}

	let suiteName = "dev.itsy.editor.tests.theme.\(UUID().uuidString)"
	let defaults = try #require(UserDefaults(suiteName: suiteName))
	defer { defaults.removePersistentDomain(forName: suiteName) }
	defaults.set("bundled:default-light", forKey: SyntaxTheme.selectedThemeDefaultsKey)

	let selected = try SyntaxTheme.loadSelectedOrDefault(defaults: defaults)
	#expect(selected == (try SyntaxTheme.loadDefaultLight()))
}

@Test func syntaxThemeListsUserThemeChoices() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-themes-\(UUID().uuidString)", isDirectory: true)
	defer {
		try? FileManager.default.removeItem(at: directory)
	}
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	try #"""
"keyword" = "#112233"
"""#.write(to: directory.appendingPathComponent("night.toml"), atomically: true, encoding: .utf8)
	try #"""
"keyword" = "#445566"
"""#.write(to: directory.appendingPathComponent("day.toml"), atomically: true, encoding: .utf8)
	try "skip".write(to: directory.appendingPathComponent("README.md"), atomically: true, encoding: .utf8)

	let userChoices = SyntaxTheme.availableChoices(userThemesURL: directory)
		.filter { $0.id.hasPrefix("user:") }
	#expect(userChoices == [
		SyntaxThemeChoice(id: "user:day.toml", displayName: "day"),
		SyntaxThemeChoice(id: "user:night.toml", displayName: "night"),
	])
}

@Test func syntaxThemeDefaultsToBundledLight() throws {
	let suiteName = "dev.itsy.editor.tests.theme.default.\(UUID().uuidString)"
	let defaults = try #require(UserDefaults(suiteName: suiteName))
	defer { defaults.removePersistentDomain(forName: suiteName) }

	let selected = try SyntaxTheme.loadSelectedOrDefault(defaults: defaults)
	#expect(SyntaxTheme.defaultChoiceID == "bundled:default-light")
	#expect(selected == (try SyntaxTheme.loadDefaultLight()))
}

@Test func syntaxPipelineDetectsLanguageAndAllocatesParserLazily() throws {
	let url = URL(fileURLWithPath: "/tmp/example.ts")
	let language = try #require(SyntaxPipeline.language(forFileURL: url))
	#expect(language == .typescript)
	var pipeline = SyntaxPipeline(language: language)
	#expect(!pipeline.didAllocateParser)
	let tree = try pipeline.parse(Rope("const value = 1;\n"))
	#expect(pipeline.didAllocateParser)
	#expect(!tree.rootNode.hasError)
}

@Test func syntaxPipelineDetectsNewGrammarFiletypes() throws {
	let cases: [(String, Language)] = [
		("/tmp/run.sh", .bash),
		("/tmp/build.zig", .zig),
		("/tmp/main.swift", .swift),
		("/tmp/query.sql", .sql),
		("/tmp/Dockerfile", .dockerfile),
		("/tmp/Dockerfile.dev", .dockerfile),
		("/tmp/app.dart", .dart),
		("/tmp/Main.kt", .kotlin),
		("/tmp/app.exs", .elixir),
	]
	for (path, language) in cases {
		#expect(SyntaxPipeline.language(forFileURL: URL(fileURLWithPath: path)) == language)
	}
}

@Test func parserLoadsNewGrammarSymbols() throws {
	let cases: [(Language, String)] = [
		(.bash, "echo hi\n"),
		(.zig, "const x: i32 = 1;\n"),
		(.swift, "let x = 1\n"),
		(.sql, "select 1;\n"),
		(.dockerfile, "FROM scratch\n"),
		(.dart, "void main() {}\n"),
		(.kotlin, "fun main() {}\n"),
		(.elixir, "x = 1\n"),
	]
	for (language, source) in cases {
		let rope = Rope(source)
		let tree = try Parser(language: language).parse(rope)
		#expect(tree.rootNode.byteRange == 0 ..< rope.length)
		#expect(!tree.rootNode.hasError)
	}
}

@Test func highlightQueriesLoadForNewGrammars() throws {
	for language in [Language.bash, .zig, .swift, .sql, .dockerfile, .dart, .kotlin, .elixir] {
		_ = try HighlightQuery(language: language)
	}
}

@Test func syntaxPipelineIncrementalMiddleEditFitsFrameBudget() throws {
	var editor = Editor(text: largeTypeScriptLineSetWithMiddleLine())
	var pipeline = SyntaxPipeline(language: .typescript)
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
