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

Validation steps in the script:

- `codesign --verify --deep --strict --verbose=2 Itsy.app`
- `codesign -dvvv --entitlements :- Itsy.app`

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

## Notes

- No app entitlements are currently required. The app does not request Apple events, sandbox exceptions, network extensions, or JIT/debug allowances.
- `spctl` acceptance is expected only after id:302 notarization and stapling.

## References

- https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac
- https://developer.apple.com/documentation/security/resolving-common-notarization-issues
