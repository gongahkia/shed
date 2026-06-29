# Code signing 2026-06-29

## Target

id:301 requires a Developer ID Application signature with hardened runtime:

```sh
codesign --force --sign "Developer ID Application: <name>" --options runtime --timestamp Itsy.app
```

## Script

`scripts/codesign.sh` signs `Itsy.app` with:

- `--options runtime`
- `--timestamp`
- a detected `Developer ID Application:` identity, or `ITSY_CODESIGN_IDENTITY`
- nested code under `Contents/Frameworks` before the app bundle

Validation steps in the script:

- `codesign --verify --deep --strict --verbose=2 Itsy.app`
- `codesign -dvvv --entitlements :- Itsy.app`

## Release pipeline

- `scripts/make_dmg.sh` uses `create-dmg` when installed, falls back to `hdiutil`, verifies the app signature unless `ITSY_ALLOW_UNSIGNED_DMG=1`, builds `dist/Itsy-0.1.0.dmg`, verifies the DMG, mounts it with `scripts/verify_dmg.sh`, optionally signs it, and writes `dist/Itsy-0.1.0.dmg.sha256`.
- `scripts/notarize.sh` submits the DMG with `xcrun notarytool`, waits, staples, validates the staple, and runs `spctl` on the DMG.
- `.github/workflows/release.yml` runs on `v*.*.*` tags, imports a Developer ID Application certificate from secrets, builds/tests/signs/packages/notarizes, verifies the DMG/SHA assets, uploads the DMG artifact, and creates a GitHub Release with `gh release create --verify-tag`.

Required release secrets:

- `DEVELOPER_ID_APPLICATION_CERTIFICATE_BASE64`
- `DEVELOPER_ID_APPLICATION_CERTIFICATE_PASSWORD`
- `KEYCHAIN_PASSWORD`
- `DEVELOPER_ID_APPLICATION_IDENTITY` if auto-detection is ambiguous
- `APPLE_ID`
- `APPLE_TEAM_ID`
- `APPLE_APP_SPECIFIC_PASSWORD`

Release workflow validation:

- YAML parse: `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/release.yml")'`
- Local unsigned asset smoke: `ITSY_ALLOW_UNSIGNED_DMG=1 scripts/make_dmg.sh && shasum -c dist/Itsy-0.1.0.dmg.sha256 && ITSY_ALLOW_UNSIGNED_DMG=1 scripts/verify_dmg.sh dist/Itsy-0.1.0.dmg`
- [Unverified] The GitHub release job itself requires repository secrets and a Developer ID Application certificate, so it was not executed locally.

## Local status

Blocked locally. `security find-identity -v -p codesigning` found one valid identity:

```text
Apple Development: angryapplegravy@gmail.com (Q2J4QWZLR7)
```

No `Developer ID Application:` identity is installed, so this machine cannot produce the id:301 distribution signature.

Running `scripts/codesign.sh` currently fails fast with:

```text
expected exactly one Developer ID Application identity; found 0
set ITSY_CODESIGN_IDENTITY='Developer ID Application: <name> (<team>)'
```

Unsigned DMG packaging is locally smoke-tested with:

```sh
ITSY_ALLOW_UNSIGNED_DMG=1 scripts/make_dmg.sh
```

This validates bundle layout and DMG integrity only. It does not satisfy id:301 or id:302.

2026-06-29 local result: unsigned `hdiutil` fallback built `dist/Itsy-0.1.0.dmg`; `hdiutil verify` and mounted bundle validation passed. SHA-256: `88e8c5619565c932cb89a475f77062fbd2374f2f68c101046571c7d5868c3302`.

## Notes

- No app entitlements are currently required. The app does not request Apple events, sandbox exceptions, network extensions, or JIT/debug allowances.
- `spctl` acceptance is expected only after id:302 notarization and stapling.

## References

- https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac
- https://developer.apple.com/documentation/security/resolving-common-notarization-issues
