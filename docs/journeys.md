# Journey diagnostics

Journey evidence connects a study outcome to the people, routes, resources,
and recorded moments behind it. Every newly simulated study and benchmark case
gets `journeys.json`, `journeys.csv`, and `journeys.md`. The main report links
to those files and includes distance, moving-time, route-wait, and low-progress
comparisons across the same available seed pairs.

[Agent experiments](agent-experiments.md) retain this same journey evidence
for controlled alternatives and expose paginated people, queue, decision,
low-progress, and exact-step trace queries to calling agents.

The analysis uses the original run. It does not change ordinary movement or
regenerate WFC geometry. Journey analysis has its own version, options, source
bundle hash, and checksum. Earlier study and benchmark reports remain readable
with their original hashes and omitted journey fields.

## Inspect an existing run

Keep every integration frame when recording a run:

```console
cargo run --release --locked -p chiyoda -- run examples/knowledge/unseen-closure.json --trace-every 1 -o out/journey-source
cargo run --release --locked -p chiyoda -- diagnose run out/journey-source/run.json -o out/journey-evidence --isolate-all --replay
cargo run --release --locked -p chiyoda -- diagnose verify out/journey-evidence/journeys.json --replay
```

Each destination must be new. `diagnose run` copies the unchanged source bundle
into its output directory so the report and replay commands remain portable.
Sparse traces are rejected: sampled chords can miss walked distance and
sampled states cannot reconstruct every queue or dwell interval. Rerun the
scenario with `--trace-every 1` to obtain that evidence. Distinct integration
boundaries must also retain distinct recorded timestamps; a cadence finer than
the bundle clock can resolve is rejected rather than treated as zero time.

Without `--replay`, verification checks bundle hashes, complete trace shape,
time/resource accounting, analysis checksums, and a fresh derivation of the
analysis from saved evidence. `--replay` additionally reconstructs the source
and every saved isolated run with the installed runtime. Existing simulation
checks and replay requirements in studies and benchmarks still apply.

## Read a person's journey

The per-person CSV retains the authored release eligibility, actual admission,
evacuation time, elapsed admission-to-evacuation time, distance, time budget,
static reference, and local-motion counters. An unfinished or unadmitted
person has no completed elapsed journey. Empty CSV values and JSON `null`
remain unavailable rather than becoming zero.

Walked distance sums every same-surface recorded segment outside connector
transit, including the final evacuation segment and movement in an admission
interval. Connector-interior distance is separate. Intervals entering or
leaving transit are retained as transition distance because snapshots alone
cannot divide them exactly between walking, portal placement, and connector
travel.

The exclusive time budget uses the state at the **preceding integration
boundary**, matching the runtime's queue accounting. Moving, departure wait,
waypoint pause, no-route wait, each service wait mechanism, transit, and time
after evacuation sum to the complete observed run duration. A state event or
admission can occur before movement in the next recorded interval. This is
discrete state exposure, not an interpolated physical stopwatch. The separate
elapsed journey measurement uses admission and evacuation event timestamps.

Moving exposure can include stationary motion and queue-slot approaches.
Low-progress windows identify little traveled distance while the trace says
`moving`. Their merged spans are an overlay on the time budget, not another
component to add. Spatial cells allocate those spans over the recorded linear
intervals and retain the affected people and replay steps. A hotspot does not
establish deadlock, physical contact, or a particular cause.

Resource wait, peak counts, and queue-entry exposure come from the existing
per-resource runtime audits. People listed for a resource have a recorded
queue-entry event. Their individual wait at that exact resource is not guessed
from nearby positions; individual budgets identify the service mechanism.

## Compare with references

Each person has an obstacle-aware static nominal reference when a route can
be priced. It respects radius, height and connector restrictions, required
waypoints in order, authored dwell, gate approaches, and nominal connector
traversal. It assumes all resources open and every declared exit familiar,
and ignores release schedules, service queues, later operations, information,
and other people. It uses centre-to-centre routes; the simulator's disc-contact
service rule can admit an agent before reaching the exact centre. The reference
is neither a guaranteed lower bound nor a free-flow simulation of the closure
and communication schedule.

Optional isolated controls execute the same motion law for one original
generated person with every other person absent. They retain the original
generated ID and ordinal, spawn, release eligibility, seed, geometry, services,
operations, knowledge, and information. Groups are not rewritten or renumbered.
The complete original scenario remains embedded in the isolated bundle; its
`0.45-isolated.1` bundle contract and recorded `isolated_agent` and
execution-scope options determine which person is
materialized. `chiyoda replay` reconstructs that scope. Extracting its scenario
alone returns the original complete population.

Removing people also changes capacity demand and may change admission,
observation times, message eligibility, and later route choices. Crowded minus
isolated elapsed time describes that complete counterfactual. It is available
only when both people finish, and it can be negative. It must not be added to
service wait or static route differences as a causal decomposition.

