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

```toml
[editor]
font = "Menlo"
font_size = 14.95
line_numbers = false
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
