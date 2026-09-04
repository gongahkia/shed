# `Shed` Configuration

This is the complete TOML configuration reference for `Shed`.

## Config Location

| Path | Purpose |
| :--- | :--- |
| `~/.shed/config.toml` | Main user config file loaded at startup |
| `~/.shed/snippets/` | User snippets in VS Code-shaped JSON files |
| `~/.shed/plugins/` | User plugin directory (`.shed` + `.lua`) |
| `~/.shed/sessions/` | Saved session/workspace data (default) |
| `.shed.toml` | Optional per-project override file (nearest parent directory) |
| `.shedtests` | Optional workspace-local Test Explorer adapter declarations; not part of global settings |

The Settings Editor writes `~/.shed/config.toml`; `settings.toml` is not loaded.

## File Format

| Rule | Details |
| :--- | :--- |
| Format | TOML v1.0, UTF-8 |
| Schema root | `schema_version = 1` |
| Keys | Quote the full Shed key: `"tab.size" = 4` |
| Strings | Quote values: `"theme" = "nightfox"` |
| Booleans | Use `true` / `false` |
| Persistence | `:set! key=value` writes one key, `:config save` writes current runtime overrides |

Shed validates the full TOML document at startup. If parsing fails, a value is unsupported, or the file cannot be read, Shed leaves it unchanged and starts with built-in defaults; `[config recovery]` lists each exact failure and directs you to correct it, then run `:reload`. Typed-value diagnostics include the file line and column, expected type or range, and active fallback value. While running, Shed polls for global config changes and applies valid edits; an invalid edit leaves the last-known-good configuration active and opens the recovery report. Use `:config status` to reopen the report. A missing config file also uses built-in defaults and can be created with `:config save`.

## Schema Version and Ownership

Every global `config.toml` and project `.shed.toml` starts with the unquoted root key `schema_version = 1`. Missing, non-integer, or unsupported versions reject the complete file, retain the file unchanged, and activate safe defaults. Version `1` is the only supported schema version; Shed does not infer or migrate a version. `:set` and `:set!` cannot override it; persistence emits the supported version.

`ConfigSchema` owns schema-version validation. `TypedSettings` owns core defaults and their TOML type, range, and enum validation. `ConfigManager` coordinates recovery reports, dynamic string namespaces, and TOML persistence.

## Runtime Commands

| Command | Behavior |
| :--- | :--- |
| `:settings`, `:config` | Open the graphical Settings Editor; changes write immediately to `~/.shed/config.toml` |
| `:config file`, `:config toml`, `:config text` | Open `~/.shed/config.toml` directly |
| `:set key=value` | Set runtime value only |
| `:set! key=value` | Set and persist one key to disk |
| `:config save` / `:config write` | Persist current runtime config |
| `:config defaults` | Create a complete commented default config only when no config exists |
| `:config! defaults` | Confirm replacement with a complete commented default config |
| `:config inspector`, `:config ui` | Open the Settings Editor; search by key or description, select a category, edit typed controls, or open raw TOML |
| `:config reset <key>` | Reset one typed setting to its canonical default and remove its global TOML override |
| `:config reference`, `:help settings` | Open generated typed-setting help with its description, allowed values, default, and live/restart behaviour |
| `:config status` | Show current config load/recovery details |
| `:reload` / `:source` | Reload config from disk |

The inspector and generated reference derive each typed setting's identifier, description, allowed values, default, and behaviour from one runtime metadata source.

