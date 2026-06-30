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

## M2 — Hackability surface wire-up

### M2.3 Wire `CooperativeApps` runtime behaviors

**Goal:** Today `Sources/ollyDSL/CooperativeApps.swift` is intent-only — `Config.resolvedApply` (`Sources/ollyDSL/Rule.swift:259-266`) auto-floats cooperative app windows. Add three more behaviors that the runtime actually executes.

**New behaviors:**
```swift
public enum CooperativeBehavior: String, Codable, Sendable {
    case floatOnly             // existing default
    case floatAndHideOnSwitch  // park off-screen when tag inactive
    case floatAndReserveSpace  // contribute frame to safe zones
    case dockAware             // shrink layout frame to its outer rect
}
```

**Files to modify:**
- `Sources/ollyDSL/CooperativeApps.swift` — extend `CooperativeApp.init(_ bundleID: String, behavior: CooperativeBehavior = .floatOnly)`.
- `Sources/ollyKit/SafeZoneCalculator.swift` — accept dynamic `SafeZoneReserve`s sourced from cooperative-app AX frames; merge into `safeZones()` (`Sources/ollyRuntime/OllyRuntimeCommands.swift:302`). Throttle live AX frame reads via `Sources/ollyKit/WindowSnapshotCache.swift` to ≤1 Hz.
- `Sources/ollyRuntime/OllyRuntimeAX.swift:168` (`upsertRuntimeWindow`) — for `floatAndHideOnSwitch` bundles, set `WindowState.layoutOrder = .max` so dispatcher hides when tag inactive.

**IPC additions:** `list-cooperative-apps` returns resolved bundle IDs + behaviors.

**App-shell:** Settings → "Cooperative apps" tab listing the resolved set with current detected matches.

**Test plan:**
- DSL: a config with `floatAndReserveSpace` injects a `SafeZoneReserve` into the calculator; verify via `SafeZoneCalculator.result(for:)`.
- Runtime: a `floatAndHideOnSwitch` cooperative app does not appear in `visibleWindows(displayID:)` after a tag switch.

**Acceptance:**
- `CooperativeApp("com.felixkratz.SketchyBar", behavior: .floatAndReserveSpace)` causes managed-window layout to leave SketchyBar's frame untiled.

---

### M2.4 Rule preview inspector

**Goal:** `ollyctl explain-window <id>` and `ollyctl explain-rule <id>` produce a traced match log showing which rule(s) matched a window and why.

**Files to modify:**
- `Sources/ollyDSL/Rule.swift` — add `Rule.id: UUID` (stable hash of `(match, apply, label)`).
- `Sources/ollyDSL/Rule.swift:85` — refactor `RuleMatch.matches(_:)` → `match(_:) -> RuleMatchTrace?` returning `nil` when not matched.
- Add `RuleMatchTrace { ruleID: UUID; bundleIDMatched: Bool?; titleMatched: Bool?; roleMatched: Bool?; subroleMatched: Bool?; predicateMatched: Bool? }`.
- Add `Rules.resolvedExplanation(for context:) -> RuleExplanation { traces: [RuleMatchTrace]; finalApply: RuleApply }`.
- `Sources/ollyIPC/IPCCommand.swift` — new commands `explainWindow(windowID:)`, `explainRule(ruleID:)`; result type `IPCRuleExplanation`.
- `Sources/ollyRuntime/OllyRuntimeCommands.swift` — implement; reuse `Config.resolvedApply` path with the explanation API.
- New: `Sources/ollyctl/OllyCtlExplainCommands.swift` — argparse subcommands; pretty-print + `--json` mode.
- `Sources/ollyApp/CommandPaletteActions.swift` — "Explain focused window" entry.

**Test plan:**
- Fixture config with 5 rules; verify trace contains entries in declaration order with correct booleans (`Tests/ollyDSLTests/RuleExplanationTests.swift`).
- Golden test for `ollyctl explain-window --json` shape (`Tests/ollyctlTests/`).

**Acceptance:**
- `ollyctl explain-window <id>` outputs a human-readable trace listing each rule attempted and the final applied state.

---

### M2.5 Macro recorder + replay

