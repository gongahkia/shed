import Dispatch
import Foundation
import ItsyEditor
@testable import ItsySyntax
import Testing

@Suite(.serialized)
struct ParserTests {

@Test func parserParsesTypeScriptProgram() throws {
	let rope = Rope("const answer: number = 42;\n")
	let parser = try Parser(language: .typescript)
	let tree = try parser.parse(rope)
	let root = tree.rootNode
	#expect(root.type == "program")
	#expect(root.byteRange == 0 ..< rope.length)
	#expect(!root.hasError)
}

@Test func parserLoadsGrammarFromDylibAndUnloadsForTests() throws {
	let repo = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
	let output = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-json-grammar-\(UUID().uuidString)", isDirectory: true)
	try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
	defer {
		try? FileManager.default.removeItem(at: output)
	}
	let libraryURL = output.appendingPathComponent("libitsy-tree-sitter-json.dylib")
	try runXcrunClang([
		"-dynamiclib",
		"-O0",
		"-fPIC",
		"-mmacosx-version-min=13.0",
		"-o",
		libraryURL.path,
		"-I\(repo.appendingPathComponent("Sources/CTreeSitter/upstream/lib/include").path)",
		"-I\(repo.appendingPathComponent("Sources/CTSGrammars/grammars/json/src").path)",
		repo.appendingPathComponent("Sources/CTSGrammars/grammars/json/src/parser.c").path,
	])
	GrammarLoader.configureForTests(libraryDirectories: [output], useDefaultSymbols: false)
	GrammarLoader.unloadLibraryStemForTests("json")
	defer {
		GrammarLoader.unloadLibraryStemForTests("json")
		GrammarLoader.configureForTests()
	}

	do {
		let rope = Rope(#"{"ok": true}"#)
		let parser = try Parser(language: .json)
		let tree = try parser.parse(rope)
		#expect(tree.rootNode.byteRange == 0 ..< rope.length)
		#expect(!tree.rootNode.hasError)
		#expect(GrammarLoader.loadedLibraryStemsForTests().contains("json"))
	}
	GrammarLoader.unloadLibraryStemForTests("json")
	#expect(!GrammarLoader.loadedLibraryStemsForTests().contains("json"))
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

@Test func tagQueryCapturesSwiftDefinitions() throws {
	let text = """
	struct AppShell {
		func renderFrame() {}
		let frameCount = 0
	}
	func makeApp() {}
	"""
	let symbols = try #require(TreeSitterSymbolExtractor.workspaceSymbols(
		in: text,
		fileURL: URL(fileURLWithPath: "/tmp/App.swift"),
		relativePath: "Sources/App.swift"
	))

	#expect(symbols.contains { $0.name == "AppShell" && $0.kind == .type && $0.line == 1 && $0.column == 8 })
	#expect(symbols.contains { $0.name == "renderFrame" && $0.kind == .method })
	#expect(symbols.contains { $0.name == "frameCount" && $0.kind == .variable })
	#expect(symbols.contains { $0.name == "makeApp" && $0.kind == .function })
}

@Test func tagQueryCapturesTypeScriptDefinitionsAndDocs() throws {
	let text = """
	/** Build the app. */
	function buildApp() {}
	class Widget {
		render() {}
	}
	const makeWidget = () => new Widget()
	"""
	let symbols = try #require(TreeSitterSymbolExtractor.workspaceSymbols(
		in: text,
		fileURL: URL(fileURLWithPath: "/tmp/app.ts"),
		relativePath: "src/app.ts"
	))

	#expect(symbols.contains { $0.name == "buildApp" && $0.kind == .function && $0.documentation == "Build the app." })
	#expect(symbols.contains { $0.name == "Widget" && $0.kind == .type })
	#expect(symbols.contains { $0.name == "render" && $0.kind == .method })
	#expect(symbols.contains { $0.name == "makeWidget" && $0.kind == .function })
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
	#expect(try SyntaxColor(hex: "#abc") == SyntaxColor(red: Float(0xaa) / 255, green: Float(0xbb) / 255, blue: Float(0xcc) / 255))
	#expect(try SyntaxColor(hex: "#abcd") == SyntaxColor(red: Float(0xaa) / 255, green: Float(0xbb) / 255, blue: Float(0xcc) / 255, alpha: Float(0xdd) / 255))
}

