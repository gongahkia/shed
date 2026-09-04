# Remote Workspaces

Remote workspaces are explicit local working-tree connections. They do not install a server, start a background synchronizer, or run code on a remote machine merely because a URI is opened.

```text
:remote providers
:remote open git+https://host/owner/repository.git
:remote open ssh://user@host/absolute/path
:remote open container://container-name/absolute/path
:remote pull <connection-id>
:remote push <connection-id>
:remote exec <connection-id> <command...>
:remote terminal <connection-id> [command...]
:remote close <connection-id>
```

## Built-in providers

| URI | Local representation | Pull | Push |
| --- | --- | --- | --- |
| `git:`, `git+https:`, `git+ssh:`, eligible `https:`/`ssh:` ending in `.git` | A Git clone below `~/.shed/remote-workspaces/` | `git fetch --all --prune`, then `git pull --ff-only` | `git push` |
| `ssh://user@host/absolute/path` | An `rsync` mirror below `~/.shed/remote-workspaces/` | `rsync -az --protect-args` remote-to-local | the same explicit local-to-remote transfer |
| `container://name/absolute/path` or `docker://name/absolute/path` | A Docker-copy mirror below `~/.shed/remote-workspaces/` | `docker cp` container-to-local | `docker cp` local-to-container |
| `wsl://distribution/absolute/path` | Direct `//wsl$/` folder on Windows | no-op | no-op |

SSH, Git, and Docker credentials remain with the user-installed tools and their credential helpers. URI passwords are rejected. Paths must be absolute, cannot contain `..`, and mirror paths are checked against traversal and symbolic-link escapes. Git and remote-command output is capped and commands time out rather than being left attached indefinitely.

## Explicit remote commands

`:remote exec <id> <command...>` runs only after a user requests it. The command is parsed as direct argv; Shed does not invoke a local shell to process it. The result opens in a scratch buffer and has a capped output size.

`:remote terminal <id> [command...]` opens an explicit interactive terminal at the connection root. SSH receives a PTY (`ssh -tt`) and a safely quoted remote shell/command; Docker receives `docker exec -it`; WSL starts the selected distribution shell. An optional command is direct argv. Terminal session state and remote command history are not restored.

`:task remote <id> <name>` is the structured equivalent for a validated `.shedtasks` entry. It transfers only the direct command argv, a path relative to the connection root, and declared environment values to a provider that supports task execution. See [Workspace Tasks](TASKS.md#explicit-remote-tasks) for shell and cancellation boundaries.

| Connection type | Execution location |
| --- | --- |
| SSH mirror | SSH host, after safely changing to the URI path |
| Docker mirror | Container, through `docker exec --workdir` |
| WSL | Selected distribution, through `wsl.exe -d … --cd` |
| Git clone | The local clone |

SSH necessarily passes a safely quoted command to the remote POSIX shell. For that route, Shed accepts DNS host names and simple SSH user names only; use a normal SSH config alias if the endpoint needs a more complex connection setup. This command path is explicit process execution, so it inherits the remote account/container's permissions and must be treated like opening a terminal there.

## Opt-in remote language servers

Set this **global** setting and configure the server command that already exists in the remote environment:

```toml
remote.lsp.enabled = true
"lsp.py.command" = "pyright-langserver"
"lsp.py.args" = "--stdio"
```

For a connected SSH, Docker-container, or WSL workspace, Shed carries that configured LSP process through `ssh`, `docker exec -i`, or `wsl.exe`; it initializes the server and document requests with the URI path from the remote workspace, while the editor keeps its local mirror. An already running local Dev Container is also supported: Shed first runs `devcontainer exec … pwd` to learn the mounted workspace path, then launches the server with `devcontainer exec`. Run `:lsp restart <ext>` after changing `remote.lsp.enabled`; a running client retains the URI mode it started with. Remote LSP is never enabled by a project `.shed.toml`, and Shed neither downloads the server nor runs managed local language-service artifacts remotely. Closing a remote workspace stops its associated LSP clients.

Remote terminal output can open a source location only when its absolute remote path is beneath that same declared remote root and its mapped local-mirror file exists. This supports compiler-style `path:line[:column]` output without exposing remote paths outside the mirror. Relative links assume the terminal began at the remote workspace root; Shed does not infer a later remote `cd` for SSH/container terminals.

The bridge supports only ordinary absolute `file:` URIs inside the connected workspace root. It does not support SSH login banners on stdout, remote URI schemes, port-forwarded language servers, remote extension hosts, remote test/debug placement, or a server bootstrap/reconnect protocol.

## Semantics and limitations

- Pull and push never happen on a timer. `:remote close` disconnects the workspace and retains its local mirror.
- SSH mirroring deliberately omits `--delete`; a pull cannot silently delete an unrelated local mirror file.
- Container remote-workspace support itself is file synchronization. It does not run an extension host inside a container; the separate local Dev Container CLI bridge below is explicit and does not alter that mirror model.
- WSL support is Windows-only and uses the local WSL filesystem bridge rather than a remote server.
- This is not VS Code or Zed remote-development parity: there is no remote extension host, remote test/debug placement, automatic task placement, SSH server bootstrap, port-forwarding UI, Codespaces service, browser editor, persistent remote-server/reconnect protocol, or automatic conflict resolver. Remote LSP is a narrow explicit process bridge, not a remote workbench host.

Extensions can add URI schemes using `RemoteWorkspaceProvider`; they must disclose their own authentication, synchronization, and network behavior.

## Local Dev Container CLI

For a workspace containing `.devcontainer/devcontainer.json`, Shed also exposes an explicit local CLI bridge:

```text
:container status
:container up
:container exec <command...>
:container terminal [command...]
:task container <name>
:container open <container> <absolute-path>
```

`up`, `exec`, and `:task container` use the user-installed `devcontainer` CLI as explicit, cancellable local processes; `terminal` opens a PTY using `devcontainer exec`. A container task first resolves the runtime workspace path, applies task environment values as `--remote-env`, and then runs the validated task. With global `remote.lsp.enabled=true` and a user-configured `lsp.<ext>.command`, opening a matching file in an already running container likewise probes that path and starts only that LSP process through `devcontainer exec`; it does not start the container or install the server. Direct tasks require the workspace-root cwd because the CLI exposes no cwd option; login-shell tasks may explicitly change into a subdirectory. Shed does not install the CLI, create a configuration, invoke these commands during workspace open, or claim to run an extension host in the container. `open` creates the same explicit Docker-copy mirror described above.

In a multi-root workspace, Dev Container commands use the deepest configured folder containing the current file. A scratch or outside file uses the Explorer-selected folder.
