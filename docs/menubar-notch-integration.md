# Menu Bar, Notch, and Ecosystem Integration

Status: target contract for `olly` v0.1 ecosystem behavior. Last checked: 2026-06-26.
Scope: `NORTHSTAR.md` §7, §7b, and §12b.

## Base Contract

`olly` yields screen real estate and emits events. It does not replace menu bar, notch,
launcher, hotkey, border, keyboard-sound, or screen-recording tools.

Tiled windows are arranged inside already-shrunk display bounds:

| Zone | Reserve | Source |
|---|---|---|
| Native menu bar | top strip excluded by `NSScreen.visibleFrame` | macOS |
| Dock | excluded by `NSScreen.visibleFrame` when visible | macOS |
| Notch | `NSScreen.safeAreaInsets.top + 12 px` by default | macOS 12+ |
| User reserves | `reserve(rect:on:)` | olly DSL |
| Cooperative app windows | app window frame | `cooperativeApps` |

`SafeZoneCalculator` derives the default per-display `layoutFrame` from `Display.frame`,
`Display.visibleFrame`, and `Display.safeAreaInsets`; `EngineHost.arrange(display:)` passes
that shrunk frame to layout engines.

Users can tune notch padding and add edge-aligned reserves:

```swift
SafeZones {
    notchPadding(16)
    reserve(rect: CGRect(x: 0, y: 900, width: 1512, height: 82), on: 1)
}
```

Floating windows may overlap reserved zones. Tiled windows must not.

IPC surface for integrations:

```sh
ollyctl state --json
ollyctl events --json
ollyctl focus next
ollyctl switch-tag 1
ollyctl move-to-tag 1
ollyctl set-engine niri-scroll
```

## App Matrix

