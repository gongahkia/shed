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
- Added process transport, client session, lifecycle state, and typed event decoding.
- Added `ItsyDebugger` session facade for threads, stack frames, scopes, variables, evaluate, `setVariable`, and execution controls.
- Added persisted breakpoint, launch config, and watch stores.
- Added AppKit debugger UI for launch configs, breakpoint gutter, call stack, variables, watches, debug console, and controls.
- Added `bench/scripts/dap_smoke.sh` validating LLDB-DAP launch, breakpoint hit, step-over, and variable read on `bench/corpus/debug-hello.swift`.
- Added DAP adapter registry defaults and `dap.toml` overrides for LLDB, debugpy, delve, and vscode-js-debug.
- Added persisted breakpoint sync before `configurationDone`.

## Closed

- LLDB-DAP path is verified for Swift launch -> breakpoint -> step-over -> variable read.
- Core debugger UI surfaces now exist for Phase 30.

## Still Missing

- Exception filter UI and `setExceptionBreakpoints`.
- Reverse-debug UI gated on adapter capabilities.
- CI-hosted debugpy coverage when `debugpy` is not installed locally.
