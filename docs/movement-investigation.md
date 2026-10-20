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

## Motion contract 0.45

Mobile ORCA pairs now share the correction on both sides of the velocity
obstacle. Immobile people retain a zero speed bound and moving neighbours bear
the correction. Static half-planes participate in velocity selection before
any final clipping, so a wall does not erase an independently promised lateral
avoidance step. This follows the reciprocal-responsibility and hard-obstacle
separation in the [RVO2 reference implementation](https://github.com/snape/RVO2/blob/main/src/Agent.cc);
Chiyoda's rectangular, per-step static domains remain its own reference model.

Every proposed on-surface segment is resolved before positions, service, or
exit state change. A deterministic analytic swept-disc guard shortens
conflicting segments, retaining safe escape motion where possible. Connector
landings must clear complete surface intervals. Stationary queue slots clear
stored momentum, and FIFO right of way no longer removes immediate physical
conflicts. A small right-hand cruising preference breaks exactly opposing
symmetries. Visibility corners remain steering guides rather than stopping
points. If the 2.5 s program is infeasible, the solver retries the actual step,
retaining static bounds and stable neighbour order. The guard still resolves
the resulting segments. These choices and their telemetry are specified in
[executable semantics](semantics.md).

Small wall-counterflow and shared-corner fixtures exercise completion, speed
bounds through existing trajectory checks, boundary and swept clearance, and
guard-event reconstruction. The original standard and strict stress benchmark
checks remain in place. Historical pre-JSON execution hashes remain archived;
a separate versioned reference records the intentional 0.45 execution change.
An authentic 0.44 knowledge bundle retains its original hash and explicit
legacy inspection status.

The full ten-case simulation and reconstruction test is opt-in because it
reruns the large obstacle layouts twice per case:

```console
cargo test --release --locked -p chiyoda-core --test wayfinding_pairs -- --include-ignored
```

The default test checks the immutable scenario hashes and paired controls.
The full test additionally requires every case to finish, reconstruct, and
retain zero overlap in both independent disc audits. It leaves the frozen
suite's original assertions unchanged.

## Fixed-input rerun

Under 0.45, all ten cases pass the original 120 s full-completion and replay
checks. All ten report zero maximum boundary and swept reference-disc overlap.
The five previously incomplete silent cases now finish alongside their matched
announcement controls:

| Pattern / seed / load | Original silent completion | 0.45 silent clearance | 0.45 announced clearance |
| --- | ---: | ---: | ---: |
| Concourse / 73 / busy | 20/47 | 110.6 s | 88.6 s |
| Concourse / 74 / light | 22/24 | 107.4 s | 78.0 s |
| Concourse / 74 / busy | 30/47 | 116.8 s | 96.4 s |
| Corridors / 73 / light | 18/24 | 115.0 s | 106.2 s |
| Corridors / 73 / busy | 28/47 | 117.6 s | 111.6 s |

Announcements reduce modeled full-clearance time by 6.0–29.4 s in these five
selected pairs. The original partial runs have no full-clearance time and are
not assigned one for a before/after speed comparison. Selection deliberately
focused on previous failures; it is not an unbiased estimate of policy effect.

Solver fallbacks remain visible: 68–812 agent-steps in the five silent cases.
The concourse/73 busy case retains one merged low-progress alert for
`study_0_20:0`, from 76.2 to 101.4 s. It waits near `(40.25, 39.70)` while a
sequence of other people passes through the adjacent corner, then continues
and exits. This is temporary modeled crowd delay; the alert does not establish
a permanent deadlock or validate the delay as realistic. A passing completion
check is not a claim that every movement assumption is settled.

## Architecture and communication rerun

The complete saved 32-case configuration now passes 32/32, compared with 27/32
in 0.44. All 32 retain identical scenario hashes, layout hashes, and controlled
physical/population/operations context. All reconstruct and report zero maximum
overlap in both disc audits. The ten benchmark bundles also match their study
counterparts exactly, so the two execution paths agree on more than completion.

The 32-case report retains all policy, scale, load, and pattern comparisons.
For the large cases, the official announcement's mean clearance change across
the two seeds is −15.8 s for light concourses, −21.2 s for busy concourses,
−4.5 s for light corridors, and −3.9 s for busy corridors. These are paired
within-layout simulator results with two samples per cell. Different patterns
have different generated layouts and route lengths; these numbers alone do
not rank architectural safety or establish a scaling law.

The separate 24-case awareness and 24-case rumor/correction studies also pass
with reconstruction and zero overlap in both audits. All ten standard and all
five strict stress benchmarks pass their original checks. The
[recorded result summary](../examples/benchmarks/wayfinding-pairs.motion-0.45.json)
retains the report checksums, case input/bundle hashes, movement telemetry,
remaining walking alert, and policy sample counts.

Full local artifacts from this investigation are in
`out/movement-validation-20261020/`: `reference-044`, `isolated-044`,
`wayfinding-final`, `study-final`, `local-awareness-final`,
`communication-final`, `standard-final`, and `stress-final`. Intermediate
pilot directories are development evidence, not the final comparison. Rebuild
and use the commands above to recreate the final fixed-input and study reports.
The failed 0.44 reference remains an observation; it has not been promoted to
a passing performance baseline.

## Verification

`CARGO_PROFILE_TEST_OPT_LEVEL=2 make verify` passes formatting, Clippy with
warnings denied, all 244 workspace tests, the demo check/run/replay smoke path,
and the development workspace build. The separate full frozen regression run
passes both tests, including all ten simulations and their reconstructions;
that expensive test remains opt-in during ordinary workspace testing.
`cargo build --release --workspace --locked` also passes, and the release CLI
reconstructs the dense first frozen bundle exactly. Current study archives and
the original failed 0.44 study archive retain verified report checksums.
README.md is unchanged by this investigation.
