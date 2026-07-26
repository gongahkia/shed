# Phase 21 Refactor Checkpoint

Date: 2026-07-02; refreshed 2026-07-07

Scope:

- moved root `Sources/ItsyApp/*.swift` feature files into existing feature directories.
- added first-line `// @file ...` purpose comments to moved files.
- updated `Sources/ItsyApp/README.md` directory ownership notes.

Verification:

- `find Sources/ItsyApp -maxdepth 1 -type f | sort` returns only `Sources/ItsyApp/README.md`.
- `swift build -c release && swift test && bench/scripts/regression.sh` passed on 2026-07-07.
- `swift test` passed with 438 Swift Testing tests.
- `bench/scripts/regression.sh` now excludes vendored grammar submodule fixtures from `swift_loc`.
- Tree-sitter highlight query compilation is serialized; concurrent `ts_query_new` calls previously crashed `swiftpm-testing-helper`.
- PieceTree rebuild/split/ASCII paths were tightened to keep the regression gate below its KPI limits.

Checkpoint command requested by issue #2:

```sh
swift build -c release && swift test && bench/scripts/regression.sh
```

2026-07-07 regression sample:

| Metric | Baseline | Current | Limit | Status |
|---|---:|---:|---:|---|
| cold_start_ready_ms | 8.000 ms | 6.298 ms | 8.400 ms | pass |
| piecetree_random_insert_ns_per_op | 1000 ns/op | 657.017 ns/op | 1050 ns/op | pass |
| piecetree_random_remove_ns_per_op | 350000 ns/op | 97782 ns/op | 367500 ns/op | pass |
| piecetree_sequential_insert_ns_per_op | 800.000 ns/op | 568.204 ns/op | 840.000 ns/op | pass |
| piecetree_slice_ns_per_op | 200.000 ns/op | 53.138 ns/op | 210.000 ns/op | pass |
| binary_size_kb | 11573 KB | 8778 KB | 12152 KB | pass |
| swift_loc | 32730 LOC | 33335 LOC | 35000 LOC | pass |
| lsp_didopen_to_diagnostics_ms | 5000 ms | 2018 ms | 5000 ms | pass |

Conclusion:

- #2 implementation work for file placement and file-purpose headers is done.
- #2 acceptance is met on the refreshed current tree.
