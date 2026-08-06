# Network and telemetry boundary

This is a source-level audit of Shed's app-owned outbound-capable paths. Shed has no telemetry collector, analytics endpoint, or automatic catalog refresh. Automatic update metadata checks are disabled until explicit consent and valid global configuration. Local diagnostics, recovery, workspace indexing, and benchmarks remain on disk.

## Default state

- GitHub review integration defaults to disabled with no consent receipt. `:github prs`, pull-request details, and review submission are blocked until `:github consent` persists both flags.
- Automatic update metadata checks default to disabled with no consent receipt. `:update check` and launch-time checks are blocked until `:update consent` persists both flags plus a configured endpoint and verification key.
- Managed-language catalog entries are bundled metadata. `:lsp manage` opens a local panel; only a fresh explicit approval in that panel may start a pinned JDT LS HTTPS download or an exact npm language-service install. No catalog refresh occurs.
- Plugin packages, Git remotes, browser links, debug transports, terminals, tasks, language servers, and plugin shell hooks do not start from a background updater or telemetry path.

## App-owned paths

| Path | Authorization and behavior | Data boundary |
| :--- | :--- | :--- |
| GitHub review | `:github consent` persists consent; each PR list/detail/review action remains explicit. `:github status` runs local CLI capability probes only. | The user-installed `gh` CLI owns any permitted GitHub request; Shed does not transmit telemetry. |
| Signed update metadata | `UpdateMetadataTransport` runs only after `:update consent` enables one launch-time metadata check; `:update check` is explicit and `:update disable` revokes consent. HTTPS metadata is size-limited, does not follow redirects, and must verify against the configured Ed25519 public key before an update is surfaced. | Shed sends only the metadata request to the configured endpoint. It never downloads, installs, or replaces an application file; `:update open` explicitly delegates a verified installer URL to the system browser. |
| Landing page HTTPS source | `LandingPageRemoteTransport` runs only when the user explicitly sets global `landing.source` to an HTTPS URL. Requests are timeout- and size-limited, do not follow redirects, and never run by default. | Shed sends one GET request to the configured URL, caches the response in the configured local landing file, and never uploads local edits. |
| Managed language catalog/install/update | `ManagedLanguageCatalog` and `ManagedLanguageDistributionCatalog` are static release-bundled metadata. A one-use approval created by the Language Services GUI is required before `ManagedLanguageSupportService` starts an install. JDT LS uses an HTTPS archive transfer with redirects disabled and SHA-256 verification before safe extraction. Pyright, TypeScript/JavaScript, JSON, and Markdown invoke the user-installed `npm` only after the same review; it runs in Shed's cache with lifecycle scripts, audit, funding, and update notifications disabled, and uses exact top-level package versions. | JDT LS sends only an HTTPS GET to the pinned Eclipse archive URL. The npm route lets npm request its registry packages and dependencies after the review; Shed does not send workspace/editor/telemetry data, install globally, or refresh a catalogue. npm lockfile integrity is not equivalent to Shed having an independently published SHA-256 for each dependency. |
| Managed plugin install/update | `PluginManager` runs `:plugin install` with an exact URL and `:plugin update` only as explicit commands. Remote sources require a SHA-256 checksum; package loading at startup reads only local package metadata and files. | A remote source may be fetched only by the explicit install/update command; no scheduled package update exists. |
| Git fetch/pull/push | The Git history document performs remote work only from its Fetch, Pull, or Push controls; Pull and Push require confirmation. | Git owns the remote protocol; status/history refreshes use local repository data. |
| Debug adapter TCP | `DebugAdapterTransport` starts only from an explicitly configured debug session; TCP targets are rejected unless loopback. | No remote adapter address is accepted. |
| Browser links | Open-link actions and explicit clicks on Markdown-preview links delegate a URI to the platform browser. | The browser, not Shed, owns any resulting request. Markdown, TeX, and Mermaid rendering run locally; remote images/content are never fetched. |
| User-controlled child processes | Terminals, tasks, shell-enabled plugins, configured language servers, and an opt-in external formatter can launch user-selected programs. | Those programs can have their own network behavior; Shed does not inspect or authorize their traffic. |

`gh`, Git, browsers, terminals, tasks, plugins, and language servers are external programs. Shed can constrain the arguments it gives them, but it cannot verify their implementation or network side effects.

## Regression audit

`NetworkConsentAuditTest` checks the default GitHub and update consent gates and fails when the app-owned direct transport baseline changes: `URLConnection`, `HttpClient`, direct sockets, browser delegation, PTY/process launchers, and their source files are enumerated. Add a new transport only with an updated row here and reviewed consent/default-off coverage.
