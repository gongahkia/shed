# Debug Adapter Protocol Architecture

Shed's debug architecture is adapter-capability driven. The Debug Adapter Protocol separates the editor from language-specific debuggers. Configuration is validated before a transport can start; the transport does not select configurations, launch a debuggee, collect telemetry, or download adapters.

## Safe Defaults

- `debug.enabled = false`; selecting a profile or opening the Debug panel does not start a process.
- Shed includes one narrow profile, `python-debugpy`, for the separately user-installed `debugpy-adapter` executable. Shed neither bundles, downloads, nor probes `debugpy`; the profile is unavailable until that executable is on `PATH`.
- Other adapters are user-managed and configured in global `~/.shed/config.toml` by default.
- Project `.shed.toml` debug settings, adapters, and configuration declarations are unsafe and remain blocked until `project.config.allow.unsafe = true` and the project config is trusted. When enabled, Shed resolves the file at the selected workspace root, merges it with global declarations for that root only, captures its feature flags for the session, and never mutates the active editor configuration to do so.
- A workspace `.vscode/launch.json` is a read-only, runtime-only compatibility input. It can add a profile only after the user explicitly starts or selects it, and only when its `type` maps to an already configured Shed adapter. It never contributes an adapter command, installs software, changes `.shed.toml`, changes global configuration, or bypasses the project-config trust gate for adapter declarations.
- Adapter commands are direct executable tokens plus whitespace-separated arguments; Shed does not invoke a shell.
- Configuration scope is always `workspace`; `cwd` and a `program` launch target must remain under `${workspaceFolder}`, except `${file}` for the active workspace file. A launch may instead use one validated dotted `module` name or bounded inline `code`; neither accepts path placeholders.
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
"debug.open.source.on.stop" = true

"debug.adapter.java.command" = "java-debug-adapter"
"debug.adapter.java.args" = "--stdio"
"debug.adapter.java.transport" = "stdio"
"debug.adapter.java.capabilities" = "launch,attach,configuration_done,breakpoints,exception_breakpoints,conditional_breakpoints,hit_conditional_breakpoints,log_points,threads,stack_trace,scopes,variables,evaluate,continue,next,step_in,step_out,pause,goto"

"debug.configuration.main.adapter" = "java"
"debug.configuration.main.request" = "launch"
"debug.configuration.main.scope" = "workspace"
"debug.configuration.main.program" = "${file}"
"debug.configuration.main.cwd" = "${workspaceFolder}"
"debug.configuration.main.args" = ""
"debug.configuration.main.prelaunch_task" = "build"

