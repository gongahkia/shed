# LSP Gap

## Sources checked

- Microsoft LSP 3.17 specification: base protocol uses `Content-Length` headers and JSON-RPC payloads; `initialize` is the first request; `textDocument/publishDiagnostics` is the diagnostics notification.
- JSON-RPC 2.0 specification: messages use `jsonrpc: "2.0"`; requests have `method` and `id`; notifications omit `id`; responses carry either `result` or `error`.
- Microsoft DAP specification was checked for later gap 7; it uses the same header/body framing shape but a different message schema, so it should not be mixed into the LSP module.

## Current implementation slice

- Added `ItsyLSP` as a pure Foundation static target.
- Added JSON-RPC ID/value/request/notification/response/error models.
- Added incremental `Content-Length` frame extraction and frame encoding.
- Added LSP document, change, position/range, initialize, and publish-diagnostics data models.
- Added an actor-isolated session core with request ID allocation, pending-response routing, initialize/initialized sequencing, shutdown/exit sequencing, and injectable transport.
- Added a `Process`/`Pipe` transport with guarded start/write/close/terminate and stdout/stderr/termination events.
- Added a high-level process client/router that feeds stdout into the session, exposes server/stderr/termination events, and owns the read pump.

## Not done yet

- No document sync from `ItsyDocument`.
- No diagnostics/problem UI.
- No completion, hover, definition, references, rename, code action, or formatting request plumbing.

## Next slice

Phase 19 is the current LSP reference: id:620-id:625 cover registry, manager, document sync, diagnostics/gutter diagnostics, and capabilities; id:626-id:634 cover completion, hover, definition, references, rename, code actions, formatting, and signature help; id:635-id:637 cover workspace configuration, cold-start budget, and smoke QA.

Next LSP slice should be post-Phase19 server-specific polish/QA, not the old document-sync-first path.
