# Phase 35 Extensions Checkpoint

Date: 2026-07-02

Implemented slice:

- added `docs/design/extensions.md` covering v1 contribution ABI, trust, install/uninstall, storage, schema stability, and non-goals.
- kept schema v1 task manifests compatible.
- added schema v2 declarative contribution models for commands, keybindings, themes, snippets, languages, and problem matchers.
- defaults missing contribution arrays to empty arrays.
- validates non-empty identifiers, command metadata, keybindings, language IDs, paths, and problem patterns.
- left task discovery mapping unchanged; non-task contributions are metadata-only in this slice.
- added `VouchStore` parser/API for the documented local VOUCHED format.
- added default repo/user/project VOUCHED URL order helper.
- deny records take precedence over allow records.
- added `ExtensionCommandMapper` for scoped command registry IDs and explicit extension command dispatch.

References checked:

- VS Code contribution points: https://code.visualstudio.com/api/references/contribution-points
- Tree-sitter code navigation tags: https://tree-sitter.github.io/tree-sitter/4-code-navigation.html
- [Unverified] No canonical VOUCHED file format was found during web search; the design treats the local VOUCHED format as Itsy-owned until `vouch` CLI integration pins an external contract.

Verification:

```sh
test -f docs/design/extensions.md
swift test --filter ExtensionManifest
swift test --filter VouchStore
swift test --filter extensionCommandMapper
```

Result:

- `ExtensionManifest`: 6 tests passed.
- `VouchStore`: 5 tests passed.
- `extensionCommandMapper`: 1 test passed.
- `CommandRegistry`: 2 tests passed.
- `docs/design/extensions.md`: exists; references and non-goals grep passed.

Remaining for #7:

- app-level command registry loading for installed extension manifests.
- keymap/theme/snippet/grammar/problem-matcher registration.
- Vouch parser property tests.
- install-time trust/integrity enforcement and `vouch` CLI integration.
- marketplace/install/publish flows.
- Extensions panel and command-palette entries.
