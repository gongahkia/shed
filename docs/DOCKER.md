# Docker

`:docker`, `:docker ui`, and `:docker workbench` open the themed **Docker Workbench**. It is a fast local control plane for the user-installed Docker CLI and daemon, with a read-only output/inspection area and cancellable jobs.

The workbench has these tabs:

- **Containers:** list, inspect, start, stop, restart, pause/resume, bounded logs, one-shot resource stats, process list, PTY terminal, direct-argv exec, Docker-copy workspace mirror, and confirmed removal.
- **Images:** list, inspect, pull, run an image with optional direct command arguments, and confirmed removal.
- **Volumes:** list, inspect, create, and confirmed removal.
- **Networks:** list, inspect, create, connect/disconnect a selected container, and confirmed removal.

**Docker Command…** accepts any arguments after `docker` as direct argv. It is the fast escape hatch for supported Docker CLI operations that do not have a dedicated button. Commands containing `rm`, `prune`, `kill`, or `down` require a second confirmation. Nothing is sent through a shell.

The text interface remains useful for keyboard-driven or scripted workflows:

```text
:docker list
:docker inspect <container>
:docker start <container>
:docker stop <container>
:docker restart <container>
:docker logs <container> [lines]
:docker exec <container> <command...>
:docker terminal <container> [command...]
:docker open <container> <absolute-container-path>
```

`list`, inspection, lifecycle, logs, and `exec` run as cancellable background jobs and place bounded output in scratch buffers. `terminal` opens a PTY through `docker container exec -it`; Docker therefore requires the target container to be running.

`open` is intentionally separate from Docker lifecycle: it creates a local copy mirror of the specified absolute container path using the built-in remote-workspace bridge. Use `:remote pull <id>` and `:remote push <id>` to synchronize that mirror explicitly, or `:remote use <id>` to route new terminals and tasks through its container execution boundary.

Shed accepts only ordinary Docker container names or IDs composed of letters, digits, `_`, `.`, and `-` for its targeted commands. The workbench always uses direct argv; the advanced command is likewise parsed into direct arguments rather than executed by a local shell.
