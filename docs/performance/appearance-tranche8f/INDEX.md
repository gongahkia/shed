# Tranche 8F evidence index

The implementation and interpretation are in [UI_DENSITY_PRESETS.md](../../UI_DENSITY_PRESETS.md). All `.gz` files are ordinary gzip, readable with `gzip -dc FILE`. No application artifact or heavyweight heap dump is checked in.

## Primary evidence

- `matrix-report.json.gz`: all samples from six paired packaged-editor workloads (12 processes), fixture/artifact/probe hashes, JVM arguments, RSS series and host controls.
- `measurement-summary.json.gz`: per-operation counts, p50/p95/p99/max, heap/commit observations and sampled RSS peaks. Percentiles use nearest-rank; p50 uses the median.
- `default-comparison.json`: all 12 initial/restored Default geometry comparisons, including 36 component PNG hash matches against the unchanged entry JAR.
- `visual/`: sampled header, editor strip and footer images from the small-sidebar pair. Baseline Default and candidate Default/Compact/Comfortable/restored Default are included. These are component captures, not a claim of full-desktop pixel equivalence.
- `artifacts.json`: immutable artifact/probe identities and sampling boundaries. The baseline does not implement density; its labelled requests are no-ops.
- `design.md.gz`: bounded component/ownership contract recorded before production edits.

## Memory and additional controls

- `split-memory-repeat-report.json.gz`: identical 1 MiB split workload, reversed pair order; short-settle difference reproduced, post-disposal live heap converged.
- `memory-diagnostic-{baseline,candidate}-stdout.log.gz`: a separate pair with live class histograms and a further bounded two-second active settling control. This is not substituted for the original short-settle result.
- `memory-diagnostic-{baseline,candidate}-{immediate,settled}-histogram.txt.gz`: `jcmd GC.class_histogram` output; diagnostic attach/GC overhead is separate from the main matrix.
- `memory-diagnostic-{baseline,candidate}-command.json.gz`: exact diagnostic process invocations.
- `histogram-summary.json`: totals and selected document/buffer object counts from those histograms.
- `wrapped-repeat-v2-report.json.gz`: identical 1 MiB wrapped workload, reversed pair order; preserves variable typing, tab-return and resize costs.
- `repeat-split-memory.py.gz`, `repeat-wrapped-control.py.gz`, `diagnose-split-memory.py.gz`: narrow diagnostic drivers with recorded scratch paths. The histogram driver generates an isolated copy of the production benchmark probe; it does not change application code. Set the repository/toolchain paths appropriately to reproduce elsewhere.
- Corresponding `.log.gz` files record completion.

## Verification and rejected runs

- `full-tests.log.gz` and `full-results.json.gz`: complete Maven run, 1,601 passing / five existing skips / zero failures or errors.
- `focused-results.json.gz`: final 103-test focused run, no skips/failures/errors.
- `tooling.log.gz`: 44 fast tooling tests passing.
- `packaged-smoke.json.gz`: packaged image/math/Mermaid runtime smoke PASS, with exact JAR identity and renderer timings.
- `setting-tests.log.gz`: initial configuration-reference failure, corrected by documenting the new setting.
- `density-initial.log.gz`: reproduced cross-window density-write/font-replay failure, corrected and retained as a regression test.
- `header-regression-before.log.gz` / `header-regression-after.log.gz`: 49-versus-41-pixel Default header restoration regression and passing correction.
- `pilot*`: unsuccessful/diagnostic harness runs before final matrix. Early controls attached a file tree to a source-owning split, invalidating paint ownership; later controls use the real file-tree route. The early real-tree pilot additionally exposed the header restoration issue. None is used as final successful product evidence.
- `wrapped-repeat.log.gz`: initial repeat launch failed before starting Java because its shell had not sourced the recorded Java 21 toolchain. The `v2` run used the correct environment and completed.
- `split-memory-diagnostic.log.gz`: initial diagnostic-driver argument assertion failed before Java launch; corrected driver results are under `memory-diagnostic-*` and `split-memory-diagnostic-v2.log.gz`.

## Repository preservation

- `baseline.json.gz`: entry HEAD/status, baseline JAR hash and hashes of 1,569 existing source/documentation/tooling files, including pre-existing 8E.1 work.
- `repository-verification.json`: entry and observed final HEAD/status, README hashes, all 16 intentionally changed existing paths, no missing starting files, 1,553 unchanged starting files, unchanged measured probe hashes, and equality of all 1,426 current compiled classes with the frozen candidate JAR.
- `entry-to-final-existing-files.patch.gz`: reviewable diff against entry copies, including changes captured by an external commit while this work was in progress. New source/tooling files are present at their normal repository paths.
- `checksums.sha256`: hashes of the evidence files, excluding the checksum file itself.

The agent made no commits or pushes. The externally advanced HEAD was preserved. Production Unicode first-edit stalls, HTML-preview layout stalls, unresolved native-memory attribution and incomplete platform qualification remain open.
