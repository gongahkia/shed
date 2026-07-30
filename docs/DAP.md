# Debug Adapter Protocol Architecture

Shed's debug architecture is adapter-capability driven. The Debug Adapter Protocol separates the editor from language-specific debuggers. Configuration is validated before a transport can start; the transport does not select configurations, launch a debuggee, collect telemetry, or download adapters.

## Safe Defaults

- `debug.enabled = false`; no adapter or configuration is built in.
- Adapters are user-managed and configured only in global `~/.shed/config.toml` by default.
- Project `.shed.toml` debug settings are unsafe and remain blocked until `project.config.allow.unsafe = true` and the project config is trusted.
- Adapter commands are direct executable tokens plus whitespace-separated arguments; Shed does not invoke a shell.
- Configuration scope is always `workspace`; `cwd` and launch `program` must remain under `${workspaceFolder}`, except `${file}` for the active workspace file.
- Attach targets are loopback-only (`localhost`, `127.0.0.1`, or `::1`) in M0.

## TOML Schema

```toml
schema_version = 1

"debug.enabled" = true
"debug.breakpoints.enabled" = true
"debug.threads.enabled" = true
"debug.stacktrace.enabled" = true
"debug.scopes.enabled" = true
"debug.variables.enabled" = true
"debug.evaluate.enabled" = true
"debug.attach.enabled" = true

"debug.adapter.java.command" = "java-debug-adapter"
"debug.adapter.java.args" = "--stdio"
"debug.adapter.java.transport" = "stdio"
"debug.adapter.java.capabilities" = "launch,attach,configuration_done,breakpoints,threads,stack_trace,scopes,variables,evaluate"

"debug.configuration.main.adapter" = "java"
"debug.configuration.main.request" = "launch"
"debug.configuration.main.scope" = "workspace"
"debug.configuration.main.program" = "${file}"
"debug.configuration.main.cwd" = "${workspaceFolder}"
"debug.configuration.main.args" = ""
```

Adapter identifiers and configuration names are `[A-Za-z0-9_-]+`. `transport` is `stdio` (default) or `tcp`; `stdio` requires `command`, while `tcp` must not set one. Capabilities are comma-separated: `launch`, `attach`, `configuration_done`, `breakpoints`, `threads`, `stack_trace`, `scopes`, `variables`, and `evaluate`.

Each configuration requires `adapter` and `request` (`launch` or `attach`). A launch requires a workspace-scoped `program`. An attach requires a loopback `host` and `port` from `1..65535`. Invalid fields are reported with TOML line and column during config loading; Shed retains safe defaults and does not create a launch plan.

## Future Session Boundary

A debug session may only give the transport a validated `Plan`: selected adapter, declared capabilities, configuration, workspace root, resolved working directory, and resolved program. It must perform DAP initialization before later adapter-specific launch or attach requests and honor the capabilities returned by the adapter. Adapter-specific request arguments remain deferred because DAP leaves them adapter-defined.

## Transport Boundary

`DebugAdapterTransport` implements the DAP base framing: ASCII `Content-Length` headers and UTF-8 JSON objects. It accepts only bounded frames (16 KiB headers and 8 MiB bodies), validates JSON syntax before dispatch, and treats malformed or truncated adapter output as an isolated session failure. A failure completes outstanding requests, closes streams/sockets, stops a stdio adapter process, and reports a local diagnostic; it does not crash the editor.

- It requires `debug.enabled`; attach plans also require `debug.attach.enabled` before opening a process or socket.
- `stdio` starts the configured direct executable in the validated workspace `cwd`. Adapter stderr is drained separately, never mixed into the DAP stdout stream.
- `tcp` connects only to the validated loopback host and port. It never starts a local adapter process.
- Request timeouts issue DAP `cancel` only after the adapter declares `supportsCancelRequest`; otherwise Shed ignores a late response.
- Closing a transport sends `disconnect` when possible, setting `terminateDebuggee` for launch sessions only, then closes the connection and terminates a stdio adapter if it remains alive.
- Adapter-initiated requests receive a deterministic unsupported response until a later feature supplies a handler. Events and responses are delivered separately.

