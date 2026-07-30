# Network and telemetry boundary

This is a source-level audit of Shed's app-owned outbound-capable paths. Shed has no telemetry collector, analytics endpoint, automatic update check, or automatic catalog refresh. Local diagnostics, recovery, workspace indexing, and benchmarks remain on disk.

## Default state

- GitHub review integration defaults to disabled with no consent receipt. `:github prs`, pull-request details, and review submission are blocked until `:github consent` persists both flags.
- Managed-language catalog entries are bundled metadata. `:lsp manage`, `install`, and `update` currently present availability/review information; they do not download or refresh a catalog.
- Plugin packages, Git remotes, browser links, debug transports, terminals, tasks, language servers, and plugin shell hooks do not start from a background updater or telemetry path.

## App-owned paths

| Path | Authorization and behavior | Data boundary |
| :--- | :--- | :--- |
| GitHub review | `:github consent` persists consent; each PR list/detail/review action remains explicit. `:github status` runs local CLI capability probes only. | The user-installed `gh` CLI owns any permitted GitHub request; Shed does not transmit telemetry. |
| Managed language catalog/install/update | Catalog is static in `ManagedLanguageCatalog`; management UI reports local detection or consent-required availability. | No catalog refresh or artifact download is wired into the current UI. |
| Managed plugin install/update | `PluginManager` runs `:plugin install` with an exact URL and `:plugin update` only as explicit commands. Remote sources require a SHA-256 checksum; package loading at startup reads only local package metadata and files. | A remote source may be fetched only by the explicit install/update command; no scheduled package update exists. |
| Git fetch/pull/push | The Git history document performs remote work only from its Fetch, Pull, or Push controls; Pull and Push require confirmation. | Git owns the remote protocol; status/history refreshes use local repository data. |
| Debug adapter TCP | `DebugAdapterTransport` starts only from an explicitly configured debug session; TCP targets are rejected unless loopback. | No remote adapter address is accepted. |
| Browser links | Open-link and Markdown-preview actions explicitly delegate a URI to the platform browser. | The browser, not Shed, owns any resulting request. |
| User-controlled child processes | Terminals, tasks, shell-enabled plugins, and configured language servers can launch user-selected programs. | Those programs can have their own network behavior; Shed does not inspect or authorize their traffic. |

`gh`, Git, browsers, terminals, tasks, plugins, and language servers are external programs. Shed can constrain the arguments it gives them, but it cannot verify their implementation or network side effects.

## Regression audit

`NetworkConsentAuditTest` checks the default GitHub consent gate and fails when the app-owned direct transport baseline changes: `URLConnection`, direct sockets, browser delegation, PTY/process launchers, and their source files are enumerated. Add a new transport only with an updated row here and reviewed consent/default-off coverage.
