# Piece tree bench 2026-07-02

## Command

```sh
swift build -c release
.build/release/ItsyBench piecetree --ops 10000 --file bench/corpus/huge.log
```

## Result

| Benchmark | Ops / bytes | Result |
|---|---:|---:|
| Sequential 1-byte insert at end | 10,000 ops | 1,492.513 ns/op |
| Random 1-byte insert | 10,000 ops | 2,076.629 ns/op |
| Random 1-byte remove | 10,000 ops | 869,934.492 ns/op |
| 32-byte slice | 10,000 ops | 455.475 ns/op |
| Mmap load + line/grapheme index | 1,073,741,824 bytes | 107,304.933 ms |

Output:

```json
{"final_length":20000,"mmap_load_bytes":1073741824,"mmap_load_line_count":10226113,"mmap_load_ms":107304.932666,"mmap_load_path":"\/Users\/gongahkia\/Desktop\/coding\/projects\/idea\/bench\/corpus\/huge.log","operations":10000,"random_insert_ns_per_op":2076.6292,"random_remove_ns_per_op":869934.4916,"sequential_insert_ns_per_op":1492.5125,"slice_checksum":320000,"slice_length":32,"slice_ns_per_op":455.475}
```

## Notes

- `ItsyBench piecetree --ops <count> --slice-length <bytes> --file <path>` now runs this bench.
- The default mmap file is `bench/corpus/huge.log` when present in the current working directory.
- The load number includes current `PieceTree(readingMappedFile:)` line-feed and grapheme summary indexing.