## Core Editor Keys

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `theme` | `one-dark-pro` | string | Built-in theme id |
| `font.family` | `Monospaced` | string | Buffer font family; Java logical monospace resolves locally |
| `font.size` | `16` | int | Buffer font size |
| `ui.font.family` | empty | string | UI font family; empty retains the system UI font |
| `ui.font.size` | `0` | int | UI font size; `0` retains each system UI default size |
| `terminal.font.family` | `Monospaced` | string | Terminal font family |
| `terminal.font.size` | `14` | int | Terminal font size |
| `snippets.directory` | `~/.shed/snippets` | path | User snippet directory; applies immediately |
| `tab.size` | `4` | int | Tab width (`:set ts=` command clamps to `1..16`) |
| `line.numbers` | `absolute` | enum | `none`, `absolute`, `relative`, `relativeabsolute` (`hybrid` alias supported) |
| `show.current.line` | `true` | bool | Highlight active line |
| `expand.tab` | `true` | bool | Insert spaces for tab input |
| `auto.indent` | `true` | bool | Auto-indent on newline |
| `highlight.search` | `true` | bool | Search result highlighting |
| `list` | `false` | bool | Whitespace visualization |
| `ruler.column` | `0` | int | Draw vertical ruler at column (`0` disables) |
| `scrolloff` | `0` | int | Keep cursor context while scrolling |
| `textwidth` | `0` | int | Paragraph formatting width (`0` disables) |
| `auto.pairs` | `true` | bool | Auto-pair brackets/quotes |
| `zen.mode.width` | `80` | int | Goyo content width in columns |
| `minimap` | `false` | bool | Stored key; minimap visibility is currently controlled by `:minimap` |
| `minimap.width` | `84` | int | Minimap width in pixels; minimum `40` |
| `limelight.coefficient` | `0.5` | double | Dim strength for non-focused paragraphs; `0.0..1.0` |
| `limelight.paragraph.span` | `0` | int | Adjacent paragraphs retained at full brightness |
| `multi.selection.enabled` | `false` | bool | Enable experimental multi-selection editing |
| `multi.selection.max.cursors` | `16` | int | Maximum total cursors when enabled; `2..256` |
| `markdown.preview.scroll.sync` | `true` | bool | Keep a Markdown preview aligned with its source cursor and source scrolling |
| `landing.source` | `~/.shed/landing.md` | string | Local path, `file:` URI, or explicitly configured HTTPS URL |
| `landing.remote.cache.path` | `~/.shed/landing.remote.md` | path | Local editable cache for an HTTPS source |
| `landing.remote.timeout.ms` | `5000` | int | HTTPS source timeout; `1000..30000` |

## Landing Page

At startup without a file argument or restored session, Shed opens `landing.source` as a normal file buffer. The default `~/.shed/landing.md` is created with the startup text on its first use, then can be edited and saved like any other file. Relative paths resolve from the user home directory; `file:` URIs are local paths.

An `https://` `landing.source` is an explicit global opt-in to fetch that URL when the landing page opens. Shed uses HTTPS only, never follows redirects, limits the response to 1 MiB, and caches it in `landing.remote.cache.path`; edits save to that local cache and are never uploaded. Project `.shed.toml` cannot override landing settings.

## Session, File, and Shell Limits

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `session.restore.on.start` | `false` | bool | Restore session/workspace on launch |
| `session.autoload` | `default` | string | Session name used when autoloading |
| `session.dir` | `~/.shed/sessions` | path | Session storage directory |
| `terminal.session.restore` | `false` | bool | Persist terminal pane cwd and restore a fresh interactive shell; never stores or replays commands, scrollback, shell arguments, or process state |
| `terminal.shell.integration` | `true` | bool | Enable generated, per-terminal Bash/Zsh/Fish command and cwd hooks; affects newly opened terminals only |
| `workspace.index.enabled` | `false` | bool | Enable persisted Git-ignore-aware workspace indexing |
| `large.file.threshold.mb` | `25` | int | Selects read-only large-file mode above this MiB value; effective minimum `1` |
| `large.file.line.threshold` | `500000` | int | Selects read-only large-file mode above this logical-line count; effective minimum `1000` |
| `large.file.preview.lines` | `1000` | int | Initial large-file preview lines; effective minimum `50` |
| `process.timeout.ms` | `15000` | int | Async shell/LSP helper timeout |
| `process.output.max.bytes` | `1048576` | int | Max captured process output bytes |
| `shell.command.enabled` | `true` | bool | Enable `:!` shell commands |
| `shell.command.max.length` | `4096` | int | Max accepted shell command length |

## LSP Feature Settings

LSP feature keys appear in `:config inspector` under **Language Server**. Capability and initialization settings require `:lsp restart [ext]` for an existing server; the completion interaction controls below apply to the next request. `true` permits a server-advertised request and `false` prevents Shed from invoking it; the snippets key instead controls the client capability advertised at initialization. These toggles do not create network access, and diagnostics remain stored locally.

