import CTSGrammars
import CTreeSitter
import Testing

@Test func grammarSymbolsLink() {
	let languages = [
		tree_sitter_c(),
		tree_sitter_cpp(),
		tree_sitter_css(),
		tree_sitter_go(),
		tree_sitter_html(),
		tree_sitter_javascript(),
		tree_sitter_json(),
		tree_sitter_markdown(),
		tree_sitter_markdown_inline(),
		tree_sitter_python(),
		tree_sitter_rust(),
		tree_sitter_toml(),
		tree_sitter_tsx(),
		tree_sitter_typescript(),
		tree_sitter_yaml(),
		tree_sitter_bash(),
		tree_sitter_c_sharp(),
		tree_sitter_graphql(),
		tree_sitter_haskell(),
		tree_sitter_java(),
		tree_sitter_julia(),
		tree_sitter_latex(),
		tree_sitter_lua(),
		tree_sitter_nix(),
		tree_sitter_ocaml(),
		tree_sitter_php(),
		tree_sitter_proto(),
		tree_sitter_r(),
		tree_sitter_ruby(),
		tree_sitter_scss(),
		tree_sitter_svelte(),
		tree_sitter_zig(),
		tree_sitter_swift(),
		tree_sitter_sql(),
		tree_sitter_terraform(),
		tree_sitter_dockerfile(),
		tree_sitter_dart(),
		tree_sitter_kotlin(),
		tree_sitter_elixir(),
		tree_sitter_vue(),
	]
	#expect(languages.allSatisfy { $0 != nil })
}

@Test func additionalGrammarSmokeFixturesParse() throws {
	for fixture in additionalGrammarFixtures {
		let parser = try #require(ts_parser_new())
		defer {
			ts_parser_delete(parser)
		}
		#expect(ts_parser_set_language(parser, fixture.language))
		let tree = fixture.source.withCString { pointer in
			ts_parser_parse_string(parser, nil, pointer, UInt32(fixture.source.utf8.count))
		}
		let parsed = try #require(tree)
		defer {
			ts_tree_delete(parsed)
		}
		let root = ts_tree_root_node(parsed)
		#expect(!ts_node_has_error(root))
	}
}

private struct GrammarFixture {
	var language: OpaquePointer
	var source: String
}

private let additionalGrammarFixtures: [GrammarFixture] = [
	GrammarFixture(language: tree_sitter_c_sharp(), source: "class Program { static void Main() {} }\n"),
	GrammarFixture(language: tree_sitter_graphql(), source: "type Query { hello: String }\n"),
	GrammarFixture(language: tree_sitter_haskell(), source: "main = putStrLn \"hi\"\n"),
	GrammarFixture(language: tree_sitter_java(), source: "class Main { void run() {} }\n"),
	GrammarFixture(language: tree_sitter_julia(), source: "function f(x)\n x + 1\nend\n"),
	GrammarFixture(language: tree_sitter_latex(), source: "\\section{Hi}\ntext\n"),
	GrammarFixture(language: tree_sitter_lua(), source: "local x = 1\nprint(x)\n"),
	GrammarFixture(language: tree_sitter_nix(), source: "{ pkgs ? import <nixpkgs> {} }: pkgs.hello\n"),
	GrammarFixture(language: tree_sitter_ocaml(), source: "let x = 1\n"),
	GrammarFixture(language: tree_sitter_php(), source: "<?php echo \"hi\";\n"),
	GrammarFixture(language: tree_sitter_proto(), source: "syntax = \"proto3\"; message Foo { string name = 1; }\n"),
	GrammarFixture(language: tree_sitter_r(), source: "x <- 1\nprint(x)\n"),
	GrammarFixture(language: tree_sitter_ruby(), source: "class App\n def run\n  1\n end\nend\n"),
	GrammarFixture(language: tree_sitter_scss(), source: "$color: red;\n.a { color: $color; }\n"),
	GrammarFixture(language: tree_sitter_svelte(), source: "<script>let x = 1;</script><h1>{x}</h1>\n"),
	GrammarFixture(language: tree_sitter_terraform(), source: "resource \"x\" \"y\" { name = \"z\" }\n"),
	GrammarFixture(language: tree_sitter_vue(), source: "<template><div>{{ msg }}</div></template>\n"),
]
