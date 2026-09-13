# Remote Workspaces

Remote workspaces keep an explicit local working tree. Shed has built-in Git, SSH, Docker-container, and Windows WSL providers; it does not load third-party remote providers or run a remote extension host.

```text
:remote providers
:remote open <uri>
:remote reconnect <connection-id>
:remote bootstrap ssh://[user@]host[:port]/absolute-workspace-path
:remote pull <connection-id>
:remote push <connection-id>
:remote sync start <connection-id> [seconds]
:remote sync stop <connection-id>
:remote exec <connection-id> <command...>
:remote terminal <connection-id> [command...]
:remote use <connection-id>
:remote unuse <connection-id>
:remote forward <connection-id> <local-port> <remote-host> <remote-port>
:remote forward list
:remote forward close <local-port>
:remote close <connection-id>
```

## Built-in providers

| URI | Local representation | Pull | Push | Remote execution |
| --- | --- | --- | --- | --- |
| `git:`, `git+https:`, `git+ssh:`, eligible `.git` HTTPS/SSH URIs | Git clone below `~/.shed/remote-workspaces/` | fetch and fast-forward pull | Git push | local clone |
| `ssh://user@host/absolute/path` | `rsync` mirror | remote-to-local `rsync` | local-to-remote `rsync` | SSH at the URI path |
| `container://name/absolute/path` or `docker://name/absolute/path` | Docker-copy mirror | `docker cp` into mirror | `docker cp` into container | `docker exec --workdir` |
| `wsl://distribution/absolute/path` | direct `//wsl$/` path on Windows | no-op | no-op | `wsl.exe` |

URIs require an absolute path with no traversal segments. URI passwords are rejected. Credentials remain with Git, SSH, Docker, WSL, and their configured credential helpers.

## Connection and synchronization

`:remote open` connects asynchronously, fetches the first local representation, and adds it to the workspace. :remote reconnect <id>` opens the same URI again and replaces that connection only after the new mirror succeeds. An active automatic-pull schedule remains attached to that connection id after a successful reconnect.

`:remote bootstrap <ssh-uri>` creates only the requested remote directory with `mkdir -p`; it does not install software, configure SSH, start a server, or leave a remote process behind. It is useful before the first SSH mirror connection when the workspace path does not yet exist.

Pull and push are explicit. `:remote sync start <id> [seconds]` adds an opt-in automatic **pull** with an interval from 5 to 3,600 seconds. Each result, including failures, is recorded in Shed’s local command log. `:remote sync stop <id>` cancels it. Automatic sync is session-only, does not push, does not delete mirror files, and stops when the connection or application closes.

## Execution, terminals, and tasks

`:remote exec` starts one explicit direct-argv command as a cancellable background job. Its bounded output opens in a scratch buffer. `:remote terminal` opens one PTY: SSH uses `ssh -tt`, Docker uses `docker exec -it`, and WSL uses `wsl.exe`.

`:remote use <id>` is the explicit routing switch. It makes new ordinary terminal panes and `:task run`/`:task dry-run` for files under that connection use the remote workspace. `:remote unuse` removes that routing without closing the mirror. An active remote session takes precedence over a connected Dev Container for the same files.

`:remote forward` supports loopback-only SSH forwards. Shed starts the installed SSH client with `BatchMode=yes`, `ExitOnForwardFailure=yes`, and a `127.0.0.1` listener; it never scans ports or creates a forward on connection.

Remote LSP, testing, and debugging remain explicit bridges. Global `remote.lsp.enabled=true` plus a configured `lsp.<extension>.command` is required before Shed starts a user-selected server in an SSH, Docker, WSL, or connected Dev Container environment. Shed does not install the server or adapter.

## Local Dev Container CLI

For an active workspace containing `.devcontainer/devcontainer.json`, Shed delegates to the installed Development Containers CLI:

```text
:container status
:container build
:container up
:container lifecycle
:container stop
:container down
:container connect
:container disconnect
:container exec <command...>
:container terminal [command...]
:container open <container> <absolute-container-path>
:task container <name>
```

`build`, `up`, `lifecycle`, `stop`, and `down` map to the CLI’s corresponding lifecycle actions. `connect` runs `up`, probes the mounted workspace with `devcontainer exec … pwd`, then records an in-memory mapping used by new terminals and ordinary tasks. `disconnect` removes only that Shed mapping; it leaves the container running. LSP, tests, and debugging never start or rebuild a container implicitly.

## Boundary

Remote support is intentionally a local-mirror and explicit-process model. There is no remote extension host, third-party remote provider API, automatic conflict resolution, automatic push, persisted remote session, remote file watcher, remote shell state restore, remote TCP DAP, port discovery, browser editor, or SSH software installation. Background remote execution is limited to user-requested `:remote exec`, tasks, and opt-in automatic pull jobs, all of which report through the normal local job/logging paths.