**Goal:** Record an ordered sequence of IPC commands; persist to disk; replay on demand; bindable via DSL.

**Files to add:**
- `Sources/ollyRuntime/MacroRecorder.swift` — `actor` with `start(name:)`, `stop()`, `run(name:)`. Records every `IPCRequestEnvelope` flowing through `OllyRuntime.handle(line:connection:)` (`Sources/ollyRuntime/OllyRuntime.swift:240`) except the macro commands themselves.
- Persistence: `~/.config/olly/macros/<name>.json` — `{ name, createdAt, recordedDurationMs, commandCount, commands: [IPCCommand] }`.
- `Sources/ollyctl/OllyCtlMacroCommands.swift` — `ollyctl macro record start <name>` / `stop` / `run <name>` / `list` / `delete <name>`.

**Files to modify:**
- `Sources/ollyRuntime/OllyRuntime.swift:240` (`handle(line:connection:)`) — split: if recording, append the request to the recorder before dispatching.
- `Sources/ollyDSL/Keybind.swift:130-142` — add `Action.macro(String)` case.
- `Sources/ollyRuntime/OllyRuntimeInteractionCommands.swift` — handle `.macro(name)` by delegating to `MacroRecorder.run(name:)`.
- `Sources/ollyApp/CommandPaletteActions.swift` — discover macros from disk; surface as palette entries.

**IPC additions:** `macro-start`, `macro-stop`, `macro-run`, `macro-list`, `macro-delete`.

**Gotchas:**
- Macro recording stalls the IPC pipeline if the recorder is slow — keep `append` O(1) (in-memory deque, flush on `stop`).
- Concurrent recording: starting a second recording while one is active should reject with `IPCErrorPayload(code: "macro_already_recording")`.
- Mostly side-effect-free macros (window operations) replay deterministically; macros containing `tag-add`/`tag-remove` against per-display state should serialize the `displayID` at record time.

**Test plan:**
- Record 3 commands, stop, reload, replay; assert state changes match.
- Snapshot the on-disk JSON for schema stability.

**Acceptance:**
- `ollyctl macro record start workflow1` then issue a series of commands then `ollyctl macro record stop`; then `ollyctl macro run workflow1` replays them in order.

---

## M3 — Snap & glance UX

All overlays inherit from `Sources/ollyApp/Overlays/OverlayPanel.swift` (M0.2); honour `NSWorkspace.shared.accessibilityDisplayShouldReduceMotion`; subscribe via `RuntimeEventBus` (M0.2).

### M3.1 Focus-ring overlay in-process

**Goal:** Native focus ring without requiring JankyBorders.

**Research summary:**
- JankyBorders pattern: per-window borderless overlay sized to managed window's frame + N px outset, level `.floating`, click-through, with a `CAShapeLayer` stroke. Reposition on every `kAXWindowMoved`/`kAXWindowResized` notification with explicit `setFrame` (no animation to avoid lag). JankyBorders does not use AX for the rendering itself — uses a separate fast path.

**Files to add:**
- `Sources/ollyApp/Overlays/FocusRingController.swift` — subscribes to `IPCEvent.focus`; reads focused window's frame; renders one `OverlayPanel` per managed window.
- `Sources/ollyDSL/FocusRing.swift` — `FocusRing { color(.systemBlue); width(2); cornerRadius(8); reduceMotion(.respectSystem) }`. Hook into `ConfigBuilder` and `ConfigSection` mirroring `Animation` (`Sources/ollyDSL/Config.swift:127`).

**Files to modify:**
- `Sources/ollyKit/WindowMover.swift:277` (`SystemAXWindowMoveClient.frame(for:)`) — refactor into public helper `AXFrameReader` so the focus ring shares the AX-frame read path.

**Implementation hint:**
- One overlay panel per focused window (singular at a time). On focus change, animate the panel from old window's frame to new window's frame using `NSAnimationContext.runAnimationGroup`; honour reduce-motion by setting `duration = 0`.
- Use `CAShapeLayer` with `strokeColor` = `FocusRing.color.cgColor`, `lineWidth` = `FocusRing.width`, `path` = `CGPath(roundedRect: bounds, ...)`.

