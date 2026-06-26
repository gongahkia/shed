# olly — NORTHSTAR

Single source of truth. Load this every session. TODO.md references this file by section anchor.

## 1. One-Line Pitch

`olly` is a pure-Swift macOS window manager whose **layout engine is a first-class plugin primitive, hot-swappable per workspace**, built on accessibility APIs only (no SIP-off required), with River-style tag-based workspaces and ecosystem-friendly first-party hooks for SketchyBar / JankyBorders / Übersicht / Raycast.

## 2. Why This Exists (Differentiators)

- All extant macOS Niri-style WMs (`niri-mac`, `Miri`, `Paneru`, `OmniWM`/`Hiro`, `Nehir`) ship 1–2 hardcoded layouts. Nehir explicitly narrowed scope by dropping multi-layout. Gap: no macOS WM exposes a stable layout-engine plugin contract.
- `J-x-Z/macniri` (the cited inspiration) was archived 2026-04-04. The author concluded a Rust+Wayland port to macOS was a dead end. We avoid that mistake by going pure Swift + AX.
- AeroSpace dominates conventional i3-style tiling on macOS but is single-paradigm. Yabai needs SIP off. Hammerspoon/Phoenix are scripting bridges, not WMs.
- Our wedge: **one binary, N hot-swappable layouts per workspace, user-authored layouts via Swift DSL, ecosystem-friendly**.

## 3. Inspirations (cite in README)

- `niri-wm/niri` — scrollable-tiling Wayland compositor; primary UX model
- `J-x-Z/macniri` — archived Rust port; cautionary tale
- `riverwm/river` — separate-process layout generator pattern; architectural inspiration for our plugin ABI
- `nikitabobko/AeroSpace` — virtual-workspace emulation on top of single native Space
- `BarutSRB/Hiro` (formerly OmniWM) — multi-layout precedent
- `apphane-dev/nehir` — IPC + live config + command palette UX bar to clear
- `karinushka/paneru` — Lua scripting precedent
- `Hammerspoon/hammerspoon` — extension ecosystem reference
- `tmandry/Swindler` — Swift AX abstraction reference (study, do not vendor unless license fits)

## 4. Locked Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **Language:** pure Swift (≥ 5.9) | Native, idiomatic, matches macOS toolchain, AX bindings are direct. |
| D2 | **Privilege:** AX-only, no SIP off, no private CGS in v0.x | Broadest audience, no enterprise blockers, survives macOS updates. |
| D3 | **Workspace model:** River-style **tags** | Windows carry tag bitfields; displays show one or more tags. Per-display independent. |
| D4 | **Layout-engine ABI:** Swift protocol; built-ins compiled in v0.1; user-authored via Swift DSL recompile path; dynamic `.dylib` deferred to ≥ v1.0 once ABI stabilizes. | Type safety, no Swift-ABI fragility, fast enough iteration via DSL package. |
| D5 | **Config:** Custom Swift DSL (result builders), single `~/.config/olly/Config.swift` compiled to a sidecar that olly loads. | Powerful, type-checked, plugin contracts are first-class types. |
| D6 | **IPC:** Unix-domain socket + `ollyctl` CLI; JSON line protocol. | Matches Nehir/AeroSpace expectations; scriptable from any language. |
| D7 | **Multi-monitor:** per-display tag state, per-display layout, AeroSpace-style virtual workspaces emulated on a single native Space. | Native Spaces are unreliable; emulation is the only stable path. |
| D8 | **Distribution:** Developer ID signed + notarized; Homebrew cask; loose-leaf zip. | Enterprise-safe (Hiro precedent), zero install friction. |
| D9 | **License:** MIT (or Apache-2.0 if defensive patent grant matters; default MIT for permissiveness). | Maximizes adoption. |
| D10 | **Plugin namespacing:** Bundle ID prefix `dev.olly.*`; package prefix `olly-plugin-*`. | Discoverability, anti-squatting. |

## 5. v0.1 MVP Layout Engines (built-in)

