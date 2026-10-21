# Inspectable replay review

## Renderer 4 layout consistency

The single-run and comparison modes now share headings, outcome summaries and
one full-width timeline with a time label attached to its playhead. Single runs
open as a full-width plan; selecting a person or resource opens its inspector.
Clearing selection or hiding the inspector restores the full plan. The older
branding bar, permanent dashboard, source panel and keyboard-help footer have
been removed. This applies to authored/generated scenarios, native playback,
plain/annotated PNGs, GIFs and agent image exports.

Verification passed **41 replay tests and 14 agent/MCP tests**, formatting,
workspace Clippy and locked release builds of both binaries. The native macOS
driver passed **25 checks in each mode**, using the optimized test binary: the
52-person interchange exercised contextual inspection, inspector hide/restore,
resource selection, pan, zoom, floor/projection changes, seeking and playback;
the station comparison exercised both panels and their shared controls.
The driver restores the target window's focus before global mouse events.

Fresh floor-plan, overview, person and comparison PNGs were inspected. The
comparison PNG remains byte-identical to the preceding cleaned layout, with
the same measured evidence. Release CLI evidence matches the viewer's PNG
bytes. Older exports retain their recorded renderer revision: source/hash
verification remains available, while an exact-pixel request with a different
renderer returns `renderer_mismatch`. Canonical scenarios, runs and simulation
semantics are unchanged. README.md was not edited.

The review below records the preceding implementation and its verification.

Full review, 21 October 2026. Scope: person/resource inspection, annotated PNG
exports, MCP image results, native navigation, synchronized comparisons and
crowd playback. This extends the [presentation review](replay-visual-review.md)
and closes its native-controls and large-population verification gaps on macOS.
The CPU framebuffer and bundled typography remain the presentation system.

## Coverage

| Category | Evidence inspected | Result |
| --- | --- | --- |
| Typography | Person/resource sidebars, long IDs, sparse-trace reasons, event details, comparison labels and timeline captions | Bounded text and tabular measurements retain the existing type hierarchy. Authored goals, recorded decisions, frame timestamps and whole-run measurements have distinct labels. |
| Surfaces | Floor plan, overview, zoom/pan clipping, selected trails, queue history, changed-population comparison and four live-watch states | One camera serves drawing and hit testing. Both comparison panels use a union of authored extents and the same metric scale. Watch failures retain the last valid map. |
| Animations | Actual OS-driven play/pause, seek, stepping and speed changes; recorded-time unit tests; 0.1× native crowd playback capture | Playback catches up to elapsed time using recorded frames. No positions are interpolated. Continuous human motion review was not performed; slow playback was exercised through OS events and its resulting frame inspected. |
| Icons | Selected-person ring, resource endpoints, historical entrant rings, trajectory segments, low-progress bands and event ticks | Selection adds restrained vector annotations. Inactive and connector-transit trail segments are omitted. Historical queue entries are explicitly labelled. |
| Performance | 120-frame CPU benchmarks for 1,000/5,000 people; actual native playback of both crowds; paused-frame caching and startup timing | Bounded analytical circle rasterization avoids repeated full-canvas work. Frame rendering and reconstruction/analysis costs are reported separately below. |

## Addressed findings

