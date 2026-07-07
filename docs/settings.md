# Settings

Itsy reads user settings from:

```toml
~/.config/itsy/settings.toml
```

The file is TOML-shaped and intentionally small. Unknown keys are ignored with a warning in the Settings window. Bad values keep the previous/default value.

```toml
[editor]
font = "Menlo"
font_size = 14.95
line_numbers = false
tab_width = 4

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
```
