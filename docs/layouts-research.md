# Layouts Research

Backlog notes for post-v0.1 layout plugins. These are stubs, not commitments to ship in core.

## 2026-06-28 Landscape Survey

Scope: `NORTHSTAR.md` §2 and §6. Search covered current macOS Niri-style ports,
AeroSpace/yabai changes, and Hyprland/PaperWM-style layout plugin trends.

Findings:

- `J-x-Z/macniri` and `maria-rcks/miri` are archived. `dimixar/miri` is an
  independent active continuation with a Niri-like column model, virtual
  workspaces, snapshot transitions, and a small declared private-API surface.
- New or newly noticed macOS Niri-style experiments include `ncky/klotski`,
  `pde201/niri-mac` (`nami`), `Gwen0x4c3/NiriSpace`, `stearz/Darniri`, and
  `Floxyi/Spatial`. They broaden the landscape, but each still centers one
  workflow: scrolling columns, AeroSpace-derived Niri layout, or a spatial
  workspace canvas.
- `Paneru`, `OmniWM`/`Hiro`, `Nehir`, and `AeroSpace` all remain active as of
  this survey. `Nehir` has IPC and a command palette, but its README describes
  the Niri scrolling column layout as the core layout paradigm.
- `hy3` remains active in the Hyprland ecosystem. `hyprscroller` is still listed
  by Awesome Hyprland, but the repo is archived; track `hyprslidr` as another
  sliding/PaperWM-inspired plugin.
- `PaperWM` remains active and is still the best GNOME precedent for variable
  width scrolling columns. `yabai` remains the mature macOS BSP/SIP tradeoff
  reference.

Positioning impact:

- Updated `NORTHSTAR.md` §2 to mention the broader active macOS Niri-style set.
  [Inference] The olly wedge remains the stable layout-engine plugin contract plus
  per-workspace hot-swapping.

Sources checked on 2026-06-28:

- https://github.com/J-x-Z/macniri
- https://github.com/maria-rcks/miri
- https://github.com/dimixar/miri
- https://github.com/ncky/klotski
- https://github.com/pde201/niri-mac
- https://github.com/Gwen0x4c3/NiriSpace
- https://github.com/stearz/Darniri
- https://github.com/Floxyi/Spatial
- https://github.com/karinushka/paneru
- https://github.com/BarutSRB/OmniWM
- https://github.com/apphane-dev/nehir
- https://github.com/nikitabobko/AeroSpace
- https://github.com/asmvik/yabai
- https://github.com/outfoxxed/hy3
- https://github.com/dawsers/hyprscroller
- https://github.com/paperwm/PaperWM
- https://github.com/hyprland-community/awesome-hyprland

## 2026-06-28 Layout Release Monitor

Scope: recurring watchlist from TODO Phase 13.

Current state:

- PaperWM: active; latest GitHub release `v50.0.1` published 2026-04-21.
- Karousel: active KDE/KWin scrollable tiling script; latest GitHub release
  `v0.17` published 2026-06-07.
- Bismuth: archived; latest GitHub release `v3.1.4` was published 2022-09-23.
  Keep as an i3-like KDE precedent, not as an active release source.
- hy3: active Hyprland i3/manual-tiling plugin; latest GitHub release
  `hl0.55.0` published 2026-05-14.
- hyprscroller: archived; no GitHub latest-release endpoint. Keep as historical
  precedent for Hyprland scrolling layouts.
- scroll (`dawsers/scroll`): active sway fork with one PaperWM/niri-like scrolling
  layout; latest GitHub release `1.12.15` published 2026-05-26.

Sources checked on 2026-06-28:

- https://github.com/paperwm/PaperWM
- https://github.com/peterfajdiga/karousel
- https://github.com/Bismuth-Forge/bismuth
- https://github.com/outfoxxed/hy3
- https://github.com/dawsers/hyprscroller
- https://github.com/dawsers/scroll

## v0.2 Candidates

### Monocle

Source model: dwm's `monocle(Monitor *m)` counts visible clients, updates the layout symbol, then resizes every tiled client to the monitor work area. This is the cleanest precedent for olly because it is pure placement with focus cycling outside the layout.

olly shape:
- `MonocleLayoutEngine`
- focused tiled window receives `bounds`
- siblings are hidden offscreen with `hidden = true`
- cycle helpers expose next/previous focus IDs

Screenshot:

![Monocle layout screenshot](layout-screenshots/monocle.png)

Source:
- https://git.suckless.org/dwm/file/dwm.c.html

### Spiral / Fibonacci

Source model: XMonad's `XMonad.Layout.Spiral` exposes `spiral` and `spiralWithDir`, with a ratio controlling successive window sizes and configurable starting direction/rotation. awesome also exposes `awful.layout.suit.spiral` and `awful.layout.suit.spiral.dwindle` in its built-in layout list.

olly shape:
- `SpiralLayoutEngine`
- config: `splitRatio`, defaulting to the golden ratio
- pure recursive split of the remaining rect
- alternates split sides while choosing the longer remaining axis
- no hidden windows

Screenshot:

![Spiral layout screenshot](layout-screenshots/spiral.png)

Source:
- https://xmonad.github.io/xmonad-docs/xmonad-contrib/XMonad-Layout-Spiral.html
- https://awesomewm.org/apidoc/libraries/awful.layout.html

### Grid

Source model: Qtile `Matrix` divides the screen into equal cells with configurable columns. awesome's `fair` layout also targets roughly equal client sizes.

olly shape:
- `GridLayoutEngine`
- config: `policy = squareish | fixedCols(Int) | fixedRows(Int)`
- deterministic row-major placement
- windows are sorted by AX window ID before placement
- last row may have empty cells; do not resize earlier rows based on tail count

