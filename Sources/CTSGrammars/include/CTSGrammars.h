#ifndef CTSGRAMMARS_H
#define CTSGRAMMARS_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct TSLanguage TSLanguage;

const TSLanguage *tree_sitter_c(void);
const TSLanguage *tree_sitter_cpp(void);
const TSLanguage *tree_sitter_css(void);
const TSLanguage *tree_sitter_go(void);
const TSLanguage *tree_sitter_html(void);
const TSLanguage *tree_sitter_javascript(void);
const TSLanguage *tree_sitter_json(void);
const TSLanguage *tree_sitter_markdown(void);
const TSLanguage *tree_sitter_markdown_inline(void);
const TSLanguage *tree_sitter_python(void);
const TSLanguage *tree_sitter_rust(void);
const TSLanguage *tree_sitter_toml(void);
const TSLanguage *tree_sitter_tsx(void);
const TSLanguage *tree_sitter_typescript(void);
const TSLanguage *tree_sitter_yaml(void);
const TSLanguage *tree_sitter_bash(void);
const TSLanguage *tree_sitter_c_sharp(void);
const TSLanguage *tree_sitter_graphql(void);
const TSLanguage *tree_sitter_haskell(void);
const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_julia(void);
const TSLanguage *tree_sitter_latex(void);
const TSLanguage *tree_sitter_lua(void);
const TSLanguage *tree_sitter_nix(void);
const TSLanguage *tree_sitter_ocaml(void);
const TSLanguage *tree_sitter_php(void);
const TSLanguage *tree_sitter_proto(void);
const TSLanguage *tree_sitter_r(void);
const TSLanguage *tree_sitter_ruby(void);
const TSLanguage *tree_sitter_scss(void);
const TSLanguage *tree_sitter_svelte(void);
const TSLanguage *tree_sitter_zig(void);
const TSLanguage *tree_sitter_swift(void);
const TSLanguage *tree_sitter_sql(void);
const TSLanguage *tree_sitter_terraform(void);
const TSLanguage *tree_sitter_dockerfile(void);
const TSLanguage *tree_sitter_dart(void);
const TSLanguage *tree_sitter_kotlin(void);
const TSLanguage *tree_sitter_elixir(void);
const TSLanguage *tree_sitter_vue(void);

#ifdef __cplusplus
}
#endif

#endif
