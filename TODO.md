# TODO

Drop-in implementation tasks. Each entry is self-contained: file paths with line numbers, API signatures, gotchas, and references inline. A coding agent should be able to execute any single task from this document plus the repo, with no need to re-research.

Constraint floor for every task: Accessibility-only, no SIP-off, no private windowing APIs. The `OLLY_ENABLE_PRIVATE_AX_WINDOW_ID` env flag in `Sources/ollyKit/WindowRef.swift` already exists but is the only exception — do not widen private-API use.

Single IPC bump `v1 → v2` covers all new commands/events in M0..M4; bump site `Sources/ollyIPC/OllyIPC.swift:7`.

## Sequencing

1. **M0 Foundations** — AX recovery, overlay host, event bus, IPC schema bump, persistence migrations. Everything else depends on M0.
2. **M1 Reliability spine** — per-display engine binding, sticky/pinned, workspace decouple, per-window engine override, fullscreen, Spaces, focus-stealing.
3. **M2 Hackability surface** — wire hooks/Action.raw/CooperativeApps to runtime, rule inspector, macro recorder.
4. **M3 Snap & glance UX** — focus ring, drag-snap, grid overlay, cheatsheet, Alt-Tab, scratchpad, animated transitions.
5. **M4 Product polish** — app-launch on tag, session restore, dialog protection, resize bindings, FFM, first-run wizard, crash telemetry, settings export/import, error log, conflict UI, a11y/i18n.
6. **D Mass distribution (deferred)** — sign, notarize, Sparkle, DMG, GH Actions, Homebrew cask, telemetry posture, docs site.

Verification command after each task: `./scripts/bootstrap-dev.sh && swiftlint lint --config .swiftlint.yml --strict && ./scripts/check-no-private-api.sh && swift build -c release && swift test && ./scripts/smoke-app-ipc.sh && swift run -c release PerfBench`.

---

## M0 — Foundations

## M3 — Snap & glance UX

All overlays inherit from `Sources/ollyApp/Overlays/OverlayPanel.swift` (M0.2); honour `NSWorkspace.shared.accessibilityDisplayShouldReduceMotion`; subscribe via `RuntimeEventBus` (M0.2).

## M4 — Product polish

### M4.13 CLI completions + manpage

**Goal:** `ollyctl --completions zsh|bash|fish` and `ollyctl manpage`.

**Files to add:**
- `Sources/ollyctl/CompletionGenerator.swift` — walks the argparse spec and emits shell completions. (If using `swift-argument-parser`, it has built-in completion generation.)

---

### M4.14 In-app changelog viewer

**Goal:** Show `CHANGELOG.md` on first launch after upgrade.

**Files to modify:**
- Bundle `CHANGELOG.md` as a resource in `Sources/ollyApp/Resources/`.
- In `OllyApp.applicationDidFinishLaunching`, compare current version against `UserDefaults["olly.lastShownChangelogVersion"]`; if differ, show modal with rendered Markdown (`NSAttributedString` from Markdown).

---

## D — Mass distribution (deferred until M0..M4 land)

### D.1 Developer ID code signing

**Goal:** Sign `ollyApp.app` bundle for Gatekeeper acceptance.

**Research summary:**
- Apple requires Hardened Runtime + Developer ID Application certificate for notarization eligibility.
- `codesign --options=runtime --timestamp -f -s "Developer ID Application: <Team Name> (<Team ID>)" path/to/ollyApp.app`.
- Universal binary: `swift build -c release --arch arm64 --arch x86_64` (depending on SPM toolchain version; verify with `xcrun --show-sdk-path` and the toolchain in use).
- Window manager doing AX work needs **no special entitlements** — AX trust is user-grantable. If any JIT/dlopen, add `com.apple.security.cs.allow-jit` or `com.apple.security.cs.allow-dyld-environment-variables`. Olly doesn't currently JIT; entitlements file can be minimal.

**Files to add:**
- `scripts/sign-app.sh` — given `.build/release/ollyApp` (the SPM binary), produce `dist/Olly.app/Contents/MacOS/ollyApp` bundle layout, copy `Info.plist`, run `codesign`.
- `Resources/Olly.entitlements` — minimal entitlements file (Hardened Runtime + no special grants).

