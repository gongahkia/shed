# Replay presentation review

Follow-up: the [inspectable replay review](inspectable-replay-review.md) adds
inspection/comparison and verifies native OS controls and crowd performance.

Full review, 21 October 2026. Scope: the native replay window, floor plan,
isometric overview, watch loading/failure states, PNG snapshots and headless
PNG/GIF exports. The application retains minifb and its CPU framebuffer;
tiny-skia supplies vector antialiasing and fontdue rasterizes the bundled Inter
font. Presentation tokens live in `src/render/mod.rs` within `chiyoda-replay`.
No browser framework, texture system, new simulation behaviour, or README edit
is part of this change.

## Coverage

| Category | Evidence inspected | Result |
| --- | --- | --- |
| Typography | `render/canvas.rs`, source IDs, timestamps, counts, PNG and decoded GIF text | Bundled font, tabular numbers, measured ellipses, cached glyphs. Unsupported glyph fallback is documented; complex text shaping is outside this renderer. |
| Surfaces | Plan/overview PNGs at steps 0, 100 and 420; empty, failed and last-valid watch captures | Uniform metric scale, fixed chrome regions and map clipping; status information stays outside the plan. |
| Animations | Playback orchestration, exact-frame seek tests, all 22 decoded GIF frames and their delays | Recorded-frame mapping and timing retained. No cosmetic animation or interpolated position added. Manual viewing at 10% speed was not performed. |
| Icons | Connector/exit/gate symbols, active/inactive people, legend and queue paths | Vector glyphs, semantic colours and filled/hollow state distinctions replace sprites; waits and transit have additional interior marks. |
| Performance | Release PNG/GIF exports, persistent canvas/glyph/palette caches, native startup smoke check | Four PNG exports took 1.020–1.444 s each; the final 22-frame GIF took 2.883 s, including bundle reconstruction. Large-population interactive frame rates were not benchmarked. |

## Addressed findings

Locations are relative to `crates/chiyoda-replay/src` unless otherwise noted.
All findings in this table have been addressed.

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM | `main.rs` and removed `assets/replay/` | Optional sprite flag, atlas loader, tile types, image dependency and sprite-specific provenance | Remove the flag, loader, atlas assets, sprite parameters, documentation and active demo treatment | One consistent drawing vocabulary; fulfills the requested sprite removal. Regression tests reject the removed flag. |
| HIGH | `render/scene.rs:26` | Independent horizontal/vertical projection scales distorted physical proportions | Fit one metric scale to authored surface bounds; add grid and scale bar | A spatial comparison must preserve proportions. Camera bounds remain independent of frame positions. |
| HIGH | `render/mod.rs:156`, `render/canvas.rs:38` | Debug/status overlays could cover geometry; map drawings could enter UI regions | Separate header, map, sidebar and timeline; clip spatial vectors and text to their viewport | Geometry and evidence remain readable while playback and watch status change. |
| MEDIUM | `render/canvas.rs:201`, `assets/fonts/` | Enlarged bitmap lettering and fixed character wrapping | Antialiased bundled typography, tabular digits, measured truncation and bounded glyph cache | Legible labels and stable numerical alignment without platform font differences. License and source are included. |
| MEDIUM | `render/scene.rs:314`, `render/mod.rs:285` | Bright unrelated colours and square markers with little hierarchy | Quiet architectural surfaces, restrained semantic colours, consistent glyphs, a matching legend and circular people markers | Status remains distinct from static geometry; hollow and interior marks supplement colour. Minimum marker size is disclosed. |
| MEDIUM | `render/scene.rs:360` | Unlabelled resources and overview floors | Place resource IDs where they fit and render elevation annotations above floor geometry | Readers can connect the visual plan to authored resources. Dense labels can be omitted without removing their symbols. |
| HIGH | `render/mod.rs:106`, `main.rs:103` | Verification status primarily appeared in terminal/window text | Explicit reconstructed, legacy hash-only and unpersisted live labels; headless exports require reconstruction | The presentation identifies the evidence it actually displays. |
| MEDIUM | `render/mod.rs:252` | Large watch errors competed with debug content | Preserve the last valid plan; put concise status below it and failure detail in a reserved sidebar region | Error feedback remains visible without hiding spatial evidence or its key. |
| MEDIUM | `render/mod.rs:31`, `main.rs` GIF writer | A small geometric palette could not represent antialiasing; adaptive quantization shifted rare colours during review | One shared palette with exact semantic colours and edge-coverage ramps; cache colour mappings | The animation keeps status colours stable across frames. PNG preserves full rendered RGB detail. |
| MEDIUM | `main.rs` export functions, `docs/replay.md` | Single-frame export required opening a window; projection scope was implicit | Exact-step headless PNGs and selectable initial projection; sidecars list selected/rendered surfaces, source hashes, renderer and font | Agents can export inspectable evidence without display automation. Existing image/sidecar targets are rejected. |
| LOW | `main.rs`, `render/` | Input, drawing, font and atlas logic shared one large file | Separate shared canvas, spatial layers and presentation; reuse renderer state for playback/animation | Native and exported views use the same rendering path without repeated font construction per frame. |

