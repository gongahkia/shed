# olly — TODO

todo.txt format. Each line is a single actionable task.
Format: `(PRIO) YYYY-MM-DD task description +project @context key:value`
Priority `(A)` = blocker for next phase. `(B)` = important. `(C)` = nice-to-have.
Projects: `+phase0` … `+phase9`. Context tags: `@swift`, `@ax`, `@ipc`, `@dsl`, `@layout`, `@ui`, `@ecosystem`, `@docs`, `@ci`, `@release`.
Read NORTHSTAR.md before starting. Cite NORTHSTAR section anchors in PRs (e.g. `ref:N§5`).

---

## PHASE 0 — Repo bootstrap


---

## PHASE 1 — AX foundation (`ollyKit`)


---

## PHASE 2 — Tag-based workspace model (`ollyCore`)


---

## PHASE 3 — Layout engine plugin contract (`ollyLayouts`)


---

## PHASE 4 — Built-in layout engines


---

## PHASE 5 — Swift DSL config (`ollyDSL`)

---

## PHASE 6 — IPC + `ollyctl`

---

## PHASE 7 — UX surfaces (`ollyApp`)


---

## PHASE 8 — Ecosystem bridges

(A) 2026-06-26 Implement safe-zone calculator: per-display reserved rects from notch (`NSScreen.safeAreaInsets` + 12 px buffer) and menubar; layout engines receive `bounds` already shrunk +phase8 @swift @layout ref:N§12b
(A) 2026-06-26 Add DSL primitive `reserve(rect:on:)` and `notchPadding(_:)`; document and exercise in example Config +phase8 @swift @dsl ref:N§12b
(B) 2026-06-26 Add `extensions/ubersicht/` simple-bar-compatible widget reading olly IPC; document config +phase8 @ecosystem @docs
(B) 2026-06-26 Document delegation to Karabiner-Elements / skhd / BetterTouchTool / Hammerspoon for users who prefer external hotkey daemons; clarify olly will not double-bind and logs conflicts on startup +phase8 @docs @ecosystem
(B) 2026-06-26 On startup, scan registered Carbon hotkeys via `CopySymbolicHotKeys` (if accessible) and DSL-declared chords; log + toast every collision detected with known daemons (Karabiner, skhd) +phase8 @swift @ecosystem
(B) 2026-06-26 Verify olly's offscreen-park coordinates never overlap any attached display rect — capture-tool friendly (OBS/CleanShot won't accidentally record parked windows) +phase8 @swift @ecosystem ref:N§7
(C) 2026-06-26 Open-source the cooperative-apps allowlist as a separate YAML file + CONTRIBUTING note inviting community PRs to add bundle IDs +phase8 @docs @ecosystem

---

## PHASE 9 — Docs, demo, release

(A) 2026-06-26 Flesh out README.md: hero GIF, value prop, install (cask + dmg), 60-second config example, comparison table vs Nehir/Hiro/Paneru/Miri/AeroSpace/Yabai, full inspiration credits per NORTHSTAR §3 +phase9 @docs @release ref:N§3
(A) 2026-06-26 Record 30-second screencast: 4-engine hot-swap across 2 displays + tag switching + command palette; commit as `docs/demo.gif` +phase9 @docs @release
(A) 2026-06-26 Set up Developer ID signing + notarization in CI; gate release on stapled `.dmg` artifact +phase9 @ci @release ref:N§4-D8
(A) 2026-06-26 Cut v0.1.0 GitHub Release with `.dmg`, source tarball, SHA256SUMS; draft Homebrew cask PR +phase9 @release
(B) 2026-06-26 Write a launch post for Hacker News + Lobsters + r/MacOS: lead with the multi-paradigm hot-swap hook, link benchmark table, screenshot grid; schedule for a Tue 09:00 ET +phase9 @release
(B) 2026-06-26 Open RFC issues for the v0.2 backlog (Monocle, Spiral, Grid, ThreeCol, Accordion) inviting community plugins +phase9 @docs @layout
(C) 2026-06-26 Add `docs/multi-monitor.md` deep-dive: virtual-workspace emulation, single-Space invariant, hotplug behavior, known limits vs Mission Control +phase9 @docs ref:N§7
(C) 2026-06-26 Track adoption metrics: GitHub stars trajectory, brew install counts, Raycast extension installs; revisit positioning quarterly +phase9 @release