**Test plan:**
- Unit: `FocusRing` DSL decode round-trips through JSON (`Tests/ollyDSLTests/FocusRingTests.swift`).
- UI smoke: assert panel frame matches focused window frame within 16ms tick.

**Acceptance:**
- `FocusRing { color(.systemBlue); width(2) }` in Config produces a visible 2px ring around the focused window.

**Refs:**
- https://github.com/FelixKratz/JankyBorders

---

### M3.2 Drag-to-snap preview overlay

**Goal:** On window drag, show a translucent overlay with snap-target zones; on release, snap to the hovered zone.

**Files to add:**
- `Sources/ollyApp/Overlays/DragSnapOverlayController.swift` — subscribes to `AXDragSession` (M0.3); computes zones from `SafeZoneCalculator.layoutFrame(for:)` (`Sources/ollyKit/SafeZoneCalculator.swift:104`).
- `Sources/ollyApp/Overlays/SnapZoneView.swift` — `NSView` with one `CALayer` per zone; hover highlight via `CABasicAnimation`.

**Files to modify:**
- `Sources/ollyDSL/SafeZones.swift` — add `customZone(name: "leftQuarter", rect:, on:)` to DSL builder.

**Zones (built-in):** left-half, right-half, top-half, bottom-half, top-left, top-right, bottom-left, bottom-right, center, maximize. (Same set as existing `IPCSnapPosition` in `Sources/ollyRuntime/OllyRuntimeInteractionCommands.swift:177`.)

**Snap commit:** synthesise `IPCSnapWindowCommand`; frame math is already in `IPCSnapPosition.frame(in:current:)` (`Sources/ollyRuntime/OllyRuntimeInteractionCommands.swift:177-227`).

**Gotchas:**
- AX doesn't emit a true "drag started" event — derived heuristically by M0.3 from `kAXWindowMoved` debounce. False positives (programmatic moves from olly itself) filtered by `WindowMover.lastFrames` check.
- The overlay must be click-through (`ignoresMouseEvents = true`) during drag so it doesn't intercept the drag.

**Test plan:**
- Unit: `SnapZoneResolver.zone(for: mousePoint, in: layoutFrame)` returns correct `IPCSnapPosition` for boundary cases.
- Manual UI: drag a window over each zone; assert overlay highlights and snap commits on release.
- Soak: 1000× drag cycles in `Sources/SoakHarness/SoakHarness.swift`; assert `OverlayPanelHost.activeCount == 0` after each.

**Acceptance:**
- Dragging a window to the left edge shows a left-half preview; releasing snaps the window to the left half.

---

### M3.3 Grid overlay hotkey

**Goal:** `cmd+?` shows current snap-zone grid as an overlay; arrow-key navigate; enter snaps.

**Files to add:**
- `Sources/ollyApp/Overlays/GridOverlayController.swift` — reuses `SnapZoneView` from M3.2.

**Files to modify:**
- `Sources/ollyApp/OllyApp.swift:applicationDidFinishLaunching` — register hotkey via `NSEvent.addLocalMonitorForEvents` (or surface as DSL-bindable `Action.showGridOverlay`).
- `Sources/ollyDSL/Keybind.swift:130` — add `.showGridOverlay`, `.showAltTab`, `.showCheatsheet`, `.showOverlay(kind)`.
- `Sources/ollyRuntime/OllyRuntimeInteractionCommands.swift` — handle new actions.
- `Sources/ollyIPC/IPCCommand.swift` — new IPC command `show-overlay <kind>`.

**Test plan:**
- UI: simulate `cmd+?`; assert one panel per screen; arrow keys advance selection; enter dispatches `snap-window`.

**Acceptance:**
- Hitting `cmd+?` shows the grid; navigating with arrows + enter snaps the focused window.

---

### M3.4 In-app cheatsheet (`cmd+/`)

**Goal:** Transient panel listing all bindings from `Config.keybinds`.

**Files to add:**
- `Sources/ollyApp/Overlays/CheatsheetController.swift` — reads `Config.keybinds.bindings` (`Sources/ollyDSL/Keybind.swift:206`); groups by category (focus, swap, move, tag, engine, snap, custom).