| App | How olly yields | Integration recipe | Known conflicts |
|---|---|---|---|
| SketchyBar | Reserve native menu bar height; do not draw status UI over it. | Use `extensions/sketchybar/`; source its `sketchybarrc`; it forwards `ollyctl events --json` into `sketchybar --trigger olly_update`. | Custom bars taller than the native menu bar need explicit `reserve(rect:on:)`; duplicate event subscribers can churn item updates. |
| Übersicht/simple-bar | Reserve top bar space; keep the bar display-only. | Build a widget around `ollyctl state --json`; for live updates run a helper process consuming `ollyctl events --json`; first-party widget remains TODO under `extensions/ubersicht/`. | Widget refresh polling can waste CPU; prefer the event stream. Custom top-bar height needs explicit reserve. |
| Bartender | Do not force-pin `ollyApp`'s status item; float Bartender surfaces. | Let Bartender own menu item visibility; include `com.surteesstudios.Bartender` in `cooperativeApps`. | User may hide the only visible olly control surface; keep CLI/palette fallback documented. |
| Hidden Bar | Do not force-pin `ollyApp`'s status item; float Hidden Bar surfaces. | Let Hidden Bar own menu item visibility; include `com.dwarvesf.hidden` in `cooperativeApps`. | Same as Bartender: hidden status item can make CLI/palette the recovery path. |
| Ice | Do not force-pin `ollyApp`'s status item; float Ice surfaces. | Let Ice own menu item visibility; include `com.jordanbaird.Ice` in `cooperativeApps`. | Menu item compaction can hide olly state; do not rely on status item as sole control path. |
| Alcove | Reserve notch safe area plus configured vertical padding; float its surfaces. | Keep default notch reserve; add `com.lowtechguys.Alcove` to `cooperativeApps`; add a larger `reserve(rect:on:)` if its hover UI drops below 60 px. | Expanded notch UI can overlap top tiles if user reduces padding. |
| NotchNook | Reserve notch safe area plus configured vertical padding; float its surfaces. | Keep default notch reserve; add `com.akashpawar.notchnook` to `cooperativeApps`; tune `notchPadding(_)` per display. | Popovers below the notch need manual reserve if taller than default. |
| Boring Notch | Reserve notch safe area plus configured vertical padding; float its surfaces. | Keep default notch reserve; add `com.tymmesyde.boring-notch` to `cooperativeApps`. | Dynamic notch panels can collide with top-row tiles if padding is too small. |
| NotchBook | Treat as a notch utility until verified. | [Unverified] Bundle ID and official source not verified on 2026-06-26; users should add its bundle ID to `cooperativeApps` manually and tune `notchPadding(_)`. | [Unverified] Known conflicts cannot be product-specific without a verified source; default notch overlay conflicts apply. |
| Brow | Reserve notch safe area plus configured vertical padding; float its surfaces. | Keep default notch reserve; add `com.brow-app.Brow` to `cooperativeApps`. | Top-center hover panels need explicit reserve if they exceed default notch depth. |
| NotchFlow | Reserve notch safe area plus configured vertical padding; float its surfaces. | Keep default notch reserve; add `com.lukegrubb.NotchFlow` to `cooperativeApps`. | [Unverified] Official source not verified on 2026-06-26; validate bundle ID before shipping built-in allowlist updates. |
| TopNotch | Reserve notch safe area; do not special-case wallpaper/menu bar visuals. | Keep default notch reserve; add `com.codykerns.TopNotch` to `cooperativeApps` only if it creates windows on the target macOS version. | Primarily visual notch hiding; no IPC needed. |
| Notchmeister | Reserve notch safe area plus configured vertical padding; float its surfaces. | Keep default notch reserve; add `com.notchmeister.Notchmeister` to `cooperativeApps`. | Official source URL not verified on 2026-06-26; validate before changing bundle ID. |
| MediaMate | Reserve notch/top overlay area; float its HUD windows. | Keep default notch reserve; add observed bundle ID to user `cooperativeApps` until a built-in ID is verified. | Volume/media HUDs can animate below the notch; increase reserve if top tiles are obscured. |
| DynamicLake Pro | Reserve notch safe area plus configured vertical padding; float its surfaces. | Keep default notch reserve; add `com.dynamiclake.pro` to `cooperativeApps`. | Expanded island UI can cover top tiles if padding is too small. |
| Tuneful | Reserve notch/top overlay area; float its surfaces. | Keep default notch reserve; add observed bundle ID to user `cooperativeApps` until a built-in ID is verified. | Music popovers can overlap top tiles during expansion. |
| Alfred | Do not steal Alfred's global hotkey; float Alfred windows. | Use `extensions/alfred/`; set `OLLYCTL=/path/to/ollyctl` in workflow env if needed. | Alfred and olly can double-bind the same chord; user chooses one owner. |
| Raycast | Do not steal Raycast's global hotkey; float Raycast windows. | Use `extensions/raycast/`; set `ollyctlPath` if `ollyctl` is not on `PATH`; store submission waits for v0.1. | Raycast and olly can double-bind the same chord; user chooses one owner. |
| LaunchBar | Do not steal LaunchBar's global hotkey; float LaunchBar windows. | Create a LaunchBar action that executes `/path/to/ollyctl` with target args, for example `focus next`. | LaunchBar and olly can double-bind the same chord; user chooses one owner. |
| Klack | Do not add a keyboard event tap; keep hotkey path short. | No direct integration; run Klack normally. | Keyboard sound latency is user-visible; olly hotkey dispatch must stay within NORTHSTAR §12a budget. |
| Klakk | Do not add a keyboard event tap; keep hotkey path short. | No direct integration; run Klakk normally. | [Unverified] Official source not verified on 2026-06-26; passive keyboard-sound conflict model applies. |
| OBS | Park hidden windows outside every `CGDisplayBounds` rect; float OBS controls/overlays. | Use normal display/window capture after olly arranges windows; do not capture parked-window coordinates. | If parked windows appear in capture, offscreen parking is wrong for that display topology. |
| ScreenFlow | Park hidden windows outside every `CGDisplayBounds` rect; float ScreenFlow controls. | Use normal ScreenFlow recording; keep olly Accessibility and recorder Screen Recording grants separate. | Recording overlays can be tiled unless ScreenFlow is in `cooperativeApps`. |
| CleanShot X | Park hidden windows outside every `CGDisplayBounds` rect; float CleanShot controls/overlays. | Use normal CleanShot capture/recording; add `pl.maketheweb.cleanshotx` to `cooperativeApps`. | Selection/recording overlays must not be tiled; parked windows must stay outside capture regions. |
| Karabiner-Elements | Let Karabiner own remapping; olly owns only DSL-declared Carbon hotkeys. | Use a complex modification `shell_command` that runs `ollyctl ...`, or keep command ownership in olly DSL. | Double-bound chords cause duplicate actions; startup collision logging is a separate TODO. |
| skhd | Let skhd own hotkeys when users prefer external bindings. | Bind commands such as `alt - j : ollyctl focus next`. | Double-bound chords cause duplicate actions; choose skhd or olly DSL as owner per chord. |
| BetterTouchTool | Let BetterTouchTool own gestures/hotkeys when users prefer it. | [Unverified] Configure a trigger action that runs `/path/to/ollyctl ...`; keep matching olly DSL chord unbound. | Double-bound gestures/hotkeys cause duplicate actions; BetterTouchTool docs URL was not publicly fetchable from this environment beyond the product site. |
| Hammerspoon | Let Hammerspoon own hotkeys/automation when users prefer Lua. | Use `hs.hotkey.bind(...)` and `hs.execute("/path/to/ollyctl ...")`. | Double-bound chords cause duplicate actions; long Lua callbacks add latency before `ollyctl` runs. |
| JankyBorders | Emit focus events; do not draw first-party borders by default. | Use `extensions/jankyborders/olly_focus_borders.sh &`; it consumes `ollyctl events --json` and avoids duplicate focus replays. | Runtime flicker depends on installed `borders`; fixture verifier only covers duplicate-event suppression. |

