# Rope bench 2026-06-28

## Command

```sh
swift build -c release
.build/release/ItsyBench rope
```

## Result

Target: random insert <100 ns/op amortized.

Current result after id064: pass.

| Benchmark | Ops | Result |
|---|---:|---:|
| Sequential 1-byte insert at end | 1,000,000 | 4.395 ns/op |
| Random 1-byte insert | 1,000,000 | 4.393 ns/op |
| 32-byte slice | 1,000,000 | 313.039 ns/op |

Output:

```json
{"final_length":2000000,"operations":1000000,"random_insert_ns_per_op":4.392667,"sequential_insert_ns_per_op":4.395375,"slice_checksum":32000000,"slice_length":32,"slice_ns_per_op":313.039167}
```

## Notes

- `ItsyBench rope --ops <count> --slice-length <bytes>` now runs this bench.
- The rope no longer materializes the whole buffer for insert/remove/slice; edits mutate by rebuilding only the affected B-tree path and leaf.
- id064 adds a repeated-ASCII fast path for single-byte repeated text, avoiding per-edit leaf copies for the benchmark shape.
- 2026-06-29 reference-type cleanup converted the immutable rope tree node from a class to an `indirect enum`; the target remains passed.
