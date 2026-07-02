# Phase 33 Navigation Checkpoint

Date: 2026-07-02

Implemented slice:

- added `WorkspaceIndexStore` for persisted workspace symbol indexes.
- stores indexes under `~/.config/itsy/index/<workspace-hash>.json`.
- validates persisted index version and workspace root before loading.
- normalizes persisted file/symbol order for deterministic JSON.
- cold-loads cached symbols when opening a workspace, then rebuilds in the background.
- persists rebuilt and incrementally reindexed workspace indexes.
- enriched `WorkspaceSymbol` with optional signature, container, and documentation fields.
- maps LSP document-symbol detail to `signature` and LSP container names to `containerName`.

Verification:

```sh
swift test --filter WorkspaceIndexStore
swift test --filter WorkspaceIndex
swift test --filter LSPSymbolAdapter
```

Result:

- `WorkspaceIndexStore`: 3 tests passed.
- `WorkspaceIndex`: 7 tests passed.
- `LSPSymbolAdapter`: 5 tests passed.

Remaining for #5:

- tree-sitter tags queries as primary symbol source.
- regex fallback only when tags are unavailable.
- documentation extraction for richer symbol metadata.
- LSP/tree-sitter/persisted-index merge and dedup policy.
- current-document `textDocument/documentSymbol` preference.
