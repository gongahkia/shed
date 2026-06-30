# Multi-Monitor Model

Status: v0.1 target contract. Scope: `NORTHSTAR.md` section 7 and section 12b.

Olly treats each physical `Display` as an independent workspace view while keeping all managed
windows on one native macOS Space. The visible state is:

```text
(display, active tags, layout engine, safe layout bounds)
```

## Virtual Workspace Emulation

Native Spaces are not the workspace primitive. Tags are.

- A `WindowState` stores a tag bitfield.
- `TagStore` stores active tags per display.
- `TagDispatcher` parks windows whose tags are not active for the display being applied.
- Parked windows move outside every active `CGDisplayBounds` rect through `OffscreenParking`.
- Layout engines only arrange the visible windows for one `(display, activeTags)` view.

This matches the AeroSpace-style single-Space approach called out in `NORTHSTAR.md` section 7,
but with River-style tags instead of one workspace index per display.

## Per-Display Engine Binding

Each display can show a different active tag set, and each active tag can bind to a different
layout engine. That makes these states valid at the same time:

| Display | Active tags | Engine |
|---|---|---|
| Built-in | `code` | `bsp` |
| External | `web` | `niri-scroll` |
| Studio Display | `ops`, `meet` | `master-stack` |

The engine receives bounds already adjusted for menu bar, Dock, notch, and user reserves. See
`SafeZoneCalculator` and `docs/menubar-notch-integration.md`.

## Single-Space Invariant

Olly does not drive Mission Control. Managed windows are expected to remain on the same native
Space so virtual tag switches can be implemented by moving and parking windows.

`NativeSpaceInvariant` checks managed windows against a baseline `NativeSpaceID`:

- `isVerified == true`: every known managed window shares the same native Space.
- `unsupportedNativeSpaces`: the active provider cannot inspect native Space IDs through public APIs.
- `unknownSpace`: public APIs cannot identify a window's native Space.
- `drifted`: a window moved to another native Space.

Current public provider behavior is conservative and public-only: olly does not call private SkyLight/CGS
Spaces APIs. The default provider reports `unsupportedNativeSpaces` instead of guessing.
At runtime, olly uses `CGWindowListCopyWindowInfo(.optionOnScreenOnly)` as a public active-Space
presence heuristic and keeps absent managed windows in state as `isOffSpace`.

Drift policy:

| Policy | Behavior |
|---|---|
| `.followWindow` | Keep drifted windows managed, set `isOffSpace`, and skip layout until the window returns. |
| `.unmanage` | Remove drifted windows from olly management and leave macOS in control. |
| `.rehome` | Ask the platform layer to move the window back to the baseline Space. |

## Hotplug Behavior

Display changes arrive through `CGDisplayRegisterReconfigurationCallback` and are exposed as
`DisplayChange` events from `DisplayMonitor`.

On hotplug, olly should:

1. Refresh the sorted display list.
2. Recompute safe layout bounds for every display.
3. Recompute offscreen parking from current active `CGDisplayBounds`.
4. Preserve per-display tag state for displays that still exist.
5. Move orphaned display state to the main display only after the original display disappears.
6. Re-run layout for visible tags.
7. Re-run `NativeSpaceInvariant` before accepting the new display topology as stable.

If a display is unplugged while windows are parked, the next parking operation must recompute
against the remaining active display bounds. `OffscreenParking` tests cover this.

## Known Limits vs Mission Control

| Area | Olly behavior | Mission Control behavior |
|---|---|---|
| Workspace identity | Tags live inside olly state. | Spaces are native system objects. |
| App expose thumbnails | Parked windows may not map cleanly to native Space thumbnails. | Native windows remain in their Space. |
| Fullscreen apps | Out of scope for v0.x; macOS owns native fullscreen. | Fullscreen apps create native Spaces. |
| Per-Space wallpapers/focus modes | Not represented. | Owned by macOS. |
| Cross-Space window moves | Marked `isOffSpace` when observable; optional policies can rehome or unmanage. | Native operation. |
| Crash recovery | `restore-windows` uses the recovery journal to move parked windows back when AX targets are still known. | macOS keeps native Space assignment. |

## Verification

- `NativeSpaceInvariantTests` cover verified, drifted, follow-window, rehome, unmanage, unsupported, and unknown-space cases.
- `OffscreenParkingTests` cover active `CGDisplayBounds` avoidance and display-unplug recompute.
- `TagDispatcherTests` cover display-scoped apply and offscreen parking through a display provider.
- `OllyRuntimeAXAcceptanceTests` are opt-in: set `OLLY_RUN_AX_ACCEPTANCE=1` and grant Accessibility trust.
- `OllyRuntimeAXMatrixTests` are opt-in: set `OLLY_RUN_AX_MATRIX=1`; they emit an installed/running/window-count compatibility report for common apps.