## Request bounded controls

Standalone selection flags are `--isolated-agent ID` (repeatable),
`--isolate-low-progress`, and `--isolate-all`. Supply a JSON file with
`--options` to change windows, cells, selection, or budgets. Studies and
benchmark suites accept the same object as `journey_diagnostics`:

```json
{
  "low_progress_window_s": 5,
  "max_low_progress_travel_m": 0.1,
  "hotspot_cell_size_m": 2,
  "isolated_controls": {
    "agents": [],
    "low_progress_agents": true,
    "all_agents": false,
    "max_agents": 16,
    "max_simulated_agent_steps": 20000,
    "verify_replay": true
  }
}
```

Ordinary analysis runs automatically; controls run only when requested.
Selections form a union, ordered by original generated identifier. Defaults
permit at most 16 simulated controls and 20,000 simulated agent steps per case,
including a second execution when control replay is enabled. Configurable
limits are 1–64 controls and 1–200,000 simulated agent steps. Missing identities,
budget-limited selections, incomplete controls, and incompatible source
runtimes remain explicit results. They are not replaced with successful
controls or silently removed from the requested population. Selecting people
with low progress depends on the observed outcome, so different policy arms
can select different people. This is not an unbiased population sample. Use
explicit IDs for a planned matched sample, and read the selected identities
and completion availability before comparing control averages. Isolated controls
require the installed ordinary runtime contract and are not nested inside
another isolated execution.

Every completed control retains its reconstructible bundle in
`isolated-NNNN/run.json`. Case verification checks those artifacts and their
original source scenario and identity. The main simulation's completion and
movement checks remain separate from whether an optional control completed.

## Follow a decision

Per-person timelines retain information receipt and acceptance details,
resource observations, route recomputations and unavailable routes, waypoint
stages, connector use, service reservations, gate passage, and evacuation.
Event order and authored timestamps remain unchanged. Reported event positions
come from the first integration frame at or after the event, so they may
include movement after delivery in that interval.

Replay commands select the recorded surface and exact step:

```console
chiyoda view run.json --paused --at-step 381 --surface floor_0
```

The viewer rejects a step absent from the recording. Compare policy-arm
walking distances and waits, then inspect accepted-information and route events
for the affected people. Different patterns and scales change geometry;
matching root seeds does not make those layouts equivalent. Observed changes,
static planning references, and isolated counterfactuals provide evidence for
an explanation without claiming calibrated architectural safety or an
automatic causal ranking.

## Recorded example and regression evidence

The [focused study](../examples/studies/journey-diagnostics.json) retains the
same geometry, population, operations, and random draws as its frozen pair.
For `study_0_20:0`, the recorded results are:

| Policy | Crowded elapsed journey | Isolated elapsed journey | Crowded walked distance | Isolated walked distance |
| --- | ---: | ---: | ---: | ---: |
| No announcement | 110.2 s | 64.2 s | 82.883 m | 76.449 m |
| Official announcement | 40.4 s | 40.0 s | 47.923 m | 47.604 m |

The silent run's 25.2 s low-progress window is 76.2–101.4 s, at recorded steps
381–507, around a 2 m cell centred on `(41, 39)` on `floor_0`. The report
identifies the person, exposes the route and information events, and supplies
the exact replay command. The accepted announcement is recorded at 10 s; the
silent person first records the alternative route at 29.6 s. These are one
person's reference-runtime observations from one fixed seed.

The default example automatically selects the silent person because of the
low-progress window. The announced isolated row above uses an additional
explicit selection, even though that arm has no qualifying window:

```console
cargo run --release --locked -p chiyoda -- diagnose run out/journey-study/case-0001/run.json --isolated-agent study_0_20:0 -o out/journey-announced-control
```

The [recorded reference](../examples/studies/journey-diagnostics.reference.json)
retains case and analysis checksums, selected-person evidence, both isolated
controls, and regression results. The ten frozen benchmarks pass and compare
unchanged on every original comparison metric. All 32 architecture-study
simulation bundle hashes, layouts, controlled inputs, and runtime metrics
match the preceding motion reference exactly; journey evidence is additional.
All five strict stress cases and the 24-case awareness study also pass.

Verification includes 265 workspace tests, the focused trace-resolution tests,
formatting, Clippy with warnings denied, development and release builds, the
demo check/run/replay path, source and isolated replay through the release CLI,
and derivation of the saved diagnostic archives. Ordinary pre-analysis report
hashes remain verified. The expensive ten-case test remains opt-in; the full
frozen CLI benchmark and baseline comparison were run for this change.

Final local artifacts are in `out/journey-validation-20261020/`: `example-final`,
`announced-control`, `study-final`, `wayfinding-final`, `stress-final`, and
`local-awareness`. Earlier pilot folders are development evidence rather than
the final comparison. README.md is unchanged.
