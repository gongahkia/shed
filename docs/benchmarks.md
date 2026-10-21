# Fixed benchmarks and baseline comparisons

Benchmarks hold scenario source and assertions fixed while the runtime changes.
WFC evaluation explores layouts; a benchmark reruns archived versioned JSON scenarios.
Both use independent geometric preflight, full-step trajectory checks, and replay
reconstruction by default. The benchmarks are reference-runtime regression
contracts, not empirical calibration against measured pedestrian data.

## Run and compare

From the repository root:

```console
cargo build --release --locked -p chiyoda
target/release/chiyoda benchmark profiles
target/release/chiyoda benchmark run --profile all --label reference -o out/benchmark-baseline
```

Keep the baseline directory. After changing or checking out another runtime
revision, rebuild and run:

```console
target/release/chiyoda benchmark run --baseline out/benchmark-baseline/report.json --label candidate -o out/benchmark-candidate
```

With only `--baseline`, the command reuses the baseline's embedded suite. It does
not reload an embedded profile or regenerate WFC layouts. Runtime version and
optional caller-supplied revision labels appear in both reports. Use a commit
hash as a label when helpful; labels are not verified Git provenance.

Every output directory must be new. A failed run retains all available case
artifacts and returns nonzero after processing all cases. Regressions,
incompatible comparisons, and failed absolute checks all produce nonzero exit
status, making the same command suitable for CI. A baseline must pass every
absolute check. Existing output directories are never overwritten.

An offline comparison reads reports without running simulations:

```console
target/release/chiyoda benchmark compare --baseline out/benchmark-baseline/report.json --current out/benchmark-candidate/report.json -o out/benchmark-comparison
```

## Profiles and behavior contracts

| Profile | Cases |
| --- | --- |
| `smoke` | Free walking, obstacle detour, gate queue, connector closure |
| `navigation` | Free walking, obstacle detour, paced bottleneck |
| `crowds` | Paced bottleneck, counterflow, staggered crossing, serpentine queue |
| `operations` | Gate, exit, lift, connector closure, serpentine queue |
| `all` | All ten standard cases, without duplicates |
| `stress` | Dense bottleneck, symmetric crossing, strict gate/exit/lift clearance |

The default profile is `smoke`. Sources are embedded in the executable, so
running from another directory cannot substitute different fixtures. Each
standard case requires full evacuation and an explicit clearance-time bound.
The queue cases require actual queue exposure and nonzero recorded wait; a
scenario that bypasses the intended queue cannot pass just by finishing early.
The connector-closure case requires route-recomputation events, and the
serpentine case requires recorded service reservations.

The standard bottleneck uses paced arrivals, and the crossing uses staggered
streams. The stress profile retains simultaneous arrivals and stricter overlap
checks to exercise crowded movement:

```console
target/release/chiyoda benchmark run --profile stress -o out/benchmark-stress
```

At introduction, the dense bottleneck produced disc overlaps, the symmetric
crossing failed to clear, and the gate/exit/lift queue cases produced overlaps.
Under motion contract 0.45 all five pass their original checks, including their
zero-overlap limits. Their scenario sources and thresholds are unchanged.
The standard versions of those three queue cases still check operations
without an absolute overlap cap; their measured overlaps remain in reports
and baseline comparisons. Other standard cases retain zero-overlap limits.
A failed stress run cannot be accepted as a passing baseline.

The ten [frozen wayfinding cases](movement-investigation.md) retain five
incomplete 0.44 architecture-study runs and their matched announcement controls.
Their original inputs and completion criteria remain fixed while the movement
contract changes. The investigation records provenance, isolated-agent controls,
and the subsequent architecture and communication comparison.

## Read the evidence

Each run writes:

```text
suite.json
progress.json
report.json
report.md
case-0000/
  scenario.json
  report.json
  run.json
...
comparison.json  # when --baseline is provided
comparison.md
```

`suite.json` contains structured scenario documents, assertions, replay settings,
and diagnostic options. `progress.json` records completed results incrementally. The final
report requires every requested case in suite order. A well-formed case that cannot be validated or simulated retains its
scenario and failure report; it has no run bundle. Malformed JSON, unsupported
schema versions, and wrong field types are rejected while loading the suite,
before creating its output directory. An expected-unreachable case also has no simulated metrics or
bundle. Neither receives a replay command.

`report.md` shows completion, clearance, wait, overlap, assertion failures, and
walking alerts. It links to individual sources and case reports and supplies
`chiyoda view ... --paused` commands for available bundles. Run those commands
from the report directory. Use the viewer's step controls to inspect the listed
time intervals and integration steps; see [replay controls](replay.md).

Walking alerts measure path length over a rolling window while an agent is
recorded as moving on one surface. Defaults are at most 0.1 m traveled over
5 s. Queue, waypoint dwell, unreleased, and transit states reset the window.
Adjacent qualifying windows merge into one observation with the minimum
observed window travel. These observations locate low movement; they do not
assert deadlock or measure remaining distance to the agent's goal.

`comparison.md` puts regressed and incompatible cases first. It lists metric
values, signed current-minus-baseline deltas, allowed deterioration, assertion
failures, evidence links, and current walking alerts. Offline comparisons use
absolute paths to the supplied report directories; move the artifacts together
and rerun the comparison if those paths change. Comparison itself requires
only report JSON, so a saved metric report can be compared without its bundle.

New simulated cases also save `journeys.json`, `journeys.csv`, and
`journeys.md`. The case section links to per-person time and distance,
low-progress locations, information/route timelines, and exact recorded replay
steps. Optional `journey_diagnostics` settings request bounded isolated controls
and retain their separate bundles. See [journey evidence](journeys.md).
Explicit analysis options participate in the fixed-input hash. Omitted options
preserve existing suite/input hashes, and old reports keep their checksums.

