# Debug Adapter Protocol Architecture

Shed's debug architecture is adapter-capability driven. The Debug Adapter Protocol separates the editor from language-specific debuggers; this phase defines configuration and validation only. It does not launch a process, open a socket, send DAP messages, collect telemetry, or download adapters.

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

A later debug session implementation must only receive a validated `Plan`: selected adapter, declared capabilities, configuration, workspace root, resolved working directory, and resolved program. It must perform DAP initialization before later adapter-specific launch or attach requests and honor the capabilities returned by the adapter. Adapter-specific request arguments are intentionally deferred because DAP leaves them adapter-defined.