@Test func itsyThemeLoadsWorkbenchColorsAndAppearance() throws {
	let theme = ItsyTheme(id: "test", displayName: "Test", colors: [
		"editor.background": try SyntaxColor(hex: "#101820"),
		"editor.foreground": try SyntaxColor(hex: "#f0f3f6"),
		"keyword": try SyntaxColor(hex: "#ff00aa"),
	])
	let background = try SyntaxColor(hex: "#101820")
	let keyword = try SyntaxColor(hex: "#ff00aa")
	#expect(theme.appearance == .dark)
	#expect(theme.color(for: "editor.background") == background)
	#expect(theme.syntax.color(for: "keyword") == keyword)
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
		("/tmp/Program.cs", .csharp),
		("/tmp/schema.graphql", .graphql),
		("/tmp/Main.java", .java),
		("/tmp/notebook.jl", .julia),
		("/tmp/paper.tex", .latex),
		("/tmp/init.lua", .lua),
		("/tmp/flake.nix", .nix),
		("/tmp/main.ml", .ocaml),
		("/tmp/index.php", .php),
		("/tmp/service.proto", .proto),
		("/tmp/analysis.R", .r),
		("/tmp/app.rb", .ruby),
		("/tmp/style.scss", .scss),
		("/tmp/App.svelte", .svelte),
		("/tmp/main.tf", .terraform),
		("/tmp/App.vue", .vue),
		("/tmp/Main.hs", .haskell),
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
		(.csharp, "class Program { static void Main() {} }\n"),
		(.graphql, "type Query { hello: String }\n"),
		(.haskell, "main = putStrLn \"hi\"\n"),
		(.java, "class Main { void run() {} }\n"),
		(.julia, "function f(x)\n x + 1\nend\n"),
		(.latex, "\\section{Hi}\ntext\n"),
		(.lua, "local x = 1\nprint(x)\n"),
		(.nix, "{ pkgs ? import <nixpkgs> {} }: pkgs.hello\n"),
		(.ocaml, "let x = 1\n"),
		(.php, "<?php echo \"hi\";\n"),
		(.proto, "syntax = \"proto3\"; message Foo { string name = 1; }\n"),
		(.r, "x <- 1\nprint(x)\n"),
		(.ruby, "class App\n def run\n  1\n end\nend\n"),
		(.scss, "$color: red;\n.a { color: $color; }\n"),
		(.svelte, "<script>let x = 1;</script><h1>{x}</h1>\n"),
		(.terraform, "resource \"x\" \"y\" { name = \"z\" }\n"),
		(.vue, "<template><div>{{ msg }}</div></template>\n"),
	]
	for (language, source) in cases {
		let rope = Rope(source)
		let tree = try Parser(language: language).parse(rope)
		#expect(tree.rootNode.byteRange == 0 ..< rope.length)
		#expect(!tree.rootNode.hasError)
	}
}

