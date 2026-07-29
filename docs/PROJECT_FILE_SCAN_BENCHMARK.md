# Project File Scan Benchmark

`ProjectFileScanBenchmark` runs the prior recursive scan and the current deterministic scanner against the same local root and cap. Its report includes both durations, file counts, optimized directory count, and `equivalentFileSet` after sorting results.

Benchmark timing is local observed data. `equivalentFileSet=true` is the semantic gate: it proves the current root and cap produce the same visible path set before comparing performance.
