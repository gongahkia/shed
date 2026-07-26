# Undo phase 23

## Command

```sh
swift build -c release
.build/release/ItsyBench undo --ops 100000
```

## Result

| Metric | Result |
|---|---:|
| Buffer | 10,485,760 bytes |
| Operations | 100,000 |
| Retained undo entries | 100,000 |
| Baseline RSS | 18,880 KB |
| Sampled peak RSS | 103,056 KB |
| Max sampled RSS delta | 84,176 KB |
| RSS budget | 102,400 KB |
| Record | 453,375.710 ns/op |
| Undo | 1,914.140 ns/op |
| Redo | 1,916.648 ns/op |

Output:

```json
{"after_record_rss_delta_kb":38112,"after_record_rss_kb":56992,"after_redo_rss_delta_kb":84176,"after_redo_rss_kb":103056,"after_undo_rss_delta_kb":79488,"after_undo_rss_kb":98368,"baseline_rss_kb":18880,"buffer_bytes":10485760,"final_checksum":10491972,"final_length":10485760,"final_undo_entries":100000,"max_rss_delta_kb":84176,"operations":100000,"record_ns_per_op":453375.71041,"redo_ns_per_op":1916.6475,"retained_undo_entries":100000,"rss_budget_kb":102400,"rss_budget_passed":true,"sampled_peak_rss_kb":103056,"undo_ns_per_op":1914.14042}
```

## Notes

- `ItsyBench undo --ops <count>` defaults to a 10 MiB piece-tree buffer and a 100 MiB RSS-delta budget.
- The workload retains all undo entries by setting `UndoStack(maxEditCount: ops, maxTotalRemovedBytes: Int.max)`.
- [Inference] Staying below 100 MiB while retaining 100,000 edits is consistent with reverse-edit storage rather than full-buffer undo snapshots.
