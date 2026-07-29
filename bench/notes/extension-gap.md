# Extension ABI Gap

Closed: 2026-07-07

Phase 35 now covers the original gap:

- schema v2 manifest metadata for commands, keybindings, themes, snippets, languages, problem matchers, and tasks.
- command registry and app-level workspace command discovery.
- keybinding contribution mapping into the app keymap layer.
- scoped non-command contribution registry with extension-root path validation.
- `VouchStore` parser/API, default repo/user/workspace store order, and property-style parser rejection tests.
- SHA-256 and trust-checked install flow from staged extracted extensions into versioned install roots.
- marketplace index/cache models.
- minimal Extensions panel and command-palette entries.
- `scripts/package_extension.sh` local publish/package flow with SHA-256 and VOUCHED allow-line output.

No executable extension host is introduced. v1 remains declarative and non-executable.