@Test func bundledLanguageInventoryCoversEverySyntaxGrammarAndFixture() throws {
	let inventory = BundledLanguageInventory.languages
	#expect(Set(inventory.map(\.grammarID)) == Set(Language.allCases.map(\.inventoryID)))
	#expect(inventory.count == Language.allCases.count)
	for language in Language.allCases {
		let entry = try #require(inventory.first { $0.grammarID == language.inventoryID })
		let rope = Rope(entry.fixture)
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

@Test func highlightQueriesLoadForAdditionalGrammars() throws {
	for language in [Language.csharp, .graphql, .haskell, .java, .julia, .latex, .lua, .nix, .ocaml, .php, .proto, .r, .ruby, .scss, .svelte, .terraform, .vue] {
		_ = try HighlightQuery(language: language)
	}
}

@Test func tagQueriesLoadForBundledGrammars() throws {
	for language in [Language.c, .cpp, .dart, .elixir, .go, .javascript, .kotlin, .python, .rust, .swift, .typescript] {
		_ = try TagQuery(language: language)
	}
}

@Test func injectionQueriesLoadForCoveredGrammars() throws {
	for language in [Language.markdown, .javascript, .typescript, .tsx, .html, .rust, .python, .go, .c, .cpp, .swift, .dockerfile] {
		_ = try InjectionQuery(language: language)
	}
}

@Test func localQueriesCaptureDefinitionsAndReferences() throws {
	let cases: [(Language, String, String)] = [
		(.rust, "fn main() { let value = 1; value; }\n", "value"),
		(.python, "def main(value):\n    local = value\n    return local\n", "local"),
		(.go, "package main\nfunc main() { value := 1; _ = value }\n", "value"),
		(.swift, "func main(value: Int) { let local = value; _ = local }\n", "local"),
		(.javascript, "function main(value) { const local = value; return local; }\n", "local"),
		(.typescript, "function main(value: number) { const local = value; return local; }\n", "local"),
		(.c, "int main() { int value = 1; return value; }\n", "value"),
		(.cpp, "int main() { int value = 1; return value; }\n", "value"),
	]
	for (language, source, expectedName) in cases {
		var pipeline = SyntaxPipeline(language: language)
		let tree = try pipeline.parse(Rope(source))
		let captures = try LocalQuery(language: language).captures(in: tree, source: source)
		#expect(captures.contains { $0.capture == "local.definition" && $0.text == expectedName })
		#expect(captures.contains { $0.capture == "local.reference" && $0.text == expectedName })
	}
}

@Test func indentQueriesIndentAfterOpeningDelimiter() throws {
	let cases: [(Language, String)] = [
		(.rust, "fn main() {}"),
		(.python, "values = []"),
		(.go, "package main\nfunc main() {}"),
		(.swift, "func main() {}"),
		(.javascript, "function main() {}"),
		(.typescript, "function main(): void {}"),
		(.c, "int main() {}"),
		(.cpp, "int main() {}"),
	]
	for (language, source) in cases {
		var pipeline = SyntaxPipeline(language: language)
		let tree = try pipeline.parse(Rope(source))
		let offset = try #require(byteRange(of: "{", in: source)?.upperBound ?? byteRange(of: "[", in: source)?.upperBound)
		let insertion = try pipeline.indentationAfterNewline(in: tree, source: source, offset: offset, tabWidth: 4)
		#expect(insertion == "\n    ")
	}
}

@Test func indentQueriesUseConfiguredIndentationUnit() throws {
	let source = "func main() {}"
	var pipeline = SyntaxPipeline(language: .swift)
	let tree = try pipeline.parse(Rope(source))
	let offset = try #require(byteRange(of: "{", in: source)?.upperBound)
	#expect(try pipeline.indentationAfterNewline(in: tree, source: source, offset: offset, indentationUnit: "\t") == "\n\t")
}

@Test func markdownFenceHighlightsEmbeddedSwift() throws {
	let source = "```swift\nlet value = 1\n```\n"
	var pipeline = SyntaxPipeline(language: .markdown)
	let tree = try pipeline.parse(Rope(source))
	let spans = try pipeline.highlights(in: tree, source: source, includeInjections: true)
	let letRange = try #require(byteRange(of: "let", in: source))
	#expect(spans.contains { $0.range == letRange && $0.capture.hasPrefix("keyword") })
}

@Test func injectionExpansionKeepsBaseHighlightsWhenQueryIsMissing() throws {
	let source = #"{"value": 1}"#
	var pipeline = SyntaxPipeline(language: .json)
	let tree = try pipeline.parse(Rope(source))
	let spans = try pipeline.highlights(in: tree, source: source, includeInjections: true)
	#expect(spans.contains { $0.capture == "string" })
	#expect(spans.contains { $0.capture == "number" })
}

@Test func injectionQueriesCaptureRepresentativeSites() throws {
	let cases: [(Language, String, Language, String)] = [
		(.markdown, "```swift\nlet value = 1\n```\n", .swift, "let value = 1\n"),
		(.javascript, "graphql`type Query { ok: String }`;\n", .graphql, "type Query { ok: String }"),
		(.typescript, "const query = graphql`type Query { ok: String }`;\n", .graphql, "type Query { ok: String }"),
		(.tsx, "const query = graphql`type Query { ok: String }`;\n", .graphql, "type Query { ok: String }"),
		(.html, "<script>const value = 1;</script>\n", .javascript, "const value = 1;"),
		(.rust, "fn main() { sql! { let value = 1; } }\n", .rust, "{ let value = 1; }"),
		(.python, "sql(\"select 1\")\n", .sql, "select 1"),
		(.go, "package main\nfunc main() { graphql(`type Query { ok: String }`) }\n", .graphql, "type Query { ok: String }"),
		(.c, "int main() { sql(\"select 1\"); }\n", .sql, "select 1"),
		(.cpp, "int main() { sql(\"select 1\"); }\n", .sql, "select 1"),
		(.swift, "func main() { sql(\"select 1\") }\n", .sql, "select 1"),
		(.dockerfile, "FROM scratch\nRUN echo hi\n", .bash, "echo hi"),
	]
	for (language, source, expectedLanguage, expectedContent) in cases {
		var pipeline = SyntaxPipeline(language: language)
		let tree = try pipeline.parse(Rope(source))
		let sites = try InjectionQuery(language: language).injections(in: tree, source: source)
		#expect(sites.contains { site in
			site.language == expectedLanguage && String(decoding: Array(source.utf8)[site.range], as: UTF8.self) == expectedContent
		})
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

private func byteRange(of needle: String, in haystack: String) -> Range<Int>? {
	guard let range = haystack.range(of: needle) else {
		return nil
	}
	let lower = haystack[..<range.lowerBound].utf8.count
	let upper = lower + needle.utf8.count
	return lower ..< upper
}

private func runXcrunClang(_ arguments: [String]) throws {
	let process = Process()
	process.executableURL = URL(fileURLWithPath: "/usr/bin/xcrun")
	process.arguments = ["clang"] + arguments
	let errorPipe = Pipe()
	process.standardError = errorPipe
	try process.run()
	process.waitUntilExit()
	guard process.terminationStatus == 0 else {
		let data = errorPipe.fileHandleForReading.readDataToEndOfFile()
		let message = String(data: data, encoding: .utf8) ?? "clang failed"
		throw NSError(domain: "ItsySyntaxTests", code: Int(process.terminationStatus), userInfo: [NSLocalizedDescriptionKey: message])
	}
}
