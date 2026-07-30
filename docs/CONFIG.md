# `Shed` Configuration

This is the complete TOML configuration reference for `Shed`.

## Config Location

| Path | Purpose |
| :--- | :--- |
| `~/.shed/config.toml` | Main user config file loaded at startup |
| `~/.shed/plugins/` | User plugin directory (`.shed` + `.lua`) |
| `~/.shed/sessions/` | Saved session/workspace data (default) |
| `.shed.toml` | Optional per-project override file (nearest parent directory) |

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
| `:settings`, `:config` | Open `~/.shed/config.toml` |
| `:set key=value` | Set runtime value only |
| `:set! key=value` | Set and persist one key to disk |
| `:config save` / `:config write` | Persist current runtime config |
| `:config defaults` | Create a complete commented default config only when no config exists |
| `:config! defaults` | Confirm replacement with a complete commented default config |
| `:config inspector` | Open the typed settings inspector; search by key or description, select a category, edit a value, or reset the selected setting to its default |
| `:config reset <key>` | Reset one typed setting to its canonical default and remove its global TOML override |
| `:config reference`, `:help settings` | Open generated typed-setting help with its description, allowed values, default, and live/restart behaviour |
| `:config status` | Show current config load/recovery details |
| `:reload` / `:source` | Reload config from disk |

The inspector and generated reference derive each typed setting's identifier, description, allowed values, default, and behaviour from one runtime metadata source.

## Core Editor Keys

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `theme` | `one-dark-pro` | string | Built-in theme id |
| `font.family` | `Hack` | string | Falls back to bundled Hack, then `Monospaced` |
| `font.size` | `16` | int | Editor font size |
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
| `zen.mode.width` | `80` | int | Preferred zen-mode content width |
| `minimap` | `false` | bool | Stored key; minimap visibility is currently controlled by `:minimap` |
| `multi.selection.enabled` | `false` | bool | Enable experimental multi-selection editing |
| `multi.selection.max.cursors` | `16` | int | Maximum total cursors when enabled; `2..256` |

## Session, File, and Shell Limits

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `session.restore.on.start` | `false` | bool | Restore session/workspace on launch |
| `session.autoload` | `default` | string | Session name used when autoloading |
| `session.dir` | `~/.shed/sessions` | path | Session storage directory |
| `workspace.index.enabled` | `false` | bool | Enable persisted Git-ignore-aware workspace indexing |
| `large.file.threshold.mb` | `100` | int | Selects large-file mode above this MiB value; effective minimum `1` |
| `large.file.line.threshold` | `50000` | int | Selects large-file mode above this logical-line count; effective minimum `1000` |
| `large.file.preview.lines` | `1000` | int | Initial large-file preview lines; effective minimum `50` |
| `process.timeout.ms` | `15000` | int | Async shell/LSP helper timeout |
| `process.output.max.bytes` | `1048576` | int | Max captured process output bytes |
| `shell.command.enabled` | `true` | bool | Enable `:!` shell commands |
| `shell.command.max.length` | `4096` | int | Max accepted shell command length |

## LSP Feature Settings

All LSP feature keys are typed booleans and appear in `:config inspector` under **Language Server**. Changing one writes/reloads immediately, but its effective capability state is negotiated when that language server starts; run `:lsp restart [ext]` for an existing server. `true` permits a server-advertised request and `false` prevents Shed from invoking it; the snippets key instead controls the client capability advertised at initialization. These toggles do not create network access, and diagnostics remain stored locally.

Managed language support has no configuration key or network path yet. Its planned ownership, consent, catalog, integrity, cache, platform, and revocation policy is documented in [Managed Language Support Trust Model](MANAGED_LANGUAGE_SUPPORT.md); existing `lsp.<ext>.command` and `lsp.<ext>.args` remain user-managed local commands. Java resolves locally through `jdtls` by default and Python through `pyright-langserver --stdio`; set `lsp.java.command` or `lsp.py.command` when an executable or runtime differs.

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `lsp.completion.enabled` | `true` | bool | Completion requests |
| `lsp.snippets.enabled` | `false` | bool | Advertises snippet-completion support during initialization |
| `lsp.signature.help.enabled` | `true` | bool | Signature-help requests |
| `lsp.hover.enabled` | `true` | bool | Hover requests |
| `lsp.semantic.tokens.enabled` | `true` | bool | Semantic-token requests |
| `lsp.inlay.hints.enabled` | `true` | bool | Inlay-hint requests |
| `lsp.definition.enabled` | `true` | bool | Navigation: definition requests |
| `lsp.references.enabled` | `true` | bool | Navigation: reference requests |
| `lsp.rename.enabled` | `true` | bool | Rename requests |
| `lsp.code.actions.enabled` | `true` | bool | Actions: code-action requests |
| `lsp.command.execution.enabled` | `true` | bool | Actions: execute-command requests |
| `lsp.formatting.enabled` | `true` | bool | Document-formatting requests |