Open **Language Services** in Settings or run `:lsp manage` for local detection and managed installs. Nothing downloads, installs, updates, or changes LSP configuration on startup, file open, detection, or a command alone: every install/update requires a fresh **Yes** in the panel's review dialog. Java uses the SHA-256-pinned Eclipse JDT LS archive, which Shed verifies before extraction under `~/.shed/managed-languages/`. Pyright, TypeScript/JavaScript, JSON, HTML/CSS, and Markdown use exact npm package versions in the same cache, never a global npm installation; their review discloses that dependency integrity is recorded in the local npm lockfile rather than verified against an independently published archive checksum. npm lifecycle scripts, audit, funding, and update notifications are disabled. Shed writes the managed launcher and required arguments for every extension covered by the selected service, then restarts those LSP clients. gopls, rust-analyzer, and clangd remain user-managed because their official paths depend on the user's Go/Rust/LLVM toolchains. Full ownership and cache policy: [Managed Language Support Trust Model](MANAGED_LANGUAGE_SUPPORT.md).

Shed keeps an independent client for each `(extension, workspace root)` pair, so files from separate projects do not replace each other's server. A server that advertises incremental document synchronization receives debounced range changes; others receive a full-document update for compatibility. Semantic tokens and inlay hints render automatically when supported, with inline rendering controlled separately below. Document symbols use an on-demand LSP request with local fallback. `:workspace symbols <query>` first queries usable active LSP servers; if none produces a result, it performs one explicit asynchronous local lexical scan across workspace folders. The scan respects the workspace ignore filter, reads only regular UTF-8 files at most 2 MiB each, returns at most 300 matches, and either revalidates the existing opt-in workspace file index or scans without persisting it. It does not build a background symbol index, parse source structurally, or replace LSP symbols when an LSP result exists.

`remote.lsp.enabled` is a global opt-in for a connected SSH, Docker-container, or WSL workspace, or for an already running local Dev Container. It requires an explicit global `lsp.<ext>.command` for a server already installed in that remote environment. Shed starts that one direct-argv server through the provider and translates only files under the local mirror to its remote `file:` URIs; for a Dev Container it first asks the user-installed CLI for the mounted workspace path. Run `:lsp restart <ext>` after changing this setting; a live client retains the URI mode it started with. It does not install a remote server, run managed local artifacts remotely, bootstrap a host, or make test or extension hosts remote. An SSH login banner on stdout corrupts the LSP stream and must be disabled in the user's SSH setup.

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `lsp.completion.enabled` | `true` | bool | Completion requests |
| `lsp.completion.auto.show` | `true` | bool | Show suggestions while typing; applies live |
| `lsp.completion.delay.ms` | `90` | integer `0..1000` | Idle debounce before automatic suggestions; applies live |
| `lsp.completion.trigger.characters` | `true` | bool | Request completion after a server-advertised trigger character; applies live |
| `lsp.completion.fuzzy.matching` | `true` | bool | Fuzzy-filter and rank completion labels; applies live |
| `lsp.completion.local.words` | `true` | bool | Include cached words from open buffers as a fallback; applies live |
| `lsp.completion.commit.characters` | `true` | bool | Accept a server completion when its commit character is typed; applies live |
| `lsp.snippets.enabled` | `false` | bool | Advertises snippet-completion support during initialization |
| `lsp.signature.help.enabled` | `true` | bool | Signature-help requests |
| `lsp.hover.enabled` | `true` | bool | Hover requests |
| `lsp.semantic.tokens.enabled` | `true` | bool | Semantic-token requests |
| `lsp.inlay.hints.enabled` | `true` | bool | Inlay-hint requests |
| `lsp.semantic.tokens.inline` | `true` | bool | Render supported semantic-token colours inline; applies immediately |
| `lsp.inlay.hints.inline` | `true` | bool | Render supported inlay hints inline; applies immediately |
| `lsp.definition.enabled` | `true` | bool | Navigation: definition requests |
| `lsp.type.definition.enabled` | `true` | bool | Navigation: type-definition requests |
| `lsp.call.hierarchy.enabled` | `true` | bool | Navigation: call-hierarchy requests |
| `lsp.type.hierarchy.enabled` | `true` | bool | Navigation: type-hierarchy requests |
| `lsp.references.enabled` | `true` | bool | Navigation: reference requests |
| `lsp.rename.enabled` | `true` | bool | Rename requests |
| `lsp.code.actions.enabled` | `true` | bool | Actions: code-action requests |
| `lsp.command.execution.enabled` | `true` | bool | Actions: execute-command requests |
| `lsp.formatting.enabled` | `true` | bool | Document-formatting requests |
| `lsp.format.on.save.enabled` | `false` | bool | Default format-on-save policy for extensions without an override; an unavailable, failed, or stale formatting request leaves the buffer unsaved |
| `remote.lsp.enabled` | `false` | bool | Globally opt in to explicitly configured LSP servers running through connected SSH/container/WSL workspaces or an already running Dev Container; project configuration cannot enable it |

