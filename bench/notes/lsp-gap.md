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

## Not done yet

- No server process lifecycle.
- No initialize/shutdown state machine.
- No document sync from `ItsyDocument`.
- No diagnostics/problem UI.
- No completion, hover, definition, references, rename, code action, or formatting request plumbing.

## Next slice

Implement an `LSPClient` process/session layer with request IDs, pending response routing, initialize/initialized/shutdown sequencing, and injected byte streams for tests before attaching it to the editor UI.
