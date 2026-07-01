# Phase30 DAP checkpoint

Date: 2026-07-01

## Sources checked

- Microsoft DAP: launch flow uses `initialize`, launch/attach request, `initialized`, breakpoint/configuration requests, then `configurationDone`.
- Microsoft DAP: variable editing uses `setVariable` only when `supportsSetVariable` is advertised.
- Local `lldb-dap`: verified with `bench/scripts/dap_smoke.sh`.

## Completed

- Added process transport, client session, lifecycle state, and typed event decoding.
- Added debugger session facade for threads, stack frames, scopes, variables, evaluate, and execution controls.
- Added persisted breakpoints, launch config loading, and watch expression storage.
- Added AppKit launch picker, breakpoint gutter, call stack, variables, watches, debug console, and control strip/menu actions.
- Added LLDB-DAP smoke QA for Swift: compile corpus program, launch via `.itsy/debug.json`, hit breakpoint, step over, and read `stepped == 42`.

## Verification

```sh
bench/scripts/dap_smoke.sh
swift test --no-parallel --filter 'ItsyDAPTests|ItsyDebuggerTests'
swift build
swift build -c release
bench/scripts/regression.sh
git diff --check
```

Result:

- DAP smoke: `dap smoke ok: breakpoint line 2, step-over, stepped=42`.
- DAP + debugger tests: pass.
- Debug and release builds: pass.
- Regression: pass (`cold_start_ready_ms 6.143`, `rope_slice_ns_per_op 48.514`, `swift_loc 28895/30000`).
- Whitespace check: pass.

## Residual gaps

- Exception filters and reverse-debug UI remain follow-up items.
- Smoke QA verifies direct DAP behavior; full app-driven LLDB smoke should be added once launch-time breakpoint synchronization is wired.
