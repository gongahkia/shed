# Rope bench 2026-06-28

## Command

```sh
swift build -c release
.build/release/PicoBench rope
```

## Result

Target: random insert <100 ns/op amortized.

Result: fail.

| Benchmark | Ops | Result |
|---|---:|---:|
| Sequential 1-byte insert at end | 1,000,000 | 11,372.288 ns/op |
| Random 1-byte insert | 1,000,000 | 331,601.466 ns/op |
| 32-byte slice | 1,000,000 | 165.039 ns/op |

Output:

```json
{"final_length":2000000,"operations":1000000,"random_insert_ns_per_op":331601.466042,"sequential_insert_ns_per_op":11372.288167,"slice_checksum":32000000,"slice_length":32,"slice_ns_per_op":165.038916}
```

## Notes

- `PicoBench rope --ops <count> --slice-length <bytes>` now runs this bench.
- The rope no longer materializes the whole buffer for insert/remove/slice; edits mutate by rebuilding only the affected B-tree path and leaf.
- [Inference] Random insert is still dominated by per-edit `String` leaf copy/summary recomputation and node allocation.
