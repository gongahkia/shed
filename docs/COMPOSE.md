# Docker Compose

Shed provides an explicit local Docker Compose bridge for a single standard configuration at the active workspace root. It recognizes the first present file in this order: `compose.yaml`, `compose.yml`, `docker-compose.yaml`, then `docker-compose.yml`. It does not walk parent folders, infer an external Compose file, contact Docker during workspace open, or install Docker/Compose.

```text
:compose status
:compose up [service...]
:compose build [service...]
:compose ps
:compose services
:compose logs [service...]
:compose exec <service> <command...>
:compose terminal <service> [command...]
:compose redeploy <service>
:compose down
```

`status` reads and displays only the local configuration. Every other command starts the user-installed `docker compose -f <configuration>` CLI as a cancellable job, except `terminal`, which opens it through Shed's PTY terminal. `logs` requests the most recent 200 lines per selected service rather than following indefinitely. `exec` supplies `-T` for predictable non-interactive job output; `terminal` retains Compose's interactive TTY behavior.

`redeploy <service>` is deliberately narrow: it runs `docker compose build <service>` and, only if that succeeds, `docker compose up --no-deps -d <service>`. `down` has no forwarded options, so Shed does not request volume or image deletion. Those actions can still be performed in the terminal by the user.

Compose service names are restricted to letters, digits, `.`, `_`, and `-`; commands are parsed as direct argv and Shed does not compose a shell string. Compose/Docker retains responsibility for credential helpers, registries, remote Docker hosts, images, networks, mounted paths, and lifecycle semantics. This bridge is useful for a local Compose-defined stack; it is not a deployment dashboard, Kubernetes client, cloud deployer, secrets manager, or remote-development environment.
