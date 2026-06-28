# Release Readiness

Status: v0.1.0 is not cut. This file records the local artifact smoke test and
the external blockers for the GitHub Release task.

## Local Artifact Smoke Test

Command run on 2026-06-28:

```sh
VERSION=v0.1.0 BUILD_NUMBER=1 CODESIGN_IDENTITY='-' ./scripts/package-macos-dmg.sh
mkdir -p release/v0.1.0
cp dist/Olly.dmg release/v0.1.0/Olly-v0.1.0-ad-hoc.dmg
git archive --format tar.gz --prefix olly-v0.1.0/ -o release/v0.1.0/olly-v0.1.0-source.tar.gz HEAD
(cd release/v0.1.0 && shasum -a 256 Olly-v0.1.0-ad-hoc.dmg olly-v0.1.0-source.tar.gz > SHA256SUMS)
```

Result:

```text
65ccf84c60ef529a2cd8f1be5df155d933afd46a9b295e994f73935f01859eca  Olly-v0.1.0-ad-hoc.dmg
07a8ee0c093e312f13852de3da84a9a789b5788e1552b4e2356bbd9b18d2c566  olly-v0.1.0-source.tar.gz
```

The DMG is ad-hoc signed only. It is not notarized and must not be published as
the final v0.1.0 artifact.

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

## Release Gate

Do not remove the v0.1.0 release TODO until:

- Release workflow has the signing/notarization secrets above.
- `Release DMG` workflow passes for tag `v0.1.0`.
- GitHub Release has `Olly-v0.1.0.dmg`, `olly-v0.1.0-source.tar.gz`, and `SHA256SUMS`.
- Homebrew cask PR exists and points at the final release artifact.

When the required secrets are configured, `.github/workflows/release-dmg.yml`
publishes the notarized DMG, source tarball, and checksums to the tag's GitHub
Release.
