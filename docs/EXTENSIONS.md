# Java Extensions

Shed has two different local customization paths. Existing `.shed`/Lua plugins remain for small declarative and shell-backed behavior. Java extensions are the supported workbench-integration path: they can contribute commands, languages, debuggers, test providers, SCM providers, terminal profiles, tool views, custom editors, and remote-workspace providers.

Java extensions are local JARs, not a Marketplace and not a sandbox. An activated extension executes with the same JVM user permissions as Shed. Install only code you trust.

## Install and lifecycle

Use one of these explicit commands:

```text
:extension install /absolute/path/provider.jar
:extension install https://example.invalid/provider.jar --checksum=<sha256>
:extension status
:extension disable example.provider
:extension reload
:extension remove example.provider
```

Remote installation accepts HTTPS only and requires the SHA-256 supplied by the user. Local JARs are copied into `~/.shed/extensions/`; both local and remote installs record their resulting digest in `extensions-v1.json`. Each load rechecks the JAR digest, receipt, descriptor, regular-file status, and API version before activating it. Extension state belongs in `~/.shed/extension-data/<extension-id>/`.

`remove` deletes only the recorded extension JAR and that extension's state directory. It does not remove a source JAR, a workspace, or arbitrary files. No extension lookup, update check, or download is automatic.

## Descriptor

Every JAR contains `META-INF/shed-extension.toml`:

```toml
id = "example.provider"
version = "1.0.0"
api_version = 1
main_class = "example.ProviderExtension"
```

`main_class` implements `shed.api.ShedExtension`. `activate(ExtensionContext)` registers contributions; `deactivate()` must release listeners, processes, and extension-owned resources. Shed removes all registrations for an extension before a reload, disable, replacement, or shutdown.

## API surface

`ExtensionContext` registers these contribution types:

| Contribution | Host integration |
| --- | --- |
| `ExtensionCommand` | Command palette and `:<extension-id>.<command>` dispatch |
| `LanguageContribution` | File-extension routing and an optional direct-argv LSP launch |
| `DebugAdapterContribution` | DAP configuration validation and explicit debug launch |
| `TestContribution` | Tests view discovery, run results, Problems locations, and debug configuration mapping |
| `ScmContribution` | `:scm` provider status and declared actions |
| `TerminalProfile` | Named direct-argv terminal profiles |
| `ToolViewContribution` | Docked workbench view opened through `:view` |
| `CustomEditorContribution` | Alternate text or binary editor component |
| `RemoteWorkspaceProvider` | Explicit `:remote open <uri>` schemes |
| `WorkspaceToolContribution` | Declared database, deployment, collaboration, or container actions through `:integration` |

Command ids are namespaced to their extension id and cannot overwrite a built-in or another extension's command. SCM actions are allowlisted by the provider's declared action list; Shed does not turn them into shell strings.

`WorkspaceToolContribution` is the workspace-aware command boundary for database consoles, deployment workflows, collaboration clients, and container controls; `ToolViewContribution` adds their docked UI. Java extension code remains responsible for credentials, process/network policy, cancellation, and UI. Details: [Workspace Integrations](WORKSPACE_INTEGRATIONS.md).

## Custom editors

`CustomEditorContribution.createComponent(CustomEditorDocument)` receives a file path, a defensive byte snapshot, binary detection, and an explicit `write(byte[])` operation. `write` replaces only that resource through Shed's atomic file writer and reloads its buffer model. It is suitable for binary previews and editors as well as text views. The earlier `createComponent(Path, String)` method remains supported for API-v1 text extensions.

Shed currently presents one custom component per editor pane. It does not supply a generalized custom-document undo/redo, backup, serializer, or multi-view synchronization protocol; binary editor extensions own their format model and should implement those capabilities where required.

## Boundaries

- There is no extension Marketplace, web-extension runtime, remote extension host, browser workbench, VSIX compatibility layer, or permission sandbox.
- A Java extension can make network requests or start processes because it runs as local JVM code. Shed records and verifies its JAR but cannot confine its implementation.
- Built-in managed language installation is separate from this API and retains its explicit review and integrity policy.
- Extension JAR API compatibility is currently `api_version = 1`; newer API versions are rejected rather than guessed.
