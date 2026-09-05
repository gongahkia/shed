# Debug Adapter Protocol Architecture

Shed's debug architecture is adapter-capability driven. The Debug Adapter Protocol separates the editor from language-specific debuggers. Configuration is validated before a transport can start; the transport does not select configurations, launch a debuggee, collect telemetry, or download adapters.

## Safe Defaults

- `debug.enabled = false`; selecting a profile or opening the Debug panel does not start a process.
- Shed includes two narrow local profiles: `python-debugpy` for the separately user-installed `debugpy-adapter` executable, and `go-delve` for the separately user-installed `dlv` executable. When Shed supplies `python-debugpy`, it selects a regular executable local `.venv/bin/debugpy-adapter` or `.venv/Scripts/debugpy-adapter.exe` before falling back to `PATH`; an explicit `debug.adapter.python-debugpy` configuration wins. It also recognizes the separately user-installed `netcoredbg --interpreter=vscode` adapter for explicit C#/.NET configuration or compatible `coreclr`/`netcoredbg` VS Code launch import, plus `lldb-dap` and `gdb --interpreter=dap` for explicit compiled native launch. Shed neither bundles, downloads, nor probes these debuggers.
- For an ELF, Mach-O, or PE regular executable directly in local `build/`, `target/debug/`, or `target/release/`, Shed adds session-only `suggested-native-gdb-*` and `suggested-native-lldb-*` launch configurations. Discovery excludes conventional library/object names, does not recurse, follow symbolic links, alter a configuration file, or start a process; the exact target is shown by `:debug configurations`, and starting it remains explicit.
- For a root C# project, Shed adds a session-only `suggested-csharp-netcoredbg-*` launch configuration only when its project-named DLL and matching `.runtimeconfig.json` exist in `bin/Debug[/<framework>]/` or `bin/Release[/<framework>]/`. Discovery does not infer a custom assembly name or output path, recurse into project subdirectories, follow symbolic links, alter configuration, or start a process.
- Other adapters are user-managed and configured in global `~/.shed/config.toml` by default.
- Project `.shed.toml` debug settings, adapters, and configuration declarations are unsafe and remain blocked until `project.config.allow.unsafe = true` and the project config is trusted. When enabled, Shed resolves the file at the selected workspace root, merges it with global declarations for that root only, captures its feature flags for the session, and never mutates the active editor configuration to do so.
- A workspace `.vscode/launch.json`, or the `launch` object of an explicitly imported standard `.code-workspace` whose folders still match, is a read-only, runtime-only compatibility input. It can add a profile only after the user explicitly starts or selects it, and only when its `type` maps to an already configured Shed adapter. It never contributes an adapter command, installs software, changes `.shed.toml`, changes global configuration, or bypasses the project-config trust gate for adapter declarations.
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
"debug.adapter.java.capabilities" = "launch,attach,configuration_done,breakpoints,function_breakpoints,exception_breakpoints,conditional_breakpoints,hit_conditional_breakpoints,log_points,threads,stack_trace,scopes,variables,set_variable,evaluate,continue,next,step_in,step_out,pause,goto"

"debug.configuration.main.adapter" = "java"
"debug.configuration.main.request" = "launch"
"debug.configuration.main.scope" = "workspace"
"debug.configuration.main.program" = "${file}"
"debug.configuration.main.cwd" = "${workspaceFolder}"
"debug.configuration.main.args" = ""
"debug.configuration.main.prelaunch_task" = "build"

# Literal adapter-defined DAP launch/attach fields. Core fields above cannot appear here.
"debug.configuration.main.adapter_options" = '{"type":"pwa-node","sourceMaps":true}'