**Entitlements example:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- No JIT or special grants needed for AX-only WM -->
</dict>
</plist>
```

**Verification:**
- `codesign --verify --deep --strict --verbose=2 dist/Olly.app`
- `spctl -a -t exec -vv dist/Olly.app` — expect "accepted source=Developer ID".

**Refs:**
- https://developer.apple.com/developer-id/
- https://gist.github.com/rsms/929c9c2fec231f0cf843a1a746a416f5

---

### D.2 Notarization with `notarytool`

**Goal:** Submit signed artifact to Apple notary service; staple ticket.

**Research summary:**
- `altool` deprecated; `xcrun notarytool` is current path.
- Store credentials once: `xcrun notarytool store-credentials "olly-notary" --apple-id "<apple-id>" --team-id "<team-id>" --password "<app-specific-password>"`. Stored in Keychain.
- Submit: `xcrun notarytool submit dist/Olly-v0.1.0.dmg --keychain-profile "olly-notary" --wait` — returns submission id; `--wait` blocks until accepted/rejected.
- On accept: `xcrun stapler staple dist/Olly-v0.1.0.dmg`. Stapling works for `.dmg`, `.pkg`, `.app`.
- On fail: `xcrun notarytool log <submission-id> --keychain-profile "olly-notary"` to inspect.

**Files to add:**
- `scripts/notarize-dmg.sh` — given a signed DMG path and keychain profile name, submit + wait + staple.

**Refs:**
- https://medium.com/@yo7chen/effortless-mac-code-signing-and-notarization-a-comprehensive-guide-using-terminal-b8285df9bf9c
- https://tonygo.tech/blog/2023/notarization-for-macos-app-with-notarytool

---

### D.3 Sparkle 2 auto-update integration

**Goal:** In-app silent background updates.

**Research summary:**
- SwiftPM dep: `.package(url: "https://github.com/sparkle-project/Sparkle", from: "2.5.0")` (verify latest minor with `swift package show-dependencies` post-merge).
- Target dep: `.product(name: "Sparkle", package: "Sparkle")`.
- Info.plist keys:
  - `SUFeedURL` = `https://yourdomain/appcast.xml`
  - `SUPublicEDKey` = base64 ed25519 public key (generated by `bin/generate_keys` from Sparkle source distribution).
  - `SUEnableInstallerLauncherService` = `YES` for Sparkle 2's XPC architecture.
- Appcast generated by Sparkle's `bin/generate_appcast` tool. Hosted on GitHub Pages or S3 or any HTTPS endpoint with modern TLS.
- Initialise:
```swift
import Sparkle
final class UpdaterDelegate: NSObject, SPUUpdaterDelegate {
    let controller = SPUStandardUpdaterController(startingUpdater: true,
        updaterDelegate: nil, userDriverDelegate: nil)
}
```
- The `SPUStandardUpdaterController` adds an "Updater" menu item; for menu-bar apps, hook a "Check for Updates..." NSMenuItem to `controller.updater.checkForUpdates(_:)`.

**Files to modify:**
- `Package.swift` — add Sparkle dep.
- `Resources/Info.plist` — add `SUFeedURL`, `SUPublicEDKey`, `SUEnableInstallerLauncherService`.
- `Sources/ollyApp/OllyApp.swift` — initialize updater controller.

