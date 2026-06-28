# Release Readiness

Status: v0.1.0 is not cut. This file records the local artifact smoke test and
the blockers for the GitHub Release task.

## Runtime Release Gate

Do not tag v0.1.0 until the app runtime owns the IPC service used by `ollyctl`.
The current `ollyApp` target owns the menu bar, AX onboarding, settings,
overview mode, and command palette UI, but no `UnixDomainSocketServer` wiring was
found in `Sources/ollyApp`.

Verified on 2026-06-28:

```sh
swift run -c release ollyctl state
```

Result:

```text
Error: connect failed with errno 2
```

This is a release blocker independent of signing/notarization.

## Local Artifact Smoke Test

Command run on 2026-06-28:

```sh
VERSION=v0.1.0 BUILD_NUMBER=1 CODESIGN_IDENTITY='-' ./scripts/package-macos-dmg.sh
mkdir -p release/v0.1.0
cp dist/Olly.dmg release/v0.1.0/Olly-v0.1.0-ad-hoc.dmg
git archive --format tar.gz --prefix olly-v0.1.0/ -o release/v0.1.0/olly-v0.1.0-source.tar.gz HEAD
(cd release/v0.1.0 && shasum -a 256 Olly-v0.1.0-ad-hoc.dmg olly-v0.1.0-source.tar.gz > SHA256SUMS)
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
- Release workflow has the signing/notarization secrets above.
- `Release DMG` workflow passes for tag `v0.1.0`.
- GitHub Release has `Olly-v0.1.0.dmg`, `olly-v0.1.0-source.tar.gz`, and `SHA256SUMS`.
- Homebrew cask PR exists and points at the final release artifact.

When the required secrets are configured, `.github/workflows/release-dmg.yml`
publishes the notarized DMG, source tarball, and checksums to the tag's GitHub
Release.

The Homebrew cask PR body is drafted in `docs/homebrew-cask-pr.md`; it still
needs the final release SHA before submission.

The 30-second two-display demo blocker is tracked separately in
`docs/demo-readiness.md`.
