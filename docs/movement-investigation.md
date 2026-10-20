# Frozen wayfinding pairs

The [fixed suite](../examples/benchmarks/wayfinding-pairs.json) contains ten
archived scenarios: five incomplete local-awareness runs and their five
completed announcement counterparts. Each pair has identical geometry,
population, release times, seed, physical closures, and knowledge assumptions.
The announcement is the only changed scenario input. WFC is not run when this
suite executes.

The [provenance record](../examples/benchmarks/wayfinding-pairs.origin.json)
retains the source revision, runtime version, verified study report hash,
original case indices, input and bundle hashes, controlled-input hashes, and
original metrics. The [study configuration](../examples/benchmarks/wayfinding-study.json)
retains the complete 32-case experiment for a later comparison. A conformance
test checks the frozen scenario hashes and paired inputs.

```console
cargo build --release --locked -p chiyoda
target/release/chiyoda benchmark run --suite examples/benchmarks/wayfinding-pairs.json --label candidate -o out/wayfinding-pairs
target/release/chiyoda study run --config examples/benchmarks/wayfinding-study.json -o out/wayfinding-study
```

The original 0.44 run passes five cases and fails five completion checks.
All ten reconstruct. The original `min_evacuated_fraction: 1.0`, 120 s
duration, and replay checks remain unchanged. Incomplete runs retain a missing
clearance time. Their observed last exit is not a substitute for clearance.
A failing reference is retained as evidence and cannot be accepted as a
passing benchmark baseline.