## Bundle-ID Policy

Built-in defaults come from `NORTHSTAR.md` §7b. Apps without verified bundle IDs in that
section must stay user-configured until verified through a signed app bundle, `mdls`, or an
official source.

Users extend defaults:

```swift
CooperativeApps {
    CooperativeApp("com.example.CustomOverlay")
}
```

Users replace defaults:

```swift
CooperativeApps(mode: .replace) {
    CooperativeApp("com.example.OnlyOverlay")
}
```

## Source Notes

Checked sources with reachable product or official-doc URLs on 2026-06-26:

- SketchyBar: <https://felixkratz.github.io/SketchyBar/>
- simple-bar: <https://github.com/Jean-Tinland/simple-bar>
- Bartender: <https://www.macbartender.com/>
- Hidden Bar: <https://github.com/dwarvesf/hidden>
- Ice: <https://icemenubar.app/> and <https://github.com/jordanbaird/Ice>
- Alcove: <https://tryalcove.com/>
- NotchNook: <https://lo.cafe/notchnook>
- Boring Notch: <https://github.com/TheBoredTeam/boring.notch>
- Brow: <https://brow-app.com/>
- TopNotch: <https://topnotch.app/>
- MediaMate: <https://wouter01.github.io/MediaMate/>
- DynamicLake: <https://dynamiclake.com/>
- Tuneful: <https://github.com/martinfekete10/Tuneful>
- Alfred Script Filter JSON: <https://www.alfredapp.com/help/workflows/inputs/script-filter/json/>
- Raycast developer docs: <https://developers.raycast.com/>
- LaunchBar actions: <https://www.obdev.at/products/launchbar/actions.html>
- Klack: <https://tryklack.com/>
- OBS macOS screen capture source: <https://obsproject.com/kb/macos-screen-capture-source>
- ScreenFlow: <https://www.telestream.net/screenflow/overview.htm>
- CleanShot X: <https://cleanshot.com/>
- Karabiner-Elements `shell_command`: <https://karabiner-elements.pqrs.org/docs/json/complex-modifications-manipulator-definition/to/shell-command/>
- skhd: <https://github.com/koekeishiya/skhd>
- BetterTouchTool product site: <https://folivora.ai/>
- Hammerspoon hotkeys and shell execution: <https://www.hammerspoon.org/docs/hs.hotkey.html>, <https://www.hammerspoon.org/docs/hs.html#execute>
- JankyBorders: <https://github.com/FelixKratz/JankyBorders>
- Apple safe-area and display bounds APIs: <https://developer.apple.com/documentation/appkit/nsscreen/safeareainsets>, <https://developer.apple.com/documentation/coregraphics/cgdisplaybounds(_:)>

No verified official URL was found from this environment for NotchBook, NotchFlow,
Notchmeister, or Klakk on 2026-06-26.
