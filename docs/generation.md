# WFC space generation and evaluation

Chiyoda can generate different rectangular floor plans using tiled wave
function collapse (WFC), build versioned JSON scenarios, and evaluate
them with the existing pedestrian runtime. Generation does not change the scenario model,
routing cost, ORCA movement, service rules, or run-bundle contract. The original
`generate --seed ... -o example.json` command retains its fixed-layout behavior.

## Generate a space

From the repository root:

```console
cargo run --release --locked -p chiyoda -- generate-space --seed 73 --preset concourse -o out/space-73
cargo run --release --locked -p chiyoda -- check out/space-73/scenario.json --geometry
cargo run --release --locked -p chiyoda -- run out/space-73/scenario.json -o out/space-73-run
cargo run --release --locked -p chiyoda -- replay out/space-73-run/run.json
```

`generate-space` writes `scenario.json` and `generation.json` into a new
directory. An existing destination is rejected. If all generation attempts
fail, it writes `generation-failure.json`, including the seed, complete config,
attempt logs, and versions, and exits nonzero.

The presets are:

| Preset | Spatial pattern |
| --- | --- |
| `concourse` | More halls and kiosk modules, with connecting corridors |
| `corridors` | More narrow corridors, turns, junctions, and dead ends |
| `bottleneck` | A solid divider with exactly one east-west corridor crossing |

All presets reserve connected paths between spawn halls, connector landings,
and the ground floor's two exits before WFC fills the remaining cells. The
first floor is `floor_0` at elevation zero; higher floors are spaced 3 m apart.
Each floor is one surface containing wall obstacles. Adjoining modules do not
become disconnected surfaces. Exits are abstract service locations inside
reserved perimeter halls, not inferred building door geometry.

```console
cargo run --release --locked -p chiyoda -- generate-space --seed 19 --preset bottleneck --width 6 --height 5 --floors 3 --agents-per-floor 8 -o out/bottleneck-19
```

## Author a rule pattern

Export a complete editable config, including the module catalog:

```console
cargo run --locked -p chiyoda -- space-rules --preset corridors > /tmp/corridor-rules.json
cargo run --release --locked -p chiyoda -- generate-space --seed 41 --config /tmp/corridor-rules.json -o out/custom-41
```

`SpaceConfig` JSON accepts partial configurations; omitted fields use the
versioned defaults. Unknown fields are rejected. A small config is available
at [`examples/generation/concourse.json`](../examples/generation/concourse.json).
Explicit CLI dimensions, floor count, agent count, and preset override the
corresponding config fields. A nonempty `modules` array replaces the preset's
catalog; use `"modules": []` to select the preset catalog and weights again.

A module has these fields:

```json
{
  "id": "hall_nes",
  "weight": 8,
  "openings": [true, true, true, false],
  "kind": "hall"
}
```

Openings are ordered **north, east, south, west**. North is decreasing grid row
and decreasing surface y. Neighboring openings must match. Every outward grid
boundary is closed. Weights are positive integers governing weighted search
choices; they are not guarantees about final module frequencies.

Kinds have defined physical geometry:

- `solid`: a completely blocked tile; all openings must be false.
- `corridor`: a central square with arms toward its declared openings.
- `hall`: an open room with perimeter walls and centered openings.
- `kiosk`: a hall with a central square obstacle and bypass space around it.

Passages have `passage_width_m`; perimeter walls are 4% of `tile_size_m`.
The generator merges blocked regions into rectangular obstacles. Validation
requires passage and kiosk bypass widths to clear the authored agent diameter,
and every generated spawn disc must fit inside its reserved hall.

The preset catalogs contain `solid`, corridor and hall variants for every
nonempty combination of openings, and `kiosk_nesw`. Names use compass suffixes,
for example `corridor_ew`, `hall_ne`, and `hall_nesw`.

Additional adjacency exclusions and cell constraints use catalog identifiers:

