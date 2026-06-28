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

#ifdef __cplusplus
}
#endif

#endif
