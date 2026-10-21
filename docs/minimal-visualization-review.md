# Minimal visualization review

Full review of renderer revision 5. Scope: single runs, comparisons, selected
people/resources, live watch, PNG/GIF exports, MCP images and committed previews.
The implementation retains the existing minifb window, tiny-skia CPU canvas,
bundled Inter font and semantic geometry colours. README.md is unchanged.

Every visualization contains only the simulation and progress bar. Single runs
use one fixed scene viewport; comparisons show the baseline on the left and
candidate on the right. Selection adds spatial highlights and trails. Detailed
measurements, IDs, route events, held-frame timestamps and provenance remain in
JSON evidence and agent results. Watch errors and legacy notices remain in the
terminal. The operating-system window title is `Chiyoda`.

## Coverage

| Category | Evidence inspected | Result |
| --- | --- | --- |
| Typography | Timeline drawing, numeric glyph advances and render-time text records | Only three timestamp labels are drawn for a loaded run. They use the existing tabular digits. Empty watch canvases have no text. |
| Surfaces | Both projections, single/comparison clipping and transformed hit tests | The enlarged scene has fixed bounds. Selecting and clearing do not reserve space for a sidebar. |
| Animations | Recorded-frame clock tests, GIF timing/export tests, regenerated floor/overview GIFs and native playback/seek checks | No cosmetic motion or new interpolation. Manual viewing at 10% speed is not verified. |
| Icons | Exit, gate, connector and waypoint drawing; people and selected trails | Resource glyph text is replaced by geometric marks. Existing state colours and filled/hollow people remain. |
| Performance | Persistent canvas/glyph/palette caches and native redraw reuse | Removing chrome does not add a drawing framework, external font or per-frame analysis pass. No new large-crowd benchmark was performed. |

## Addressed findings

Locations below are relative to `crates/chiyoda-replay/src/` unless specified.

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM | `render/mod.rs:132`, `render/compare.rs:12` | Titles, frame metadata, outcomes and selection summaries surround the simulation | Scene drawing followed only by the shared timeline | Apply the requested bare presentation to every renderer caller. |
| MEDIUM | Removed `render/inspector.rs`, `render/scene.rs:17`, `viewer/native.rs:277` | Selecting a person opens a sidebar and changes the camera fit | One fixed viewport, shared by drawing and picking | Selection must not reintroduce panels or move the simulation. |
| MEDIUM | `render/scene.rs:295` | Resource IDs, connector letters, floor names, grid, scale and axis/footer annotations | Authored geometry, resource marks, people and spatial selection only | Remove secondary visual furniture while preserving simulated geometry. |
| MEDIUM | `render/chrome.rs:14`, `render/mod.rs:33` | Diagnostic ticks/bands and different time captions | Identical numeric timestamps and playhead; shared 64-pixel seek target | Retain one restrained bar and a usable desktop hit area. |
| MEDIUM | `viewer/native.rs:129`, `viewer/mod.rs:247` | Changing title, loading copy and in-canvas errors | Fixed window name, blank loading scene and terminal diagnostics | Empty and failure states follow the same visual scope. |
| MEDIUM | `evidence.rs:1`, `crates/chiyoda-cli/src/agent/tools.rs:120` | Evidence images include a text inspector | Spatial selection image plus unchanged structured measurements | Preserve agent capability while removing visual panels. |
| MEDIUM | `scripts/replay_native_smoke.py:86`, `scripts/replay_watch_smoke.py:25` | Native checks depend on verbose titles and a narrower selected viewport; watch state changes lack a native pixel check | Compare window snapshots with exact headless PNGs and exercise watch failure/recovery | Validate controls without restoring the removed UI for automation. |
| MEDIUM | `assets/demo/`, `docs/replay.md:246` | Bundled previews and documentation describe dashboards | Revision 5 previews and current behavior documentation | Keep examples consistent with the implementation. |

## Considered but rejected

| Location | Candidate | Rejected because |
| --- | --- | --- |
| Renderer options | Add an opt-in minimal theme beside the old dashboard | The requested behavior applies to every visualization. |
| Evidence export | Remove diagnostic fields along with their panels | Agents and study analysis still need structured evidence. |
| Spatial layout | Stretch geometry to fill the scene | Uniform metric proportions remain necessary for architecture comparisons. |

## Verification

The CLI/replay suite passed **83 tests**, including 14 agent/MCP checks, exact
PNG receipts, campaign renderer-mismatch checks, both GIF timing paths, sparse
evidence, source immutability and transformed picking. Formatting, workspace
Clippy, locked release compilation and headless checks passed:

```console
CARGO_PROFILE_TEST_OPT_LEVEL=2 cargo test -p chiyoda -p chiyoda-replay --locked
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo check -p chiyoda --all-targets --no-default-features --locked
cargo check -p chiyoda-replay --lib --no-default-features --locked
cargo build --release --locked -p chiyoda
```

Render-time assertions inspect every text draw and reject any text outside the
timeline in both projections, with no selection, a person and a resource, in
single and comparison views. Pixel checks reject chrome outside scene/timeline
bounds. The empty-scene check permits only an unlabelled inactive track.
Review captures were saved with `CHIYODA_REPLAY_REVIEW_DIR` and inspected.

Actual macOS OS-input checks completed **25 checks for the single run and 27
for comparison**, using the optimized debug binary. Ten window snapshots match
headless references exactly, including selected views, zoom and overview.
Both report only `Chiyoda` as their window title. The single-run driver measured
29.36 window updates/s and the comparison 29.47; these include pacing and the
reference exports, and are not a new crowd-performance claim.

```console
python3 scripts/replay_native_smoke.py --binary target/debug/chiyoda --bundle out/minimal-visualization-review/interactive/run.json --agent concourse_arrivals:0 --at-step 100 --output out/minimal-visualization-review/native-single
python3 scripts/replay_native_smoke.py --binary target/debug/chiyoda --bundle out/minimal-visualization-review/station/arms/baseline/run.json --candidate out/minimal-visualization-review/station/arms/announcement/run.json --agent passengers:4 --at-step 100 --output out/minimal-visualization-review/native-comparison
python3 scripts/replay_watch_smoke.py --output out/minimal-visualization-review/native-watch
```

The release watch check passed **four states**: missing source, valid source,
invalid JSON and corrected geometry. The missing-source screenshot matches the
empty renderer capture. Invalid JSON emits a terminal diagnostic and preserves
the exact last-valid pixels. Corrected geometry matches its new headless
reference and differs from the preceding scene.

Release-generated plan, overview, person, resource, comparison and WFC PNGs were
inspected. The release plan and comparison match the native driver screenshots
byte for byte. All five committed PNG previews and the showcase GIF were
regenerated with renderer 5. Their source bundle hashes, inspection/comparison
objects and GIF timings are unchanged. Both floor-plan and overview GIF export
were exercised. README.md and Cargo.lock remain unchanged.

Historical reviews retain their original commands, renderer revisions and
measurements. This review supersedes their descriptions of visible chrome.

Verdict: **Approve**. No actionable interface findings remain. Manual motion
review at 10% speed and a new large-crowd performance benchmark were not run;
simulation timing and the existing cache strategy are unchanged.