Locations are repository-relative. Findings include implementation review and
actual interaction review; every row describes an implemented change.

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM | `crates/chiyoda-replay/src/lib.rs:1`, `crates/chiyoda-replay/Cargo.toml:15` | Rendering/export logic was tied to the native binary | Shared library with optional native feature; CLI/MCP depend on the headless library | Reuse the same evidence and pixels without requiring a display backend. |
| HIGH | `crates/chiyoda-replay/src/render/canvas.rs:148` | Repeated path-based circle work made large-crowd rendering expensive | Rasterize antialiased circles only inside their visible bounding rectangle | Keep common crowd markers responsive without changing their semantic colours or the simulation. |
| MEDIUM | `crates/chiyoda-replay/examples/render_benchmark.rs:1`, `crates/chiyoda-replay/src/native.rs:600` | Crowd playback cost was unmeasured | Reproducible CPU benchmark and optional native timing receipt | Distinguish drawing throughput, window pacing and one-time loading costs. |
| MEDIUM | `crates/chiyoda-replay/src/inspection.rs:133` | People could only be identified through raw artifact inspection | Reusable, source-bound evidence index with exact snapshot/authored/event pointers and journey analyzer provenance | Selection connects visual state to recorded evidence and preserves its derivation. |
| HIGH | `crates/chiyoda-replay/src/inspection.rs:189`, `crates/chiyoda-replay/src/render/mod.rs:591` | Initial inspection design risked mixing frame state, full-run totals and unavailable sparse accounting | Explicit scopes; events/trails stop at the selected frame; whole-run totals stay labelled; sparse traces expose an unavailable reason | Prevent future information or insufficient sampling from being mistaken for a measurement at the selected time. |
| MEDIUM | `crates/chiyoda-replay/src/inspection.rs:430`, `crates/chiyoda-replay/src/render/mod.rs:660` | Resource queue evidence and availability changes required manual log reads | Queue audit, historical entrant highlights, raw operational changes and exact resource IDs | Explain resource outcomes without inferring current membership or unrecorded individual resource waits from proximity. Captions use singular/plural units. |
| MEDIUM | `crates/chiyoda-replay/src/inspection.rs:170`, `crates/chiyoda-replay/src/render/mod.rs:746` | Initial inspector's recent events were dominated by movement audit records | Prioritize the latest route/availability event and retain bounded route, information, release and service evidence | Show the recorded decision that explains a route; preserve movement audit totals and the full source log separately. |
| MEDIUM | `crates/chiyoda-replay/src/render/scene.rs:571`, `crates/chiyoda-replay/src/native.rs:290` | No spatial selection | Shared camera hit testing, person click, resource-preferring Shift-click and empty-space clear | Make visual marks inspectable at the same transformed positions where they are drawn. |
| MEDIUM | `crates/chiyoda-replay/src/render/scene.rs:19`, `crates/chiyoda-replay/src/navigation.rs:9`, `crates/chiyoda-replay/src/native.rs:210` | Fixed camera made larger spaces difficult to inspect | Bounded anchored zoom, right/middle pan, fit/focus, keyboard zoom and initial camera flags | Explore large plans while retaining authored metric proportions and map clipping. |
| MEDIUM | `crates/chiyoda-replay/src/render/mod.rs:480`, `crates/chiyoda-replay/src/native.rs:309` | Timeline was display-only; low-progress spans were only a count in the initial inspector | Click/drag seeking, low-progress interval bands, retained-event ticks and an explicit span range | Connect diagnostic periods to replay time. Whole-run and unavailable accounting stay distinguishable. |
| MEDIUM | `crates/chiyoda-replay/src/navigation.rs:72`, `crates/chiyoda-replay/src/native.rs:180` | Interactive navigation lacked speed controls and used per-frame pacing | Elapsed-time clock, bracket speed controls and exact recorded-frame stepping | Keep simulation time consistent across sampling cadences and slow rendering. Reuse unchanged frames while processing window events. |
| HIGH | `crates/chiyoda-replay/src/evidence.rs:94`, `crates/chiyoda-replay/src/export.rs:433` | A plain frame did not carry the selected person's/resource's diagnostic evidence | Reconstructed annotated PNG plus image/source hashes, exact request, measurements, source pointers and bounded retention; exclusive owned artifact writes | Keep pixels and their evidence bound together, refuse existing destinations and clean up only files created by the failed PNG operation. |
| MEDIUM | `crates/chiyoda-cli/src/agent/replay.rs:1`, `crates/chiyoda-cli/src/agent/tools.rs:119` | Agents could query diagnostics but could not obtain the corresponding image | Ninth tool, `chiyoda_render_evidence`, returns a bounded receipt and MCP image content verified against the saved PNG hash | Agents can request a precise person/resource moment through the existing workspace and protocol contracts. |
| HIGH | `crates/chiyoda-replay/src/comparison.rs:161`, `crates/chiyoda-replay/src/render/compare.rs:10` | Two runs required independently aligned windows | Shared camera/clock, common surface IDs, actual held-frame timestamps, separate frame/full-run outcomes and nullable unavailable differences | Make a comparison interpretable across different layouts and trace cadences. Changed population disables individual pairing. |
| MEDIUM | `crates/chiyoda-replay/src/render/compare.rs:63`, `crates/chiyoda-replay/src/native.rs:61` | Initial comparison repeated the same scenario title for different experiment arms | Distinct baseline/candidate labels from parent directory names, retained in the comparison request | Identify which intervention a panel represents; source hashes remain authoritative. |
| MEDIUM | `crates/chiyoda-replay/src/native.rs:143`, `crates/chiyoda-replay/src/main.rs:313` | New native orchestration needed to retain live-debugging failure behavior | Background analysis, stale-revision handling, complete terminal errors, initial empty status and last-valid map preservation | Inspection and navigation must not obscure source failures or block valid scene recovery. |
| LOW | `crates/chiyoda-replay/tests/cli.rs:109`, `scripts/replay_native_smoke.py:1`, `docs/replay.md:1` | New flags, native interactions and artifact boundaries lacked a repeatable check and usage guide | Headless CLI integration checks, OS-input smoke driver, explicit unsupported combinations and documented evidence limits | Make behavior reproducible; reject a comparison trail-start flag rather than silently ignoring it. |

