# Phase 35 Extensions Checkpoint

Date: 2026-07-07

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
- app command registry now reloads workspace extension command contributions on workspace open.
- duplicate or conflicting extension commands are skipped with a log instead of failing app startup.
- added `ExtensionContributionRegistry` for scoped theme, snippet, language, and problem-matcher metadata.
- contribution file paths are resolved relative to the extension root and reject absolute paths, `..`, missing files, and invalid matcher regexes.
- added SHA-256/trust-checked install flow from a staged extracted extension directory into versioned install roots.
- install validation rejects symlinks, nested `.app` bundles, executable files, hash mismatches, deny records, and missing trust.
- install trust loads repo/user/workspace `VOUCHED` stores and accepts injected/optional `vouch` CLI evidence.
- added marketplace index/cache load/save models.
- added a minimal Extensions panel listing installed extension versions.
- command palette now includes `Extensions` and `Reload Extension Contributions`.
- added `scripts/package_extension.sh` for local publish packaging, SHA-256 output, and VOUCHED allow-line generation.

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
swift test --filter extensionCommandDiscovery
swift test --filter Extension
swift test --filter CommandRegistry
swift test --filter VouchStore
swift build --target ItsyApp
bash -n scripts/package_extension.sh
scripts/package_extension.sh <temp-extension> <temp-out> && shasum -c <temp-out>/dev.example.pack-0.1.0.itsyext.zip.sha256
```

Result:

- `ExtensionManifest`: 7 tests passed.
- `VouchStore`: 5 tests passed.
- `extensionCommandMapper`: 1 test passed.
- `extensionCommandDiscovery`: 1 test passed.
- `Extension`: 17 tests passed.
- `CommandRegistry`: 2 tests passed.
- `VouchStore`: 6 tests passed.
- `ItsyApp` build: passed.
- `scripts/package_extension.sh`: bash syntax check and temp packaging smoke passed.
- `docs/design/extensions.md`: exists; references and non-goals grep passed.

Remaining follow-up:

- remote marketplace submission is still policy/process work.
- snippet/theme/language/problem-matcher runtime consumers now have registered metadata, but richer editor UX can expand in later feature issues.
