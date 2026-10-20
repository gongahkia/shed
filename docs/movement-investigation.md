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

## Reference diagnosis

The [diagnostic observations](../examples/benchmarks/wayfinding-pairs.diagnosis.json)
inspect the original full-step traces and retain the single-agent probes used
to distinguish route length from interactions. Every one of the 71 unfinished
people completes within the same 120 s limit when run alone with its original
group ID, start, seed, geometry, closures, messages, and knowledge. Only the
other people are removed for those probes; the frozen suite remains unchanged.

| Pattern / seed / load | Unfinished with crowd | Finish alone | Latest isolated exit |
| --- | ---: | ---: | ---: |
| Concourse / 73 / busy | 27 | 27 | 93.2 s |
| Concourse / 74 / light | 2 | 2 | 109.0 s |
| Concourse / 74 / busy | 17 | 17 | 114.0 s |
| Corridors / 73 / light | 6 | 6 | 105.6 s |
| Corridors / 73 / busy | 19 | 19 | 107.2 s |

All unfinished agents are recorded as moving, and aggregate exit-queue wait is
only 0.2–0.4 agent-seconds in the five incomplete runs. Two people in the busy
concourse/74 run travel less than 0.1 m over the final 20 s; other unfinished
people continue travelling at different speeds. These observations distinguish
stationary stalls and delayed journeys without labeling every incomplete run
a deadlock. Interaction delays contribute to all five failures. Isolation alone
does not establish whether a delay is realistic congestion or an algorithmic
defect; movement fixes need smaller controlled reproductions as well.
