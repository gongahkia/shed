# Tranche 8H evidence index

Read [APPEARANCE_FINAL_QUALIFICATION.md](../../APPEARANCE_FINAL_QUALIFICATION.md) for the decisions, boundaries and three-track audit handoff. This is verification only; no production changes. Use `gzip -dc FILE.gz` for compressed evidence. No JAR, heap dump, personal configuration or unrelated desktop capture is included.

## Regression and preservation

- `focused.log.gz`: 72 focused tests passed, zero failures/errors/skips.
- `full-tests.log.gz`, `test-results.json.gz`: 1,621 total / 1,616 passed / five existing skips / zero failures/errors; suite and exact skip identities.
- `tooling.log.gz`: 47 tests passed.
- `packaged-smoke.json.gz`, `packaged-smoke.log.gz`: actual packaged image/math/Mermaid PASS, artifact/probe identity and outputs.
- `baseline.json.gz`: entry HEAD/status and hashes of 1,716 pre-existing files, including unfinished 8G.1 documentation/evidence.
- `repository-verification.json`: final hash preservation, README identity, HEAD/status and authored-file inventory. Pre-existing changes are not attributed to 8H.
- `artifact-verification.json`: all 1,426 compiled production classes equal the frozen packaged JAR; missing/mismatch lists empty.
- `source-inventory.json`: benchmark/probe source hashes. New probe is in `src/benchmark/java/shed/AppearanceQualificationProbe.java`, independently compiled outside normal Maven production/test sources.
- `manifest.json`, `checksums.sha256`: complete evidence inventory and hashes. Manifest excludes itself/checksums to avoid self-hash recursion; checksums exclude only themselves.

## Fresh measurements

- `environment.json`: host hardware/OS, load, memory pressure, existing swap, displays; per-process reports also retain environment snapshots.
- `startup.json`: three fresh JVM controls, exact established budgets, 75 edit/paint and ranking samples. All this set's gates pass; this does not erase the independent load timeout or historical startup misses.
- `density-matrix-report.json.gz`, `density-remaining-report.json.gz`: all twelve planned density children, same artifact in both arms; one 100 KiB reference times out before loading. Eleven children complete. All raw first/warm events, geometry/PNG hashes, heap and 250 ms RSS samples remain.
- `density-matrix.log.gz`, `density-remaining.log.gz` and per-child `*-stdout.log.gz`: completion/failure and full event streams, including the original timeout. The remaining driver runs only cases not yet executed; no failed appearance case is retried.
- `default-comparison.json`: ten exact complete-pair Default geometry/PNG-hash-map comparisons; one explicitly incomplete pair. The original 8F pre-density goldens remain separately preserved.
- `pane-verified-report.json.gz`, per-child logs: two current-artifact 1 MiB lifecycle controls, twelve split cycles each, first/60 warm two-pane and 30 three-pane activations, 50 warm inputs before/after. Native Robot focus explicitly NOT_REQUESTED; zero closed views and stable seven listeners.
- `focus-current-report.json.gz`, per-child logs: unwrapped 10 MiB and wrapped 100 KiB normal/focus controls, first inputs separately, 50 warm inputs per phase, toggles, scroll/resize, source checks and distinct heap boundaries.
- `theme-current-report.json.gz`, per-child logs: four children, manual reference versus live preview on the same JAR. Small HTML preview and 100 KiB split/focus cases; first switch/cancel, nine repeated switches, rapid preview changes, accept/save, nine closed gallery references each, heap and RSS.
- `summary.json.gz`: nearest-rank p50/p95/p99/max and counts by operation/numeric field, separate timing/allocation/heap/RSS, lifecycle rows, visual and rooted events, warning register. The raw source is authoritative; do not sum independent timing percentiles or compare different disposal boundaries.

## Visual, accessibility and rooted history

- `visual/*.png`: eight component-root renders, not OS/desktop screenshots. Light/dark × three densities, combined focus/center, narrow compact splits. Inspected images show mixed-script missing-glyph and overprint observations; native-display equivalence is **unverified**, not a visual pass. No wallpaper or unrelated window is captured.
- `visual.log.gz`: accessible pane/gallery names, source/selection contrast, actual 2× transform, unchanged font/line height, source hit testing and exact source/state assertions. **Contains a caught `BadLocationException` at `LineNumberPanel:106` during queued repaint.** Child exit zero only means its explicit assertions/completion succeeded; it does not override this warning.
- `rooted.log.gz`: 24 split closures in batches of eight, zero retired-caret edges/views at 0/8/16/24, same active document/history, seven listeners, 51 exact Redo/Undo pairs and final source/revision/caret.
- `final-controls.json`: exact child commands, exits, bounds, durations and RSS for the visual/rooted controls. Existing feature scripts' reports record their own JVM commands and RSS.

## Exact orchestration and unsuccessful setup

- `remaining-density.py.gz`: imports the unchanged existing density runner and selects only its remaining four fixture configurations.
- `final-controls.py.gz`: imports existing pane/focus/theme/desktop tools with explicit finite matrices, then compiles/executes the two visual/rooted diagnostics with isolated homes and timeouts. Run with the established JDK21 toolchain environment and replace repository/scratch paths when reproducing elsewhere. Existing output directories intentionally reject reuse.
- `final-controls-verified.log.gz` plus named `benchmark-*.log.gz`: actual bounded run completions; per-stage return codes are in `final-controls.json`.
- `final-controls.log.gz`: first driver setup failed toolchain resolution **before any GUI child**. The existing toolchain environment was supplied for the actual run; the incomplete scratch directory was preserved. This is not counted as a product test or silently erased.

Historical 7C/8A–8G.1 evidence is referenced, not rewritten or pooled with these samples. Main-window weak references remain reachable in short controls; native memory and system-low-RAM behavior remain unqualified. Successful Java-object controls do not establish whole-process or graphics-resource release. No new remediation, benchmark track or agent-control implementation is delivered.