Screenshot:

![Grid layout screenshot](layout-screenshots/grid.png)

Source:
- https://docs.qtile.org/en/latest/manual/ref/layouts.html
- https://awesomewm.org/apidoc/libraries/awful.layout.html

### ThreeCol / CenteredMaster

Source model: XMonad `ThreeCol` is Tall-like but with three columns. `ThreeColMid` places the main window between stack columns; both stack columns use the same size when both are visible.

olly shape:
- `ThreeColLayoutEngine`
- config: `masterRatio`
- centered master uses left/right stacks with balanced window counts
- ultrawide-first defaults, not a MasterStack replacement

Screenshot:

![ThreeCol layout screenshot](layout-screenshots/three-col.png)

Source:
- https://xmonad.github.io/xmonad-docs/xmonad-contrib/XMonad-Layout-ThreeColumns.html

### Accordion

Source model: XMonad exposes decorated layouts based on `Accordion`. Its practical UI is vertical ribbons where only one region is expanded and siblings remain visible through title/decor strips.

olly shape:
- `AccordionLayoutEngine`
- config: `stripHeight`
- focused window gets expanded rect
- non-focused windows keep small top/bottom strips
- strip height clamps when needed to keep the expanded region visible

Screenshot:

![Accordion layout screenshot](layout-screenshots/accordion.png)

Source:
- https://xmonad.github.io/xmonad-docs/xmonad-contrib/XMonad-Layout-DecorationMadness.html

## v0.3+ Research Hooks

### Tabbed / Stacked Bar UX

Tabbed and Stacked layouts need a visible selector because multiple windows share one logical tile. The layout engine should stay pure and emit placement plus tab/stack metadata; rendering belongs in `ollyApp`.

AX-only rendering:
- Uses the target windows' own frames, titles, focus, and raise actions.
- Keeps olly out of the drawing path and avoids overlay z-order bugs.
- Cannot draw real tab strips or stack rails on another app's window chrome.
- Offers no reliable pointer hit targets for selecting hidden siblings.
- Works as a keyboard-only fallback when overlay windows are disabled.

Overlay `NSWindow` rendering:
- Uses olly-owned borderless panels positioned above the tile or along the stack rail.
- Can draw selected state, titles, icons, hover, drag targets, and click-to-focus.
- Must track display changes, safe zones, active tags, window moves, and Space visibility.
- Must not become an app switcher target, steal key focus during normal tiling, or cover notch/menu-bar reserves.
- Needs explicit accessibility labels and keyboard equivalents so tabs are not pointer-only.

Decision for implementation:
- Keep `TabbedLayoutEngine` and `StackedLayoutEngine` pure.
- `TabbedLayoutEngine` reserves a top tab-strip rect, places only the selected window in the content rect, and emits `TabbedLayoutTab` metadata for an app overlay.
- `StackedLayoutEngine` reserves a left title rail, places only the selected window in the content rect, and emits `StackedLayoutItem` metadata.
- `TreeTabLayoutEngine` reserves a configurable side tree rail, places only the selected window in the content rect, and emits depth-aware `TreeTabLayoutItem` metadata.
- Add a separate `TabBarOverlayController` in `ollyApp` fed by engine metadata.
- Treat AX-only mode as keyboard fallback, not as the primary tab/stack UI.
- Do not model overlays as child windows of foreign app windows; olly owns them and repositions them from snapshots.

### Frame Tree

Source model: herbstluftwm starts with one frame; frames can contain windows or split into two frames. This maps to an olly plugin host that can nest layout engines inside frame leaves.

Source:
- https://herbstluftwm.org/tutorial.html
- https://man.archlinux.org/man/herbstluftwm.1

### Multi-Tag Union

Source model: river supports tags instead of fixed workspaces; a window can have multiple tags and multiple tags can be displayed at once. olly already stores tags as a bitset, so the layout input can be the active union without changing the engine protocol.

Source:
- https://github.com/riverwm/river
- https://isaacfreund.com/blog/river-intro/

### Pinned Columns

Source model: hyprscroller exposes `scroller:pin` for pinning a column in a scrolling layout. Niri and PaperWM establish the scrollable column model: columns live on an infinite horizontal strip and focus/navigation scrolls the visible viewport.

olly shape:
- `PinnedColumnsLayoutEngine<Base>`
- wraps a scrollable base engine instead of extending `LayoutEngine`
- detects columns from shared placement x-position and width
- moves configured pinned columns to a fixed viewport edge
- raises pinned placements above the scrolling strip so overlap remains visible

Source:
- https://github.com/dawsers/hyprscroller
- https://github.com/niri-wm/niri
- https://github.com/paperwm/PaperWM

### PaperWM Scroll

Source model: PaperWM tiles new windows to the right of the active window, scrolls the tiling to reveal activated windows, can absorb windows vertically into the active column, and supports preferred window widths. This differs from `NiriScrollLayoutEngine`, whose columns use configured width presets (`oneThird`, `half`, `twoThirds`, `full`) rather than content-derived widths.

olly shape:
- `PaperWMScrollLayoutEngine`
- `PaperWMScrollStrip` tracks explicit columns and viewport offset
- column width is the max configured/current width of the windows in that column
- missing windows append as new columns to the right
- focus scroll clamps the viewport just enough to reveal the focused column

Source:
- https://github.com/paperwm/PaperWM
- https://github.com/niri-wm/niri

## Implementation Gate

For any candidate above:
- add focused unit tests for geometry edge cases
- add a golden fixture under `Tests/ollyLayoutsTests/Fixtures/LayoutSnapshots/`
- emit typed `EngineEvent`s for user-visible state changes
- keep `LayoutEngine.arrange()` synchronous and pure
- no private macOS APIs
