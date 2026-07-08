# Release Readiness

This page is the Phase 16 release checklist. It is intentionally explicit because the final signed release cannot be proven without private Apple credentials and a published release asset.

## Current State

- Product name: `Itsy`.
- Current bundle id: `dev.itsy.editor`.
- Release bundle path: `Itsy.app`.
- Release DMG path: `dist/Itsy-0.1.0.dmg`.
- Local unsigned release smoke is supported.
- Signed release is blocked until a `Developer ID Application` identity and notary credentials are available.
- Sparkle is linked and bundled, but remains inactive unless `SUFeedURL` and `SUPublicEDKey` are present.
- Homebrew cask submission is blocked until the signed/notarized GitHub Release DMG and final SHA-256 exist.

## Required Secrets

GitHub Actions release flow expects:

- `DEVELOPER_ID_APPLICATION_CERTIFICATE_BASE64`
- `DEVELOPER_ID_APPLICATION_CERTIFICATE_PASSWORD`
- `KEYCHAIN_PASSWORD`
- `DEVELOPER_ID_APPLICATION_IDENTITY`
- `APPLE_ID`
- `APPLE_TEAM_ID`
- `APPLE_APP_SPECIFIC_PASSWORD`

Local signing/notarization expects either:

- `ITSY_CODESIGN_IDENTITY`
- `ITSY_NOTARY_PROFILE`

or:

- `ITSY_CODESIGN_IDENTITY`
- `ITSY_NOTARY_APPLE_ID`
- `ITSY_NOTARY_TEAM_ID`
- `ITSY_NOTARY_PASSWORD`

Signed Sparkle release config expects:

- `ITSY_SPARKLE_FEED_URL`
- `ITSY_SPARKLE_PUBLIC_ED_KEY`

## Local Smoke

```sh
swift build -c release
bench/scripts/make_app.sh
ITSY_RELEASE_MODE=unsigned scripts/release_doctor.sh
ITSY_ALLOW_UNSIGNED_DMG=1 scripts/make_dmg.sh
shasum -c dist/Itsy-0.1.0.dmg.sha256
ITSY_ALLOW_UNSIGNED_DMG=1 scripts/verify_dmg.sh dist/Itsy-0.1.0.dmg
scripts/make_homebrew_cask.sh
```

This validates app layout, DMG integrity, mounted bundle layout, and checksum. It does not satisfy Developer ID signing or notarization.

## Signed Release

```sh
export ITSY_SPARKLE_FEED_URL=https://github.com/gongahkia/itsy/releases/latest/download/appcast.xml
export ITSY_SPARKLE_PUBLIC_ED_KEY=<sparkle-public-ed-key>
swift build -c release
bench/scripts/make_app.sh
scripts/release_doctor.sh
scripts/codesign.sh
scripts/make_dmg.sh
scripts/notarize.sh
shasum -c dist/Itsy-0.1.0.dmg.sha256
scripts/verify_dmg.sh dist/Itsy-0.1.0.dmg
scripts/make_homebrew_cask.sh
```

Expected properties:

- `scripts/codesign.sh` signs nested code under `Contents/Frameworks` first.
- `codesign` uses `--options runtime --timestamp`.
- `scripts/notarize.sh` uses `xcrun notarytool`, staples the DMG, validates the staple, and runs `spctl`.
- `.github/workflows/release.yml` uploads `dist/*.dmg` and `dist/*.sha256` and publishes a GitHub Release for `v*.*.*` tags.

## Sparkle Gate

Before Sparkle can ship:

- Pick the production appcast URL.
- Generate and store the Sparkle EdDSA public key.
- Build the app with `ITSY_SPARKLE_FEED_URL` and `ITSY_SPARKLE_PUBLIC_ED_KEY` set together.
- Confirm `Sparkle.framework` and its XPC services are present under `Itsy.app/Contents/Frameworks`.
- Ensure the Sparkle framework and XPC services are signed before the app bundle via `scripts/codesign.sh`.
- Generate the appcast from signed release assets.

Candidate appcast URL shape:

```text
https://github.com/gongahkia/itsy/releases/latest/download/appcast.xml
```

Do not enable automatic updates until the URL and signing key are final.

After the signed DMG and EdDSA key are available, generate the appcast with:

```sh
SPARKLE_GENERATE_APPCAST=/path/to/generate_appcast scripts/make_appcast.sh
```

## Homebrew Cask Gate

The cask can be submitted after the first signed, notarized, stapled DMG is published.

Candidate cask values:

```ruby
cask "itsy" do
  version "0.1.0"
  sha256 "<signed-release-dmg-sha256>"

  url "https://github.com/gongahkia/itsy/releases/download/v#{version}/Itsy-#{version}.dmg"
  name "Itsy"
  desc "macOS-native code editor"
  homepage "https://github.com/gongahkia/itsy"

  app "Itsy.app"
end
```

Validation before PR:

```sh
brew audit --cask --new --online itsy
brew install --cask ./itsy.rb
brew uninstall --cask itsy
```

Submit to `homebrew/homebrew-cask` only after the URL is public and the SHA-256 is for the signed/notarized DMG.

Draft the cask from the current DMG with:

```sh
scripts/make_homebrew_cask.sh
```

## Name And Domain Gate

Current repo metadata uses `Itsy` and `dev.itsy.editor`.

Before the first signed release:

- Verify final product name.
- Verify domain ownership or choose a bundle id that matches controlled infrastructure.
- If bundle id changes, update `bench/scripts/make_app.sh`, `scripts/release_doctor.sh`, `scripts/verify_dmg.sh`, README install docs, and any release notes.

## References

- Apple notarization: https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution
- Apple distribution signing: https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac
- Apple hardened runtime: https://developer.apple.com/documentation/security/hardened-runtime
- Sparkle documentation: https://sparkle-project.org/documentation/
- Sparkle publishing: https://sparkle-project.org/documentation/publishing/
- Homebrew Cask cookbook: https://docs.brew.sh/Cask-Cookbook
- Homebrew pull requests: https://docs.brew.sh/How-To-Open-a-Homebrew-Pull-Request
