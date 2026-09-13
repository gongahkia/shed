# Docker

Shed exposes explicit local Docker Engine operations through the user-installed `docker` CLI. It does not start Docker, install images, create containers, or contact a registry by itself.

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

Shed accepts only ordinary Docker container names or IDs composed of letters, digits, `_`, `.`, and `-`. It uses direct argv for every command, so command text is never sent through a local shell.