---

## PHASE 10 — Performance & profiling (parallel with all phases; P0 for v0.1)

(A) 2026-06-26 Define perf budget targets in `docs/performance.md` mirroring NORTHSTAR §12a; reference from PR template +phase10 @docs @ci ref:N§12a
(A) 2026-06-26 Implement `PerfBench` target: scripted scenarios (cold start, hotkey-to-move, tag switch with N windows, wake-from-sleep, 7-day soak); exports JSON of p50/p95/p99 +phase10 @swift @ci ref:N§12a
(A) 2026-06-26 Add CI job that runs `PerfBench` on each PR, fails if any p95 regresses > 10 % vs main baseline; baseline stored in `.perf-baseline.json` updated on main merges +phase10 @ci ref:N§12a
(A) 2026-06-26 Implement AX-write coalescer: per-display 60 Hz CADisplayLink-driven flush; coalesce identical target frames; skip no-op writes (< 1 px delta) +phase10 @swift @ax ref:N§12a
(A) 2026-06-26 Implement event-driven invariant: zero NSTimers in production code paths; CI grep gate to reject `Timer(`, `scheduledTimer`, `DispatchSourceTimer` outside test code +phase10 @swift @ci ref:N§12a
(A) 2026-06-26 Add `os_signpost` regions: ax.write, layout.arrange, dispatch.tagSwitch, dsl.reload; `xctrace` template committed under `scripts/profile.tracetemplate` +phase10 @swift ref:N§12a
(A) 2026-06-26 Make `LayoutEngine.arrange()` non-suspending (synchronous, pure) by contract — enforced via protocol shape; CI compile-time check via attribute +phase10 @swift @layout ref:N§12a
(B) 2026-06-26 Implement 7-day soak harness: simulate ~5 k window events/day, sample RSS every 10 min, gate < 5 % growth from baseline; runs nightly on a self-hosted Mac mini if available +phase10 @ci ref:N§12a
(B) 2026-06-26 Profile + tune wake-from-sleep: subscribe to `NSWorkspace.didWakeNotification`, restore active engine state from in-memory snapshot rather than re-querying all AX; target ≤ 500 ms +phase10 @swift @ax ref:N§12a
(B) 2026-06-26 Add window-snapshot caching layer with weak references; invalidation only on confirmed AX delta, never on timer +phase10 @swift @ax
(B) 2026-06-26 Build a flamegraph artifact uploader: PRs touching hot paths attach a release-build flamegraph from `PerfBench` as a CI artifact +phase10 @ci
(C) 2026-06-26 Investigate using `dispatch_source_create(DISPATCH_SOURCE_TYPE_MACH_RECV)` for AX observer wakeups vs `CFRunLoop`; measure latency delta +phase10 @swift @ax
(C) 2026-06-26 Memory-arena allocator for `Placement` arrays passed to WindowMover to avoid per-frame Swift `Array` allocations +phase10 @swift

---

## PHASE 11 — Tier-1 layouts (v0.2)

(A) 2026-06-26 Implement `Monocle` engine: single visible tile fills bounds; siblings hidden offscreen; cycle via `next/prev` actions; cite dwm precedent in doc-comment +phase11 @swift @layout ref:N§6
(A) 2026-06-26 Implement `Spiral` engine: recursive golden-ratio (or configurable) splits; first window full, next halves longer axis spirally; cite XMonad Spiral + Yabai fibonacci +phase11 @swift @layout ref:N§6
(A) 2026-06-26 Implement `Grid` engine: square-ish auto-pack with configurable policy (squareish/fixedRows(n)/fixedCols(n)); deterministic ordering by AX windowID +phase11 @swift @layout ref:N§6
(A) 2026-06-26 Implement `ThreeCol` engine: centered master + left/right slave stacks; configurable masterRatio (default 0.5) and side-balance; ultrawide-friendly +phase11 @swift @layout ref:N§6
(A) 2026-06-26 Implement `Accordion` engine: focused window expanded, others collapse to title strips at top/bottom; configurable stripHeight +phase11 @swift @layout ref:N§6
(A) 2026-06-26 Snapshot tests for all 5 Tier-1 engines; document each in `docs/layouts-research.md` with a screenshot +phase11 @swift @docs @layout
(B) 2026-06-26 Add DSL bindings: `Engines { Monocle(); Spiral(); Grid(.squareish); ThreeCol(masterRatio: 0.5); Accordion() }` with full doc-comments +phase11 @swift @dsl
(B) 2026-06-26 Cut v0.2.0 release with five new layouts; update README comparison table +phase11 @release

