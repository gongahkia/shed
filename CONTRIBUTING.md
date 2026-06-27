# Contributing

## Cooperative Apps Allowlist

Community PRs can add bundle IDs to `docs/cooperative-apps.yml` when an app window should be
floated by default to preserve olly's ecosystem non-interference contract.

For each allowlist PR:

- Add the bundle ID to `docs/cooperative-apps.yml`.
- Mirror the same ID in `CooperativeApps.defaultBundleIDs`.
- Include evidence: a screenshot, short repro, or app-behavior note showing why tiling conflicts.
- Include how the bundle ID was verified, for example `mdls -name kMDItemCFBundleIdentifier -r /Applications/App.app`.
- Update `docs/menubar-notch-integration.md` if the app needs product-specific notes.
- Run `swiftlint lint --config .swiftlint.yml --strict`, `./scripts/check-no-private-api.sh`, `swift build -c release`, and `swift test`.

Do not add preference panes, helper tools, or apps that work correctly with user-level
`CooperativeApps { ... }` config only.
