# Workspace Index Benchmark

`WorkspaceIndexBenchmark` measures one local, fresh persistent-index build against the current workspace root. Its stable key/value report compares input files and bytes with indexed output files and cache bytes, and records elapsed nanoseconds plus observed heap before/after/delta bytes.

The report contains no source content and no network measurements. Run the same workspace and settings again to compare current local indexing inputs and output; duration and heap values are observed resource costs, not portability guarantees.

In Shed, use `:perf benchmark` (or `:workspace index benchmark`) to start the same explicit cancellable local measurement. `:perf` shows whether a file-backed buffer or tree root is currently available and lists the benchmark limits; use `:jobs` and `:jobcancel <id>` to inspect or cancel it.