---

## PHASE 12 — Tier-2 layouts (v0.3, advanced UX)

(A) 2026-06-26 Implement `Pseudotile` modifier engine (Hyprland precedent): wraps another engine; honors per-window preferred size centered in its slot; cite hyprland-wiki +phase12 @swift @layout ref:N§6
(A) 2026-06-26 Implement `Tabbed` engine (i3/Sway precedent): multiple windows share a tile, accessible via a top tab bar; render tab bar as a child `NSWindow` overlay or via AX-managed title bar +phase12 @swift @layout @ui ref:N§6
(A) 2026-06-26 Implement `Stacked` engine: like Tabbed but full-height left title stack +phase12 @swift @layout @ui ref:N§6
(A) 2026-06-26 Implement `TreeTab` engine (Qtile precedent): vertical tree of tabbed windows on a side rail; configurable rail width and side +phase12 @swift @layout @ui ref:N§6
(A) 2026-06-26 Implement `VerticalTile` engine (Qtile precedent): single master full-height + horizontal slaves; rotation-aware (detects portrait displays) +phase12 @swift @layout ref:N§6
(B) 2026-06-26 Implement `RatioTile` engine: pack N windows honoring their AX min/max-size constraints via 2D bin-packing; fall back to grid on infeasible +phase12 @swift @layout ref:N§6
(B) 2026-06-26 Document Tab-bar/Stack-bar UX in `docs/layouts-research.md`; trade-offs of AX-only tab rendering vs overlay NSWindow +phase12 @docs @ui
(C) 2026-06-26 Cut v0.3.0 release; update comparison table; blog post: "olly is now the macOS WM with the most layout engines" +phase12 @release

---

## PHASE 13 — Tier-3 architectural layouts + community engines (v1.0)

(A) 2026-06-26 Implement `Frame` engine (herbstluftwm precedent): each display holds a tree of recursive frames; each frame holds its own sub-engine choice (composable layout-of-layouts); cite herbstluftwm tutorial +phase13 @swift @layout ref:N§6
(A) 2026-06-26 Implement `MultiTagUnion` view mode (River precedent): display arranges union of windows from N active tags simultaneously +phase13 @swift @layout @core ref:N§6
(A) 2026-06-26 Implement `PinnedColumns` modifier for scrollable engines (Niri/hyprscroller/PaperWM precedent): pin a column to stay visible regardless of scroll position +phase13 @swift @layout ref:N§6
(A) 2026-06-26 Implement `PaperWMScroll` engine: alternative scrollable model with variable-width content-driven columns; differentiate from NiriScroll in `docs/layouts-research.md` +phase13 @swift @layout ref:N§6
(B) 2026-06-26 Promote external layout engine API: `.dylib` loading at runtime, signed package format `.ollyplugin`, version negotiation; defer until ABI proves stable across 2+ minor versions +phase13 @swift @dsl @release ref:N§4-D4
(B) 2026-06-26 Publish a `Layout Engine Showcase` repo with 3 community-contributed engines as case studies +phase13 @docs @release
(C) 2026-06-26 Continuous: monitor PaperWM (GNOME), Karousel (KDE), Bismuth (KDE), hy3, hyprscroller, scrollwm; add notes to `docs/layouts-research.md` on each release +phase13 @docs

---

## PHASE 14 — DSL polish & documentation