```json
{
  "forbidden_neighbors": [
    {"tile": "kiosk_nesw", "direction": "east", "neighbor": "kiosk_nesw"}
  ],
  "pins": [
    {"floor": 0, "x": 0, "y": 2, "allowed_tiles": ["hall_nes"]}
  ]
}
```

An exclusion also forbids the reversed pair in the opposite direction. Pins
intersect the scaffold and boundary constraints; they never override them.
Contradictory pins exhaust the bounded search and produce a failure artifact.
A custom catalog must provide hall modules suitable for the reserved cells.
The bottleneck preset also needs a solid module and an east-west corridor.

The default config uses a 5 by 4 grid, two floors, 6 m tiles, 2 m passages,
four agents per floor, 0.3 m agent radii, 1.2 m/s walking speed, a 90 s
duration, a 0.2 s timestep, and up to 64 generation attempts. `connector_kinds` defaults to `["stair",
"lift"]`; a nonempty list of distinct `stair`, `ramp`, `escalator`, and `lift`
values cycles across the two directed downward connectors per level.

`perturbations: true` closes the primary exit halfway through the run. On
multi-floor spaces it also schedules a false connector-closure message at 20%
of the duration and a correction at 35%. Capacities, message trust, connector
cycles, and heights are explicit structural inputs emitted in the source;
generation does not infer them from widths or a real facility.

## Connectivity, retries, and provenance

The solver performs weighted minimum-entropy choices, adjacency propagation,
and chronological backtracking. `max_backtracks` and `max_decisions` bound each
floor's search. `max_attempts` bounds whole-space attempts, including rejection
of disconnected floor layouts or invalid geometry. After a disconnected layout, the generator
retains the isolated cells as repair targets and reserves connecting
paths in subsequent attempts. WFC still chooses modules and additional
connections under all local rules and pins. Noncritical scaffold cells may be
kiosks because their bypass geometry connects their openings. Contradiction and budget
exhaustion are different recorded outcomes.

`require_connected` defaults to true: every nonsolid module on each floor must
belong to one connected component. Setting it false permits unused isolated
rooms, but every declared journey must still pass geometric preflight.

Generation follows this sequence:

1. Validate the config and resolve its catalog.
2. Reserve route scaffolds and solve each floor's wave.
3. Check whole-floor connectivity when requested.
4. Construct typed scenario data, validate it, and serialize schema-versioned JSON.
5. Independently check every deterministic spawn, waypoint leg, and exit
   candidate through radius-expanded rectangular free space.

`check SOURCE --geometry` runs the same independent preflight on an authored
scenario. Ordinary `check SOURCE` keeps its original validation behavior.
Preflight joins free-space components using height- and class-eligible directed
connectors. A gated exit requires a reachable gate and a clear same-surface
walk from that gate to the exit. It ignores schedules, beliefs, queues,
individual portal-lane assignments, and other agents. It proves static
geometric reachability under the reference geometry, not eventual evacuation.

`generation.json` includes the resolved config, catalog, generator/solver/runtime
versions, root seed, floor seeds, reserved cells, solved tile maps, search
statistics, and every rejected attempt before success. `json_sha256` hashes
the exact emitted UTF-8 JSON, including its terminal newline; `scenario_hash`, `config_hash`, and `rules_hash`
use Chiyoda's canonical JSON hashing. Repeating a seed and config with the
same versions reproduces the artifacts on a supported numerical environment.
Changing algorithms requires a generator or solver version change. Cross-version
and cross-architecture numerical identity are not promised.

Generation supports 4..16 cells per axis, 1..4 floors, 1..64 agents per floor,
1..128 attempts, and at most 100,000 integration steps. The standalone WFC
API supports up to 64 tiles and 4,096 cells. Geometric preflight bounds each
floor/radius decomposition to 1,000,000 cells and reports a resource-limit
error instead of allocating an unbounded grid.