# Choose exactly one launch target. These replace `program` when used.
# "debug.configuration.main.module" = "package.main"
# "debug.configuration.main.code" = "print('hello from Shed')"
```

For an explicit test-debug target, map an adapter in workspace `.shedtests` to one of these global or permitted root-local configurations with `debug_configuration = "main"`. During `:test debug <test-id>` or **Debug Selection**, the selected Tests root determines its DAP declaration set, and `${testId}` and `${testFile}` are available in `debug.configuration.<name>.args`; unknown placeholders and test files outside the selected workspace reject the launch before an adapter process starts.

Adapter identifiers and configuration names are `[A-Za-z0-9_-]+`. `transport` is `stdio` (default) or `tcp`; `stdio` requires `command`, while `tcp` must not set one. Capabilities are comma-separated: `launch`, `attach`, `configuration_done`, `breakpoints`, `exception_breakpoints`, `conditional_breakpoints`, `hit_conditional_breakpoints`, `log_points`, `threads`, `stack_trace`, `scopes`, `variables`, `evaluate`, `continue`, `next`, `step_in`, `step_out`, `pause`, and `goto`.

Each configuration requires `adapter` and `request` (`launch` or `attach`). A launch requires exactly one target: workspace-scoped `program`, a dotted identifier `module`, or `code` up to 64 KiB without NUL. `module` and `code` are passed as DAP values rather than shell arguments; they cannot contain path placeholders. An attach requires a loopback `host` and `port` from `1..65535`; a legacy `program` value is accepted but is not sent to an attach request, while `module` and `code` are rejected. An optional `file_extensions` value is a comma-separated allowlist such as `.py,.pyw`; it is valid only for `program` and rejects a launch whose resolved program has another extension. An optional `prelaunch_task` is a task identifier from the selected workspace’s `.shedtasks`. For an explicit debug start, Shed validates and runs that task in the local workspace or through the selected remote/Dev Container bridge before opening the adapter; a missing, invalid, cancelled, timed-out, or non-zero task stops the session before any adapter process starts. Invalid fields are reported with TOML line and column during config loading; Shed retains safe defaults and does not create a launch plan.

`python-debugpy` launches the current `.py` or `.pyw` file with the upstream `debugpy-adapter` DAP executable. Its request carries only `program`, `cwd`, and `args`, which debugpy accepts for program launch. This is not a bundled Python runtime, environment manager, or debugger marketplace.

## VS Code launch.json compatibility

When the selected workspace contains a regular, non-symlink `.vscode/launch.json` no larger than 1 MiB, Shed reads it as bounded JSONC (comments and trailing commas are accepted). `:debug configurations` includes accepted entries and `:debug vscode` opens the complete accepted/skipped report. The import is recomputed in memory, has a 100-profile and 64-nesting-depth limit, and is never written back. Imported names are `vscode:<name>`; select one and start it explicitly as with native Shed configurations.

An entry must use only `name`, `type`, `request`, `program`, `module`, `code`, `cwd`, `args`, `preLaunchTask`, `host`, and `port`. Every supported field maps directly to Shed's existing plan schema; an entry with any other field is skipped rather than partly translated. `python` and `debugpy` map to the already present `python-debugpy` adapter. Any other type must exactly match an already configured Shed adapter id (case-insensitive). The selected adapter must advertise the requested launch or attach capability.

The same workspace containment, active-file, loopback-attach, argument-size, launch-target, and Shed task-identifier checks apply before a profile is admitted. In particular, no `env`, `console`, `presentation`, `compound`, adapter-specific options, arbitrary VS Code variables, remote debug host, or VS Code task-label translation is supported. A `preLaunchTask` is retained only as an exact Shed task identifier and must succeed before the adapter starts. This is a safe compatibility bridge for an exact subset, not `launch.json` parity.

## Future Session Boundary

A debug session may only give the transport a validated `Plan`: selected adapter, locally declared capabilities, configuration, workspace root, resolved working directory, and exactly one resolved launch target (`program`, `module`, or `code`) when launching. It performs DAP initialization before later adapter-specific launch or attach requests. `configurationDone` and rich source-breakpoint fields require both Shed's local adapter declaration and the matching capability advertised in the adapter's initialize response; a mismatch rejects the rich option with a diagnostic. Other adapter-specific request arguments remain deferred because DAP leaves them adapter-defined.

## Transport Boundary

`DebugAdapterTransport` implements the DAP base framing: ASCII `Content-Length` headers and UTF-8 JSON objects. It accepts only bounded frames (16 KiB headers and 8 MiB bodies), validates JSON syntax before dispatch, and treats malformed or truncated adapter output as an isolated session failure. A failure completes outstanding requests, closes streams/sockets, stops a stdio adapter process, and reports a local diagnostic; it does not crash the editor.

- It requires `debug.enabled`; attach plans also require `debug.attach.enabled` before opening a process or socket.
- `stdio` starts the configured direct executable in the validated workspace `cwd`. Adapter stderr is drained separately, never mixed into the DAP stdout stream.
- `tcp` connects only to the validated loopback host and port. It never starts a local adapter process.
- Request timeouts issue DAP `cancel` only after the adapter declares `supportsCancelRequest`; otherwise Shed ignores a late response.
- Closing a transport sends `disconnect` when possible, setting `terminateDebuggee` for launch sessions only, then closes the connection and terminates a stdio adapter if it remains alive.
- Adapter-initiated requests receive a deterministic unsupported response until a later feature supplies a handler. Events and responses are delivered separately.

The transport has no UI dependency and records to `DiagnosticLog` only when its caller supplies one; it performs no telemetry or network access other than an explicitly configured loopback TCP connection.

## Explicit remote stdio adapters

An explicit debug start in a connected SSH, Docker, or WSL workspace, or an already-running local Dev Container, can run an already-installed stdio DAP adapter through that bridge. Shed translates launch paths, breakpoints, Run to Cursor, and stack-frame source paths only between the declared remote root and the local mirror or mounted workspace; a configured pre-launch task executes through that same target. The editor remains the local DAP client.

TCP DAP remains local-loopback-only. The bridge does not install adapters, start or rebuild a Dev Container, forward ports, create a remote host service, automatically synchronize files, or run extensions remotely. SSH stdout must be a clean DAP byte stream; a login banner fails the session. Extension providers opt in only by implementing `debugAdapterCommand` and `debugAdapterRoot`; the Dev Container bridge first verifies its mounted workspace path with the user-installed CLI.

## Adapter Detection

`DebugAdapterDetector` reports configured adapter and configuration availability for one workspace without starting an adapter, opening a socket, or modifying workspace state. Missing executables and invalid debug settings are remediation states only; `normalEditingAvailable` remains true.

Adapter versions are `NOT_PROBED`: the published DAP schema has no adapter-discovery or version request, and Shed does not invoke a configured adapter with guessed arguments during read-only detection. The report exposes each declared capability as `AVAILABLE`, `DISABLED` by the corresponding debug setting, or `UNDECLARED`, plus launch/attach configuration availability. Resolution is skipped while `debug.enabled=false`.

For a connected SSH, Docker, or WSL workspace, or a workspace with a Dev Container configuration, a configured stdio adapter that Shed can bridge is shown as available with executable `remote`. This validates only that Shed can construct the local bridge command; it does not invoke the Dev Container CLI, probe the remote adapter binary, or establish a debug session. An explicit start is still required to validate the remote runtime.

## Explicit Session Lifecycle

Use `:debug` to open the docked Debug panel, `:debug configurations` to inspect configured adapters and runtime-compatible VS Code profiles, or `:debug vscode` for the compatibility report without starting one. The panel and `:debug select <name>` choose a configuration, while `:debug start [name]` explicitly begins a session. If no configuration is selected, an explicit start of the active `.py` or `.pyw` file chooses the built-in `python-debugpy` profile; no other language is inferred and opening a file never starts debugging. The editor starts the validated adapter, sends DAP `initialize`, then sends the configured `launch` or `attach` request. When the adapter emits `initialized`, Shed sends configuration requests before `configurationDone` only when both the declared adapter capability and the DAP initialize response support it. `:debug stop`, `:debug restart [name]`, and `:debug status` provide visible lifecycle state and retained diagnostics; prefix a command with `:debug text` for legacy scratch output.

Shed never starts a debug adapter while inspecting configurations or selecting one. A rejected configuration, adapter start error, timeout, or failed DAP response leaves the session `FAILED` with diagnostics visible in `[debug status]`; normal editing remains available. Generic launch arguments are exactly one of `program`, `module`, or `code`, plus `cwd` and `args`; attach arguments are only `host`, `port`, `cwd`, and `args`, so adapters that require additional adapter-specific settings fail visibly rather than receiving inferred values. Test debugging never guesses a framework or adapter; it only starts the explicit mapping in `.shedtests`.

## Execution controls

`:debug continue`, `:debug next`, `:debug stepin`, and `:debug stepout` work only while the adapter has reported a paused thread and has declared the corresponding capability. `:debug pause` requires declared `pause` and `threads` capabilities; Shed asks the adapter for its current thread list instead of assuming a thread ID. `:debug goto [line]` and **Run to Cursor** send standard DAP `gotoTargets` then `goto` for the active local workspace file and caret (or the supplied positive one-based line). They require a paused thread, locally declared `goto`, and `supportsGotoTargetsRequest=true` in the adapter's initialize response. Shed only uses an unambiguous returned target; it does not choose among ambiguous adapter locations. The Debug panel exposes the same controls. A request is explicit, asynchronous, and diagnostic on failure. Shed does not claim support for reverse execution, data breakpoints, function breakpoints, disassembly, or adapter-specific debug console input.

## Source Breakpoints

Click the left gutter to add or remove a source breakpoint. The Debug panel and `:debug breakpoint` command can change a selected breakpoint's enabled state, condition, hit condition, or log message. Shed stores workspace-scoped breakpoint JSON beneath the configured `session.dir` in `breakpoints/`; it writes no source file or project metadata. The store records requested lines, enabled state, bounded single-line option strings, and verified/rejected/adapter-adjusted locations with atomic replacement. Existing version-one breakpoint files migrate with enabled ordinary breakpoints.

Shed sends one DAP `setBreakpoints` request per source only when `debug.breakpoints.enabled=true` and the selected adapter declares `breakpoints`. Disabled breakpoints are retained locally but omitted from that source request. Conditions, hit conditions, and log messages require both the corresponding local declaration and the matching `initialize` capability (`supportsConditionalBreakpoints`, `supportsHitConditionalBreakpoints`, or `supportsLogPoints`); an unsupported configured option is marked rejected with a diagnostic rather than being silently sent as an ordinary breakpoint. A `setBreakpoints` response replaces the displayed state for requested locations: rejected locations use an outlined red gutter marker, disabled locations use an outlined marker, adjusted locations use yellow, and details remain in `:debug status`. The request contains the complete enabled supported set for the source, not an incremental delta. If an adapter does not emit `initialized`, Shed retains the breakpoint state and performs the compatible post-start synchronization with a retained diagnostic.

## Exception Breakpoints

When the selected adapter declares `exception_breakpoints` and returns `exceptionBreakpointFilters` during DAP initialization, Shed sends `setExceptionBreakpoints` before `configurationDone`. Adapter defaults apply unless the user explicitly changes a filter with `:debug exception enable <filter>`, `:debug exception disable <filter>`, or the Debug panel's **Exception Breakpoints** control; only those workspace-scoped overrides are persisted beneath `session.dir/breakpoints/`. `:debug exception list` and the panel expose the adapter-provided filter IDs and labels after a compatible session starts. Unknown or unavailable filters are not sent. Shed supports only the DAP filter enable/disable state here, not adapter-specific exception conditions or options.

This is intentionally limited to enable/disable filter selection. Shed does not expose exception-filter conditions, exception options, function breakpoints, or data breakpoints.

## Paused-frame Inspection

On a DAP `stopped` event, Shed requests paused-frame inspection. With `debug.open.source.on.stop=true` (the default), it opens the adapter-selected frame only when its source maps to an existing local regular file; remote paths must be under the connected mirror's declared root. It records a jump and positions the caret from DAP's one-based line and column. Disable that setting to keep navigation manual. The Debug panel's **Open Source** action remains available, and `:debug frame <id>` selects a returned frame before reloading its scopes and variables. Loading, unavailable-capability, and error states remain visible. `:debug watch add <expression>`, `:debug watch remove <expression>`, `:debug watch list`, and `:debug watch clear` manage session-local watches; evaluation uses DAP `evaluate` with `context: watch` for the selected paused frame.

Shed sends `threads`, `stackTrace`, `scopes`, `variables`, and `evaluate` only when their corresponding configured adapter capabilities and feature settings are enabled. A later `continued`, `terminated`, or `exited` event invalidates paused-frame references and leaves watches pending until the next stop; stale responses from a prior suspended state are discarded.

## Debug Console

DAP `output` events are retained in received order as categorized `stdout`, `stderr`, or `console` text. They never open or focus a buffer. Use `:debug console` to inspect the explicit `[debug console]` scratch view and `:debug console clear` to discard retained output. The recovery buffer retains only its most recent 64 KiB and labels truncation. `terminated`, `exited`, transport failure, and explicit stop update the visible console connection state without deleting retained output.

## Integration Fixture

`ReferenceDebugAdapter` is an in-process framed DAP fixture used by automated tests. It exercises initialize, delayed launch completion, `initialized`, full-source breakpoints, `configurationDone`, stopped-frame inspection, watch evaluation, disconnect, timeout cancellation, malformed adapter output, and process cleanup without relying on a platform-specific debugger executable.
