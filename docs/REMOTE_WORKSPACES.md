# Remote Workspaces

Remote workspaces are explicit local working-tree connections. They do not install a server, start a background synchronizer, or run code on a remote machine merely because a URI is opened.

```text
:remote providers
:remote open git+https://host/owner/repository.git
:remote open ssh://user@host/absolute/path
:remote open container://container-name/absolute/path
:remote pull <connection-id>
:remote push <connection-id>
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

## Semantics and limitations

- Pull and push never happen on a timer. `:remote close` disconnects the workspace and retains its local mirror.
- SSH mirroring deliberately omits `--delete`; a pull cannot silently delete an unrelated local mirror file.
- Container remote-workspace support itself is file synchronization. It does not run an extension host inside a container; the separate local Dev Container CLI bridge below is explicit and does not alter that mirror model.
- WSL support is Windows-only and uses the local WSL filesystem bridge rather than a remote server.
- This is not VS Code Remote Development: there is no remote extension host, SSH server bootstrap, port-forwarding UI, Codespaces service, browser editor, or automatic conflict resolver.

Extensions can add URI schemes using `RemoteWorkspaceProvider`; they must disclose their own authentication, synchronization, and network behavior.

## Local Dev Container CLI

For a workspace containing `.devcontainer/devcontainer.json`, Shed also exposes an explicit local CLI bridge:

```text
:container status
:container up
:container exec <command...>
:container terminal [command...]
:container open <container> <absolute-path>
```

`up` and `exec` use the user-installed `devcontainer` CLI as direct argv and run as cancellable jobs; `terminal` opens a PTY using `devcontainer exec`. Shed does not install the CLI, create a configuration, invoke these commands during workspace open, or claim to run an extension host in the container. `open` creates the same explicit Docker-copy mirror described above.

In a multi-root workspace, Dev Container commands use the deepest configured folder containing the current file. A scratch or outside file uses the Explorer-selected folder.