# Choose exactly one launch target. These replace `program` when used.
# "debug.configuration.main.module" = "package.main"
# "debug.configuration.main.code" = "print('hello from Shed')"
```

For an explicit test-debug target, map an adapter in workspace `.shedtests` to one of these global or permitted root-local configurations with `debug_configuration = "main"`. During `:test debug <test-id>` or **Debug Selection**, the selected Tests root determines its DAP declaration set, and `${testId}` and `${testFile}` are available in `debug.configuration.<name>.args`; unknown placeholders and test files outside the selected workspace reject the launch before an adapter process starts.

Adapter identifiers and configuration names are `[A-Za-z0-9_-]+`. `transport` is `stdio` (default) or `tcp`; `stdio` requires `command`, while `tcp` must not set one. Capabilities are comma-separated: `launch`, `attach`, `configuration_done`, `breakpoints`, `function_breakpoints`, `data_breakpoints`, `instruction_breakpoints`, `exception_breakpoints`, `conditional_breakpoints`, `hit_conditional_breakpoints`, `log_points`, `threads`, `stack_trace`, `scopes`, `variables`, `set_variable`, `evaluate`, `continue`, `next`, `step_in`, `step_out`, `pause`, `goto`, `reverse_continue`, `step_back`, `restart_frame`, `exception_details`, `modules`, `loaded_sources`, `read_memory`, and `disassemble`.

Each configuration requires `adapter` and `request` (`launch` or `attach`). A launch requires exactly one target: workspace-scoped `program`, a dotted identifier `module`, or `code` up to 64 KiB without NUL. `program` may use `${file}` or, for explicit Test Explorer debug selection, `${testFile}`. `module` and `code` are passed as DAP values rather than shell arguments; they cannot contain path placeholders. An attach requires a loopback `host` and `port` from `1..65535`; a legacy `program` value is accepted but is not sent to an attach request, while `module` and `code` are rejected. An optional `file_extensions` value is a comma-separated allowlist such as `.py,.pyw`; it is valid only for `program` and rejects a launch whose resolved program has another extension. An optional `prelaunch_task` normally names an identifier from the selected workspace’s `.shedtasks`; an imported VS Code launch profile can instead map its task label to an accepted session-local process or POSIX-shell task. For an explicit debug start, Shed validates and runs the resolved task in the local workspace or through the selected remote/Dev Container bridge before opening the adapter; a missing, invalid, cancelled, timed-out, or non-zero task stops the session before any adapter process starts. A local background pre-launch task is accepted only with a literal `ready_when` marker; Shed waits for that marker within the normal process timeout, then leaves the watcher as a separately cancellable task job. Remote and Dev Container background pre-launch tasks remain unsupported. Invalid fields are reported with TOML line and column during config loading; Shed retains safe defaults and does not create a launch plan.

`python-debugpy` launches the current `.py` or `.pyw` file with the upstream `debugpy-adapter` DAP executable. For Shed's default adapter it uses a regular, non-symlinked launcher at project `.venv/bin/debugpy-adapter` or `.venv/Scripts/debugpy-adapter.exe` when executable, then falls back to `PATH`; a user-defined adapter is never replaced. Its request carries only `program`, `cwd`, and `args`, which debugpy accepts for program launch. `go-delve` starts the user-installed `dlv dap --listen=127.0.0.1:0` process in the selected workspace, accepts only its announced `127.0.0.1` endpoint, and sends `mode: "debug"` with the current `.go` program, `cwd`, and `args`. The Delve process is local and single-session; it is stopped when the DAP transport closes. `csharp-netcoredbg` runs the user-installed `netcoredbg --interpreter=vscode` over stdio but deliberately has no automatic `.cs` profile: its `program` must be an explicit compiled `.dll`/executable, such as one supplied by an accepted `coreclr` launch configuration. `native-lldb` runs user-installed `lldb-dap` over stdio with an explicit compiled executable; it has no source-file auto-profile and does not translate CodeLLDB or `cppdbg` settings. `native-gdb` runs user-installed `gdb --interpreter=dap` over stdio with an explicit compiled executable; its DAP interpreter requires a GDB build with Python support, it has no source-file auto-profile, and it does not translate `cppdbg` MI settings. None is a bundled runtime, environment manager, remote debugger, or debugger marketplace.

## VS Code launch compatibility

When the selected workspace contains a regular, non-symlink `.vscode/launch.json`, or an explicitly imported standard `.code-workspace` still declares the current folder set and contains `launch`, Shed reads the source as bounded JSONC (comments and trailing commas are accepted). `:debug configurations` includes accepted entries and `:debug vscode` opens the complete accepted/skipped report for both sources. The import is recomputed in memory, has a 100-profile and 64-nesting-depth limit per source, and is never written back. Imported names are `vscode:<name>`; select one and start it explicitly as with native Shed configurations.

Shed validates `name`, `type`, `request`, `program`, `module`, `code`, `cwd`, `args`, `env`, `preLaunchTask`, `host`, and `port` as core fields. Every remaining field is retained as a bounded typed adapter option only when its JSON value is safe (at most 16 nested levels, 100 values per collection, and 64 KiB in total) and its property name cannot replace a core field. Shed forwards those options unchanged to the selected already-configured adapter, including `type`; it neither interprets nor expands them. `python` and `debugpy` map to `python-debugpy`; `go` and `delve` map to `go-delve`; `coreclr` and `netcoredbg` map to `csharp-netcoredbg`; `lldb-dap` maps to `native-lldb`. Any other type must exactly match an already configured Shed adapter id (case-insensitive). The selected adapter must advertise the requested launch or attach capability.

The same workspace containment, active-file, loopback-attach, argument-size, and launch-target checks apply before a profile is admitted. A launch-only `env` object may contain at most 100 portable environment names and single-line string values, up to 64 KiB total; Shed passes that exact map to the DAP adapter without changing Shed's own environment. `null` unsets, `${...}` values, non-portable names, consoles, compounds, arbitrary VS Code variables, and remote debug hosts remain unsupported. Adapter options are literal JSON values, so `${...}` inside one is not expanded. Launch arguments may use bounded active-workspace/file metadata (`workspaceFolder`, `workspaceFolderBasename`, `file`, `fileWorkspaceFolder`, `relativeFile`, `relativeFileDirname`, `fileBasename`, `fileBasenameNoExtension`, `fileExtname`, `fileDirname`, and `fileDirnameBasename`). `${testFile}` and `${testId}` are accepted only for explicit Test Explorer debug selection; a normal `:debug start` rejects their missing context. A `preLaunchTask` is retained as an exact Shed task identifier, or is translated only when its exact VS Code label resolves to one accepted session-local `process` or POSIX-shell task across the compatible folder and imported-workspace sources. Its validated sequential prerequisites run first; duplicate labels, provider tasks, parallel/object-form dependencies, or otherwise unsupported tasks do not resolve. Every stage must succeed before the adapter starts. This is a safe compatibility bridge, not full `launch.json` parity.

## Future Session Boundary

A debug session may only give the transport a validated `Plan`: selected adapter, locally declared capabilities, configuration, workspace root, resolved working directory, and exactly one resolved launch target (`program`, `module`, or `code`) when launching. It performs DAP initialization before later adapter-specific launch or attach requests. Core arguments are supplied by Shed; a validated `adapter_options` JSON object may add typed literal fields but cannot replace any core argument. `configurationDone`, function breakpoints, and rich source-breakpoint fields require both Shed's local adapter declaration and the matching capability advertised in the adapter's initialize response; a mismatch rejects the rich option with a diagnostic.

## Transport Boundary

`DebugAdapterTransport` implements the DAP base framing: ASCII `Content-Length` headers and UTF-8 JSON objects. It accepts only bounded frames (16 KiB headers and 8 MiB bodies), validates JSON syntax before dispatch, and treats malformed or truncated adapter output as an isolated session failure. A failure completes outstanding requests, closes streams/sockets, stops a stdio adapter process, and reports a local diagnostic; it does not crash the editor.

- It requires `debug.enabled`; attach plans also require `debug.attach.enabled` before opening a process or socket.
- `stdio` starts the configured direct executable in the validated workspace `cwd`. Adapter stderr is drained separately, never mixed into the DAP stdout stream.
- User-configured `tcp` adapters connect only to the validated loopback host and port; they never start a local adapter process. The hard-coded `go-delve` profile is the sole exception: it starts `dlv dap` with a kernel-selected loopback port, reads its bounded readiness line, and rejects any non-`127.0.0.1` endpoint before connecting.
- Request timeouts issue DAP `cancel` only after the adapter declares `supportsCancelRequest`; otherwise Shed ignores a late response.
- Closing a transport sends `disconnect` when possible, setting `terminateDebuggee` for launch sessions only, then closes the connection and terminates any Shed-started stdio or loopback-TCP adapter if it remains alive.
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

Use `:debug` to open the docked Debug panel, `:debug configurations` to inspect configured adapters and runtime-compatible VS Code profiles, or `:debug vscode` for the compatibility report without starting one. The panel and `:debug select <name>` choose a configuration, while `:debug start [name]` explicitly begins a session. If no configuration is selected, an explicit start of the active `.py`/`.pyw` file chooses `python-debugpy`, and an active `.go` file chooses `go-delve`; opening a file never starts debugging. The editor starts the validated adapter, sends DAP `initialize`, then sends the configured `launch` or `attach` request. When the adapter emits `initialized`, Shed sends configuration requests before `configurationDone` only when both the declared adapter capability and the DAP initialize response support it. `:debug stop`, `:debug restart [name]`, and `:debug status` provide visible lifecycle state and retained diagnostics; prefix a command with `:debug text` for legacy scratch output.

Shed never starts a debug adapter while inspecting configurations or selecting one. A rejected configuration, adapter start error, timeout, or failed DAP response leaves the session `FAILED` with diagnostics visible in `[debug status]`; normal editing remains available. Generic launch arguments are exactly one of `program`, `module`, or `code`, plus `cwd`, `args`, an imported bounded `env` map, and validated literal adapter options. Attach arguments are `host`, `port`, `cwd`, `args`, and the same option boundary. Test debugging gives an explicit `.shedtests` mapping precedence; otherwise, only an explicit Test Explorer debug action may infer user-installed debugpy pytest/unittest or Delve Go-test launch arguments for an unambiguous selected test ID.

## Execution controls

`:debug continue`, `:debug next`, `:debug stepin`, and `:debug stepout` work only while the adapter has reported a paused thread and has declared the corresponding capability. `:debug pause` requires declared `pause` and `threads` capabilities; Shed asks the adapter for its current thread list instead of assuming a thread ID. `:debug goto [line]` and **Run to Cursor** send standard DAP `gotoTargets` then `goto` for the active local workspace file and caret (or the supplied positive one-based line). They require a paused thread, locally declared `goto`, and `supportsGotoTargetsRequest=true` in the adapter's initialize response. Shed only uses an unambiguous returned target; it does not choose among ambiguous adapter locations. The Debug panel exposes the same controls. A request is explicit, asynchronous, and diagnostic on failure. Adapter-specific debug-console input remains unsupported.

Reverse execution is similarly explicit: `:debug reverse-continue` and `:debug stepback` require a paused thread, their matching local declaration, and `supportsReverseContinue=true` or `supportsStepBack=true` from DAP initialization. `:debug restart-frame` requires the selected paused frame, `restart_frame`, and `supportsRestartFrame=true`. The Debug panel exposes all three actions. A successful reverse or restart request invalidates suspended-state references immediately; Shed waits for the adapter's next stopped event before reloading inspection. It does not emulate reverse execution when an adapter lacks the standard DAP capability.

## Source Breakpoints

Click the left gutter to add or remove a source breakpoint. The Debug panel and `:debug breakpoint` command can change a selected breakpoint's enabled state, condition, hit condition, or log message. Shed stores workspace-scoped breakpoint JSON beneath the configured `session.dir` in `breakpoints/`; it writes no source file or project metadata. The store records requested lines, enabled state, bounded single-line option strings, and verified/rejected/adapter-adjusted locations with atomic replacement. Existing version-one breakpoint files migrate with enabled ordinary breakpoints.

Shed sends one DAP `setBreakpoints` request per source only when `debug.breakpoints.enabled=true` and the selected adapter declares `breakpoints`. Disabled breakpoints are retained locally but omitted from that source request. Conditions, hit conditions, and log messages require both the corresponding local declaration and the matching `initialize` capability (`supportsConditionalBreakpoints`, `supportsHitConditionalBreakpoints`, or `supportsLogPoints`); an unsupported configured option is marked rejected with a diagnostic rather than being silently sent as an ordinary breakpoint. A `setBreakpoints` response replaces the displayed state for requested locations: rejected locations use an outlined red gutter marker, disabled locations use an outlined marker, adjusted locations use yellow, and details remain in `:debug status`. The request contains the complete enabled supported set for the source, not an incremental delta. If an adapter does not emit `initialized`, Shed retains the breakpoint state and performs the compatible post-start synchronization with a retained diagnostic.

## Function Breakpoints

The Debug panel and `:debug function` manage named workspace-scoped function breakpoints without touching source files. Use `:debug function add <name>`, `enable`, `disable`, `remove`, `clear-condition`, or `clear-hit`; condition and hit values use `:debug function condition <name> -- <value>` and `hit <name> -- <value>` so names may contain spaces. Shed stores the enabled state, bounded single-line condition and hit-condition strings, and requested/verified/rejected adapter state atomically under `session.dir/breakpoints/`.

Shed sends a complete DAP `setFunctionBreakpoints` request, including an empty list when all entries are disabled or removed, only when `debug.breakpoints.enabled=true`, the selected adapter declares `function_breakpoints`, and its `initialize` response advertises `supportsFunctionBreakpoints=true`. Conditions and hit conditions also require the same configured-and-advertised conditional or hit-condition capability checks as source breakpoints. Unsupported options are retained but marked rejected with a diagnostic. The ordered response updates the corresponding persisted entries. Function breakpoint support is protocol-level and adapter-specific: Shed does not infer it for an adapter. The built-in Go Delve and native LLDB profiles declare it from their [upstream DAP documentation](https://github.com/go-delve/delve/blob/master/Documentation/api/dap/README.md) and [capability reference](https://lldb.llvm.org/use/lldbdap.html), but each session still requires the initialize response; Python debugpy and NetCoreDbg remain unclaimed unless a user declaration enables it.

## Exception Breakpoints

When the selected adapter declares `exception_breakpoints` and returns `exceptionBreakpointFilters` during DAP initialization, Shed sends `setExceptionBreakpoints` before `configurationDone`. Adapter defaults apply unless the user explicitly changes a filter with `:debug exception enable <filter>`, `:debug exception disable <filter>`, or the Debug panel's **Exception Breakpoints** control; only those workspace-scoped overrides are persisted beneath `session.dir/breakpoints/`. `:debug exception list` and the panel expose the adapter-provided filter IDs and labels after a compatible session starts. Unknown or unavailable filters are not sent. Shed supports only the DAP filter enable/disable state here, not adapter-specific exception conditions or options.

This is intentionally limited to enable/disable filter selection. Shed does not expose exception-filter conditions or adapter-specific exception options.

Use `:debug exception details` after an exception stop to send standard `exceptionInfo` for the paused thread. It requires `exception_details` and `supportsExceptionInfoRequest=true`; the result is read-only and appears in `[debug exception details]`. Shed does not infer an exception from arbitrary console output.

## Runtime Metadata

`:debug modules [start [count]]` sends standard `modules` with a bounded count of at most 100, and `:debug sources` sends `loadedSources`. Both need their matching local capability declaration plus `supportsModulesRequest=true` or `supportsLoadedSourcesRequest=true` from initialization. Results are shown in dedicated scratch views and are bounded to 100 entries. Remote adapter paths are mapped back only when they fall under the explicitly declared workspace mapping. These inspection commands do not load source contents, modify the debuggee, or create project files.

`:debug memory <reference> [offset [count]]` sends standard `readMemory` only when `read_memory` is declared and initialization advertises `supportsReadMemoryRequest=true`. The opaque memory reference is supplied by the adapter; Shed does not derive one from an expression or address. Requests are bounded to offsets between -1,048,576 and 1,048,576 and 1 through 4,096 bytes. The base64 response is bounded, decoded into a dedicated read-only scratch view, and may report unreadable bytes. Shed does not send `writeMemory`.

`:debug disassemble <reference> [offset [count]]` sends standard `disassemble` only when `disassemble` is declared and initialization advertises `supportsDisassembleRequest=true`. The opaque memory reference must come from the adapter; Shed does not synthesize a program counter or source address. It requests between 1 and 1,024 instructions at an offset from -1,048,576 to 1,048,576, validates bounded instruction text, and displays it in a read-only scratch view.

## Data Breakpoints

When an adapter both declares `data_breakpoints` and advertises `supportsDataBreakpoints=true` during initialization, `:debug data add <variables-reference> <name> [-- read|write|readWrite]` first asks the paused adapter for standard `dataBreakpointInfo`. Shed accepts only a bounded opaque `dataId`, description, and the adapter's standard access-type list; it never manufactures a watchpoint from an expression or guesses a memory address. If the requested access type is unavailable, it chooses `write` when offered, otherwise the adapter's first supported type. The persisted entry is then synchronized as the complete `setDataBreakpoints` set.

Use `:debug data list` to show persisted data breakpoints and their opaque IDs. The `enable`, `disable`, `remove`, `access`, `condition`, `hit`, `clear-condition`, and `clear-hit` subcommands operate on that ID. Entries are workspace-scoped, atomically stored under `session.dir/breakpoints/`, and never alter source or project files. Conditions and hit conditions require the same declared-and-advertised DAP capability checks as source breakpoints; unsupported options are retained and marked rejected instead of silently omitted. A data-breakpoint lookup or synchronization failure leaves editing and the active debug session available, with details retained in `:debug status`.

## Instruction Breakpoints

When an adapter declares `instruction_breakpoints` and returns `supportsInstructionBreakpoints=true` during initialization, `:debug instruction add <reference> [offset]` persists the supplied opaque instruction reference and synchronizes the complete enabled set with standard `setInstructionBreakpoints`. `:debug instruction list`, `enable`, `disable`, `remove`, `condition`, `hit`, `clear-condition`, and `clear-hit` manage that workspace-scoped set. References are never derived from source text, variables, expressions, or numeric addresses, and offsets are bounded between -1,048,576 and 1,048,576. Conditions and hit conditions require the corresponding declared-and-advertised DAP capabilities; unsupported entries remain persisted and show their rejection rather than being silently downgraded.

## Paused-frame Inspection

On a DAP `stopped` event, Shed requests paused-frame inspection. With `debug.open.source.on.stop=true` (the default), it opens the adapter-selected frame only when its source maps to an existing local regular file; remote paths must be under the connected mirror's declared root. It records a jump and positions the caret from DAP's one-based line and column. Disable that setting to keep navigation manual. The Debug panel's **Open Source** action remains available, and `:debug frame <id>` selects a returned frame before reloading its scopes and variables. The panel lazily expands structured variables; `:debug variables <reference>` requests one explicit nested `variables` level. If an adapter was explicitly declared with `set_variable` and advertises `supportsSetVariable`, the panel's **Set** control and `:debug set <reference> <name> -- <value>` change a displayed variable in its current paused container. Expansion and mutation are bounded to the current paused state and their results are discarded after continue, stop, or frame selection. Loading, unavailable-capability, and error states remain visible. `:debug watch add <expression>`, `:debug watch remove <expression>`, `:debug watch list`, and `:debug watch clear` manage session-local watches; evaluation uses DAP `evaluate` with `context: watch` for the selected paused frame.

Shed sends `threads`, `stackTrace`, `scopes`, `variables`, and `evaluate` only when their corresponding configured adapter capabilities and feature settings are enabled. Select a stopped thread from the Debug panel or with `:debug thread <id>` to reload that thread’s frames, scopes, and watches; it never resumes, pauses, or otherwise controls the chosen thread. A later `continued`, `terminated`, or `exited` event invalidates paused-frame references and leaves watches pending until the next stop; stale responses from a prior suspended state are discarded.

## Debug Console

DAP `output` events are retained in received order as categorized `stdout`, `stderr`, or `console` text. They never open or focus a buffer. Use `:debug console` to inspect the explicit `[debug console]` scratch view and `:debug console clear` to discard retained output. `:debug eval <expression>` and the Debug panel's **Evaluate** field send standard DAP `evaluate` with `context: repl` for the selected paused frame; they do not run a shell command. The recovery buffer retains only its most recent 64 KiB and labels truncation. `terminated`, `exited`, transport failure, and explicit stop update the visible console connection state without deleting retained output.

## Integration Fixture

`ReferenceDebugAdapter` is an in-process framed DAP fixture used by automated tests. `ReferenceTcpDebugAdapter` is a child JVM fixture for the spawned-loopback transport. Together they exercise initialize, delayed launch completion, `initialized`, full-source and function breakpoints, `configurationDone`, stopped-frame inspection, nested variables, gated variable mutation, watch and REPL evaluation, disconnect, timeout cancellation, malformed adapter output, spawned TCP readiness, and process cleanup without relying on a platform-specific debugger executable.
