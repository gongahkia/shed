# `Shed` Configuration

This is the complete `shedrc` configuration reference for `Shed`.

## Config Location

| Path | Purpose |
| :--- | :--- |
| `~/.shed/shedrc` | Main user config file loaded at startup |
| `~/.shed/plugins/` | User plugin directory (`.shed` + `.lua`) |
| `~/.shed/sessions/` | Saved session/workspace data (default) |
| `.shedrc.local` | Optional per-project override file (nearest parent directory) |

`Shed` will also migrate legacy config files from `~/.shedrc` or `~/.config/shed/shedrc` into `~/.shed/shedrc` when needed.

## File Format

| Rule | Details |
| :--- | :--- |
| Line format | `key=value` |
| Comments | Lines beginning with `#` |
| Empty lines | Ignored |
| Booleans | Use `true` / `false` (recommended) |
| Persistence | `:set! key=value` writes one key, `:config save` writes current runtime overrides |

## Runtime Commands

| Command | Behavior |
| :--- | :--- |
| `:settings`, `:shedrc`, `:config` | Open `~/.shed/shedrc` |
| `:set key=value` | Set runtime value only |
| `:set! key=value` | Set and persist one key to disk |
| `:config save` / `:config write` | Persist current runtime config |
| `:reload` / `:source` | Reload config from disk |

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

## Session, File, and Shell Limits

| Key | Default | Type | Notes |
| :--- | :--- | :--- | :--- |
| `session.restore.on.start` | `false` | bool | Restore session/workspace on launch |
| `session.autoload` | `default` | string | Session name used when autoloading |
| `session.dir` | `~/.shed/sessions` | path | Session storage directory |
| `large.file.threshold.mb` | `100` | int | Large-file detection size threshold |
| `large.file.line.threshold` | `50000` | int | Large-file detection line threshold |
| `large.file.preview.lines` | `1000` | int | Preview lines shown for large files |
| `process.timeout.ms` | `15000` | int | Async shell/LSP helper timeout |
| `process.output.max.bytes` | `1048576` | int | Max captured process output bytes |
| `shell.command.max.length` | `4096` | int | Max accepted shell command length |

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
| `project.config.enabled` | `true` | bool | Enable `.shedrc.local` loading |
| `project.config.allow.unsafe` | `false` | bool | Allow unsafe local keys (`command.user.*`, `keybind.*`, etc.) |
| `project.config.require.trusted.file` | `true` | bool | Require trusted owner/permissions for `.shedrc.local` |
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
| `command.alias.<name>` | Ex-command alias to built-in command | `command.alias.ww=w` |
| `command.user.<name>` | User shell command callable as `:<name>` | `command.user.build=make -j4` |
| `keybind.<mode>.<lhs>` | Key remap by mode | `keybind.normal.H=^` |
| `lsp.<ext>.command` | LSP server command for extension | `lsp.py.command=pyright-langserver` |
| `lsp.<ext>.args` | LSP server args | `lsp.py.args=--stdio` |

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

## Example `~/.shed/shedrc`

```ini
# Editor
theme=nightfox
font.family=Hack
font.size=16
tab.size=4
line.numbers=relative
show.current.line=true
expand.tab=true
auto.indent=true
highlight.search=true
scrolloff=3
textwidth=88
ruler.column=88

# Session + safety
session.restore.on.start=true
session.autoload=work
tree.delete.protect.critical=true

# Shell/process limits
process.timeout.ms=20000
process.output.max.bytes=2097152
shell.command.max.length=4096

# LSP override
lsp.py.command=pyright-langserver
lsp.py.args=--stdio

# Aliases + keybinds
command.alias.ww=w
keybind.normal.H=^
keybind.insert.<c-s>=<esc>:w<enter>

# Palette override
ui.caret=#7AA2F7
ui.currentline=#202738
```

## Notes

- `wrap` and `conceallevel` are command-level features (`:set wrap`, `:conceal`) rather than startup-applied `shedrc` defaults.
- `:config save` persists runtime differences from built-in defaults, not a full expanded template.
- `.shedrc.local` is applied per project root and is cleared automatically when switching out of that project scope.
