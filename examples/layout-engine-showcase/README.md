# Layout Engine Showcase

Small olly layout engines as case studies for external authors.

Run from the olly repo root:

```sh
swift test --package-path examples/layout-engine-showcase
```

## Case Studies

- `DwmMonocleLayoutEngine`: every managed window fills the display; focus controls stack order.
- `DwindleSpiralLayoutEngine`: recursively splits the remainder with configurable ratio and chirality.
- `FlexibleThreeColLayoutEngine`: XMonad-style three-column layout with center/leading master modes.
- `FocusBandLayoutEngine`: focused window gets a wide center band; siblings split side rails.
- `GoldenColumnsLayoutEngine`: columns shrink by a golden-ratio progression.
- `MatrixGridLayoutEngine`: fixed-column grid with row-major or column-major fill and gaps.
- `PriorityGridLayoutEngine`: priority windows reserve the first row, remaining windows fill a grid.

All showcase engines keep `arrange()` synchronous and pure: no AX, no I/O, no async suspension, and no mutation outside returned placements.

Runtime `.dylib` loading is intentionally not demonstrated. olly v0.x external engines are SwiftPM packages loaded through the config sidecar path.

## Monocle RFC

Proposal: keep the built-in `MonocleLayoutEngine` conservative with hidden siblings, and put dwm-style fullscreen stacking in an external plugin. `DwmMonocleLayoutEngine` assigns every window the display bounds, leaves every placement visible, and uses `zOrder` plus `nextFocus` / `previousFocus` to express the focus stack.

Plugin knobs:

- `sortByLayoutOrder`: cycle by persisted layout order instead of raw input order.
- `focusedOnTop`: raise the focused window without changing the cycle order.

Metadata:

- `zOrder` is the stack position.
- `hidden == false` means overlays can render every stack participant without inventing offscreen geometry.

Fixture coverage:

- empty input
- missing focus fallback
- next/previous wraparound
- layout-order ties via `WindowSnapshot.precedes`

Config snippet:

```swift
Engines {
    EngineDeclaration(LayoutEngineID(rawValue: "dev.olly.showcase.dwm-monocle"))
}
```

## Spiral RFC

Proposal: keep the built-in `SpiralLayoutEngine` as the stable Fibonacci-style default, and use a showcase plugin for the XMonad/Yabai dwindle family. `DwindleSpiralLayoutEngine` always splits the current remainder, then rotates the split direction clockwise or counterclockwise.

Plugin knobs:

- `ratio`: fraction assigned to the next window before recursing.
- `startDirection`: first split edge.
- `clockwise`: chirality of the spiral.

Metadata:

- `zOrder` follows recursion order.
- Frames expose each recursive remainder split directly for overlay/debug rendering.

Fixture coverage:

- empty input
- one-window full bounds
- clockwise and counterclockwise chirality
- ratio clamp limits

Config snippet:

```swift
Engines {
    EngineDeclaration(LayoutEngineID(rawValue: "dev.olly.showcase.dwindle-spiral"))
}
```

## Grid RFC

Proposal: keep the built-in `GridLayoutEngine` squareish and deterministic, and put Matrix/fair-style variants in plugins. `MatrixGridLayoutEngine` fixes the column count, chooses row-major or column-major fill, and applies a per-cell gap for visual separation.

Plugin knobs:

- `columns`: explicit matrix width.
- `fillOrder`: row-major for editor-like tiling, column-major for terminal stack tiling.
- `gap`: spacing applied inside each cell.

Metadata:

- `zOrder` follows packed matrix order.
- Frames expose row/column geometry directly; overlays can infer cell position from frame and z-order.

Fixture coverage:

- empty input
- incomplete final row
- row-major and column-major fill
- clamped column count and gap inset math

Config snippet:

```swift
Engines {
    EngineDeclaration(LayoutEngineID(rawValue: "dev.olly.showcase.matrix-grid"))
}
```

## ThreeCol RFC

Proposal: keep the built-in `ThreeColLayoutEngine` centered and single-master. `FlexibleThreeColLayoutEngine` models the XMonad `ThreeCol` / `ThreeColMid` split by making master position and master count plugin policy.

Plugin knobs:

- `masterCount`: number of windows stacked in the master column.
- `masterRatio`: width reserved for the master column.
- `masterPosition`: `.center` for ThreeColMid, `.leading` for side-master ThreeCol.

Metadata:

- `zOrder` groups masters first, then first side column, then second side column.
- Column role is inferable from frame position.

Fixture coverage:

- empty input
- one-window full bounds
- centered multi-master geometry
- leading-master geometry
- clamped master count and ratio

Config snippet:

```swift
Engines {
    EngineDeclaration(LayoutEngineID(rawValue: "dev.olly.showcase.flex-three-col"))
}
```