## Considered but rejected

| Location | Candidate | Rejected because |
| --- | --- | --- |
| Native renderer | Replace minifb with a browser/GPU scene framework | Shared CPU rendering is sufficient for these plan/evidence views; changing platforms would add unrelated deployment work. |
| Playback | Interpolate people between recorded frames | Inspectable moments need exact source correspondence. The viewer instead holds the last recorded state and discloses held time in comparisons. |
| Queue inspection | Infer current queue membership or resource-specific individual waits from spatial proximity | The runtime's recorded events and aggregate audits do not justify those measurements. Historical entrants and whole-run audits are shown explicitly. |
| Comparison | Pair the same-looking person IDs after changing arrivals | Equal IDs do not establish equal physical/demand inputs. Population/seed checks conservatively gate individual elapsed comparisons. |
| Artifact scope | Add annotations and comparison to GIFs simultaneously | The requested agent evidence artifact is PNG. Keeping the existing all-frame GIF path avoids adding a second timing/palette contract to this milestone. |

## Verification

The final relevant suite passed **77 tests**: 39 replay and 38 ordinary CLI
integration tests, including 11 agent/MCP tests. Formatting, workspace Clippy,
release builds and the headless library check passed:

```console
cargo fmt --all -- --check
CARGO_PROFILE_TEST_OPT_LEVEL=2 cargo test -p chiyoda-replay -p chiyoda
cargo clippy --workspace --all-targets -- -D warnings
cargo build --release -p chiyoda-replay -p chiyoda
cargo check -p chiyoda-replay --lib --no-default-features
cargo tree -p chiyoda -e normal
```

The ordinary CLI dependency tree includes the shared replay library without
minifb. Tests exercise exact seeks, differently sampled comparisons, common
camera extents, changed populations, missing selections/surfaces, unavailable
clearance, source immutability, tampering, legacy rejection, sparse accounting,
source pointers, resource state changes, transformed hit testing, PNG ownership,
CLI conflicts, protocol/domain errors, and MCP image hash equality. Existing
GIF timing, palette and export tests continue to pass.

The native viewer was exercised through actual macOS Core Graphics key/mouse
events and Accessibility window inspection. This is an OS-level automated
interaction check, not a claim of a human manually reviewing every gesture.
Both invocations passed **25 checks**, saved snapshots, preserved source bytes
and exited successfully:

```console
python3 scripts/replay_native_smoke.py --bundle out/agent-validation-20261021/release-station/arms/baseline/run.json --agent passengers:4 --at-step 100 --output out/inspectable-replay/native-controls-final
python3 scripts/replay_native_smoke.py --bundle out/agent-validation-20261021/release-station/arms/baseline/run.json --candidate out/agent-validation-20261021/release-station/arms/announcement/run.json --agent passengers:4 --at-step 100 --output out/inspectable-replay/native-comparison-final
```