1. **NiriScroll** — infinite horizontal strip of columns; per-column dynamic vertical splits; viewport scrolls to focus.
2. **BSP** — recursive binary split tree (Hyprland Dwindle / Yabai BSP).
3. **Manual** — i3-style explicit preselect split direction.
4. **Floating** — non-tiling escape hatch, per-window opt-in; supports stacking.
5. **MasterStack** — XMonad-Tall: one master pane + N stacked slaves; configurable ratio + master count.

Each engine implements the same `LayoutEngine` protocol (see TODO.md Phase 3).

## 6. v0.2+ Layouts (defer, document as community-plugin examples)

Layouts are added in priority tiers. Each tier ships as a minor release. All implement the `LayoutEngine` protocol; nothing privileged about built-ins.

**Tier 1 — v0.2 (cheap wins, broad appeal):**
- **Monocle / Fullscreen** — single visible tiled window; cycle siblings via keybinds (dwm precedent).
- **Spiral / Fibonacci** — Yabai/XMonad spiral; recursive golden-ratio splits.
- **Grid** — N×M auto-arrange; configurable row/col policy (square-ish, fixed-cols, fixed-rows).
- **ThreeCol / CenteredMaster** — ultrawide-friendly; master in center, slaves left+right.
- **Accordion** — vertical ribbons; non-focused windows collapse to title strips (XMonad Accordion).

**Tier 2 — v0.3 (advanced UX):**
- **Pseudotile** (Hyprland) — window respects its preferred size centered in its allocated tile; great for dialogs/preferences inside a tile.
- **Tabbed** (i3/Sway) — multiple windows share a tile, accessed via a tab bar.
- **Stacked** (i3/Sway) — like Tabbed but with full-height title stack.
- **TreeTab** (Qtile) — vertical tree of tabbed windows on a side rail.
- **VerticalTile** (Qtile) — optimized for vertically rotated monitors; one master full-height + horizontal slaves.
- **RatioTile** (Qtile) — preserves per-window aspect ratio constraints across an N-window pack.

**Tier 3 — v1.0 (architectural):**
- **Herbstluftwm-style Frame tree** — manual frames that recursively split; each frame is itself a mini-layout host with its own engine choice. Enables nested layouts (e.g., Niri-scroll outside, BSP inside one column).
- **Multi-tag union view** (River) — display shows the *union* of windows from multiple active tags simultaneously; engine arranges the union.
- **Pinned columns** (hyprscroller / PaperWM) — within scrollable engines, pin a column to remain visible during scroll.
- **PaperWM-style scrolling** — alternative scrollable model where columns have variable widths driven by content.

