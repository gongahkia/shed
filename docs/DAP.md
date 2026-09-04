# Debug Adapter Protocol Architecture

Shed's debug architecture is adapter-capability driven. The Debug Adapter Protocol separates the editor from language-specific debuggers. Configuration is validated before a transport can start; the transport does not select configurations, launch a debuggee, collect telemetry, or download adapters.

## Safe Defaults

- `debug.enabled = false`; selecting a profile or opening the Debug panel does not start a process.
- Shed includes one narrow profile, `python-debugpy`, for the separately user-installed `debugpy-adapter` executable. Shed neither bundles, downloads, nor probes `debugpy`; the profile is unavailable until that executable is on `PATH`.
- Other adapters are user-managed and configured only in global `~/.shed/config.toml` by default.
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
"debug.adapter.java.capabilities" = "launch,attach,configuration_done,breakpoints,exception_breakpoints,conditional_breakpoints,hit_conditional_breakpoints,log_points,threads,stack_trace,scopes,variables,evaluate,continue,next,step_in,step_out,pause"

"debug.configuration.main.adapter" = "java"
"debug.configuration.main.request" = "launch"
"debug.configuration.main.scope" = "workspace"
"debug.configuration.main.program" = "${file}"
"debug.configuration.main.cwd" = "${workspaceFolder}"
"debug.configuration.main.args" = ""
"debug.configuration.main.prelaunch_task" = "build"
```

For an explicit test-debug target, map an adapter in workspace `.shedtests` to one of these global configurations with `debug_configuration = "main"`. During `:test debug <test-id>` or **Debug Selection**, `${testId}` and `${testFile}` are available in `debug.configuration.<name>.args`; unknown placeholders and test files outside the selected workspace reject the launch before an adapter process starts.

Adapter identifiers and configuration names are `[A-Za-z0-9_-]+`. `transport` is `stdio` (default) or `tcp`; `stdio` requires `command`, while `tcp` must not set one. Capabilities are comma-separated: `launch`, `attach`, `configuration_done`, `breakpoints`, `exception_breakpoints`, `conditional_breakpoints`, `hit_conditional_breakpoints`, `log_points`, `threads`, `stack_trace`, `scopes`, `variables`, `evaluate`, `continue`, `next`, `step_in`, `step_out`, and `pause`.

Each configuration requires `adapter` and `request` (`launch` or `attach`). A launch requires a workspace-scoped `program`. An attach requires a loopback `host` and `port` from `1..65535`. An optional `file_extensions` value is a comma-separated allowlist such as `.py,.pyw`; it rejects a launch whose resolved program has another extension. An optional `prelaunch_task` is a task identifier from the selected workspace’s `.shedtasks`. For an explicit debug start, Shed validates and runs that local task before opening the debug adapter; a missing, invalid, cancelled, timed-out, or non-zero task stops the session before any adapter process starts. Invalid fields are reported with TOML line and column during config loading; Shed retains safe defaults and does not create a launch plan.

`python-debugpy` launches the current `.py` or `.pyw` file with the upstream `debugpy-adapter` DAP executable. Its request carries only `program`, `cwd`, and `args`, which debugpy accepts for program launch. This is local Python launch support, not a bundled Python runtime, environment manager, test-debugger integration, remote debugger, or general debugger marketplace.

## Future Session Boundary

A debug session may only give the transport a validated `Plan`: selected adapter, locally declared capabilities, configuration, workspace root, resolved working directory, and resolved program. It performs DAP initialization before later adapter-specific launch or attach requests. `configurationDone` and rich source-breakpoint fields require both Shed's local adapter declaration and the matching capability advertised in the adapter's initialize response; a mismatch rejects the rich option with a diagnostic. Other adapter-specific request arguments remain deferred because DAP leaves them adapter-defined.

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

Use `:debug` to open the docked Debug panel, or `:debug configurations` to inspect configured adapters without starting one. The panel and `:debug select <name>` choose a configuration, while `:debug start [name]` explicitly begins a session. If no configuration is selected, an explicit start of the active `.py` or `.pyw` file chooses the built-in `python-debugpy` profile; no other language is inferred and opening a file never starts debugging. The editor starts the validated adapter, sends DAP `initialize`, then sends the configured `launch` or `attach` request. When the adapter emits `initialized`, Shed sends configuration requests before `configurationDone` only when both the declared adapter capability and the DAP initialize response support it. `:debug stop`, `:debug restart [name]`, and `:debug status` provide visible lifecycle state and retained diagnostics; prefix a command with `:debug text` for legacy scratch output.

Shed never starts a debug adapter while inspecting configurations or selecting one. A rejected configuration, adapter start error, timeout, or failed DAP response leaves the session `FAILED` with diagnostics visible in `[debug status]`; normal editing remains available. The generic launch arguments are only `program`, `cwd`, and `args`; attach arguments are only `host`, `port`, `cwd`, and `args`, so adapters that require additional adapter-specific settings fail visibly rather than receiving inferred values. Test debugging never guesses a framework or adapter; it only starts the explicit mapping in `.shedtests`.

## Execution controls

`:debug continue`, `:debug next`, `:debug stepin`, and `:debug stepout` work only while the adapter has reported a paused thread and has declared the corresponding capability. `:debug pause` requires declared `pause` and `threads` capabilities; Shed asks the adapter for its current thread list instead of assuming a thread ID. The Debug panel exposes the same controls. A request is explicit, asynchronous, and diagnostic on failure. Shed does not claim support for reverse execution, run-to-cursor, data breakpoints, function breakpoints, disassembly, or adapter-specific debug console input.

## Source Breakpoints

Click the left gutter to add or remove a source breakpoint. The Debug panel and `:debug breakpoint` command can change a selected breakpoint's enabled state, condition, hit condition, or log message. Shed stores workspace-scoped breakpoint JSON beneath the configured `session.dir` in `breakpoints/`; it writes no source file or project metadata. The store records requested lines, enabled state, bounded single-line option strings, and verified/rejected/adapter-adjusted locations with atomic replacement. Existing version-one breakpoint files migrate with enabled ordinary breakpoints.

Shed sends one DAP `setBreakpoints` request per source only when `debug.breakpoints.enabled=true` and the selected adapter declares `breakpoints`. Disabled breakpoints are retained locally but omitted from that source request. Conditions, hit conditions, and log messages require both the corresponding local declaration and the matching `initialize` capability (`supportsConditionalBreakpoints`, `supportsHitConditionalBreakpoints`, or `supportsLogPoints`); an unsupported configured option is marked rejected with a diagnostic rather than being silently sent as an ordinary breakpoint. A `setBreakpoints` response replaces the displayed state for requested locations: rejected locations use an outlined red gutter marker, disabled locations use an outlined marker, adjusted locations use yellow, and details remain in `:debug status`. The request contains the complete enabled supported set for the source, not an incremental delta. If an adapter does not emit `initialized`, Shed retains the breakpoint state and performs the compatible post-start synchronization with a retained diagnostic.

## Exception Breakpoints

When the selected adapter declares `exception_breakpoints` and returns `exceptionBreakpointFilters` during DAP initialization, Shed sends `setExceptionBreakpoints` before `configurationDone`. Adapter defaults apply unless the user explicitly changes a filter with `:debug exception enable <filter>`, `:debug exception disable <filter>`, or the Debug panel's **Exception Breakpoints** control; only those workspace-scoped overrides are persisted beneath `session.dir/breakpoints/`. `:debug exception list` and the panel expose the adapter-provided filter IDs and labels after a compatible session starts. Unknown or unavailable filters are not sent. Shed supports only the DAP filter enable/disable state here, not adapter-specific exception conditions or options.

This is intentionally limited to enable/disable filter selection. Shed does not expose exception-filter conditions, exception options, function breakpoints, or data breakpoints.

## Paused-frame Inspection

On a DAP `stopped` event, use the Debug panel or `:debug stack` / `:debug variables` to inspect state; loading, unavailable capability, and error states remain visible. `:debug frame <id>` selects a returned frame before reloading its scopes and variables. The panel's **Open Source** action opens a selected frame only when its adapter-provided source path names an existing local regular file; it records a jump and positions the caret from DAP's one-based line and column. `:debug watch add <expression>`, `:debug watch remove <expression>`, `:debug watch list`, and `:debug watch clear` manage session-local watches; evaluation uses DAP `evaluate` with `context: watch` for the selected paused frame.

Shed sends `threads`, `stackTrace`, `scopes`, `variables`, and `evaluate` only when their corresponding configured adapter capabilities and feature settings are enabled. A later `continued`, `terminated`, or `exited` event invalidates paused-frame references and leaves watches pending until the next stop; stale responses from a prior suspended state are discarded.

## Debug Console

DAP `output` events are retained in received order as categorized `stdout`, `stderr`, or `console` text. They never open or focus a buffer. Use `:debug console` to inspect the explicit `[debug console]` scratch view and `:debug console clear` to discard retained output. The recovery buffer retains only its most recent 64 KiB and labels truncation. `terminated`, `exited`, transport failure, and explicit stop update the visible console connection state without deleting retained output.

## Integration Fixture

`ReferenceDebugAdapter` is an in-process framed DAP fixture used by automated tests. It exercises initialize, delayed launch completion, `initialized`, full-source breakpoints, `configurationDone`, stopped-frame inspection, watch evaluation, disconnect, timeout cancellation, malformed adapter output, and process cleanup without relying on a platform-specific debugger executable.
