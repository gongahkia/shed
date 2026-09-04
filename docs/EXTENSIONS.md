# Java Extensions

Shed has two different local customization paths. Existing `.shed`/Lua plugins remain for small declarative and shell-backed behavior. Java extensions are the supported workbench-integration path: they can contribute commands, languages, language profiles, debuggers, test providers, SCM providers, terminal profiles, tool views, custom editors, and remote-workspace providers.

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
| `LanguageProfile` | File-name, extension, or first-line detection plus literal lexical metadata |
| `DebugAdapterContribution` | DAP configuration validation and explicit debug launch |
| `TestContribution` | Tests view discovery, run results, Problems locations, and debug configuration mapping |
| `ScmContribution` | `:scm` provider status and declared actions |
| `TerminalProfile` | Named direct-argv terminal profiles |
| `ToolViewContribution` | Docked workbench view opened through `:view` |
| `CustomEditorContribution` | Alternate text or binary editor component |
| `RemoteWorkspaceProvider` | Explicit `:remote open <uri>` schemes; its connected workspace may opt into `:remote exec` and structured remote-task requests |
| `WorkspaceToolContribution` | Declared database, deployment, collaboration, or container actions through `:integration` |

Command ids are namespaced to their extension id and cannot overwrite a built-in or another extension's command. SCM actions are allowlisted by the provider's declared action list; Shed does not turn them into shell strings.

## Language profiles

`LanguageContribution` selects an LSP language id and optional server command. `LanguageProfile` independently makes an extension language visible in the editor: it can match extensions, exact file names, or literal first-line prefixes, and supplies keywords, line/block comments, and string delimiters. The selected profile drives status-bar language naming, lexical highlighting, comment/uncomment commands, indentation, and LSP formatting options.

Profiles intentionally use bounded literal tokens rather than arbitrary regular expressions. This keeps their typing-path cost predictable and avoids executing extension-supplied patterns in the editor. A profile may overlap a built-in file type; the extension profile then controls lexical display for its declared match, while the built-in type continues to supply any built-in editor behavior not represented by the profile.

The API-v1 constructor ends at `keywords` and preserves the user's `tab.size` and `expand.tab` settings. The extended constructor accepts `Integer tabSize` (`1..16`) and `Boolean insertSpaces`; pass `null` for either setting to retain that user preference. These preferences are applied per editor pane as buffers change, rather than becoming a global setting.

```java
context.registerLanguageProfile(new LanguageProfile(
    "example", "Example", Set.of("ex"), Set.of("Examplefile"),
    Set.of("#!/usr/bin/env example"), List.of("//"),
    List.of(new LanguageProfile.BlockComment("/*", "*/")),
    List.of(new LanguageProfile.StringDelimiter("\"", false)),
    Set.of("module", "let", "fn")
));
```

This is lexical support only. It does not provide a TextMate grammar, injection grammar, folding query, parser, formatter, semantic tokens, symbols, or extension-defined snippets. Those capabilities remain separate implementation work; an LSP may supply semantic navigation and diagnostics when configured through `LanguageContribution`.

`WorkspaceToolContribution` is the workspace-aware command boundary for database consoles, deployment workflows, collaboration clients, and container controls; `ToolViewContribution` adds their docked UI. Java extension code remains responsible for credentials, process/network policy, cancellation, and UI. Details: [Workspace Integrations](WORKSPACE_INTEGRATIONS.md).

`RemoteWorkspace.execute(RemoteCommandRequest)` receives direct argv, a validated directory relative to the connected workspace root, and validated environment values. Providers that support `${workspaceFolder}` or `${file}` in an explicit remote task must return their absolute execution root from `executionRoot()` so Shed can map the local mirror path correctly. The API-v1 `execute(List<String>)` method remains available for command-only providers. Providers that do not override the request form reject non-root working directories and environment values rather than silently ignoring them.

## Custom editors

`CustomEditorContribution.createComponent(CustomEditorDocument)` receives a file path, a defensive byte snapshot, binary detection, and an explicit `write(byte[])` operation. `write` replaces only that resource through Shed's atomic file writer and reloads its buffer model. `revision()` and `onDidChange` expose successful host-mediated writes; `onDidDispose` lets a component release listeners when its pane is replaced, closed, or Shed shuts down. `canUndo`, `canRedo`, `undo`, and `redo` retain up to 100 host-mediated byte snapshots and 8 MiB per installed pane. Callback delivery is on the Swing event thread. It is suitable for binary previews and editors as well as text views. The earlier `createComponent(Path, String)` method remains supported for API-v1 text extensions.

Shed currently presents one custom component per editor pane. Its byte history covers only writes made through the installed document and is discarded when that pane is disposed. It does not supply a backup serializer, external-file-change stream, hot-exit, or multi-view synchronization protocol; binary editor extensions own their format model and should implement those capabilities where required.

## Boundaries

- There is no extension Marketplace, web-extension runtime, remote extension host, browser workbench, VSIX compatibility layer, or permission sandbox.
- A Java extension can make network requests or start processes because it runs as local JVM code. Shed records and verifies its JAR but cannot confine its implementation.
- Built-in managed language installation is separate from this API and retains its explicit review and integrity policy.
- Extension JAR API compatibility is currently `api_version = 1`; newer API versions are rejected rather than guessed.