(A) 2026-06-26 Every public DSL primitive carries a Swift doc-comment with: purpose, parameter docs, 1-line example, "see also" cross-references; lint rule enforces presence +phase14 @swift @dsl @docs ref:N§14
(A) 2026-06-26 Generate `docs/dsl-reference.md` via DocC; CI builds and uploads docs artifact; deploy to GitHub Pages on release +phase14 @ci @docs ref:N§14
(A) 2026-06-26 Implement `.raw { ctx in ... }` escape hatch on every level: Keybind, Rule, Engine, Workspace, Hook — provides full Swift closure access to runtime state +phase14 @swift @dsl ref:N§14
(A) 2026-06-26 Implement type-safe error catalog: duplicate-chord, duplicate-tag-name, unknown-engine-id, ambiguous-rule — all are compile-time errors via `@_unavailable` or where-clauses +phase14 @swift @dsl ref:N§14
(A) 2026-06-26 Implement `DSLVersion` enum carried in Config; mismatched versions trigger migration prompt; `ollyctl migrate-config` generates a diff suggestion +phase14 @swift @dsl @ipc ref:N§14
(A) 2026-06-26 Ship `examples/` directory with at least 6 working configs: minimal, niri-only, master-stack-heavy, ultrawide-3col, multi-display-tags, plugin-author (custom engine) +phase14 @dsl @docs
(B) 2026-06-26 Ship a DSL cookbook in `docs/dsl-cookbook.md`: 30+ snippets for common asks — "float Slack", "tag #4 always BSP", "different engine per display", "scratchpad tag", "follow-focus-to-display", "auto-rotate workspace on display unplug" +phase14 @docs @dsl
(B) 2026-06-26 Implement `Rule` predicate builders: `bundleID(_)`, `titleRegex(_)`, `role(_)`, `subrole(_)`, `windowSize(.smallerThan: ...)`, `windowSize(.largerThan: ...)`, `parentBundleID(_)` (XPC services), composable with `&&` `||` `!` operators +phase14 @swift @dsl
(B) 2026-06-26 Implement `Hooks { onTagSwitch { ... }; onDisplayChange { ... }; onWindowAppeared { ... } }` block giving users typed lifecycle hooks instead of forcing IPC roundtrip +phase14 @swift @dsl
(B) 2026-06-26 Implement DSL `Animation { duration: 200.ms; curve: .easeOut; reduceMotion: .respectSystem }` block applied per-engine or globally +phase14 @swift @dsl
(B) 2026-06-26 Implement DSL `Gestures { fourFingerHorizontal: .scrollColumns; fourFingerVertical: .switchTags }` block driving touchpad gestures via private gesture-recognizer-public-shim (no SIP needed) +phase14 @swift @dsl @ax
(C) 2026-06-26 Plugin starter template `olly-plugin-template` repo: a working `Hello, layout!` engine in 80 lines of Swift with passing snapshot test; linked from `docs/plugin-authoring.md` +phase14 @swift @dsl @docs
(C) 2026-06-26 Live config playground: an in-app sheet where users see DSL compile errors with column markers + quick-fix suggestions +phase14 @ui @dsl

---

## Cross-cutting / continuous

(A) 2026-06-26 Every PR cites the NORTHSTAR section(s) it touches; reject PRs that change locked decisions (NORTHSTAR §4) without an RFC issue first +continuous @docs ref:N§4
(A) 2026-06-26 Every PR must demonstrate it does not regress §12a perf budgets — PR template includes a `Perf impact` checkbox referencing CI bench output +continuous @ci ref:N§12a
(A) 2026-06-26 Every DSL change requires a corresponding entry in `examples/` exercising the new primitive and a doc-comment update +continuous @dsl @docs ref:N§14
(B) 2026-06-26 Maintain `docs/layouts-research.md` as living doc; add a new entry whenever a contributor proposes or studies a new layout paradigm +continuous @docs ref:N§6
(B) 2026-06-26 Maintain `docs/menubar-notch-integration.md` as living doc; add an entry whenever a new menubar/notch utility ships or breaks integration +continuous @docs @ecosystem ref:N§7
(B) 2026-06-26 No new private-API usage without an RFC + fallback path; CI gate `scripts/check-no-private-api.sh` blocks +continuous @ci ref:N§4-D2
(B) 2026-06-26 Cooperative-apps allowlist (NORTHSTAR §7b) is updated via PR; require evidence (screenshot or repro) of conflict before adding bundle IDs +continuous @ecosystem @docs ref:N§7b
(C) 2026-06-26 Quarterly: re-survey macOS WM landscape (search for new Niri ports, Nehir/Hiro releases, AeroSpace changes, Hyprland plugin trends); update NORTHSTAR §2 if positioning shifts +continuous @docs ref:N§2
(C) 2026-06-26 Quarterly: re-survey notch-app ecosystem (Alcove/NotchNook/Boring Notch releases, new entrants); update cooperative-apps list + safe-zone defaults if a notch app's expansion behavior changes +continuous @docs @ecosystem ref:N§7b
