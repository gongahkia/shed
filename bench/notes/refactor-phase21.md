# Phase 21 Refactor Checkpoint

Date: 2026-07-02

Scope:

- moved root `Sources/ItsyApp/*.swift` feature files into existing feature directories.
- added first-line `// @file ...` purpose comments to moved files.
- updated `Sources/ItsyApp/README.md` directory ownership notes.

Verification:

- `find Sources/ItsyApp -maxdepth 1 -type f | sort` returns only `Sources/ItsyApp/README.md`.
- `swift test` passed with 414 tests on the current tree after the debug-only Git process reader guard.
- The exact checkpoint chain did not pass reliably under current machine load.

Checkpoint command requested by issue #2:

```sh
swift build -c release && swift test && bench/scripts/regression.sh
```

Observed failures:

- initial sandboxed run failed before compile because SwiftPM/Clang could not write `~/.cache/clang`.
- unsandboxed run exposed a Swift Testing deadlock in concurrent Git process tests; sampled stack was blocked in `ProcessGitCommandRunner.runGitProcess`.
- after the debug-only reader guard, `swift test` passed once with 414 tests.
- a later exact rerun rebuilt release but `swift test` recorded a load-sensitive perf failure: `lineFeedIndexerIndexesHundredKLinesUnderBudget` measured `96.141209 ms` against a `50 ms` expectation.

Current-tree regression sample:

| Metric | Baseline | Current | Limit | Status |
|---|---:|---:|---:|---|
| cold_start_ready_ms | 8.000 ms | 8.444 ms | 8.400 ms | fail |
| piecetree_random_insert_ns_per_op | 1000 ns/op | 1130 ns/op | 1050 ns/op | fail |
| piecetree_random_remove_ns_per_op | 350000 ns/op | 666291 ns/op | 367500 ns/op | fail |
| piecetree_sequential_insert_ns_per_op | 800.000 ns/op | 982.746 ns/op | 840.000 ns/op | fail |
| piecetree_slice_ns_per_op | 200.000 ns/op | 267.812 ns/op | 210.000 ns/op | fail |
| binary_size_kb | 11573 KB | 8448 KB | 12152 KB | pass |
| swift_loc | 32730 LOC | 34208 LOC | 35000 LOC | pass |
| lsp_didopen_to_diagnostics_ms | 5000 ms | 2299 ms | 5000 ms | pass |

Clean-HEAD regression comparison from the same worktree:

| Metric | Baseline | Clean HEAD | Limit | Status |
|---|---:|---:|---:|---|
| cold_start_ready_ms | 8.000 ms | 7.784 ms | 8.400 ms | pass |
| piecetree_random_insert_ns_per_op | 1000 ns/op | 915.012 ns/op | 1050 ns/op | pass |
| piecetree_random_remove_ns_per_op | 350000 ns/op | 462177 ns/op | 367500 ns/op | fail |
| piecetree_sequential_insert_ns_per_op | 800.000 ns/op | 794.217 ns/op | 840.000 ns/op | pass |
| piecetree_slice_ns_per_op | 200.000 ns/op | 225.596 ns/op | 210.000 ns/op | fail |
| binary_size_kb | 11573 KB | 8448 KB | 12152 KB | pass |
| swift_loc | 32730 LOC | 34182 LOC | 35000 LOC | pass |
| lsp_didopen_to_diagnostics_ms | 5000 ms | 2239 ms | 5000 ms | pass |

Conclusion:

- #2 implementation work for file placement and file-purpose headers is done.
- #2 acceptance is not met because the regression gate fails on both current tree and clean `HEAD`; do not close #2 until the KPI baseline/gate is repaired or rerun in a stable benchmark environment.
