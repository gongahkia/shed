# IDE capability audit

This is a repository audit, not marketing copy. It compares the checked-in implementation with current official VS Code and Zed documentation as consulted on 2026-09-04. It does not count a protocol client, an extension interface, or a command wrapper as turnkey feature parity.

## Bottom line

Shed is a local, extensible code editor/workbench with substantial IDE capabilities. It is reasonable to describe it as an **IDE workbench** if the claim names its local and extension-driven nature. It is not accurate to describe it as feature-parity with VS Code or Zed, nor as a fully featured remote IDE.

VS Code's extension platform covers declarative language metadata, programmatic language features, debugging, SCM, workbench UI, configuration, keybindings, themes, publishing, and multiple extension hosts. [VS Code Extension API](https://code.visualstudio.com/api), [capability overview](https://code.visualstudio.com/api/extension-capabilities/overview), and [extension hosts](https://code.visualstudio.com/api/advanced-topics/extension-host). Zed language extensions require Tree-sitter grammar metadata and queries, and may add language servers; it also has an extension gallery. [Zed language extensions](https://zed.dev/docs/extensions/languages), [Zed extensions](https://zed.dev/docs/extensions), and [extension installation](https://zed.dev/docs/extensions/installing-extensions).

## Capability matrix

| Area | Shed now | VS Code / Zed comparison | Assessment |
| --- | --- | --- | --- |
| Core editing | Swing editor, split panes, buffers, undo history, search/replace, file tree, recovery, syntax coloring, and large-file mode | Mature code editors with deeper polish, accessibility, UI customization, and ecosystem integration | Solid local editor foundation |
| Languages | Ten managed language-service catalog entries; built-in lexical support; direct-argv LSP; extension language profiles for extensions/file names/literal first lines, keywords, comments, strings, indentation preferences, and explicit buffer-local lexical selection | VS Code supports declarative grammars, injections, snippets and rich language APIs. Zed uses Tree-sitter grammar/query assets for highlighting, structure, indentation, injections, runnable-code detection, and more. [VS Code capabilities](https://code.visualstudio.com/api/extension-capabilities/overview), [Zed languages](https://zed.dev/docs/extensions/languages) | Useful, but partial language platform |
| Debugging | DAP client with breakpoints, stepping, stack/variables/watches; one Python-only profile for a separately installed `debugpy-adapter`; manual configuration and extensions for everything else | Zed supplies adapters for several languages and contextual debug tasks; VS Code has a broad debugger-extension model. [Zed debugger](https://zed.dev/docs/debugger) | A narrow local Python path; not turnkey multi-language debugging |
| Testing | Explorer; explicit Maven, Gradle, pytest, Jest, Vitest, Go, .NET, and Cargo discovery/run adapters; coverage import; extension test provider | VS Code's Testing API and provider ecosystem cover a wider range of discovery models. Zed exposes test workflows through language tools and tasks, not a direct analogue to Shed's eight adapters. [VS Code Extension API](https://code.visualstudio.com/api), [Zed running and testing](https://zed.dev/docs/running-testing) | Useful local adapters, narrower |
| Remote workspaces | Explicit Git clone, SSH rsync mirror, Docker-copy mirror, WSL path, pull/push, remote commands, remote PTY terminals, and explicitly chosen remote workspace tasks | VS Code runs commands and extensions through a remote server. Zed runs its source, language servers, tasks, and terminals on an SSH remote server. [VS Code Remote Development](https://code.visualstudio.com/docs/remote/remote-overview), [Zed Remote Development](https://zed.dev/docs/remote-development) | Better than file mirroring alone; not remote-IDE parity |
| Dev Containers | Explicit wrapper around the user-installed `devcontainer` CLI; container terminal and command bridge; explicit structured task execution with environment and cwd limits | VS Code and Zed reopen workspaces in container context; Zed puts tasks, terminals, and language servers there. [Zed Dev Containers](https://zed.dev/docs/dev-containers) | Partial bridge |
| Multi-root | Multiple folders, folder-only portable manifests, and deepest-root selection for selected controllers; nearest `.shed.toml` project overrides | VS Code applies richer per-folder workspace settings, tasks, debug configuration, recommendations, and extension behavior | Partial |
| Source control | Git workbench, immutable current-file permalinks for public GitHub/GitLab/Bitbucket origins (GitHub/GitLab line ranges), consented GitHub PR review, and basic Mercurial/Subversion command providers | VS Code has a richer provider ecosystem. Zed supports more public and self-hosted Git hosting providers, ranges, and clickable references. [Zed Git](https://zed.dev/docs/git) | Useful, but partial |
| Terminal | PTY terminal, direct-argv extension profiles, Bash/Zsh/Fish command and cwd events, fresh-shell session restore | VS Code and Zed provide richer shell coverage, navigation, decorations, links, profiles, and terminal placement/configuration. Zed supports panel/center placement and terminal splits. [Zed terminal](https://zed.dev/docs/terminal) | Strong local terminal, partial parity |
| Notebooks / REPL | Local `.ipynb` editor with editable Markdown and sanitized in-place Markdown preview, bounded PNG/JPEG and text outputs, explicit Jupyter run-all, fresh-kernel Run to here, and an explicit local Jupyter Console with optional kernel name | VS Code supports kernels and remote Jupyter; Zed has Jupyter-kernel REPL integration. [Zed REPL](https://zed.dev/docs/repl) | Basic only |
| Extensions | Explicit checksum-verified local Java JARs with typed contributions | VS Code Marketplace/VSIX/web/remote hosts; Zed Gallery and WASM extension runtime | Local extension API, not ecosystem parity |
| Custom editors | Extension component with byte snapshots, atomic writes, bounded per-pane write undo/redo, revisions, pane/disposal callbacks, and best-effort external-change events | VS Code custom documents have lifecycle, backup/hot-exit, undo/redo, and multi-view concepts | Better lifecycle boundary; still partial |
| Database / deployment / collaboration | Explicit PostgreSQL CLI query/tables/file/terminal bridge; explicit local Docker Compose build/up/logs/exec/terminal/redeploy/down bridge; typed extension hook for other database, deployment, and collaboration providers | VS Code has a large provider ecosystem; Zed has real-time shared projects, channels, voice, and collaborative notes. [Zed collaboration](https://zed.dev/docs/collaboration/channels) | Narrow local database/deployment support; collaboration absent |

## What the code currently does

### Language profiles

`LanguageProfile` makes a Java extension language visible in the status bar and supplies bounded, literal lexical tokens to the highlighter and comment actions. It can also apply a language-local tab width and tabs-versus-spaces choice to editing and LSP formatting, without changing global user settings. It intentionally does not accept arbitrary regular expressions. This adds real editor behavior beyond LSP process selection.

It still does **not** add TextMate grammars, Tree-sitter parsing, grammar injections, language-specific folding, semantic tokens, formatter registration, or extension-defined snippets. `:language` can select a lexical profile for the current buffer, but it does not retarget an LSP server. LSP behavior is limited to what the server and Shed's existing LSP client expose.

### Remote commands

`:remote exec <id> <command...>` is direct argv at Shed's boundary. SSH execution safely quotes the remote POSIX command and changes to the URI path; Docker uses `docker exec --workdir`; WSL uses `wsl.exe --cd`; a Git connection executes in its local clone. Results are capped and shown in a scratch buffer.

This does not relocate LSP, test discovery, debugging, extension code, or file watching by default. `:remote terminal` is an explicitly selected PTY bridge; normal terminals remain local. A task can be explicitly targeted with `:task remote`; it carries only its direct argv, workspace-relative cwd, and declared environment. SSH remains a remote shell boundary; command execution has the permissions of the selected remote account or container. This is materially different from VS Code and Zed remote servers, which host core project tooling remotely.

### Terminal integration

The host creates per-terminal startup files under `~/.shed/shell-integration/`. Bash, Zsh, and Fish can emit command-start, command-finish, and current-directory events. The Fish path uses an isolated `XDG_CONFIG_HOME` and sources an existing Fish `config.fish` when present. Shed does not modify the user's shell startup files.

Events are retained only in memory and may contain sensitive command text. They are metadata, not an execution sandbox or a complete shell-integration protocol.

## Verification boundary

The repository has focused tests for lexical profiles, terminal startup generation, deterministic provider routing, and protocol/model behavior. Those tests do not prove a live SSH host, Docker container, Dev Container CLI, Jupyter kernel, debug adapter, or third-party language server works in every environment. Environment-dependent integrations remain dependent on user-installed tools and their configuration.

## Honest product language

Use: “Shed is a local Java desktop IDE workbench with LSP/DAP foundations, test and source-control tooling, explicit remote workspace bridges, and a typed local extension API.”

Avoid: “Shed is a fully featured VS Code/Zed replacement,” “remote development parity,” “full language support,” “built-in database/deployment/collaboration,” or “Marketplace-compatible extensions.”

For implementation details and exact limitations, see [Extensions](EXTENSIONS.md), [Remote Workspaces](REMOTE_WORKSPACES.md), [Terminal](TERMINAL.md), [Testing](TESTS.md), [DAP](DAP.md), [Notebooks](NOTEBOOKS.md), and [Workspace Integrations](WORKSPACE_INTEGRATIONS.md).
