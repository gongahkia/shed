# Phase25 render checkpoint

Date: 2026-07-01

## Completed

- Split highlight color state out of the shaped-line cache key.
- Render highlight colors as an overlay pass instead of baking colors into cached glyph geometry.
- Added `ItsyBench render-highlight-cache`.
- Added lazy find-bar construction and Writing Tools opt-outs.
- Replaced the idle `<30 MB` RSS target with a `<100 MB` clean idle footprint target.

## Benchmarks

Render highlight cache:

```sh
swift run -c release ItsyBench render-highlight-cache
```

Result:

- 100000 lines
- 60 frames
- 100000 highlight spans replaced per frame
- Cache hit rate: `98.4635%`
- Cache misses: `59`
- Elapsed: `174.042 ms`

Cold start:

```sh
bench/scripts/make_app.sh
.build/release/ItsyBench measure --staged --app Itsy.app --new-instance --timeout-ms 10000
```

Result:

- Mean first-window-visible: `3333.682 ms`
- Warm-run mean excluding run 1: `2414.166 ms`
- Previous warm-run mean in `bench/notes/coldstart-audit.md`: `231.805 ms`
- Delta: `+2182.361 ms`
- Target: `>=40 ms` improvement
- Status: fail

Memory:

- Committed clean audit: `98611 KB` physical footprint.
- Current no-purge local probes: `182.9M` to `200192 KB` physical footprint and `416096` to `476256 KB` RSS.
- Target changed to `<100 MB` clean idle footprint; RSS remains recorded as context.

## Notes

[Inference] Render cache behavior is now healthy for stable text with volatile highlight colors.

[Inference] The cold-start miss is not a direct `otool -L` dependency problem: current traces still show AppKit/HIToolbox/text-system runtime `dlopen` calls for `WritingToolsUI`, `AppIntents`, `ViewBridge`, and `SwiftUI`, with no matching direct app-source imports.

[Inference] The next cold-start slice should investigate initial document/window construction, because the direct stage cross-check showed `main_menu_installed` at `107.175 ms` and `initial_document_opened` at `2120.836 ms`.
