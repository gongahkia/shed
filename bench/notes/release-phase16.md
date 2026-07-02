# Phase 16 Release Checkpoint

Date: 2026-07-02

Implemented slice:

- `scripts/verify_dmg.sh` now reads `CFBundleExecutable` from the mounted app Info.plist instead of assuming `Itsy`.
- expected bundle ID is configurable with `ITSY_EXPECTED_BUNDLE_ID`, defaulting to `dev.itsy.editor`.
- current behavior remains compatible with the existing bundle ID and executable.

Verification:

```sh
bash -n scripts/codesign.sh
bash -n scripts/make_dmg.sh
bash -n scripts/notarize.sh
bash -n scripts/verify_dmg.sh
shasum -c dist/Itsy-0.1.0.dmg.sha256
ITSY_ALLOW_UNSIGNED_DMG=1 scripts/verify_dmg.sh dist/Itsy-0.1.0.dmg
```

Result:

- script syntax checks passed.
- `shasum -c dist/Itsy-0.1.0.dmg.sha256` passed.
- unsigned DMG verifier smoke passed with `ITSY_ALLOW_UNSIGNED_DMG=1`.

Remaining for #1:

- Developer ID Application certificate installation/selection.
- signed hardened-runtime app validation.
- notarization submit/staple validation with Apple credentials.
- Sparkle integration.
- Homebrew cask submission path.
- final name/domain/bundle-id decision.