## Considered but rejected

| Location | Candidate | Rejected because |
| --- | --- | --- |
| Native renderer | Replace minifb with a browser or GPU scene engine | The requested plan and recorded-state views fit the existing framebuffer; a platform rewrite adds unrelated work and deployment requirements. |
| Typography | Use the machine's installed UI font | Exported text would depend on local font availability and revisions. The bundled font has a recorded hash and license. |
| Playback | Animate additional positions between stored frames | Exact-frame diagnostics and exports need a clear correspondence with recorded evidence. Motion interpolation would require a separately specified presentation contract. |

## Verification

Commands run from the repository root, with Cargo on `PATH`:

```console
$ cargo fmt --all -- --check
$ cargo clippy --workspace --all-targets -- -D warnings
$ CARGO_PROFILE_TEST_OPT_LEVEL=2 cargo test -p chiyoda-replay
$ cargo build --release -p chiyoda-replay
```

Formatting, workspace Clippy and the release build passed; all 22 replay tests
passed. Tests cover exact seeks, projection proportions, viewport clipping,
every recorded state, watch failures and stale updates, source labeling,
unmodified trace evidence, removed/conflicting CLI options, overwrite refusal,
PNG encoding, decoded GIF dimensions/frame counts/delays, and palette stability.

The release CLI exported the grand-interchange floor plan at steps 100 and 420,
the northern platform at step 0, the overview at step 100, and the entire GIF
at 10× speed. These PNGs and decoded GIF frames were visually inspected.
The native viewer ran paused at step 100 for three seconds without a startup
failure on this macOS machine, then the smoke-check process terminated it.
Watch empty/error/last-valid states and the legacy label were rendered by the
same renderer in tests and inspected as captured PNGs.

The final GIF was independently decoded with Pillow in an ignored local virtual
environment. All 22 frames had the expected dimensions and delays; every frame
retained exact moving, waiting and completed palette colours. The refreshed
committed demos have adjacent JSON provenance sidecars.

The canonical bundle hash remained
`97c3af8e687d17f5bbce55d4317cdfc20a42fc6d0f9602423f3544f3313b31ac`.
The input bundle file's SHA-256 was
`551ba524d9f79ea3cc6d0099ff9f4fcecbb479f944fbf733d8105cddeae3d811`
before and after the release exports. Simulation sources, scenarios, and README
were not edited. Renderer changes do not alter run outcomes.

For optional watch-state review captures, set `CHIYODA_REPLAY_REVIEW_DIR` to a
new directory when running the `watch_failure_preserves_the_last_valid_plan`
test. Captures refuse to overwrite existing files, like normal PNG snapshots.

Verdict: **Approve for the reviewed presentation and exports.** No actionable
finding remains in this scope. **Not verified:** manual keyboard interactions,
Linux/Windows display startup, manual motion inspection at 10% speed, complex
text shaping, and interactive frame rates for very large populations. The
ordinary runtime's expensive movement regressions were not repeated because
simulation code and scenario inputs are unchanged.