## Formatter policy

Each extension defaults to `lsp`. Use `:formatter` for the active extension or set the dynamic keys below. `external` launches the configured command with direct argv only, sends the buffer on stdin, reads formatted stdout, uses the workspace as its working directory, and never invokes a shell. `${file}` is permitted as one complete argument. `disabled` skips formatting for that extension. `formatter.<ext>.format.on.save` overrides `lsp.format.on.save.enabled` for that extension; formatting runs asynchronously and an error or stale buffer leaves the file unwritten.

## Snippets

Use **Open Snippets** in Settings or `:snippets open` to create and open `snippets.json`. Snippets reload when the completion menu, `Ctrl-j`, or `:snippets` next uses them; no restart is needed.

`snippets.json` and `*.code-snippets` are global. A lowercase language file scopes snippets to that language (`java.json`, `python.json`, `typescript.json`, `markdown.json`); a snippet's optional comma-separated `scope` overrides that file scope. Shed accepts VS Code-shaped `prefix` (string or array), `body` (string or array), and `description` fields.

```json
{
  "Print value": {
    "prefix": "log",
    "body": ["System.out.println(${1:value});", "$0"],
    "description": "Print a value"
  }
}
```

Suggestions appear after two word characters, after an LSP server's advertised trigger character, or when `Ctrl-n` is pressed manually. Shed keeps available language-server results ahead of native snippets and only shows open-buffer words when the server is unavailable, fails, or returns no matches; the popup identifies that source and shows the selected item's signature plus documentation beside the list. Local Java extensions may add bounded snippets for a language profile or LSP language id; user snippets take precedence over extension snippets, then built-ins. Shed uses server `filterText`, `sortText`, preselection, commit characters, label details, and lazy completion-detail resolution where offered; open-buffer word snapshots are built off the UI thread. Use `Ctrl-n` to select snippets alongside local and LSP completions, or type an exact trigger and press `Ctrl-j`. `$1` / `${1:default}` create ordered editable stops and `$0` is final; built-in variables include `TM_SELECTED_TEXT`, `TM_CURRENT_LINE`, `TM_CURRENT_WORD`, line-number, filename, path, directory, and workspace-name values. Choice syntax inserts its first value; transforms, shell interpolation, and linked mirrored placeholders are not supported.

An extension `LanguageProfile` is lexical-only unless that same extension also registers a `LanguageContribution` with the identical language id. In that case automatic detection or `:language` routes the buffer to that contribution's server and restarts clients for the affected workspace; it never invents a server command for a profile-only language.

## Syntax Highlighting

Shed uses cached, stateful lexical grammars for Java, Kotlin, C#, PHP, Ruby, Swift, Python, JavaScript/TypeScript, Go, Rust, C/C++, HTML, CSS, JSON, Markdown, YAML, TOML, SQL, and shell files. It highlights a buffer when it becomes active and refreshes once after its document enters the viewport; it then re-lexes from the changed line and reuses an unchanged suffix once lexical state stabilizes, so strings and block comments no longer leak keyword highlighting. HTML injects JavaScript in `<script>` and CSS in `<style>`; Markdown injects known fenced-code languages. Kotlin, C#, PHP, Ruby, and Swift have lexical comments, strings, keywords, and cautious local outlines, but no bundled grammar, managed language runtime, or built-in LSP launcher. A user-configured `lsp.<ext>.command` can still use their recognized LSP language identifier. YAML, TOML, SQL, and shell recognition is likewise lexical only, with cautious local outline heuristics, and does not configure an LSP. LSP semantic tokens render on top when a server supports them.

