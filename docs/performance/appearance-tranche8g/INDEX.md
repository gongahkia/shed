# Tranche 8G evidence

See [ACTIVE_PANE_EMPHASIS.md](../../ACTIVE_PANE_EMPHASIS.md) for conclusions, qualifications and commands. Read compressed files with `gzip -dc FILE`. No large heap dump or application JAR is committed.

## Focus and appearance

- `close-before.log.gz`: two deterministic closure-state failures before the production correction.
- `close-after.log.gz`: the same two tests pass with the correction.
- `focused-final.log.gz`: all six new tests pass.
- `focused-regressions.log.gz`, `focused-results.json.gz`: 41 focused appearance/layout tests pass.
- `matrix-isolated-report.json.gz`: six packaged-editor processes, exact artifacts/probes/commands, host conditions, all raw focus/typing/paint/allocation/heap/RSS results. No Maven desktop suite ran concurrently with this final matrix.
- `matrix-isolated.log.gz`: all six processes complete.
- `summary.json.gz`: counts and p50/p95/p99/max for individual measured quantities. Add dispatch, validation and paint **per sample before taking a percentile** to derive total EDT percentiles; do not sum separate p95 values.
- `visual/`: unchanged entry and corrected JARs, representative light two-pane and dark three-pane workbench captures. The cue uses the existing theme role. These are component captures, not full desktop/screens-reader qualification.

## Preserved failures and diagnostic boundaries

- `focused-initial.log.gz`: initial new tests exposed lifecycle listener accumulation and a synthetic Vim input assumption.
- `focused-corrected.log.gz`: follow-up harness assumptions about key characters/dirty-state semantics.
- `focused-corrected2.log.gz`: an Undo-after-split-cycling exception discovered by the new test. Final tests preserve existing history availability and isolate activation listener stability; they do not falsely declare the separately failing Undo/disposal scenarios corrected.
- `ActivePaneUndoDiagnostic.java.gz`: exact standalone source reproduction using temporary `body\n` files, twelve alternating splits, eight focus cycles per split, Redo then Undo.
- `source-diagnostic-v2-{baseline,candidate}.log.gz`: both JARs reproduce the same `IndexOutOfBoundsException` during Undo, following successful Redo. This is an observed **pre-existing failure**, not a passing test. Both report document/snapshot lengths five afterward; full post-exception persistence/history safety is not established.
- `source-diagnostic-baseline.log.gz`: first diagnostic had an entry-only null-path readiness bug before its cleanup block. The owned shell/child were terminated; the corrected pair was run with explicit 45-second subprocess timeouts. It is not product evidence.
- `matrix-report.json.gz`, `matrix.log.gz`, `native-focus-timeout.log.gz`: initial desktop probe, including a native focus timeout while regression tests overlapped. Not used as final paired performance evidence; no definitive cause for the timeout is claimed.
- `full-tests.log.gz`, `full-first-results.json.gz`: first complete suite, 1,612 tests total / one cleanup error / five skips. DirectoryNotEmptyException during temporary-directory teardown; no assertion failure. Cause unverified.

The lifecycle records in the final raw matrix show all twelve observed closed text areas retained with an open source document and document listeners 18 → 78 in both artifacts. Post-disposal editor weak references also remained reachable in both artifacts at the bounded observation. No complete resource-release or native-memory claim is made.

## Final regressions and preservation

- `full-final.log.gz`, `full-final-results.json.gz`: separate full Maven run, 1,607 passed / five existing skips / zero failures/errors.
- `tooling.log.gz`: all 46 tooling tests pass, including two new fast checks.
- `packaged-smoke.json.gz`, `packaged-smoke.log.gz`: image/math/Mermaid packaged runtime smoke PASS with exact artifact identity.
- `baseline.json.gz`: entry HEAD/status and SHA-256 of 1,629 existing files. Entry was clean.
- `repository-verification.json`: only two existing files changed, no starting file missing, 1,627 unchanged; exact measured probe and 1,426 compiled-class matches; all six source-integrity observations and 18 native focus observations checked.
- `production-and-test.diff.gz`: exact one-line production correction and new test coverage.
- `inner-{baseline,candidate}.txt.gz`: `javap -c -p` outputs for the anonymous PaneBufferController class. They match; its changed class bytes are source line metadata.
- `checksums.sha256`: hashes of all evidence files except itself.

The opt-in runner is `scripts/benchmark-active-pane.py`; the Java probe and two companion existing probe sources are compiled against the measured JAR. The source diagnostic can be compiled with the same companions and its archived source, then invoked with a temporary file path under a disposable user home. Java 21, a real desktop and bounded child-process timeouts are required. Do not run the Robot/desktop probe alongside other UI regression harnesses.

No commits or pushes were created. Previous evidence, README, saved preferences, theme/font defaults and source ownership were preserved. No 8H or source/lifecycle remediation was implemented here.
