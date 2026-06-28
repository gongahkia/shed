# Olly Launch Post

Status: draft for first public launch after the repo is public and v0.1.0 assets
are available.

## Schedule

Current date: 2026-06-28.

| Channel | Slot | Reason |
|---|---|---|
| Hacker News Show HN | 2026-06-30 09:00 ET / 13:00 UTC / 21:00 SGT | Matches TODO's Tuesday 09:00 ET request. |
| Lobsters | 2026-06-30 09:00 ET / 13:00 UTC / 21:00 SGT | Same technical launch window. |
| r/MacOS | 2026-06-30 09:00 ET / 13:00 UTC / 21:00 SGT | Tentative; verify current subreddit rules before posting. |

Do not post before:

- GitHub repo is public.
- `docs/demo.gif` exists.
- GitHub Release includes DMG, source tarball, and SHA256SUMS.
- Homebrew cask PR exists or is clearly labeled as pending.
- r/MacOS rules are verified from a reachable moderator source.

## Links

- Repo: <https://github.com/gongahkia/olly>
- Performance table: [`docs/perfbench-results.md`](perfbench-results.md)
- Screenshot grid: [`docs/layouts-research.md`](layouts-research.md)
- Demo GIF: [`docs/demo.gif`](demo.gif)
- Release: <https://github.com/gongahkia/olly/releases/tag/v0.1.0>

## Hacker News

Title:

```text
Show HN: Olly, a Swift macOS window manager with hot-swappable layouts
```

Body:

```text
Olly is a pure-Swift macOS window manager built around one hook: each workspace
can hot-swap between multiple layout engines.

Most macOS tiling managers pick one paradigm: BSP, i3-like trees, or scrolling
columns. Olly treats the layout engine as the primitive. A tag can use Niri-style
scrolling columns, another can use BSP, another can float calls/dialogs, and the
same windows can move between them through ollyctl or the Swift config DSL.

The v0.1 shape is intentionally conservative:

- Accessibility APIs only; no SIP-off requirement.
- River-style tags instead of native Spaces orchestration.
- Unix-socket IPC plus ollyctl.
- SwiftPM plugin path for user-authored layout engines; runtime dylib plugins are
  deferred until the ABI is stable.
- First-party docs/examples for SketchyBar, JankyBorders, Ubersicht, Raycast,
  Alfred, and hotkey daemons.

Current built-in engines include Floating, MasterStack, Manual, BSP, NiriScroll,
Monocle, Spiral, Grid, ThreeCol, Accordion, Tabbed, Stacked, TreeTab,
Pseudotile, VerticalTile, RatioTile, Frame, PaperWMScroll, and pinned-column
wrappers.

Benchmarks and screenshots:

- PerfBench table: https://github.com/gongahkia/olly/blob/main/docs/perfbench-results.md
- Layout screenshots: https://github.com/gongahkia/olly/blob/main/docs/layouts-research.md
- Demo GIF: https://github.com/gongahkia/olly/blob/main/docs/demo.gif

The main thing I want feedback on is the layout-engine contract: what should be
stable before v1.0, and which layout semantics are missing for people who use
wide monitors or tag-heavy workflows?
```

## Lobsters

Title:

```text
Olly: a Swift macOS window manager with hot-swappable layout engines
```

Suggested tags: `mac`, `swift`, `programming`, `release`.

Body/comment:

```text
I built Olly around a layout-engine contract rather than a single tiling model.
The same macOS desktop can use Niri-style scrolling columns, BSP, master-stack,
floating, or other engines per tag/workspace, with the engine selected from a
Swift config DSL or through ollyctl IPC.

The implementation is AX-only for v0.x, keeps layout engines pure/synchronous,
and defers runtime dylib plugins until the Swift ABI contract has survived two
minor releases. Current plugin authoring docs include a SwiftPM template and a
separate showcase repo with three case-study engines.

Useful context:

- PerfBench results: https://github.com/gongahkia/olly/blob/main/docs/perfbench-results.md
- Layout screenshots/research notes: https://github.com/gongahkia/olly/blob/main/docs/layouts-research.md
- Plugin authoring contract: https://github.com/gongahkia/olly/blob/main/docs/plugin-authoring.md

Feedback I am looking for: layout API stability, missing engine capabilities,
and macOS-specific AX failure modes that should be modeled before v1.0.
```

## r/MacOS

Title:

```text
[Open Source] Olly - macOS window manager with hot-swappable layouts
```

Body:

```text
I am building Olly, an open-source macOS window manager written in Swift.

The core idea is that layouts are hot-swappable per workspace/tag: one workspace
can use Niri-style scrolling columns, another can use BSP, another can use
master-stack, and dialogs/calls can stay floating.

Why it may be interesting to macOS users:

- Uses Accessibility APIs only; no SIP-off requirement.
- Has ollyctl IPC for scripting.
- Includes built-in integrations/docs for SketchyBar, JankyBorders, Ubersicht,
  Raycast, Alfred, skhd, Karabiner, Hammerspoon, and notch/menu-bar utilities.
- Has screenshots for Monocle, Spiral, Grid, ThreeCol, and Accordion layouts.

Links:

- Repo: https://github.com/gongahkia/olly
- Screenshots: https://github.com/gongahkia/olly/blob/main/docs/layouts-research.md
- Performance snapshot: https://github.com/gongahkia/olly/blob/main/docs/perfbench-results.md
- Demo GIF: https://github.com/gongahkia/olly/blob/main/docs/demo.gif

I am looking for feedback from people who use macOS tiling managers on multiple
monitors or with notch/menu-bar tools. The project is still early, so bug reports
and integration edge cases are more useful than feature requests.
```

## Source Notes

- Hacker News guidelines: <https://news.ycombinator.com/newsguidelines.html>
- Hacker News Show page: <https://news.ycombinator.com/show>
- Lobsters guidelines: <https://lobste.rs/about>
- r/MacOS rules page returned 403 from this environment on 2026-06-28; verify in browser before posting.
- Homebrew cask acceptance docs: <https://docs.brew.sh/Acceptable-Casks>