This is lexical parsing, not a full compiler parser: custom grammar packs, macro expansion, and template-expression parsing are out of scope. Highlighting runs asynchronously for editable files and remains disabled only in read-only large-file mode.

## Recovery Journal Policy

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `recovery.retention.max.entries` | `32` | int | Retained recovery entries; `1..32` |
| `recovery.retention.max.content.bytes` | `8388608` | int | Retained UTF-8 recovery content bytes; `1..8388608` |
| `recovery.cleanup.on.clean.exit` | `true` | bool | Remove the journal only on a clean exit without deferred recovery |

## Backup Policy

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `backup.enabled` | `false` | bool | Opt in to local versioned backups |
| `backup.mode` | `idle` | enum | `idle` writes after 750 ms inactivity and flushes on exit; `save-only` writes before explicit saves |
| `backup.directory` | `~/.shed/backups` | path | Directory for versioned backups; created on first backup |
| `backup.retention.count` | `10` | int | Retained backups per source file; `1..100` |

## Project Replace Policy

| Key | Default | Type | Notes |
| :--- | :--- | :--- |
| `project.replace.enabled` | `false` | bool | Opt in before project replacement commands run |
| `project.replace.preview.required` | `true` | bool | Require preview; `false` enables the explicit non-preview command |
| `project.replace.confirm.required` | `true` | bool | Require `:projectreplace apply confirm` |
| `project.replace.backup.enabled` | `true` | bool | Retain original content before each changed file is written |
| `project.replace.backup.directory` | `~/.shed/project-replace-backups` | path | Directory for retained replacement backups |
| `project.replace.scope` | `workspace` | enum | `workspace` or `current-file` |

## Git Workbench

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `git.workbench.enabled` | `true` | bool | Existing top-level gate for `:git workbench`; keep enabled with `git.changes.enabled` for the changes document |
| `git.changes.enabled` | `true` | bool | Enables the graphical changes document opened by `:git workbench` |
| `git.diffs.enabled` | `true` | bool | Enables diff loading and hunk navigation inside the graphical changes document |
| `git.staging.enabled` | `true` | bool | Enables `:git add`, `:git stage`, `:git restore`, `:git unstage`, and hunk stage/unstage actions |
| `git.conflict.resolution.enabled` | `true` | bool | Enables `:git conflict`, an explicit conflict-resolution document with preserved Git sides |
| `git.history.enabled` | `true` | bool | Enables `:git history`, an asynchronous local-history document |
| `git.remote.actions.enabled` | `true` | bool | Enables explicit Fetch, Pull (fast-forward only), and Push controls in `:git history` |
| `git.panel.presentation.enabled` | `true` | bool | Enables all graphical Git documents while leaving command-mode Git commands available |
| `git.auto.refresh.enabled` | `true` | bool | Refresh the visible Git Changes panel in the background when its status changes |
| `git.auto.refresh.interval.ms` | `1500` | int | Visible-panel poll interval; `500`–`60000` ms |

The changes document is effective only when `git.workbench.enabled`, `git.changes.enabled`, and `git.panel.presentation.enabled` are all `true`. Diff controls require `git.diffs.enabled`; direct `:git diff` remains available. Conflict and history documents each require their own key plus `git.panel.presentation.enabled`; direct `:git log` remains available. Remote controls further require `git.remote.actions.enabled` after the history document opens. `git.staging.enabled` blocks only index-mutating staging and unstaging commands, including hunk stage/unstage; commit, checkout, switch, and hunk revert retain their existing command-mode behaviour. `git.panel.presentation.enabled=false` blocks graphical documents only.

`:git history` never starts network activity when opened or refreshed. Fetch requires its button click; Pull uses `git pull --ff-only` and Push each require a second confirmation. Each remote operation runs in a cancellable background job, exposes captured output or failure in the document, and can be disabled independently with `git.remote.actions.enabled`.

`:git worktrees` and `:git stash` open the local repository tools. Creating/removing a linked worktree, creating a stash, applying/popping a stash, and dropping a stash each require explicit confirmation; no force-remove or force-drop path is exposed.

## GitHub Review Integration

See [Network and telemetry boundary](NETWORK_PRIVACY.md) for the complete app-owned outbound-path audit, including explicit plugin updates, Git remotes, loopback debug adapters, browser delegation, and user-controlled child processes.

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `github.review.enabled` | `false` | bool | Requests explicit GitHub review actions; effective only with granted consent |
| `github.review.consent.granted` | `false` | bool | Consent receipt written by `:github consent`; clearing it disables review integration |

