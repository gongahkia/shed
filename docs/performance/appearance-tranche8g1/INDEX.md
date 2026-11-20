# Tranche 8G.1 evidence index

The findings, contracts, commands and limits are in [SPLIT_VIEW_LIFECYCLE_REMEDIATION.md](../../SPLIT_VIEW_LIFECYCLE_REMEDIATION.md). Read `.gz` files using `gzip -dc FILE`. No application JAR, user document or heap dump is checked in.

## Final evidence

- `focused-accepted.log.gz`: final 78 focused tests, no failures/errors/skips or uncaught EDT exceptions.
- `full-accepted.log.gz`, `accepted-results.json.gz`: final complete Maven result, 1,616 passed / five existing skips / zero failures/errors; exact skip identities.
- `package-accepted.log.gz`: successful quiet packaging step from the final verification pipeline.
- `tooling.log.gz`: 47 fast tooling tests passed.
- `packaged-smoke.json.gz`, `packaged-smoke.log.gz`: packaged image/math/Mermaid runtime smoke PASS, including exact artifact identity.
- `verified-matrix-report.json.gz`, `verified-matrix.log.gz`: six final paired packaged-editor lifecycle processes, identical 4 KiB/100 KiB/1 MiB fixtures, alternating order, raw samples and host/JVM/RSS boundaries. Explicit lifecycle-only mode does not claim native focus qualification.
- `verified-summary.json.gz`: counts and p50/p95/p99/max, separate dispatch/validation/paint/allocation, used/committed heap and sampled RSS. Total EDT percentiles are calculated from each sample's summed durations, not by summing independent percentiles.
- `verified-rooted-diagnostic-report.json.gz`: four child diagnostics, ASCII/Unicode against the starting/final JARs. Records exact commands and hashes, object identities, revisions/carets, source text, rooted-reference counts and first/warm Undo/Redo samples.
- `verified-rooted-diagnostic-*-stdout.log.gz`: full diagnostic logs, including the starting ASCII exception stack omitted from the structured event projection. An exit-zero starting diagnostic means measurement completed, **not** successful Undo: it reports zero history pairs and the exception. Corrected children require success and each complete 51 exact pairs.
- `rooted-summary.json.gz`: history timings, source states, and 0/8/16/24-cycle reference observations. Both corrected cases have zero retired-caret edges and reachable retired views at every batch.
- `measure-verified.py.gz`: exact bounded orchestration driver used locally. The reusable Java probe is `src/benchmark/java/shed/SplitLifecycleDiagnostic.java`; its three source inputs are compiled explicitly, outside Maven's routine test lifecycle. Adjust recorded repository/scratch paths when reproducing elsewhere.

## Reproduction and rejected/intermediate work

- `before-tests.log.gz`, `before-live-splits.log.gz`: deterministic original failures before production changes; the live-split control passes.
- `original-diagnostic.log.gz`, `ActivePaneUndoDiagnostic.java.gz`: initial detailed object/history/revision/notification evidence. After failed Undo the source is `body\n`, revision remains 4 and surviving caret is 6.
- `after-first.log.gz`: first corrected targeted run, preceding complete queued-callback qualification.
- `focused-expanded.log.gz`: null-caret/UI cleanup experiment emitted asynchronous NPEs, despite successful assertions. It was rejected. Final retirement retains a valid inert Swing component.
- `focused-valid-components.log.gz`: initial corrected queued-component checks.
- `presentation-before.log.gz`, `presentation-roots-before.log.gz`: confirmed owner minimap and pending-paint roots.
- `focused-final2.log.gz`: newly added minimap test initially closed the current pane rather than the explicitly captured target after shared caret notifications. Final tests close the captured target and preserve the survivor's minimap. This was a harness target-selection error, not a reason to weaken existing coverage.
- `history-owner-before.log.gz`, `history-routing-before.log.gz`: actual Vim Undo on A changes B before the legacy history-alias correction; the latter records both source texts.
- `composition-before.log.gz`: intermediate cleanup removed the existing pending IME focus-lost reset. Final cleanup keeps that callback and the new regression passes.
- `focused-release.log.gz`, `focused-verified.log.gz`: successful intermediate regressions, superseded by the final accepted run.
- `matrix.log.gz`, `matrix-report.json.gz`, `matrix-baseline-4096-stdout.log.gz`: initial native Robot focus attempt timed out on the unchanged starting artifact. It is not counted as native-focus success; no arbitrary retry or platform setting change was used.
- `lifecycle-matrix-report.json.gz`, `release-matrix-report.json.gz`, `rooted-diagnostic-report.json.gz`: intermediate artifact measurements. They are preserved but are **not** the final before/after table source. Exact artifact hashes distinguish them.
- `measure-release.py.gz`: earlier driver retained for provenance; final evidence uses `measure-verified.py.gz`.

## Repository preservation

- `baseline.json.gz`: entry HEAD/status, frozen JAR hash, and hashes of 1,669 starting files, including all previous reports/evidence.
- `repository-verification.json`: ten intentional existing-file changes, 1,659 unchanged starting files, none missing, identical README hashes, final compiled-class/probe comparisons, final status and artifact hashes. It records the external HEAD advancement; the agent made no commits or pushes.
- `entry-to-final-existing-files.diff.gz`: complete reviewable diff against entry, including changes captured by the external commit during this task. New diagnostic/report files are available in their normal repository locations.
- `checksums.sha256`: evidence checksums, excluding the checksum file itself.

The static live editor and public Toolkit listener registry provide diagnostic roots; the canonical open document is intentionally retained. Only stale view edges should disappear. GC requests are isolated diagnostic controls, never production cleanup. These results do not claim full main-window collection, native-resource release, low idle RSS, native IME coverage or release readiness for the earlier Unicode/preview blockers. No 8H work was started.
