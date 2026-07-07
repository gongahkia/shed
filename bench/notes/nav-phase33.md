# Phase 33 Navigation Checkpoint

Date: 2026-07-02; refreshed 2026-07-07

Implemented slice:

- added `WorkspaceIndexStore` for persisted workspace symbol indexes.
- stores indexes under `~/.config/itsy/index/<workspace-hash>.json`.
- validates persisted index version and workspace root before loading.
- normalizes persisted file/symbol order for deterministic JSON.
- cold-loads cached symbols when opening a workspace, then rebuilds in the background.
- persists rebuilt and incrementally reindexed workspace indexes.
- enriched `WorkspaceSymbol` with optional signature, container, and documentation fields.
- maps LSP document-symbol detail to `signature` and LSP container names to `containerName`.
- bundled `tags.scm` resources for C, C++, Dart, Elixir, Go, JavaScript, Kotlin, Python, Rust, Swift, and TypeScript.
- added Tree-sitter tag-query extraction with `@name`, `@definition.*`, and `@doc` capture handling.
- app workspace indexing now tries Tree-sitter tags first and falls back to regex when no tag query is available.
- existing palette flow keeps LSP workspace symbols ahead of indexed symbols and prefers current-file `textDocument/documentSymbol` when an LSP session is running.

References:

- Tree-sitter Code Navigation Systems: https://tree-sitter.github.io/tree-sitter/4-code-navigation.html

Verification:

```sh
swift test --filter WorkspaceIndexStore
swift test --filter WorkspaceIndex
swift test --filter LSPSymbolAdapter
swift test --filter tagQuery
swift test --filter tagQueriesLoadForBundledGrammars
swift test
```

Result:

- `WorkspaceIndexStore`: 3 tests passed.
- `WorkspaceIndex`: 8 tests passed.
- `LSPSymbolAdapter`: 5 tests passed.
- `tagQuery`: 2 tests passed.
- `tagQueriesLoadForBundledGrammars`: 1 test passed.
- `swift test`: 442 tests passed.

Conclusion:

- #5 acceptance is met on the refreshed current tree.