Replay checks included person/resource click selection, clearing, wheel/keyboard
zoom, camera pan/fit, projection/floor/details controls, exact stepping, bracket
speed changes, timeline seeking, play/pause, PNG snapshots and Escape. Comparison
checks also selected the person in each panel and exercised pan/zoom/focus from
the candidate panel with the shared camera. Native watch checks exercised a
missing initial file, a valid revision, an invalid edit and a recovered geometry
edit. The map crop was byte-identical after the invalid edit and changed after
recovery; full failure detail was present on stderr.

An independent official Python MCP SDK **2.3.0** client negotiated **2025-11-25**,
discovered all nine tools, validated generated input schemas with jsonschema
**4.26.0**, and requested person and exit evidence. Decoded image blocks matched
the saved PNG bytes and SHA-256 values; metadata hashes, step 100/time 20.0 s,
source hashes, overwrite refusal and source immutability were checked. The test
uses an ignored local virtual environment; no Python runtime dependency is added
to Chiyoda.

Eight final release PNG cases were exported and visually inspected: person,
queue resource, closed resource, same-population comparison, changed-population
comparison, overview, zoomed plan and sparse trace. Actual native snapshots of
selection, pan, overview, comparison, failed/recovered watch and crowd playback
were also inspected. The committed [person](../assets/demo/replay-person.png),
[resource](../assets/demo/replay-resource.png) and
[comparison](../assets/demo/replay-comparison.png) examples have adjacent JSON
sidecars containing their image and canonical source hashes.

## Crowd measurements

Machine: Intel i5-8279U 2.40 GHz, MacBookPro15,2, 8 GiB RAM. These are local
measurements, not a portable frame-rate guarantee. Raw values and scopes are
retained in [the verification receipt](inspectable-replay-verification.json).

```console
cargo run --release -p chiyoda-replay --example render_benchmark --no-default-features -- 1000 120
cargo run --release -p chiyoda-replay --example render_benchmark --no-default-features -- 5000 120
```

| People | CPU median / p95 | Native redraw median / p95 | Native window updates/s | Full native startup |
| --- | --- | --- | --- | --- |
| 1,000 | 5.15 / 6.19 ms | 10.19 / 11.96 ms | 29.36 | 5.85 s |
| 5,000 | 10.74 / 12.26 ms | 16.96 / 19.18 ms | 29.38 | 30.60 s |

The CPU benchmark measures 120 steady-state 1200×800 renders, including the
framebuffer copy. It excludes simulation, verification, analysis and window
presentation. Early measurements under concurrent build load found much higher
circle-rendering costs; they motivated the bounded rasterizer but are not a
controlled before/after speedup comparison.

Native measurements used valid simulated 100×100 m scenes, radius 0.25 m,
spawn (1,1), exit (95,95), duration 2 s, timestep 0.05 s and trace cadence 1.
Three playback/rewind cycles produced 108 and 109 redraw samples respectively.
The viewer targets 30 window updates/s. Observed rates are near that configured
cap, rather than a measurement of maximum hardware throughput.
Window-update rates include paused intervals while the driver sent OS inputs;
they measure event-loop presentation pacing, not continuously redrawing 5,000
people 29 times each second. Render samples exclude input, snapshot encoding
and presentation. Startup includes reading, full deterministic reconstruction
and one-time journey analysis; its cost remains visible and significant.

The 1,000-person replay was also exercised at **0.1× speed** for six seconds,
advancing to a recorded 0.65 s state. Its native redraw median/p95 was
11.37/12.16 ms. The resulting frame was inspected. Larger populations, long
recordings, complex multi-floor plans and comparisons with two large crowds
were not performance-benchmarked here.

## Verdict

**Approve for the implemented inspection, evidence exports and reviewed macOS
interactions.** No actionable interface-polish finding remains in this scope.
**Not verified:** Linux/Windows display startup, continuous human motion review,
complex text shaping, long-run memory growth, populations above 5,000 in native
playback, or native comparisons with two large crowds. The ordinary runtime's
expensive movement regressions were not rerun because simulation/model sources
and scenario inputs are unchanged. README.md was not edited.
