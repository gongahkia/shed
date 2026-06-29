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

## Not done yet

- No server process lifecycle.
- No server process read loop.
- No document sync from `ItsyDocument`.
- No diagnostics/problem UI.
- No completion, hover, definition, references, rename, code action, or formatting request plumbing.

## Next slice

Implement the process transport/read loop around `Process` and `Pipe`, then attach diagnostics to a non-rendering document model before any UI surface.
