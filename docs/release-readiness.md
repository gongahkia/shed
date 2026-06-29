# Release Readiness

Status: v0.1.0 is not cut. This file records the local artifact smoke test and
the blockers for the GitHub Release task.

## Runtime Release Gate

`ollyApp` owns the `OllyRuntime` instance and starts the Unix-domain IPC server
used by `ollyctl`. The release smoke path is:

```sh
./scripts/smoke-app-ipc.sh
```

The script launches the app, waits for the IPC socket, and verifies:

- `ollyctl version`
- `ollyctl state`
- `ollyctl restore-windows`

This gate must pass before tagging v0.1.0.

## Release Asset Gate

Release assets are prepared by:

```sh
VERSION=v0.1.0 scripts/prepare-release-assets.sh
scripts/validate-release-assets.sh dist/release
```

`prepare-release-assets.sh` produces:

- `Olly-<version>.dmg`
- `olly-<version>-source.tar.gz`
- `SHA256SUMS`
- `release-manifest.json`

`validate-release-assets.sh` verifies the checksum file, manifest syntax,
readable source archive, Developer ID DMG signature, stapled notarization ticket,
and Gatekeeper assessment. Local ad-hoc smoke builds may opt out of the
Developer ID/notarization gate with `ALLOW_ADHOC_RELEASE=1`.

## Local Artifact Smoke Test

Command run on 2026-06-28:

```sh
VERSION=v0.1.0 BUILD_NUMBER=1 CODESIGN_IDENTITY='-' ./scripts/package-macos-dmg.sh
./scripts/validate-macos-app-bundle.sh dist/Olly.app
VERSION=v0.1.0 RELEASE_DIR=release/v0.1.0 scripts/prepare-release-assets.sh
ALLOW_ADHOC_RELEASE=1 scripts/validate-release-assets.sh release/v0.1.0
```

The DMG is ad-hoc signed only. It is not notarized and must not be published as
the final v0.1.0 artifact. The source tarball SHA changes with every source
commit, so read the current ignored smoke-test values from
`release/v0.1.0/SHA256SUMS` instead of committing them here.

## Required GitHub Secrets

`.github/workflows/release-dmg.yml` requires these secrets:

- `MACOS_CERTIFICATE_P12`
- `MACOS_CERTIFICATE_PASSWORD`
- `DEVELOPER_ID_APPLICATION`
- `APPLE_ID`
- `APPLE_TEAM_ID`
- `APPLE_APP_SPECIFIC_PASSWORD`

`gh secret list --repo gongahkia/olly` returned no configured secrets on
2026-06-28 from this environment.

## Distribution Release Gate

Do not remove the v0.1.0 release TODO until:

- App runtime smoke test passes with `ollyctl state`.
- Local ad-hoc bundle validation passes.
- Release workflow has the signing/notarization secrets above.
- `Release DMG` workflow passes for tag `v0.1.0`.
- GitHub Release has `Olly-v0.1.0.dmg`, `olly-v0.1.0-source.tar.gz`,
  `SHA256SUMS`, and `release-manifest.json`.
- Homebrew cask PR exists and points at the final release artifact.

When the required secrets are configured, `.github/workflows/release-dmg.yml`
publishes the notarized DMG, source tarball, and checksums to the tag's GitHub
Release.

The Homebrew cask PR body is drafted in `docs/homebrew-cask-pr.md`; it still
needs the final release SHA before submission.

The 30-second two-display demo blocker is tracked separately in
`docs/demo-readiness.md`.
