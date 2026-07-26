# Settings

Itsy reads user settings from:

```text
~/.config/itsy/settings.json
```

Workspace overrides are loaded from:

```text
<workspace>/.itsy/settings.json
```

Both files hot-reload while Itsy is running. JSON layers merge in global, workspace, then per-language editor order. Unknown fields are ignored; malformed, unsupported-version, or out-of-range documents retain the previous/default settings and report diagnostics.

Personal UI, update, and LSP provisioning settings are global-only: `ui`, `updates`, and `lsp` fields in a workspace document are ignored with a warning.

```json
{
  "schema_version": 12,
  "settings": {
    "editor": {
      "font": "Menlo",
      "font_size": 14.95,
      "line_number_mode": "absolute",
      "keymap": "plain",
      "cursor_style": "automatic",
      "wrap": "none",
      "wrap_column": 100,
      "tab_width": 4,
      "use_spaces": false,
      "language": {
        "python": {
          "tab_width": 4,
          "use_spaces": true
        }
      }
    },
    "theme": {
      "id": "bundled:default-light"
    },
    "terminal": {
      "font_size": 12,
      "scrollback_lines": 10000,
      "presentation": "bottom"
    },
    "updates": {
      "automatically_check": false
    },
    "workbench": {
      "profile": "workbench",
      "file_tree": "automatic",
      "terminal": "automatic",
      "git": "automatic"
    },
    "ui": {
      "font_scale": 1,
      "density": "regular",
      "notification_position": "bottom_right"
    }
  }
}
```

The terminal opens in the editor's bottom split by default; Git Changes and Debugger open in one tabbed right sidebar. Settings exposes Terminal, Git, and Debugger **Presentation** controls as well as the JSON fields. Set `terminal.presentation = "window"`, `git.presentation = "window"`, or `debugger.presentation = "window"` to detach a surface. The Debugger tab includes Call Stack, Variables, Watches, and Debug Console. `terminal.font` is optional and inherits `editor.font` until explicitly set.

Each workspace stores restorable terminal state at `.itsy/terminal.json`. Schema v2 preserves tab titles, the selected tab and pane, the pane tree, and each pane’s working directory. Legacy unversioned files are migrated and rewritten on first restore. Process IDs, shell output, scrollback, and presentation are never persisted; presentation remains controlled by the `terminal` JSON settings object.

`[workbench]` selects a named, composable layout profile. `workbench` preserves the normal file tree, editor, bottom terminal, and right Git layout. `focus` hides the file tree; `review` hides the file tree and opens Git when it is enabled. Overrides apply after the selected profile. Settings exposes the same File tree, Terminal, and Git overrides as explicit controls. Git and Debugger share a single secondary sidebar, retain separate widths in workspace state, and collapse together before they constrain the editor. At narrow widths, Git collapses from full diff to a compact diff and then an explicit Files/Diff switcher. Invalid profile/override combinations open the recovery panel and leave the editor layout disabled until corrected.

`updates.automatically_check` defaults to `false`. When enabled, Itsy checks the signed appcast for the latest stable GitHub Release in the background. Updates are always downloaded and installed only after the user confirms the Sparkle prompt. **Check for Updates…** remains available from the app menu regardless of this setting.

In-app notifications appear as editor toasts. `ui.notification_position` defaults to `bottom_right`; set it to `top_right` to place them at the upper-right edge. Settings reloads confirm the active Terminal, Git, and Debugger presentations; validation errors identify the first bad setting, state when a fallback value remains active, and provide **Open Config** for the global JSON file. LSP notices expand with **Details** and can copy a diagnostic report; when no server process started, the report explicitly says that no process log is available.

Open **Settings → Language & Debugger Support** to choose each adapter’s `auto`, `system`, `managed`, or `disabled` mode. `auto` prefers an executable already on the system, then uses an enabled Itsy-managed copy; `system` never downloads; `managed` only uses a verified private copy; `disabled` does not start the language server. Workspace `.itsy/lsp.toml` remains an executable/arguments/settings override and never requests downloads or versions.

TypeScript, JavaScript, and Python have pinned, SHA-512-verified private npm provisioning. Itsy downloads a pinned private Node.js runtime before installing a managed Node server, and launches managed `.js`/`.mjs` servers with that private runtime. The runtime and packages live only under `~/Library/Application Support/Itsy/Support`; global npm state is never modified. The other declared adapters use system runtimes until a future signed catalog provides a verified private artifact. When a verified managed copy is missing on first use, Itsy offers **Open Support**; installation is still explicit.

`lsp.catalog_automatically_check` is opt-in. A release-configured Ed25519 public key verifies every catalog envelope before it is cached. Automatic checks only stage a pending catalog; the Support panel is the only place that applies it. Applying metadata never downloads a server. Builds without `ITSYLSPCatalogURL` and `ITSYLSPCatalogPublicKey` disable this feature.

`editor.cursor_style = "automatic"` uses a block cursor for Vim and Emacs keymaps, and a thin bar for Plain. Set `block` or `bar` to override that behavior; `immediate` is accepted as an alias for `bar`.

Use the command palette actions **Settings: Open User JSON**, **Settings: Open Workspace JSON**, and **Settings: Open Catalog** to edit or inspect configuration.

For automation, the bundled CLI exposes validated settings operations:

```text
Itsy.app/Contents/Helpers/itsy config path
Itsy.app/Contents/Helpers/itsy config get ui.font_scale --json
Itsy.app/Contents/Helpers/itsy config set ui.surface.command_palette.height 340
Itsy.app/Contents/Helpers/itsy config reset ui.surface.command_palette.height
```

Theme ids:

```toml
id = "bundled:default-light"
id = "bundled:default-dark"
id = "user:my-theme.toml"
```

Syntax grammar preload modes:

```toml
preload_grammars = "none"
preload_grammars = "opened"
preload_grammars = "all"
```

Related config remains separate:

```text
~/.config/itsy/keys.toml
~/.config/itsy/themes/*.toml
~/.config/itsy/snippets/<language-id>.json
```

Bundled keymaps live in `Sources/ItsyKeymap/Resources/keys.plain.toml`, `keys.vim.toml`, and `keys.emacs.toml`. Toggle the active keymap in Settings or with `editor.keymap`; user overrides stay in `~/.config/itsy/keys.toml`.

Workspace problem matchers live at:

```text
<workspace>/.itsy/matchers.toml
```

```toml
[matcher.eslint]
label = "ESLint"
pattern = "^(.+)\\((\\d+),(\\d+)\\): (warning|error) (.+)$"
file_group = 1
line_group = 2
column_group = 3
severity_group = 4
message_group = 5
source = "eslint"
```

Snippets use VS Code JSON format and load from:

```text
~/.config/itsy/snippets/<language-id>.json
<workspace>/.itsy/snippets/<language-id>.json
```

```json
{
  "Print value": {
    "prefix": ["pr", "printv"],
    "body": ["print(${1:value})", "$0"],
    "description": "Prints a value",
    "scope": "swift,typescript"
  }
}
```

`prefix` and `body` may be strings or arrays. `scope` is optional and may be a comma-list or array. Snippets appear below LSP completions and above fallback prefix completions; Tab and Shift-Tab move through `$1`, `$2`, `${1:default}`, and `$0` tab-stops.

Extension snippet contributions use the same JSON files from installed extension manifests under `~/.config/itsy/extensions/<identifier>/<version>/extension.json` or workspace development manifests under `<workspace>/.itsy/extensions/*.json`:

```json
{
  "contributes": {
    "snippets": [
      { "language": "swift", "path": "snippets/swift.json" }
    ]
  }
}
```
