# Workspace Index Gap

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

## Next

Wire `WorkspaceIndex` to the workspace root, expose a command-palette `Go to Symbol in Workspace` action, then replace regex extraction with language-server or Tree-sitter providers where available.
