# Text stack Phase 22 checkpoint 2026-07-02

## Commands

```sh
swift test
.build/release/ItsyBench open --app .build/release/ItsyApp --file bench/corpus/huge-text.log --timeout-ms 60000
bench/scripts/regression.sh
```

## Results

| Check | Result |
|---|---:|
| `swift test` | pass, 395 tests |
| 1 GB first page visible | 64.657 ms |
| 1 GB process start to first draw | 790.963 ms |
| 1 GB first page visible to first draw | 726.306 ms |
| 1 GB open RSS | 1,133,216 KB |
| Regression | pass |

Open output:

```json
{"app":"ItsyApp","file":"\/Users\/gongahkia\/Desktop\/coding\/projects\/idea\/bench\/corpus\/huge-text.log","open_first_page_visible_to_first_draw_ms":726.306042,"open_process_start_to_first_draw_ms":790.963,"open_process_start_to_first_page_visible_ms":64.656958,"open_rss_kb":1133216,"stage_ms":{"delegate_init":468.692875,"first_draw":1222.52625,"first_page_visible":496.220208,"process_start":431.56325}}
```

Regression output:

| Metric | Baseline | Current | Limit | Status |
|---|---:|---:|---:|---|
| cold_start_ready_ms | 8.000 ms | 4.170 ms | 8.400 ms | pass |
| piecetree_random_insert_ns_per_op | 1000 ns/op | 640.883 ns/op | 1050 ns/op | pass |
| piecetree_random_remove_ns_per_op | 350000 ns/op | 315981 ns/op | 367500 ns/op | pass |
| piecetree_sequential_insert_ns_per_op | 800.000 ns/op | 560.167 ns/op | 840.000 ns/op | pass |
| piecetree_slice_ns_per_op | 200.000 ns/op | 157.712 ns/op | 210.000 ns/op | pass |
| binary_size_kb | 11573 KB | 7583 KB | 12152 KB | pass |
| swift_loc | 32730 LOC | 32730 LOC | 35000 LOC | pass |
| lsp_didopen_to_diagnostics_ms | 5000 ms | 1937 ms | 5000 ms | pass |

## Notes

- `bench/scripts/gen_corpus.sh` generated `bench/corpus/huge-text.log` locally before the open run.
- Added an ASCII/no-CR UAX29 fast path so 1 GB ASCII text does not walk the full grapheme state machine.
- `MetalTextView` render/open hot paths now read from `EditorTextStorage` instead of materializing `editor.rope`.
- Regression now gates Phase 22 `piecetree_*` metrics instead of the removed rope fast-path bench.