## Recovery Journal Policy

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `recovery.retention.max.entries` | `32` | int | Retained recovery entries; `1..32` |
| `recovery.retention.max.content.bytes` | `8388608` | int | Retained UTF-8 recovery content bytes; `1..8388608` |
| `recovery.cleanup.on.clean.exit` | `true` | bool | Remove the journal only on a clean exit without deferred recovery |

## Backup Policy

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `backup.enabled` | `true` | bool | Create local versioned backups while editing |
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
| `git.workbench.enabled` | `true` | bool | Enables `:git workbench`, a read-only asynchronous repository-status, diff, and hunk-navigation view |
| `git.conflict.resolution.enabled` | `true` | bool | Enables `:git conflict`, an explicit conflict-resolution document with preserved Git sides |
| `git.history.enabled` | `true` | bool | Enables `:git history`, an asynchronous local-history document |
| `git.remote.actions.enabled` | `true` | bool | Enables explicit Fetch, Pull (fast-forward only), and Push controls in `:git history` |

`:git history` never starts network activity when opened or refreshed. Fetch requires its button click; Pull uses `git pull --ff-only` and Push each require a second confirmation. Each remote operation runs in a cancellable background job, exposes captured output or failure in the document, and can be disabled independently with `git.remote.actions.enabled`.

## Undo History Policy

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `undo.history.max.entries` | `500` | int | Retained undo/redo edits per buffer; `1..100000` |
| `undo.history.max.bytes` | `8388608` | int | Estimated retained UTF-16 edit payload bytes per buffer; `1..1073741824` |

## Dramatic UI / Theater Keys

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `ui.dramatic` | `false` | bool | Master dramatic UI toggle |
| `ui.dramatic.identity` | `true` | bool | Brand/identity accents |
| `ui.dramatic.mode.transitions` | `true` | bool | Mode transition effects |
| `ui.dramatic.command.palette` | `true` | bool | Palette animation treatment |
| `ui.dramatic.editing.feedback` | `true` | bool | Editing feedback effects |
| `ui.dramatic.panel.animations` | `true` | bool | Panel animation toggle |
| `ui.dramatic.sound` | `false` | bool | Sound cues master toggle |
| `ui.dramatic.sound.pack` | `default` | string | Active sound pack |
| `ui.dramatic.sound.volume` | `75` | int | Clamped to `0..100` |
| `ui.dramatic.sound.cue.mode` | `true` | bool | Mode cue enable |
| `ui.dramatic.sound.cue.navigate` | `true` | bool | Navigation cue enable |
| `ui.dramatic.sound.cue.success` | `true` | bool | Success cue enable |
| `ui.dramatic.sound.cue.error` | `true` | bool | Error cue enable |
| `ui.dramatic.reduced.motion` | `false` | bool | Force reduced motion |
| `ui.dramatic.reduced.motion.sync` | `true` | bool | Sync reduced motion with OS/env hints |
| `ui.dramatic.performance.guardrails` | `true` | bool | Runtime performance safety checks |
| `ui.dramatic.performance.cpu.threshold` | `0.80` | double | Runtime-clamped to `0.1..1.0` |
| `ui.dramatic.performance.line.threshold` | `20000` | int | Runtime minimum `1000` |
| `ui.dramatic.animation.ms` | `220` | int | Runtime minimum `80` |
| `ui.dramatic.minimap.width` | `84` | int | Runtime minimum `40` |
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

## Dynamic Namespaced Keys

| Key Pattern | Purpose | Example |
| :--- | :--- | :--- |
| `command.alias.<name>` | Ex-command alias to built-in command | `"command.alias.ww" = "w"` |
| `command.user.<name>` | User shell command callable as `:<name>` | `"command.user.build" = "make -j4"` |
| `keybind.<mode>.<lhs>` | Key remap by mode | `"keybind.normal.H" = "^"` |
| `lsp.<ext>.command` | LSP server command for extension | `"lsp.py.command" = "pyright-langserver"` |
| `lsp.<ext>.args` | LSP server args | `"lsp.py.args" = "--stdio"` |

Supported keybind modes: `normal`, `insert`, `visual`, `visual_line`, `replace`, `command`, `search`, `global`.

Common key tokens: `<esc>`, `<enter>`, `<tab>`, `<space>`, `<bs>`, `<del>`, `<up>`, `<down>`, `<left>`, `<right>`, `<c-x>`, `<lt>`.

Use `<nop>` as RHS to disable a key.

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
"font.family" = "Hack"
"font.size" = 16
"tab.size" = 4
"line.numbers" = "relative"
"show.current.line" = true
"expand.tab" = true
"auto.indent" = true
"highlight.search" = true
"scrolloff" = 3
"textwidth" = 88
"ruler.column" = 88

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

# Palette override
"ui.caret" = "#7AA2F7"
"ui.currentline" = "#202738"
```

## Notes

- `wrap` and `conceallevel` are command-level features (`:set wrap`, `:conceal`) rather than startup-applied TOML defaults.
- `:config save` persists runtime differences from built-in defaults, not a full expanded template.
- `.shed.toml` is applied per project root and is cleared automatically when switching out of that project scope.