## Evaluate spaces and keep failures

```console
cargo run --release --locked -p chiyoda -- evaluate --config examples/generation/evaluation.json -o out/evaluation
```

The suite config contains `generation`, an explicit `seeds` array,
`evaluation`, and optional authored `fixtures`. `--seed S --cases N` replaces
the seed array with the consecutive range starting at S. Duplicate seeds,
overflowing ranges, and suites outside 1..256 total cases are rejected.

The evaluator records a trace at every integration step. It checks finite
positions, surface bounds, radius-expanded obstacle clearance at snapshots and
along same-surface movement segments, and authored walking speed bounds.
Admission, connector transitions, and evacuation transitions are excluded from
the segment and walking-speed checks. Replay reconstruction is enabled by
default; setting `evaluation.verify_replay` false skips the second execution
and records `replay_verified: false`.

Optional `evaluation.thresholds` are:

| Field | Pass condition |
| --- | --- |
| `min_evacuated_fraction` | Evacuated/total agents is at least this value in [0,1] |
| `max_clearance_time_s` | Full system clearance exists and is no later than this time |
| `max_queue_wait_agent_seconds` | Total wait across the four capacity mechanisms is no greater |
| `max_avoidance_fallback_steps` | ORCA fallback count is no greater |
| `max_disc_overlap_m` | Maximum boundary-snapshot reference-disc overlap is no greater |
| `max_swept_disc_overlap_m` | Maximum swept reference-disc overlap is no greater |

Unset thresholds impose no metric limit. Missing required telemetry fails a
declared threshold. A partial evacuation has no clearance time, even when
`last_exit_time_s` exists. These metrics describe the reference runtime, not
measured physical crowd performance.

Each suite saves `config.json`, incremental `progress.json`, and final
`report.json`. Each numbered `case-0000/` directory contains its case report,
available source, generation manifest, and runtime bundle. A generation failure
has no source or run bundle; its complete attempt log remains in its report.
Passing and failing simulated cases both retain their bundles. Any unexpected
case failure makes the CLI exit nonzero after all cases have been processed.

The final report counts module kinds, tile identifiers (including zeroes),
passage shapes, connector classes, cases with observed capacity queues, distinct
tile arrangements, and generation rejections by reason. Unique layout hashes
exclude seeds, scenario names, and search statistics. WFC's weighted search and
post-generation rejection can bias the accepted distribution; use these
coverage and rejection counts when judging a suite, rather than seed count
alone.

## Add authored and adversarial fixtures

WFC cases complement small authored regressions. Add reachable fixtures or
valid sources that deliberately have unreachable journeys:

```console
cargo run --release --locked -p chiyoda -- evaluate --config examples/generation/evaluation.json --fixture examples/generation/detour.json --unreachable-fixture examples/generation/blocked.json -o out/with-fixtures
cargo run --locked -p chiyoda -- evaluate --cases 0 --unreachable-fixture examples/generation/blocked.json -o out/negative-only
```

Fixture names come from file stems and must be unique within the suite. An
expected-unreachable fixture passes only if source validation succeeds and
geometric preflight finds an unreachable journey. It is not simulated, has no
metrics or replay verification, and does not receive simulation thresholds.
Malformed source does not count as a successful expected rejection.

The JSON equivalent is a `fixtures` entry with `name`, a complete versioned
`scenario` object, and `expected: "reachable"` or `"unreachable"`. The scenario
object is embedded in the saved config so later file edits cannot change an
archived experiment. See [the scenario format](scenarios.md).

To hold selected layouts fixed while comparing runtime revisions, use
[benchmark profiles, evaluation freezing, and baseline comparisons](benchmarks.md).

Generator version 3 emits JSON directly and retains version 2's layout and
connectivity rules. Evaluation reports use version 2; pre-JSON evaluation
archives require [migration](scenarios.md#migrate-existing-experiments).
