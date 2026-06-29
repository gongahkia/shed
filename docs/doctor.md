# Doctor

`ollyctl doctor` checks the local runtime surface that most often blocks daily use:

- Accessibility trust.
- `Config.swift` compile/load status.
- external hotkey collisions from macOS symbolic hotkeys, Karabiner-Elements, and skhd.
- display discovery.
- IPC protocol/state reachability.
- common-app compatibility summary.

Use JSON for support bundles:

```sh
ollyctl doctor --json
ollyctl doctor --config ~/.config/olly/Config.swift --socket ~/.config/olly/olly.sock --json
```

Status semantics:

| Status | Meaning |
|---|---|
| `ok` | The check passed. |
| `warning` | Olly can still run, but user action or runtime context may be missing. |
| `error` | A required capability is unavailable or inconsistent. |

The command exits non-zero when any check is `error`.