**Files to modify:**
- `Sources/ollyApp/OllyApp.swift` — register `cmd+/` (DSL-bindable as M3.3's `Action.showCheatsheet`).
- Reuse `CommandPaletteRowView` (`Sources/ollyApp/CommandPaletteController.swift:234`) for visual parity.

**Test plan:**
- Unit: a config with 12 bindings groups into expected buckets and renders all 12 rows.

**Acceptance:**
- `cmd+/` opens a panel listing every keybind from Config.

---

### M3.5 Live Alt-Tab / exposé preview switcher

**Goal:** Show all windows on current tag as a thumbnail grid.

**Research summary (CGWindowListCreateImage → ScreenCaptureKit migration):**
- `CGWindowListCreateImage` is **obsoleted in macOS 15.0**, deprecated in macOS 14. Compiler will fail with `'CGWindowListCreateImage' is unavailable: obsoleted in macOS 15.0`.
- Recommended successor: `SCScreenshotManager.captureImage(contentFilter:configuration:)` from ScreenCaptureKit (macOS 14+).
- Difference: `CGWindowListCreateImage` returns image at captured-window size; `SCScreenshotManager` returns image at the **configured** size — set `SCStreamConfiguration.width/height` explicitly.
- **Permission**: both APIs require Screen Recording TCC (`kTCCServiceScreenCapture`). First call surfaces prompt. AX trust does NOT cover screen capture.
- AltTab moved to SCK on macOS 14+ in commit [`7821d7c`](https://github.com/lwouis/alt-tab-macos/commit/7821d7c). Performance: 200ms total for 10 windows captured eagerly was painful; cache eagerly with TTL.

**Files to add:**
- `Sources/ollyApp/Overlays/AltTabSwitcherController.swift`
- `Sources/ollyApp/Overlays/WindowThumbnailView.swift` — `NSImageView` backed by cache.
- `Sources/ollyKit/WindowThumbnailCache.swift` — `actor` caching `CGImage` per `WindowID` with TTL (250ms default).

**Implementation snippet (SCK path):**
```swift
@available(macOS 14.0, *)
func captureSCK(windowID: CGWindowID, width: Int, height: Int) async throws -> CGImage? {
    let content = try await SCShareableContent.current
    guard let win = content.windows.first(where: { $0.windowID == windowID }) else { return nil }
    let filter = SCContentFilter(desktopIndependentWindow: win)
    let cfg = SCStreamConfiguration()
    cfg.width = width; cfg.height = height; cfg.showsCursor = false
    return try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: cfg)
}
```

Fallback for macOS 13 (CGWindowListCreateImage still works there):
```swift
func captureCG(windowID: CGWindowID) -> CGImage? {
    CGWindowListCreateImage(.null, .optionIncludingWindow, windowID,
                            [.boundsIgnoreFraming, .nominalResolution])
}
```

**Performance bounds:**
- Cap visible thumbnails at 50; >50 windows switch to list view.
- Selected + neighbouring thumbnails recapture at 30 Hz via `CADisplayLink`; others 15 Hz.
- Adaptive: if `windowStore.count > 60`, halve to 8 Hz.

**Permission UX:**
- Set `NSScreenCaptureUsageDescription` in Info.plist.
- On first launch of AltTab, prompt via SCK's natural TCC trigger; if denied, fall back to a list view with no thumbnails.

**Test plan:**
- Snapshot: grid layout adapts to N=1..16 windows.
- Performance (`Sources/PerfBench`): thumbnail generation for 20 windows ≤ 80ms total.

**Acceptance:**
- `ollyctl show-overlay alt-tab` opens a thumbnail grid of windows on current tag; arrow + enter focuses the chosen window.

**Refs:**
- https://developer.apple.com/forums/thread/740493
- https://developer.apple.com/documentation/screencapturekit/scscreenshotmanager
- https://github.com/lwouis/alt-tab-macos/commit/7821d7c
- https://github.com/lwouis/alt-tab-macos/issues/45

---

### M3.6 Scratchpad windows

**Goal:** Designated windows toggle visible/hidden independent of tags.

**Files to add:**
- `Sources/ollyCore/ScratchpadRegistry.swift` — `actor` persisting `[ScratchpadEntry]` to `~/.config/olly/scratchpads.json`.
- `Sources/ollyDSL/Scratchpad.swift` — `Scratchpads { Scratchpad("term") { bundleID("com.apple.Terminal"); titleRegex("Scratch") } }`.
- `Sources/ollyRuntime/OllyRuntimeScratchpadCommands.swift` — modeled on `OllyRuntimeInteractionCommands.swift`.

**Data model:**
```swift
public struct ScratchpadEntry: Codable, Equatable, Sendable {
    public let name: String
    public let bundleID: String?
    public let titleRegex: String?
    public let role: String?
    public let lastVisibleFrame: WindowRecoveryFrame?
    public let isVisible: Bool
}
```

**Files to modify:**
- `Sources/ollyDSL/Config.swift:218-228` — add `case scratchpads(Scratchpads)` to `ConfigSection`.
- `Sources/ollyRuntime/OllyRuntime.swift:102-183` — add `let scratchpads: ScratchpadRegistry` actor.
- `Sources/ollyRuntime/OllyRuntimeAX.swift:127-152` (`windowCreated`) — check `scratchpads.matchingEntry(for:)`; if visible flag is false, immediately park.
- `Sources/ollyCore/TagDispatcher.swift:111-135` — extract `hide(_)`/`show(_)` to reusable `WindowParker` API.

**Toggle behaviour:**
1. Resolve `WindowID` from `ScratchpadEntry` predicate via `windowStore.allWindows().filter`.
2. If matching window is parked: `windowMover.setPosition/Size` to `lastVisibleFrame ?? display.frame.center` then raise focus via `AXUIElementSetAttributeValue(..., kAXFocusedAttribute)`.
3. If visible: capture frame, set offscreen via `OffscreenParking.frame(for:avoiding:)` (`Sources/ollyCore/OffscreenParking.swift:51-57`); persist.

**IPC additions:** `scratchpad-add`, `scratchpad-toggle <name>`, `scratchpad-list`, `scratchpad-remove <name>`.

**Gotchas:**
- For lazy-launch apps (Terminal, scratch app not yet open), use `NSWorkspace.shared.openApplication(at:configuration:)` (modern replacement for deprecated `launchApplication`) and only then park.
- Without `OLLY_ENABLE_PRIVATE_AX_WINDOW_ID`, `WindowID` fallback in `Sources/ollyKit/WindowRef.swift:115-149` requires `kCGWindowName == title`; for browsers/etc that rename windows dynamically, this can misidentify — document the limitation.

**Test plan:**
- Unit: `ScratchpadRegistry` round-trip persistence; toggle state machine without AX.
- AX acceptance: register a synthetic `NSWindow` as a scratchpad, exercise toggle.

**Acceptance:**
- `ollyctl scratchpad-add --name=term --bundle=com.apple.Terminal` + `ollyctl scratchpad-toggle --name=term` shows/hides Terminal independent of tag.

---

### M3.7 Animated layout transitions

**Goal:** Interpolate AX `setPosition`/`setSize` writes over `Animation.duration` using `AnimationCurve`. Honour Reduce Motion.

**Research summary:**
- `NSWorkspace.shared.accessibilityDisplayShouldReduceMotion: Bool` and `NSWorkspace.accessibilityDisplayOptionsDidChangeNotification` — subscribe at startup, cache value, invalidate on notification.
- Read once at animation start (don't poll mid-animation); apply `duration = 0` rather than skipping animator API to keep code path uniform.

**Files to modify:**
- `Sources/ollyKit/WindowMover.swift` — add `setFrameAnimated(from: CGRect, to: CGRect, duration: TimeInterval, curve: AnimationCurve, for: WindowMoveTarget)`. Schedule N intermediate frames via existing `Sources/ollyKit/WindowMoveDisplayLink.swift`. Expose `lastFrame(for: WindowMoveTarget) -> CGRect?` from the private dict at line 38.
- `Sources/ollyLayouts/EngineHost.swift` — in `applyPlacement` (constructor at `Sources/ollyRuntime/OllyRuntime.swift:166`), look up the per-engine `Animation` from `Config.animation(for: engineID)` (`Sources/ollyDSL/Animation.swift:160`) and call `setFrameAnimated` instead of immediate `setPosition`/`setSize`.

**Reduce Motion:**
- Short-circuit to one-frame write if `ReduceMotionPolicy.respectSystem` and `NSWorkspace.shared.accessibilityDisplayShouldReduceMotion == true`, or if policy is `.neverAnimate`.

**Electron caveat:**
- Many Electron apps snap to discrete sizes and ignore intermediate AX writes; result is one jump at the end. Detect Electron via existing `Sources/ollyKit/ElectronWorkaround.swift` and fall back to instant write.

**Test plan:**
- Unit: given start `(0,0,100,100)` → end `(200,200,400,400)` over 200ms with `easeOut`; verify intermediate frames at t=50,100,150ms match `easeOut(t)` interpolation within 1px.
- Integration: with `reduceMotion(.respectSystem)` + mocked `accessibilityDisplayShouldReduceMotion = true`, only one write is issued.
- PerfBench: 18-engine arrange under animation ≤ 2× the non-animated baseline (`.perf-baseline.json`).

**Acceptance:**
- A tag switch from BSP to Grid visibly animates window frames over ~150ms; turning on System Settings → Accessibility → Reduce Motion eliminates the animation.

**Refs:**
- https://developer.apple.com/documentation/appkit/nsworkspace/1525481-accessibilitydisplayshouldreduce

---

## M4 — Product polish

### M4.1 App-launch on tag switch

**Goal:** When user switches to tag "code" and no VSCode window exists, launch VSCode.

**Files to modify:**
- `Sources/ollyDSL/NamedTag.swift` — add `.launch(_ bundleID: String)` modifier on `NamedTagDeclaration`.
- `Sources/ollyRuntime/OllyRuntimeCommands.swift:103` (`switchTag`) — after activating the tag, check `windowStore.windowsForBundle(_:)`; if empty, call `NSWorkspace.shared.openApplication`.

**Modern launch API** (deprecated `launchApplication(_:)` replacement):
```swift
func launch(bundleID: String, activate: Bool = false) async -> NSRunningApplication? {
    let ws = NSWorkspace.shared
    guard let url = ws.urlForApplication(withBundleIdentifier: bundleID) else { return nil }
    let cfg = NSWorkspace.OpenConfiguration()
    cfg.activates = activate
    cfg.addsToRecentItems = false
    cfg.createsNewApplicationInstance = false
    do { return try await ws.openApplication(at: url, configuration: cfg) }
    catch { return nil }
}
```

**Gotchas:**
- `cfg.activates = true` will steal focus. Default `false` for olly's tag-switch use case; let the runtime's own focus logic decide.
- Apps may self-activate via `NSApp.activate(ignoringOtherApps: true)` in their own `applicationDidFinishLaunching` — can't prevent from launcher side.

**Test plan:**
- Unit: mock `NSWorkspace` open call; verify launch invoked when no windows exist for tagged bundle.

**Refs:**
- https://developer.apple.com/documentation/appkit/nsworkspace/3172700-openapplication

---

### M4.2 Session restore on reboot

**Goal:** On launch, restore last-known window placements.

**Files to modify:**
- `Sources/ollyRuntime/OllyRuntime.swift:185` (`start()`) — after first window scan, invoke existing `restoreJournaledWindows` (used by IPC `restoreWindows` at `Sources/ollyRuntime/OllyRuntimeCommands.swift:210-258`).
- New DSL `Session { restoreOnLaunch(true) }` — new file `Sources/ollyDSL/Session.swift`.

**Acceptance:**
- After reboot, on first olly launch, managed windows return to their last placements.

---

### M4.3 Quit-on-tag-switch dialog protection

**Goal:** Don't park `AXDialog` subrole windows (especially destructive-op confirmation dialogs) offscreen during tag switch.

**Files to modify:**
- `Sources/ollyCore/TagDispatcher.swift:shouldShow` — add branch: if `WindowState.subrole == "AXDialog"`, never park (keep visible regardless of tag).

**Test plan:**
- Snapshot: dialog window on tag 1; switch to tag 2; assert dialog still visible.

---

### M4.4 Window-resize keyboard bindings

**Goal:** Bind `Action.resize(Direction, points: CGFloat)` and `Action.split(Direction, ratio: CGFloat)` to keys.

**Files to modify:**
- `Sources/ollyDSL/Keybind.swift:130-142` — add `Action.resize(Direction, points: CGFloat)` and `Action.split(Direction, ratio: CGFloat)`.
- `Sources/ollyRuntime/OllyRuntimeInteractionCommands.swift` — implement via `WindowMover.setSize`.
- For BSP engine: `Action.split` re-balances the binary tree node containing the focused window.

---

### M4.5 Focus-follows-mouse (optional)

**Goal:** Sloppy-focus toggle.

**Files to modify:**
- Reuse `CGEventTap` infra from M1.7 (`Sources/ollyKit/FocusInputAttribution.swift`).
- New DSL: `FocusPolicy { followsMouse(delay: 100.ms) }`.

**Gotchas:**
- On `.mouseMoved` events, find window under cursor via `CGWindowListCopyWindowInfo` + bounds check; focus only if delay elapsed.
- `.mouseMoved` is high-frequency — debounce and offload from tap callback.

---

### M4.6 First-run wizard

**Goal:** Six-step setup for new users: welcome → AX → display profile → preset → cheatsheet → done.

**Files to add:**
- `Sources/ollyApp/Onboarding/FirstRunWindowController.swift` — `NSViewController` panels swapped inside an `NSWindow`.

**Steps:**
1. **Welcome + privacy summary.**
2. **AX permission** — reuse `Sources/ollyApp/AXOnboardingWindowController.swift:48`.
3. **Display profile detection** — read displays via `DisplayMonitor().displays()`; show notch / menu-bar / dock heights via `SafeZoneCalculator.result(for:)` (`Sources/ollyKit/SafeZoneCalculator.swift:70`); generated `SafeZones { notchPadding(...) }` snippet.
4. **Preset profile picker** — reuse `ConfigTemplate.swift` / `ConfigTemplateProfile.allCases` (used at `Sources/ollyApp/SettingsWindowController.swift:132`). Profiles: `starter`, `minimal`, `niri`, `bsp`, `ultrawide`.
5. **Keybind cheatsheet preview** — reuse M3.4's view.
6. **Done** — write starter `Config.swift` via existing `ensureConfigExists(profile:)` (`Sources/ollyApp/SettingsWindowController.swift:215`).

**Files to modify:**
- `Sources/ollyApp/OllyApp.swift:85` — call new controller instead of bare AX onboarding when `!ConfigLoader.defaultSourceURL().exists`.

---

### M4.7 Crash telemetry (opt-in, self-hosted)

**Goal:** Local crash capture; opt-in upload via `ollyctl telemetry flush`. No background phone-home.

**Research summary (PLCrashReporter):**
- `https://github.com/microsoft/plcrashreporter` — minimum macOS 11.5; SwiftPM-installable; lightweight; signal-safe; open-source.
- Alternative: hand-rolled `NSSetUncaughtExceptionHandler` + `signal(SIGSEGV/SIGABRT, ...)` + `backtrace`/`backtrace_symbols`. Swift function names come out mangled — demangle later via `swift demangle` for symbolication.
- Sentry SDK is feature-rich but heavy (~20MB framework size) and the privacy posture out of the box includes window titles in breadcrumbs.

**Decision:** Use PLCrashReporter for the signal-safe crash capture core (best-of-class for the niche), but ship our own writer/uploader rather than hooking Sentry's HTTP pipeline. Output local-only JSON; upload is explicit `ollyctl telemetry flush`.

**Files to add:**
- `Sources/ollyDiagnostics/CrashTelemetry.swift` — install PLCrashReporter on startup if `Telemetry.enabled`; on next launch detect pending report; write JSON to `~/Library/Logs/Olly/<ts>.crash.json` with timestamp, signal/exception, top-30 mangled frames, version, configHash, display count, tag count. **No window titles, no bundle IDs by default** (when `scrubbedBundleIDs: true`).
- `Sources/ollyDSL/Telemetry.swift` — `Telemetry { enabled(false); endpoint(nil); scrubbedBundleIDs(true) }`.
- `Sources/ollyctl/OllyCtlTelemetryCommands.swift` — `ollyctl telemetry status` / `enable` / `disable` / `flush`.

**Files to modify:**
- `Sources/ollyctl/OllyCtlDoctor.swift:124-340` — add a doctor check `telemetryCheck()` warning if pending crash reports exist and telemetry is disabled.
- `Sources/ollyApp/OllyApp.swift` — call `CrashTelemetry.install(enabled: config.telemetry.enabled)` early.

**Privacy:**
- Default `enabled: false`.
- Report includes: timestamp, signal/exception name, top-30 frames (mangled Swift symbols), olly version, DSL version, count of displays, count of tags. Nothing user-specific.
- Upload requires explicit `ollyctl telemetry flush`. Endpoint user-configurable.

**Refs:**
- https://github.com/microsoft/plcrashreporter
- https://swiftpackageindex.com/microsoft/plcrashreporter

---

### M4.8 Settings export/import

**Goal:** Single-file round-trip of `Config.swift` from the menu bar; share-with-coworker workflow.

**Files to modify:**
- `Sources/ollyApp/SettingsWindowController.swift` — add "Export Config..." and "Import Config..." menu items invoking `NSSavePanel` / `NSOpenPanel`. Reuse `ConfigTemplate`.

---

### M4.9 In-app error log + diagnostic bundle

**Goal:** "Last 5 errors" tab in Settings; "Copy diagnostic bundle" produces a zip.

**Files to modify:**
- `Sources/ollyRuntime/OllyRuntime.swift:127` — surface `lastError` history (ring buffer of size 5).
- `Sources/ollyApp/SettingsWindowController.swift` — add tab reading from `RuntimeEventBus` + the new history; bundle action produces `~/Library/Logs/Olly/<ts>-diagnostic.zip` containing recent journals.

---

### M4.10 Keybind conflict warnings UI

**Goal:** Surface keybind collisions detected by existing `Sources/ollyApp/HotKeyStartupDiagnostics.swift:23` in the Settings → Keybinds tab.

**Research summary:**
- Carbon `RegisterEventHotKey` returns `OSStatus` — check for `eventHotKeyExistsErr` (-9878). Same key combo cannot be registered twice within the same app.
- `Sources/ollyApp/HotKeyStartupDiagnostics.swift:23` already detects collisions and posts a `UNNotification` — extend the data path to also populate a model the Settings tab reads.

**Refs:**
- https://github.com/soffes/HotKey/blob/main/Sources/HotKey/HotKeysController.swift

---

### M4.11 Dark/light parity + accessibility audit

**Goal:** Every new overlay uses semantic colors (`NSColor.labelColor`, `NSColor.controlAccentColor`) — no hardcoded `.white`. Every overlay sets `accessibilityRole`, `accessibilityLabel`, `setAccessibilityElement(true)`.

**Files to modify:**
- `Sources/ollyApp/OverviewModeController.swift:234` (`OverviewView.drawHeader`) — current hardcoded `.white` becomes `.labelColor`.
- Audit every new overlay file from M3 before merge.

**A11y per overlay:**
- Drag-snap overlay: role `.layoutArea`, per-zone children with role `.button`.
- Focus ring: role `.staticText` or `.unknown` (decoration only); set `accessibilityIsHidden = true`.
- Alt-Tab: role `.list`, per-thumbnail children role `.button`, label = window title.
- Cheatsheet: role `.list`, per-row children role `.staticText`.

---

### M4.12 Localized strings strategy

**Goal:** Pull every user-visible string into `Localizable.strings` (English-only locale now); unblock community translations.

**Files to modify:**
- `Sources/ollyApp/OllyApp.swift`, `Sources/ollyApp/SettingsWindowController.swift`, `Sources/ollyApp/AXOnboardingWindowController.swift`, all new M3 overlays.
- Add `NSLocalizedString("key", comment: "context")` wraps.
- Place strings in `Sources/ollyApp/Resources/en.lproj/Localizable.strings`.

---

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
