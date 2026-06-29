# First Run

Create a config profile before starting the app:

```sh
ollyctl init-config --profile starter
ollyctl doctor
```

Profiles:

| Profile | Use |
|---|---|
| `starter` | balanced tags, BSP, scrolling columns, and floating fallbacks |
| `minimal` | one tag and one floating engine |
| `niri` | scrolling-column workflow |
| `bsp` | keyboard-first binary split workflow |
| `ultrawide` | centered master and grid workflow |

Use `--force` only when replacing the existing `Config.swift` intentionally:

```sh
ollyctl init-config --profile bsp --force
```

The Settings window can also create the missing config with the selected profile.