`:github status` is explicit and asynchronous. It checks the user-installed `gh` version, local authentication state, local origin URL, and local help for `gh pr list`, `gh pr view`, and `gh api`; it makes no GitHub API request and never changes Git state. `:github consent` presents the reviewable first-use consent before persisting both flags. `:github disable` clears both flags, revoking consent. Review integration is ineffective unless both values are `true`.

`:github prs` is an explicit, cancellable, read-only network request permitted only after consent. It runs `gh pr list --repo OWNER/REPO --state open` against the current local origin and never invokes a write subcommand.

Selecting **View Details and Diff** in that workspace explicitly runs read-only `gh pr view` and `gh pr diff` for the selected pull request. Metadata, changed-file names, and patch output are rejected if malformed or truncated; review actions remain unavailable.

The **Local Unsent Draft** tab creates and edits a local review-comment draft bound to the displayed `OWNER/REPO` and pull-request number. **Save Local Draft** persists it in `~/.shed/github-review-drafts-v1.json`; **Discard Local Draft** removes only that target's local draft. Neither action invokes `gh`, creates server-side state, or submits a review. **Submit Review…** requires a final confirmation, then explicitly runs one `gh pr review` command using the selected Comment, Approve, or Request changes action. Its result tab preserves the exact captured `gh` output; an acknowledged review removes its draft and records a local fingerprint to block duplicate resubmission. Failed or unacknowledged attempts retain the draft and never retry automatically.

GitHub review failures are classified as authentication, rate limit, permission, network, CLI-version, malformed-output, or unknown. Each result states whether a retry is safe, the remediation, and a non-GitHub fallback: local inspection with `:git workbench`, `:git diff`, or `:git log`. Uncertain network/write outcomes require server-state verification before any manual retry.

## Update Checks

Update checks require a global consent receipt, HTTPS metadata endpoint, and Base64 Ed25519 public key. Project-local config cannot override any `updates.*` setting. A check validates a signed, bounded metadata response and only opens a verified installer URL in the system browser when explicitly requested; Shed never downloads, installs, or replaces itself. See [Update Checks](UPDATES.md) for the exact metadata format, error handling, rollback boundary, and unsigned-development limitation.

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `updates.enabled` | `false` | bool | Requests automatic checks; ineffective without consent |
| `updates.consent.granted` | `false` | bool | Consent receipt; clearing it revokes future checks |
| `updates.metadata.url` | empty | string | HTTPS signed-metadata endpoint; empty blocks requests |
| `updates.metadata.public.key` | empty | string | Base64 Ed25519 SubjectPublicKeyInfo verification key |
| `updates.check.timeout.ms` | `5000` | int | Request timeout; `1000..30000` |

## Debug Adapter Protocol

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `debug.enabled` | `false` | bool | Enables explicit debug sessions; no process starts until `:debug start` |
| `debug.breakpoints.enabled` | `true` | bool | Enables breakpoint configuration when a session exists |
| `debug.threads.enabled` | `true` | bool | Enables thread presentation when adapter-supported |
| `debug.stacktrace.enabled` | `true` | bool | Enables stack-trace presentation when adapter-supported |
| `debug.scopes.enabled` | `true` | bool | Enables scope presentation when adapter-supported |
| `debug.variables.enabled` | `true` | bool | Enables variable presentation when adapter-supported |
| `debug.evaluate.enabled` | `true` | bool | Enables expression evaluation when adapter-supported |
| `debug.attach.enabled` | `true` | bool | Enables attach planning when adapter-supported |
| `debug.open.source.on.stop` | `true` | bool | Opens the selected existing local source frame after a paused-frame inspection; disable to keep navigation manual |

Shed includes a Python-only `python-debugpy` profile for a separately installed `debugpy-adapter` executable; it does not bundle or download that adapter. See [DAP Architecture](DAP.md) for the adapter registry, workspace-safe launch/attach schema, and capability declarations.

## Undo History Policy

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `undo.history.max.entries` | `500` | int | Retained undo/redo edits per buffer; `1..100000` |
| `undo.history.max.bytes` | `8388608` | int | Estimated retained UTF-16 edit payload bytes per buffer; `1..1073741824` |