**Gotchas (from Steipete's "Sparkle and Tears" pattern):**
- Sparkle 2 requires the installer XPC service to be separately signed/notarized — automated by Sparkle's build script but verify Hardened Runtime gets propagated.
- Public EdDSA key MUST be embedded in Info.plist; updates verify both Apple code signature AND Sparkle's signature.

**Refs:**
- https://github.com/sparkle-project/Sparkle
- https://sparkle-project.org/documentation/
- https://swiftpackageindex.com/sparkle-project/Sparkle

---

### D.4 GitHub release DMG packaging

**Goal:** Styled DMG with background, drag-to-Applications symlink.

**Research summary:**
- `sindresorhus/create-dmg` (Node, install via `npm install --global create-dmg`).
- Basic: `create-dmg 'dist/Olly.app' 'dist/'`. Auto-detects icon, version. Creates `Olly <version>.dmg`.
- Options: `--overwrite`, `--identity="<dev-id>"` (sign the DMG too — useful for notarization), `--dmg-title="Olly"`, `--no-code-sign` (testing only).
- Custom background: place `background.png` (1024×768 default) in the work dir; create-dmg picks it up. Or use AppleScript to lay out icon positions.

**Files to add:**
- `scripts/build-dmg.sh` — orchestrates `swift build`, `sign-app.sh`, `create-dmg`.

**Refs:**
- https://github.com/sindresorhus/create-dmg

---

### D.5 GitHub Actions sign + notarize + release workflow

**Goal:** CI/CD to produce signed, notarized, stapled DMG attached to a GitHub release on tag push.

**Research summary:**
- Use `macos-14` or `macos-15` runner (2026 availability).
- Store secrets:
  - `APPLE_DEVELOPER_ID_CERT_P12` (base64-encoded `.p12`)
  - `APPLE_DEVELOPER_ID_CERT_PASSWORD`
  - `APPLE_NOTARYTOOL_APPLE_ID`
  - `APPLE_NOTARYTOOL_TEAM_ID`
  - `APPLE_NOTARYTOOL_APP_PASSWORD`
- Workflow steps:
  1. Checkout
  2. `actions/setup-swift@v1` (or use system Swift on macos-14)
  3. Decode + import `.p12` to a temp keychain (`security create-keychain`, `security import`, `security set-key-partition-list`).
  4. `swift build -c release`
  5. `scripts/sign-app.sh`
  6. `scripts/build-dmg.sh`
  7. `xcrun notarytool submit --apple-id ... --team-id ... --password ... --wait`
  8. `xcrun stapler staple`
  9. `gh release create $TAG dist/Olly-$TAG.dmg --notes-file CHANGELOG.md`

**Files to add:**
- `.github/workflows/release.yml`

---

### D.6 Homebrew cask submission

**Goal:** `brew install --cask olly`.

**Research summary:**
- Cask DSL (Ruby) — fields `version`, `sha256`, `url`, `name`, `homepage`, `app`, `livecheck` (recommended), `zap`.
- **Signed + notarized DMG required** — unsigned casks are being deprecated by **September 2026** per Homebrew discussions. Olly's release will already meet this via D.1-D.2.
- Acceptable Casks rules:
  - App must be signed with Apple Developer ID.
  - Insufficient notability is a reject reason — Homebrew now requires ≥30 forks/watchers or ≥75 stars for OSS repos.
  - Open-source CLI-only apps go to `homebrew/core` (formula) instead of cask. Olly is GUI menu-bar so cask is correct.
  - App must work on latest macOS.
  - No adult content on homepage/root domain.
- PR process: fork `Homebrew/homebrew-cask`, create `Casks/o/olly.rb`, submit PR. CI runs `brew audit --new-cask --strict olly` automatically. Review timeline typically 1-2 weeks.

**Cask template:**
```ruby
cask "olly" do
  version "0.1.0"
  sha256 "<sha256 of DMG>"

  url "https://github.com/<owner>/olly/releases/download/v#{version}/Olly-v#{version}.dmg",
      verified: "github.com/<owner>/olly/"
  name "Olly"
  desc "Pure-Swift macOS window manager with hot-swappable layout engines"
  homepage "https://github.com/<owner>/olly"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on macos: ">= :sonoma"

  app "Olly.app"

  zap trash: [
    "~/.config/olly",
    "~/Library/Logs/Olly",
    "~/Library/Preferences/<bundle-id>.plist",
  ]
end
```

**Files to add:**
- `docs/homebrew-cask-pr.md` (already exists per ls) — extend with the cask template and submission steps.

**Refs:**
- https://docs.brew.sh/Acceptable-Casks
- https://github.com/orgs/Homebrew/discussions/6482

---

### D.7 Opt-in telemetry posture

**Goal:** Lightweight optional usage signal (counts, not individual data); explicit user consent.

**Posture:**
- Default `Telemetry.enabled = false` (from M4.7).
- First launch: `NSAlert` with three options — "Help improve Olly (anonymous counts)", "No telemetry", "Decide later".
- Persist consent to `UserDefaults["olly.telemetry.consent"]`.
- Respect `OLLY_DISABLE_TELEMETRY=1` env var.
- Endpoint: configurable; default `nil` (telemetry is local-only unless user sets it).

**Wire-loop:** A single POST per session on app quit, JSON `{ "version": "0.1.0", "displayCount": 2, "tagCount": 8, "enginesUsed": ["bsp", "niri-scroll"], "sessionDurationSec": 12345 }`. No bundle IDs, no titles, no frames.

**Files:** see M4.7 for `Sources/ollyDSL/Telemetry.swift`.

---

### D.8 Documentation site

**Goal:** Public docs beyond the GitHub README.

**Path:**
- Generate from DocC: `swift package generate-documentation --target ollyDSL --target ollyCore --target ollyLayouts --transform-for-static-hosting --hosting-base-path olly --output-path ./docs-site`.
- Host on GitHub Pages.
- Hand-author landing page (HTML + CSS, static).
- Pull in the existing `docs/` markdown files as additional sections.

**Files to add:**
- `.github/workflows/docs.yml` — build + deploy docs site on push to main.
- `docs-site/index.html` — landing page.

---

## Tooling baseline (every task)

- Build: `swift build -c release`.
- Tests: `swift test`. Opt-in AX acceptance: `OLLY_RUN_AX_ACCEPTANCE=1 swift test`.
- Lint: `swiftlint lint --config .swiftlint.yml --strict`.
- Private-API audit: `./scripts/check-no-private-api.sh`.
- Smoke: `./scripts/smoke-app-ipc.sh`.
- PerfBench: `swift run -c release PerfBench` — baselines in `.perf-baseline.json`.
- Bootstrap: `./scripts/bootstrap-dev.sh`.

## Performance budgets (extend `Sources/ollyDiagnostics/PerformanceBudget.swift:162-237`)

Add scenarios:
- `scratchpad-toggle-latency` p99 ≤ 60ms.
- `permission-revoke-recovery` max ≤ 1500ms.
- `space-drift-verify` p95 ≤ 25ms.
- `focus-rate-limit-eval` p99 ≤ 0.5ms.
- `animated-arrange` ≤ 2× non-animated baseline.
- `thumbnail-cache-fill-20-windows` ≤ 80ms.

## Reference index

- AX permission lifecycle: https://developer.apple.com/documentation/applicationservices/1459186-axisprocesstrustedwithoptions , https://jano.dev/apple/macos/swift/2025/01/08/Accessibility-Permission.html
- CGEventTap silent-disable race + signing: https://danielraffel.me/til/2026/02/19/cgevent-taps-and-code-signing-the-silent-disable-race/ , https://developer.apple.com/forums/thread/122492 , https://github.com/philptr/EventTapCore
- AeroSpace workspace-monitor model: https://nikitabobko.github.io/AeroSpace/guide , https://nikitabobko.github.io/AeroSpace/commands
- AX fullscreen detection: https://developer.apple.com/forums/thread/792917 , https://github.com/tmandry/Swindler
- Spaces public-API limits: https://developer.apple.com/documentation/appkit/nswindow/1419707-isonactivespace , https://developer.apple.com/documentation/coregraphics/cgwindowlistcopywindowinfo(_:_:)
- ScreenCaptureKit migration: https://developer.apple.com/forums/thread/740493 , https://developer.apple.com/documentation/screencapturekit/scscreenshotmanager , https://github.com/lwouis/alt-tab-macos/commit/7821d7c
- JankyBorders: https://github.com/FelixKratz/JankyBorders
- Niri scrollable layout: https://github.com/niri-wm/niri/wiki/Configuration:-Layout , https://deepwiki.com/YaLTeR/niri/3.2-layout-configuration
- Carbon hotkey conflict: https://github.com/soffes/HotKey/blob/main/Sources/HotKey/HotKeysController.swift
- NSWorkspace.openApplication: https://developer.apple.com/documentation/appkit/nsworkspace/3172700-openapplication
- Reduce Motion: https://developer.apple.com/documentation/appkit/nsworkspace/1525481-accessibilitydisplayshouldreduce
- PLCrashReporter: https://github.com/microsoft/plcrashreporter
- Sparkle 2 SwiftPM: https://github.com/sparkle-project/Sparkle , https://sparkle-project.org/documentation/
- notarytool: https://medium.com/@yo7chen/effortless-mac-code-signing-and-notarization-a-comprehensive-guide-using-terminal-b8285df9bf9c , https://tonygo.tech/blog/2023/notarization-for-macos-app-with-notarytool
- create-dmg: https://github.com/sindresorhus/create-dmg
- Homebrew cask acceptance: https://docs.brew.sh/Acceptable-Casks , https://github.com/orgs/Homebrew/discussions/6482
