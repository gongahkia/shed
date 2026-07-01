import CTSGrammars
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
		tree_sitter_zig(),
		tree_sitter_swift(),
		tree_sitter_sql(),
		tree_sitter_dockerfile(),
		tree_sitter_dart(),
		tree_sitter_kotlin(),
		tree_sitter_elixir(),
	]
	#expect(languages.allSatisfy { $0 != nil })
}
