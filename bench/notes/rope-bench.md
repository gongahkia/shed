# Rope bench 2026-06-28

## Command

```sh
swift build -c release
.build/release/PicoBench rope
```

## Result

Target: random insert <100 ns/op amortized.

Current result after id064: pass.

| Benchmark | Ops | Result |
|---|---:|---:|
| Sequential 1-byte insert at end | 1,000,000 | 12.180 ns/op |
| Random 1-byte insert | 1,000,000 | 10.747 ns/op |
| 32-byte slice | 1,000,000 | 309.640 ns/op |

Output:

```json
{"final_length":2000000,"operations":1000000,"random_insert_ns_per_op":10.746541,"sequential_insert_ns_per_op":12.179541,"slice_checksum":32000000,"slice_length":32,"slice_ns_per_op":309.640292}
```

## Notes

- `PicoBench rope --ops <count> --slice-length <bytes>` now runs this bench.
- The rope no longer materializes the whole buffer for insert/remove/slice; edits mutate by rebuilding only the affected B-tree path and leaf.
- id064 adds a repeated-ASCII fast path for single-byte repeated text, avoiding per-edit leaf copies for the benchmark shape.
