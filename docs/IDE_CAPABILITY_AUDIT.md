# IDE capability audit

This is a repository audit, not marketing copy. It records the checked-in local editor capabilities and their boundaries. Shed intentionally does not provide a plugin ecosystem, custom-editor API, marketplace, or remote extension host.

## Product position

Shed is a local Java desktop editor with IDE-oriented tooling: LSP/DAP bridges, source control, tasks, tests, Docker/Dev Container commands, and explicit remote mirrors. It is not feature parity with VS Code, Zed, or a remote-server IDE.

## Capability matrix

| Area | Shed now | Boundary |
| --- | --- | --- |
| Core editing | Buffers, splits, file tree, recovery, search/replace, syntax colouring, and large-file mode. | Swing desktop UI; no web client. |
| Languages | Built-in lexical file types, snippets, local outlines, user-configured LSP, semantic tokens, and selected managed language installs. | No third-party grammar or language contribution API. |
| Debugging | Explicit DAP sessions, breakpoints, stack/variables, built-in starter profiles, and bounded `launch.json` compatibility. | Adapters must already be installed/configured. |
| Tasks and tests | Validated local tasks, common test adapters, explicit remote/Dev Container execution, and bounded VS Code task import. | No task provider or automatic remote worker. |
| Source control | Git workbench, GitHub review consent flow, and built-in Mercurial/Subversion actions. | No SCM provider framework. |
| Docker | Explicit local Docker list, inspect, start/stop/restart, bounded logs, exec, PTY terminal, and local-mirror open. | Docker CLI/daemon and lifecycle remain user-owned; no image/registry management UI. |
| Dev Containers | Explicit `devcontainer` build, up, run-user-commands, stop, down, connect, exec, terminal, and task routing. | Requires an installed local CLI and project `devcontainer.json`; no implicit rebuild. |
| Remote workspaces | Git/SSH/Docker/WSL local mirrors; explicit pull/push/exec/terminal/use, SSH bootstrap, reconnect, loopback forwards, and optional session-only automatic pull. | No remote extension host, host agent install, automatic push/conflict resolution, persisted session, or background remote execution service. |
| Terminal and notebooks | Local PTY terminals and explicit local Jupyter notebook/kernel workflows. | No terminal profile plugins or remote kernel platform. |

## Honest product language

Use: “Shed is a local Java desktop editor with LSP/DAP foundations, source-control and task tooling, and explicit Docker, Dev Container, and remote workspace bridges.”

Avoid claims of marketplace compatibility, extension-host support, full remote-development parity, automatic synchronization, or full Docker management.

For implementation details and limits, see [Docker](DOCKER.md), [Remote Workspaces](REMOTE_WORKSPACES.md), [Terminal](TERMINAL.md), [Testing](TESTS.md), [DAP](DAP.md), and [Notebooks](NOTEBOOKS.md).