## Comparison rules and tolerances

Reports include versioned schemas, suite/input hashes, and a report checksum.
Reading a baseline or comparing reports verifies these and the case summaries.
Cases match by identifier. Changed scenario content, expected reachability, absolute
thresholds, behavior checks, replay settings, or diagnostic options make that
case incompatible. Added and removed cases are also incompatible. Runtime
versions, suite names, descriptions, and run labels can differ. JSON whitespace
and object-key order do not affect input hashes. Array order remains part of
the scenario contract.

The six comparison metrics are:

| Policy key | Preferred direction | Units |
| --- | --- | --- |
| `evacuated_fraction` | Higher | Fraction of total agents |
| `clearance_time_s` | Lower | Seconds until full evacuation |
| `queue_wait_agent_seconds` | Lower | Sum of wait across capacity mechanisms |
| `avoidance_fallback_steps` | Lower | Recorded agent-step fallback count |
| `disc_overlap_m` | Lower | Maximum reference-disc overlap |
| `swept_disc_overlap_m` | Lower | Maximum swept reference-disc overlap |

The default policy permits zero deterioration on every metric. Optional policy
JSON sets a default and/or per-metric replacement tolerances:

```console
target/release/chiyoda benchmark run --baseline out/benchmark-baseline/report.json --policy examples/benchmarks/comparison-policy.json -o out/benchmark-tolerant
```

Allowed deterioration is `absolute + relative * abs(baseline)`. Relative values
are fractions: `0.05` means 5%. Per-metric entries replace the default entry;
omitted fields within an entry are zero. The example permits 0.2 s plus 2% on
clearance time and 0.5 agent-seconds plus 5% on queue wait, keeping other metrics
strict. These example allowances are policy choices, not calibration results.
The complete policy and its hash are stored in each comparison.

Tolerances apply only to relative comparisons. They cannot waive absolute case
failures or make changed inputs comparable. Invalid, negative, nonfinite, and
unknown policy fields are rejected. Integer counts retain their precision when
calculating deltas. Small floating-point roundoff at a nonzero tolerance
boundary is allowed; the zero-tolerance default compares exactly.

Missing values stay unavailable. Partial evacuation has no clearance time;
`last_exit_time_s` is never substituted for it. Losing a previously available
clearance time is a regression. If neither run clears, the clearance metric
remains unavailable and completion/absolute assertions still apply. Missing
queue or movement telemetry makes the case incompatible. No percentage change
is invented when the baseline is zero.

## Freeze WFC evaluation cases

First generate and evaluate a suite using [WFC evaluation](generation.md). Then
freeze the archived inputs:

```console
target/release/chiyoda benchmark freeze --evaluation out/evaluation --name wfc-regressions -o out/wfc-regressions.json
target/release/chiyoda benchmark run --suite out/wfc-regressions.json --label reference -o out/wfc-baseline
target/release/chiyoda benchmark run --baseline out/wfc-baseline/report.json --label candidate -o out/wfc-candidate
```

Freezing reads every numbered `scenario.json` and checks its canonical scenario
hash against the archived evaluation report. It verifies the summary against
its case list and config, embeds the parsed scenario documents, and records the original
evaluation/runtime versions and report hash in `origin`. Changed or missing
sources are rejected before writing the destination. A generation failure
cannot be frozen because it produced no scenario; it is never silently dropped.
Cases that produced scenarios but failed runtime assertions can be frozen for
investigation, though a failing benchmark run cannot serve as a baseline.

The original expected reachability, thresholds, and replay option are preserved.
If the evaluation had no completion limits, neither does the frozen suite. Add
explicit thresholds before creating the first baseline, either by editing its
JSON or replacing reachable-case thresholds during freezing:

```console
target/release/chiyoda benchmark freeze --evaluation out/evaluation --name wfc-completion --thresholds examples/benchmarks/completion-thresholds.json -o out/wfc-completion.json
```

That example requires full evacuation within 90 s; choose bounds appropriate to
the authored duration and intended behavior. Generator rule changes after
freezing do not affect the benchmark: reruns consume embedded scenario documents directly.

## Author or edit a suite

```console
target/release/chiyoda benchmark export --profile operations -o out/operations-suite.json
target/release/chiyoda benchmark run --suite examples/benchmarks/custom-suite.json -o out/custom-benchmark
```

Both export and freeze require a new destination file. A suite has
`benchmark_version: "2"`, `name`, optional `origin`, optional `diagnostics`, and
1..256 `cases`. Each case needs a unique nonempty `id`, `description`, and a
`scenario` object containing `schema_version: "1"` and its `scenario` data. This
is the same document accepted by `chiyoda check`, embedded as an object rather
than an escaped JSON string. `expected` defaults to `"reachable"`; `evaluation`
defaults to replay enabled with no metric thresholds. Use `"unreachable"` for valid sources that
must fail geometric preflight, as in ordinary evaluations.

Optional behavior checks use mechanism names `lift`, `connector`, `gate`, and
`exit`:

```json
{
  "min_queued_agents": {"gate": 2},
  "min_queue_wait_agent_seconds": {"gate": 1.0},
  "min_events": {"queue_service_reserved": 4}
}
```

Place this object under a case's `checks` field. Unreachable fixtures cannot
require simulated queue or event checks. Compact serialized scenario documents
are limited to 4 MiB each;
unknown suite, case, diagnostic, and check fields are rejected. Editing an
assertion or source establishes a new contract: review it, create a fresh
baseline, and retain the old baseline for rollback.

Benchmark version 2 embeds JSON scenario documents. Version 1 reports and
suites containing DSL source are not accepted as new baselines. Export their
scenarios from saved run bundles and create a fresh baseline; see
[migration](scenarios.md#migrate-existing-experiments).
