# Homebrew Cask PR Draft

Status: draft only. Do not open the Homebrew PR until `v0.1.0` has a notarized
GitHub Release with final `SHA256SUMS`.

## Release Inputs

- Release URL: `https://github.com/gongahkia/olly/releases/tag/v0.1.0`
- DMG URL: `https://github.com/gongahkia/olly/releases/download/v0.1.0/Olly-v0.1.0.dmg`
- SHA256: copy the `Olly-v0.1.0.dmg` value from the final release `SHA256SUMS`.

## Cask Body

```ruby
cask "olly" do
  version "0.1.0"
  sha256 "<replace-with-final-dmg-sha256>"

  url "https://github.com/gongahkia/olly/releases/download/v#{version}/Olly-v#{version}.dmg",
      verified: "github.com/gongahkia/olly/"
  name "Olly"
  desc "Pure-Swift macOS window manager with hot-swappable layout engines"
  homepage "https://github.com/gongahkia/olly"

  depends_on macos: :sonoma

  app "Olly.app"

  zap trash: [
    "~/.cache/olly",
    "~/.config/olly",
    "~/Library/Application Support/olly",
  ]
end
```

## PR Checklist

- Replace the placeholder SHA with the final `Olly-v0.1.0.dmg` SHA256.
- Run `brew audit --cask --new olly`.
- Run `brew install --cask ./olly.rb`.
- Open the PR against `Homebrew/homebrew-cask` after the release artifact is public.