The transport has no UI dependency and records to `DiagnosticLog` only when its caller supplies one; it performs no telemetry or network access other than an explicitly configured loopback TCP connection.

## Adapter Detection

`DebugAdapterDetector` reports configured adapter and configuration availability for one workspace without starting an adapter, opening a socket, or modifying workspace state. Missing executables and invalid debug settings are remediation states only; `normalEditingAvailable` remains true.

Adapter versions are `NOT_PROBED`: the published DAP schema has no adapter-discovery or version request, and Shed does not invoke a configured adapter with guessed arguments during read-only detection. The report exposes each declared capability as `AVAILABLE`, `DISABLED` by the corresponding debug setting, or `UNDECLARED`, plus launch/attach configuration availability. Resolution is skipped while `debug.enabled=false`.

## Explicit Session Lifecycle

Use `:debug configurations` to inspect configured adapters without starting one, `:debug select <name>` to choose a configuration, and `:debug start [name]` to explicitly begin a session. The editor starts the validated adapter, sends DAP `initialize`, then sends the configured `launch` or `attach` request. When the adapter emits `initialized`, Shed sends configuration requests before `configurationDone` only when both the declared adapter capability and the DAP initialize response support it. `:debug stop`, `:debug restart [name]`, and `:debug status` provide visible lifecycle state and retained diagnostics.

Shed never starts a debug adapter while inspecting configurations or selecting one. A rejected configuration, adapter start error, timeout, or failed DAP response leaves the session `FAILED` with diagnostics visible in `[debug status]`; normal editing remains available. The generic launch arguments are only `program`, `cwd`, and `args`; attach arguments are only `host`, `port`, `cwd`, and `args`, so adapters that require additional adapter-specific settings fail visibly rather than receiving inferred values.

## Source Breakpoints

Click the left gutter to add or remove a source breakpoint. Shed stores workspace-scoped breakpoint JSON beneath the configured `session.dir` in `breakpoints/`; it writes no source file or project metadata. The store records requested lines plus verified, rejected, or adapter-adjusted locations and uses atomic replacement.

Shed sends one DAP `setBreakpoints` request per source only when `debug.breakpoints.enabled=true` and the selected adapter declares `breakpoints`. A `setBreakpoints` response replaces the displayed state for that source: rejected locations use an outlined red gutter marker, adjusted locations use yellow, and details remain in `:debug status`. The request contains the complete set for the source, not an incremental delta. If an adapter does not emit `initialized`, Shed retains the breakpoint state and performs the compatible post-start synchronization with a retained diagnostic.

## Paused-frame Inspection

On a DAP `stopped` event, use `:debug stack` or `:debug variables` to load `[debug inspector]`; loading, unavailable capability, and error states remain visible there. `:debug frame <id>` selects a returned frame before reloading its scopes and variables. `:debug watch add <expression>`, `:debug watch remove <expression>`, `:debug watch list`, and `:debug watch clear` manage session-local watches; evaluation uses DAP `evaluate` with `context: watch` for the selected paused frame.

Shed sends `threads`, `stackTrace`, `scopes`, `variables`, and `evaluate` only when their corresponding configured adapter capabilities and feature settings are enabled. A later `continued`, `terminated`, or `exited` event invalidates paused-frame references and leaves watches pending until the next stop; stale responses from a prior suspended state are discarded.

## Debug Console

DAP `output` events are retained in received order as categorized `stdout`, `stderr`, or `console` text. They never open or focus a buffer. Use `:debug console` to inspect the explicit `[debug console]` scratch view and `:debug console clear` to discard retained output. The recovery buffer retains only its most recent 64 KiB and labels truncation. `terminated`, `exited`, transport failure, and explicit stop update the visible console connection state without deleting retained output.
