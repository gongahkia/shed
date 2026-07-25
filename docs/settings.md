# Settings

Itsy reads user settings from:

```toml
~/.config/itsy/settings.toml
```

Workspace overrides are loaded from:

```toml
<workspace>/.itsy/settings.toml
```

Both files hot-reload while Itsy is running. The merge order is global, then workspace, then per-language editor overrides for the current buffer. Unknown keys are ignored with a warning in the Settings window. Bad values keep the previous/default value.

Personal UI settings are global-only: `[ui]` entries in a workspace file are ignored with a warning.

```toml
[editor]
font = "Menlo"
font_size = 14.95
line_number_mode = "absolute" # off, absolute, relative
keymap = "plain" # plain, vim, emacs
wrap = "none" # none, soft, hard
wrap_column = 100
tab_width = 4
use_spaces = false

[editor.language.python]
tab_width = 4
use_spaces = true

[theme]
id = "bundled:default-light"

[syntax]
preload_grammars = "opened"

[terminal]
font_size = 12
scrollback_lines = 10000

[ui]
font_scale = 1
density = "regular" # compact, regular, comfortable
corner_radius = 8
border_width = 1
padding = 8
notification_position = "bottom_right" # bottom_right, top_right

[ui.surface.command_palette]
width = 560
height = 280
row_height = 30
input_font_size = 18
item_font_size = 13
```

Every first-party panel has the same optional `ui.surface.<id>` fields. Valid ids include `command_palette`, `completion`, `find`, `project_find`, `terminal`, `outline`, `problems`, `references`, `tasks`, `undo_tree`, `git`, `git_graph`, `git_stash`, `debugger`, `debug_console`, `debug_variables`, `debug_watches`, `debug_launch`, `lsp_status`, `integration_health`, `integration_output`, `extensions`, `settings_catalog`, `lsp_configuration`, `managed_support`, `github_pull_request`, and `github_review_thread`.

In-app notifications appear as editor toasts. `ui.notification_position` defaults to `bottom_right`; set it to `top_right` to place them at the upper-right edge.

Use the command palette actions **Settings: Open User TOML**, **Settings: Open Workspace TOML**, and **Settings: Open Catalog** to edit or inspect configuration.

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