## Keymap

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `keymap.profile` | `vim` | string | `vim`, non-modal `plain`, or chorded `emacs`; non-Vim profiles bypass `keybind.<mode>.*` |

## Focus and Command UI Keys

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `ui.whichkey.hints` | `true` | bool | Prefix-key hint display |

## Safety and Project-Local Keys

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `project.config.enabled` | `true` | bool | Enable `.shed.toml` loading |
| `project.config.allow.unsafe` | `false` | bool | Allow unsafe local keys (`command.user.*`, `keybind.*`, etc.) |
| `project.config.require.trusted.file` | `true` | bool | Require trusted owner/permissions for `.shed.toml` |
| `tree.delete.protect.critical` | `true` | bool | Blocks deleting filesystem root, home, and cwd via `:tree rm` |

When `project.config.allow.unsafe=false`, project-local config only applies:
- `theme`
- `tab.size`
- `line.numbers`
- `show.current.line`
- `expand.tab`
- `auto.indent`
- `highlight.search`
- `scrolloff`
- `textwidth`
- `list`
- `conceallevel`
- `ruler.column`
- `minimap`
- Any key under `ui.*`, `color.*`, `font.*`

With unsafe project keys enabled and the `.shed.toml` file trusted, `debug.*` settings, `debug.adapter.*`, and `debug.configuration.*` declarations are additionally resolved per workspace root for Debug and Tests actions. They merge with global DAP declarations for that one root, and their feature flags are captured when a session starts; a configuration in one multi-root folder cannot appear in or alter a sibling root. This does not import `launch` data from a `.code-workspace` file or create VS Code configuration compatibility.

## Dynamic Namespaced Keys

| Key Pattern | Purpose | Example |
| :--- | :--- | :--- |
| `command.alias.<name>` | Ex-command alias to built-in command | `"command.alias.ww" = "w"` |
| `command.user.<name>` | User shell command callable as `:<name>` | `"command.user.build" = "make -j4"` |
| `keybind.<scope>.<lhs>` | Validated Vim keymap overlay | `"keybind.normal.H" = "^"` |
| `lsp.<ext>.command` | LSP server command for extension | `"lsp.py.command" = "pyright-langserver"` |
| `lsp.<ext>.args` | LSP server args | `"lsp.py.args" = "--stdio"` |
| `formatter.<ext>.mode` | `lsp`, `external`, or `disabled` formatter selection | `"formatter.py.mode" = "external"` |
| `formatter.<ext>.command` | Direct external formatter executable | `"formatter.py.command" = "ruff"` |
| `formatter.<ext>.args` | Quoted direct argv text; `${file}` allowed | `"formatter.py.args" = "format -"` |
| `formatter.<ext>.format.on.save` | Per-extension format-on-save override | `"formatter.py.format.on.save" = true` |
| `debug.adapter.<id>.<field>` | User-managed DAP adapter (`command`, `args`, `transport`, `capabilities`) | `"debug.adapter.java.command" = "java-debug-adapter"` |
| `debug.configuration.<name>.<field>` | DAP launch/attach configuration; a launch chooses exactly one `program`, `module`, or `code` target (`file_extensions` applies only to `program`; `prelaunch_task` names a workspace `.shedtasks` task) | `"debug.configuration.main.request" = "launch"` |

Supported keybind scopes: `normal`, `insert`, `visual`, `visual_line`, `replace`, `command`, `search`, `global`.

Common key tokens: `<esc>`, `<enter>`, `<tab>`, `<space>`, `<bs>`, `<del>`, `<up>`, `<down>`, `<left>`, `<right>`, `<c-x>`, `<lt>`.

Use `<nop>` as RHS to disable a key.

Overlays apply only while `keymap.profile = "vim"`. Scope-specific overlays take precedence over `global` for that mode; `global` remains active in all other Vim modes. Plain and Emacs fixed bindings intentionally bypass these Vim overlays. Invalid scope, LHS token, or RHS token rejects the setting without changing active bindings. Use `:keymap`, `:keymap list [query]`, `:keymap set <scope> <lhs>=<rhs>`, or `:keymap reset <scope> <lhs>` for the searchable GUI/list, persisted edits, and reset.

## Theme and Palette Keys

