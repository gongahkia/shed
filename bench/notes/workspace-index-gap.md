# Workspace Index Gap

## Sources Checked

- VS Code Code Navigation documentation: competitive baseline includes file-local symbols and workspace-wide symbol lookup.
- Microsoft LSP 3.17 specification: language servers can provide `textDocument/documentSymbol` and `workspace/symbol` responses for richer symbol sources.
- Zed Outline Panel documentation: competitive baseline includes symbol lists with type prefixes and jump-to-location behavior.

## Local Baseline

- `ProjectFind` already has root walking, `.gitignore` filtering, binary-file rejection, relative paths, and size caps.
- `FuzzyMatcher` already gives ranked fuzzy matching for command-palette style queries.
- `ItsyEditor` does not depend on `ItsySyntax`, so the first index slice stays regex-based and avoids pulling Tree-sitter into the editor core.

## Added

- Added a reusable workspace file/symbol index in `ItsyEditor`.
- Added lightweight symbol extraction for Swift, JavaScript/TypeScript, Python, Rust, Go, and C-like declarations.
- Added fuzzy file and symbol search APIs.
- Added temp-directory tests for `.gitignore`, binary skipping, symbol locations, and fuzzy lookup.

## Still Missing

- No global symbol UI.
- No incremental index refresh from file-system events.
- No Tree-sitter/LSP-backed symbol provider.
- No persistence across launches.

## Next slice

Phase18 ids id:600-id:606 cover workspace/file symbol palette, symbol keybindings, Outline panel/persistence, incremental FSEvents refresh, and indexing status. Phase18 ids id:607-id:608 are the LSP-backed symbol-provider follow-up, tied to Phase19 LSP lifecycle availability.
