# DAP Gap

## Sources Checked

- Microsoft Debug Adapter Protocol specification: base messages use `seq` plus `type` values of `request`, `response`, or `event`.
- Microsoft Debug Adapter Protocol specification: payload framing uses `Content-Length` headers with JSON bodies.
- Microsoft Debug Adapter Protocol specification: core debugger flows include `initialize`, `setBreakpoints`, `threads`, `stackTrace`, `scopes`, `variables`, execution control requests, and stopped/output events.

## Added

- Added `ItsyDAP` as a pure Foundation static target.
- Added DAP header/body framing.
- Added request/response/event message models with arbitrary JSON bodies.
- Added typed core models for initialize capabilities, error responses, source breakpoints, threads, stack frames, scopes, variables, continue, stopped, and output events.
- Added encoding/decoding and framing tests.

## Still Missing

- No DAP process transport.
- No debug session lifecycle state machine.
- No UI for breakpoints, call stack, scopes, variables, or console output.
- No adapter-specific launch/attach schema.

## Next slice

Current TODO has no Phase 18/19/20 DAP task id. Next DAP slice should be a new post-Phase20 TODO for process transport/session lifecycle, breakpoints, call stack, variables, and launch/attach schema.
