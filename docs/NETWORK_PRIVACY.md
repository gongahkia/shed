# Network and telemetry boundary

This is a source-level audit of Shed's app-owned outbound-capable paths. Shed has no telemetry collector, analytics endpoint, automatic catalog refresh, plugin marketplace, extension downloader, or remote extension host. Local diagnostics, recovery, workspace indexing, and benchmarks remain on disk.

## Default state

- GitHub review is disabled until `:github consent` persists both its setting and receipt.
- Update metadata is disabled until `:update consent` persists both its setting and receipt plus a configured endpoint and verification key.
- Managed language metadata is bundled. `:lsp manage` requires a fresh approval before `ManagedLanguageSupportService` may download or install a listed runtime.
- Remote connections, Docker and Dev Container commands, browser links, debug transports, terminals, tasks, language servers, and port forwards require an explicit user action. The only recurring remote action is a session-only pull started explicitly with `:remote sync start`.

## App-owned paths

| Path | Authorization and behavior | Data boundary |
| :--- | :--- | :--- |
| GitHub review | `:github consent` gates every review operation; `:github status` only probes local CLI capability. | The user-installed `gh` CLI owns GitHub requests. |
| Signed update metadata | `UpdateMetadataTransport` runs only after consent. It uses a configured HTTPS endpoint, bounded data, no redirects, and Ed25519 verification before surfacing an update. | Shed sends only the metadata request; it never installs or replaces itself. |
| Landing page HTTPS source | `LandingPageRemoteTransport` runs only after the user explicitly sets `landing.source` to an HTTPS URL. | One bounded GET is cached locally; local edits are never uploaded. |
| Managed language install | `ManagedLanguageCatalog` and `ManagedLanguageSupportService` require an explicit reviewed install. | The JDT LS archive or user-installed npm owns the network transfer; Shed sends no workspace or telemetry data. |
| Remote workspace | `:remote open`, reconnect, bootstrap, pull, push, exec, terminal, use, forward, and close are explicit. `:remote sync start` creates only an in-memory recurring pull with a 5–3,600 second interval. `SshPortForwardService` listens only on loopback and stops with the connection or app. | Git, rsync/SSH, Docker, or WSL owns the transport and credentials. URI passwords are rejected; no contributed provider or remote host is loaded. |
| Dev Container CLI | `DevContainerController` exposes explicit build, up, lifecycle, stop, down, connect, exec, terminal, and task commands for a workspace with local `devcontainer.json`; `DevContainerRuntime` owns direct CLI invocation. | The user-installed `devcontainer` CLI owns Docker, registry, and network behavior. |
| Docker containers | `DockerController` exposes explicit list, inspect, lifecycle, logs, exec, terminal, and mirror-open commands. | The user-installed Docker CLI and daemon own engine, registry, image, and network behavior. |
| Docker Compose | Compose actions start only from explicit commands in a workspace with a local Compose file. | The user-installed Docker/Compose CLI owns daemon, registry, image, and network behavior. |
| Notebook | Kernel discovery, selection, execution, and console actions are explicit. | User-installed Jupyter owns kernel/network behavior. |
| Git remotes | Fetch, pull, and push occur only from explicit Git controls. | Git owns the remote protocol. |
| Debug adapter TCP | `DebugAdapterTransport` starts from an explicit configured debug session and accepts loopback targets only. | No remote adapter address is accepted. |
| Browser links | Explicit actions delegate a link to the platform browser. | The browser owns resulting requests. |
| User-controlled child processes | Terminals, tasks, configured language servers, formatters, Docker, and Dev Container commands can launch user-selected programs. | Those programs can have their own network behavior; Shed does not inspect or authorize their traffic. |

`gh`, Git, browsers, terminals, tasks, Docker, the Dev Container CLI, and language servers are external programs. Shed can constrain the arguments it gives them, but it cannot verify their implementation or network side effects.

## Regression audit

`NetworkConsentAuditTest` checks the GitHub/update gates and enumerates app-owned direct transport, browser delegation, PTY, and process-launch sources. Add a new transport only with a row here and reviewed consent/default-off coverage.