### Mode colors

| Key | Meaning |
| :--- | :--- |
| `color.normal` | Normal mode background |
| `color.insert` | Insert mode background |
| `color.command` | Command/search mode background |
| `color.visual` | Visual mode background |
| `color.replace` | Replace mode background |

### UI and syntax palette overrides

| Key | Meaning |
| :--- | :--- |
| `ui.foreground` | Main editor foreground |
| `ui.caret` | Caret color |
| `ui.selection` | Selection background |
| `ui.selection.text` | Selection foreground |
| `ui.status.background` | Status bar background |
| `ui.status.foreground` | Status bar foreground |
| `ui.command.background` | Command bar background |
| `ui.command.foreground` | Command bar foreground |
| `ui.linenumber.background` | Gutter background |
| `ui.linenumber.foreground` | Inactive gutter text |
| `ui.linenumber.active` | Active line-number color |
| `ui.currentline` | Current-line highlight |
| `ui.substitute.preview` | Substitute-preview highlight |
| `ui.syntax.keyword` | Syntax keyword color |
| `ui.syntax.string` | Syntax string color |
| `ui.syntax.comment` | Syntax comment color |
| `ui.syntax.type` | Syntax type color |
| `ui.syntax.function` | Syntax function color |
| `ui.syntax.constant` | Syntax constant color |
| `ui.syntax.annotation` | Syntax annotation color |
| `ui.syntax.number` | Syntax number color |

Color values should be hex (`#RRGGBB` or `#RGB`).

## Built-in Themes

`one-dark-pro`, `dracula`, `material-theme`, `night-owl`, `ayu-mirage`, `monokai-pro`, `tokyo-night`, `nord`, `gruvbox-dark`, `shades-of-purple`, `palenight`, `catppuccin-mocha`, `github-dark`, `rose-pine`, `synthwave-84`, `cobalt2`, `andromeda`, `everforest-dark`, `kanagawa`, `poimandres`, `solarized-dark`, `noctis`, `oxocarbon-dark`, `vesper`, `sonokai`, `doom-one`, `horizon`, `papercolor-dark`, `xcode-dark`, `dimmed-monokai`, `fleet-dark`, `nightfox`.

## Example `~/.shed/config.toml`

```toml
schema_version = 1

# Editor
"theme" = "nightfox"
"font.family" = "Monospaced"
"font.size" = 16
"ui.font.family" = ""
"ui.font.size" = 0
"terminal.font.family" = "Monospaced"
"terminal.font.size" = 14
"snippets.directory" = "~/.shed/snippets"
"tab.size" = 4
"line.numbers" = "relative"
"show.current.line" = true
"expand.tab" = true
"auto.indent" = true
"highlight.search" = true
"scrolloff" = 3
"textwidth" = 88
"ruler.column" = 88
"markdown.preview.scroll.sync" = true

# Landing page
"landing.source" = "~/.shed/landing.md"
# "landing.source" = "https://example.com/start.md"
# "landing.remote.cache.path" = "~/.shed/landing.remote.md"

# Session + safety
"session.restore.on.start" = true
"session.autoload" = "work"
"tree.delete.protect.critical" = true

# Shell/process limits
"process.timeout.ms" = 20000
"process.output.max.bytes" = 2097152
"shell.command.max.length" = 4096

# LSP override
"lsp.py.command" = "pyright-langserver"
"lsp.py.args" = "--stdio"
"lsp.snippets.enabled" = true # restart the Python LSP client to apply

# Aliases + keybinds
"command.alias.ww" = "w"
"keybind.normal.H" = "^"
"keybind.insert.<c-s>" = "<esc>:w<enter>"

# Non-modal profile
"keymap.profile" = "plain"

# Emacs profile
"keymap.profile" = "emacs"

# Palette override
"ui.caret" = "#7AA2F7"
"ui.currentline" = "#202738"
```

## Notes

- `wrap` and `conceallevel` are command-level features (`:set wrap`, `:conceal`) rather than startup-applied TOML defaults.
- `:config save` persists runtime differences from built-in defaults, not a full expanded template.
- `.shed.toml` is applied per project root and is cleared automatically when switching out of that project scope. Permitted DAP declarations are separately resolved from the target workspace root, so a Tests action does not depend on whichever file is active in another root.