**Further research (track in `docs/layouts-research.md`):**
Review continuously: Hyprland master+slave plugins, dwm monocle nuances, awesome/qtile dynamic layouts catalog, herbstluftwm frame semantics, scrollwm prototypes, hy3 (Hyprland's i3-clone plugin), hyprscroller, PaperWM (GNOME), Karousel (KDE), Bismuth (KDE).

## 7. Ecosystem Posture (do not reinvent)

**Principle: olly *yields* screen real estate and *emits* events. It does not compete with these tools.** Concrete contract:

### Status / Menu bar replacements
- **SketchyBar** — ship `extensions/sketchybar/` with example consuming `ollyctl events`; document a copy-paste integration. SketchyBar reserves the entire native menubar height.
- **Übersicht / simple-bar** — publish stable IPC schema; provide a working widget example.
- **Bartender / Hidden Bar / Ice** — these hide/manage native menubar items. olly does not need to integrate, but must keep its own status item visible/hideable by user choice (no force-pinning).

### Notch utilities (MacBook Pro 14"/16" with notch)
- **Alcove, NotchNook, Boring Notch, NotchBook, Brow, NotchFlow, TopNotch, Notchmeister, MediaMate, DynamicLake Pro, Tuneful** — these draw UI in the notch region and frequently expand below it on hover/event.
- **Contract:** olly *reserves a configurable rectangle around the notch* (default: notch width + 24 px on each side, 60 px below) as a no-tile zone. Floating windows may overlap; tiled windows must not. See §14.
- **Detection:** read `NSScreen.safeAreaInsets` (macOS 12+) to detect notched displays; default reserve = `safeAreaInsets.top + 12 px` on those displays.

### Launchers / palettes
- **Alfred** — ship an Alfred workflow (`extensions/alfred/`) exposing the top ~15 olly IPC commands. Mirror Klack-Control-for-Alfred pattern.
- **Raycast** — ship `extensions/raycast/` extension; publish to Raycast store after v0.1.
- **LaunchBar** — document the equivalent JS action recipe; no first-party bundle.
- **olly's own palette** is opt-in; does not steal the global hotkey space used by these tools.

### Window borders / focus aids
- **JankyBorders** — olly emits focus events; does not draw its own borders by default. Document a one-line subscriber config.

### Hotkey daemons
- **Karabiner-Elements, skhd, BetterTouchTool, Hammerspoon** — olly owns its DSL-declared hotkeys via Carbon `RegisterEventHotKey`. Documents how users can delegate (skhd → `ollyctl ...`) or share modifier layers (Karabiner complex modifications). Conflict-detection on startup: log any double-bound chord and let user choose.

### Keyboard sound / typing utilities
- **Klack, Klakk, TypeJoy** — purely passive event consumers; olly need not integrate, but must not introduce latency in the keyboard event path that would degrade these tools. See §13 perf budget on hotkey-to-action latency.

### Screen recording / streaming
- **OBS, ScreenFlow, CleanShot X** — olly must not break full-screen capture or screen-recording overlays. Window-park offscreen coordinates must lie outside `CGDisplayBounds` for all attached displays; never overlap a recording region.

### Catch-all
- Default mode is **opportunistic non-interference**: any window whose owning app is in the user-configurable `cooperativeApps` list is auto-floated and excluded from tile placement.

## 7b. Cooperative-Apps Default Allowlist

These bundle IDs are floated by default; users can override in DSL. Updated as ecosystem evolves; track in `docs/menubar-notch-integration.md`.

```
# Notch utilities
com.lowtechguys.Alcove
com.akashpawar.notchnook
com.tymmesyde.boring-notch
com.lukegrubb.NotchFlow
com.codykerns.TopNotch
com.notchmeister.Notchmeister
com.brow-app.Brow
com.dynamiclake.pro
# Menu bar / launcher
com.surteesstudios.Bartender
com.dwarvesf.hidden
com.jordanbaird.Ice
com.runningwithcrayons.Alfred
com.raycast.macos
at.obdev.LaunchBar
# Status bar replacements (don't tile their own surfaces)
com.felixkratz.SketchyBar
de.tracesof.Uebersicht
# Borders / overlays
com.felixkratz.JankyBorders
# Keyboard sound
com.tryklack.Klack
com.klakk.macos
# Screen capture
com.obsproject.obs-studio
com.araelium.screenflow6
pl.maketheweb.cleanshotx
# Hotkey daemons
org.pqrs.Karabiner-EventViewer
com.koekeishiya.skhd
com.hegenberg.BetterTouchTool
org.hammerspoon.Hammerspoon
```

## 8. Non-Goals (explicit)

- **No** Wayland compatibility, no XQuartz integration.
- **No** native macOS Spaces orchestration (Mission Control will not see our virtual workspaces; document this loudly).
- **No** SIP-off-only features in v0.x.
- **No** full-screen-app interception (let macOS handle native fullscreen).
- **No** custom rendering surface; we move/resize native windows only.
- **No** in-process scripting language (Lua/JS/Tomo) in v0.x — Swift DSL is the contract.
- **No** auto-update outside Homebrew + GitHub Releases.

## 9. Quality Gates (every PR)

- `swift build` + `swift test` green on macOS 14+ runners.
- `swiftlint` clean (config in repo).
- `swiftformat --lint` clean.
- AX permission flow exercised in a UI test on a clean profile.
- No private API symbol references (grep gate in CI).
- README screencast / GIF up to date if user-visible behavior changed.

## 10. Vocabulary (use these exact terms in code + docs)

- **Window** — a single AX-known window of a running app.
- **Tag** — a named bitfield slot. Windows carry a tag set; displays show one or more active tags.
- **Display** — physical monitor (`CGDirectDisplayID`).
- **Layout Engine** — pluggable module that arranges windows of a given (display, active-tags) pair.
- **Workspace View** — the visible (display, active-tags, engine) tuple at a moment.
- **Column** (Niri-only) — a vertical stack inside the scrollable strip.
- **Slot** — one engine-owned rectangle a window occupies.
- **Rule** — declarative DSL clause that assigns initial tags/engine/floats to matching windows.
- **`ollyctl`** — the CLI; never abbreviate to `octl`.

## 11. Top-Level Repo Layout (target state)

```
olly/
├── Package.swift                  # SwiftPM root
├── Sources/
│   ├── ollyKit/                   # AX wrappers, observer, window model
│   ├── ollyCore/                  # tag store, dispatcher, focus stack
│   ├── ollyLayouts/               # 5 built-in engines, plus LayoutEngine protocol
│   ├── ollyDSL/                   # config result-builder + plugin contracts
│   ├── ollyIPC/                   # socket server + JSON protocol
│   ├── ollyApp/                   # menubar app target
│   └── ollyctl/                   # CLI client
├── Tests/
├── extensions/
│   ├── raycast/
│   ├── alfred/
│   ├── sketchybar/
│   ├── jankyborders/
│   └── ubersicht/
├── docs/
│   ├── architecture.md
│   ├── plugin-authoring.md
│   ├── layouts-research.md
│   ├── multi-monitor.md
│   ├── menubar-notch-integration.md
│   ├── dsl-reference.md          # generated by DocC
│   ├── performance.md
│   ├── ipc.md
│   └── safe-zones.md
├── examples/
│   └── Config.swift
├── scripts/
├── .github/workflows/
├── README.md
├── NORTHSTAR.md
├── TODO.md
└── LICENSE
```

## 12a. Performance Budgets (hard limits, enforced in CI perf suite)

Performance is a P0 concern, not a polish item. AeroSpace is the bar to clear — and to surpass on long-uptime stability. Verified user complaints we must not reproduce: 30–60 s wake delays, multi-day memory growth, sluggish workspace switches, perceptible input lag.

| Metric | Budget | Measurement |
|---|---|---|
| Cold start → menubar visible | ≤ 400 ms (M-series), ≤ 800 ms (Intel) | wall clock from `main` to `NSStatusItem.button.isHidden = false` |
| Hotkey press → action begin | ≤ 5 ms p99 | Carbon callback in, dispatcher enter |
| Hotkey press → window moved | ≤ 50 ms p95, ≤ 120 ms p99 | end-to-end with one moved window |
| Tag switch (50 windows, 2 displays) | ≤ 80 ms p95 | last AX move call settles |
| Layout-engine recompute | ≤ 4 ms p95, ≤ 16 ms p99 (one frame) | `arrange()` entry → return |
| Wake-from-sleep recovery | ≤ 500 ms to focused-window restore | wake notification → focus event |
| Steady-state CPU (idle, 50 windows) | ≤ 0.1 % | `top -pid` sampled 10 s |
| Steady-state RSS (50 windows) | ≤ 60 MB | `ps -o rss` |
| RSS growth over 7 days | ≤ 5 % from day-1 baseline | longevity soak in CI |
| AX call rate (idle) | 0/sec | signpost counter |
| AX call rate (active drag) | ≤ 120/sec coalesced | signpost counter |

**Engineering tactics that derive from these budgets:**
- All AX writes are *coalesced per display per frame* via a single dispatch source; we never call `AXUIElementSetAttributeValue` more than 60×/sec/display.
- WindowStore is an actor; reads from UI/layout paths use cached snapshots, never sync AX calls.
- Layout engines are pure functions over snapshots; no I/O, no AX, no Swift Concurrency suspension points inside `arrange()`.
- AsyncStream backpressure on AX observers; drop coalescible deltas, never queue unboundedly.
- No NSTimer polling. All work is event-driven from AX observers, display reconfig callbacks, or `NSWorkspace` notifications.
- Diff-only window moves: skip the AX call if target frame ≈ current frame within 1 px.
- Per-target tracing via `os_signpost` so Instruments + `xctrace` deliver actionable flamegraphs.
- Release builds use `-O -whole-module-optimization`; tests cover both debug + release perf.

## 12b. Safe Zones / Reserved Regions

Tiled placements must respect these exclusions per display. The Layout-Engine `arrange()` receives `bounds` already shrunk by safe zones; engines never see the raw `NSScreen.frame`.

| Zone | Default reserve | Source | Override |
|---|---|---|---|
| Native menubar | `NSScreen.frame.height - visibleFrame.height` from top | macOS | force-tile-under-menubar (advanced) |
| Notch | safeAreaInsets.top + 12 px buffer | `NSScreen.safeAreaInsets` | per-display `notchPadding(_:)` |
| Dock (when visible) | `visibleFrame` already excludes it | macOS | none |
| User-declared no-tile rects | DSL `reserve(rect:on:)` | user | declarative |
| Cooperative-app windows | sized to their current frame | runtime | `cooperativeApps` list |

[Inference] On notched displays, the safe-area top inset already accounts for the notch height when reading `NSScreen.safeAreaInsets` on macOS 12+; we add 12 px buffer for notch-utility expansion animations like Alcove's hover state.

## 13. Success Criteria (when do we ship v0.1?)

- 5 layout engines work end-to-end on 1+ display.
- Tag-based workspace switching works on 2+ displays without window drift.
- All §12a performance budgets met in CI perf suite.
- Safe zones (menubar, notch, cooperative apps) respected on notched + non-notched displays.
- `ollyctl` covers: focus, swap, move-to-tag, set-engine, reload, state-dump.
- Swift DSL config example exercises all 5 engines + rules + keybinds; `docs/dsl-reference.md` is generated from doc-comments via DocC.
- Cold-start to first-paint < 400 ms on M-series; memory < 60 MB resident with 50 windows.
- README has a 30-second demo GIF; first-run flow handles AX permission gracefully.
- Signed + notarized `.dmg` on GitHub Releases; Homebrew cask submitted.

## 14. DSL Design Principles

The Swift DSL is the user-facing contract. Its quality determines adoption above any feature count.

1. **Discoverable** — Xcode/SourceKit autocomplete must surface every option without docs lookup. Use enums and key-paths, not stringly-typed APIs.
2. **Composable** — every primitive (keybind, rule, engine, tag) returns a value that can be stored in `let`, passed to helpers, reused. No registration-by-side-effect.
3. **Type-safe** — invalid configurations are *compile errors*, not runtime errors. Examples: assigning the same chord twice, naming the same tag twice, referencing an undeclared engine ID.
4. **Layered defaults** — every primitive has sensible defaults; users override only what they care about. `Config { }` (empty) must produce a usable, sensible WM.
5. **Live-reload safe** — primitives are reference-transparent; reloading swaps the entire registry atomically; old listeners are torn down deterministically.
6. **Plugin-extensible** — third-party Swift packages register layout engines, action verbs, rule predicates, and event sinks via the same primitives the built-ins use. No internal/external API split.
7. **Escape hatches** — every level of declarative API has an `.raw { ctx in … }` escape that gives users a Swift closure with the underlying state. Power users are never blocked.
8. **Self-documenting** — every primitive carries a Swift `@_documentation(visibility:)` doc-comment; DocC build is part of CI; `docs/dsl-reference.md` is the rendered output.

The DSL contract is itself versioned (`DSLVersion.v1`). Breaking changes require a major bump and a migration tool (`ollyctl migrate-config`).
