# JSON scenario format, schema 1

Chiyoda accepts versioned JSON scenario documents. There is no custom source
language or compilation step. The same document is used by the CLI, generators,
live viewer, and the scenarios embedded in evaluation and benchmark suites.

```json
{
  "schema_version": "1",
  "scenario": {
    "name": "hall",
    "seed": 73,
    "duration_s": 30,
    "timestep_s": 0.1,
    "surfaces": [
      {"id": "floor", "origin": {"x_m": 0, "y_m": 0, "z_m": 0}, "width_m": 10, "depth_m": 10}
    ],
    "exits": [
      {"id": "out", "surface": "floor", "at": {"x_m": 9, "y_m": 5, "z_m": 0}, "width_m": 1, "capacity_per_s": 1}
    ],
    "agents": [
      {"id": "people", "count": 4, "surface": "floor", "at": {"x_m": 1, "y_m": 5, "z_m": 0}, "destination": "out", "speed_mps": 1.2, "radius_m": 0.3, "height_m": 1.7}
    ]
  }
}
```

Save this as `hall.json`. From the repository root:

```console
cargo run --locked -p chiyoda -- check hall.json --geometry
cargo run --locked -p chiyoda -- format hall.json -o hall.formatted.json
cargo run --locked -p chiyoda -- format hall.formatted.json --check
cargo run --release --locked -p chiyoda -- run hall.formatted.json -o out/hall
cargo run --release --locked -p chiyoda -- replay out/hall/run.json
```

`format` validates and normalizes JSON to stable field order with a terminal
newline. It preserves array order and floating-point values. The old `compile`
command has been removed. `run` saves the input as `scenario.json`, along with
its reproducible `run.json` bundle. `chiyoda-replay --watch hall.json` reloads
JSON after saved edits; see [replay](replay.md).

## Document and numeric rules

The outer object requires the string `schema_version: "1"` and a `scenario`
object. Bare scenario objects, missing or unsupported versions, unknown fields,
duplicate object fields, malformed JSON, and wrong value types are rejected.
Comments and trailing commas are not JSON and are not accepted.

Units are encoded in field names. Lengths and coordinates use metres (`_m`),
times use seconds (`_s`), speeds use metres per second (`_mps`), and service
rates use people per second (`_per_s`). Values are JSON numbers, such as
`"duration_s": 30`, not strings such as `"duration_s": "30s"`. Probabilities
are numbers in `[0, 1]`. Counts and seeds are unsigned integers; seeds retain
all 64 bits. Integers used as coordinates or durations are accepted as numbers.
Nonfinite numbers and overflowing numeric input are rejected.

JSON object-key order and whitespace do not affect the typed scenario or its
canonical hash. Array order is preserved: equal-time events and equal-cost
choices can depend on it. Canonical hashes use Chiyoda's typed serialization,
including its defaults and omission rules, rather than a third-party JSON
canonicalization standard.

Decoding checks structure and version. `validate` then enforces the domain
rules below. Rust callers use `decode_scenario`, `validate`, and `run`;
`encode_scenario` validates before emitting a current document. A programmatic
`Scenario::new` starts an empty scenario for callers to populate and validate.
`CanonicalScenario::from` wraps scenario data with the current schema version.

## Scenario fields

`name`, `seed`, `duration_s`, and `timestep_s` are required. The name must be
nonempty; duration and timestep must be positive and finite, and timestep must
not exceed duration. All collection fields below default to empty arrays.
Validation requires at least one surface. Resources referenced by agents or
events must exist, and entity identifiers are globally unique and nonempty.

| Collection | Entry fields |
| --- | --- |
| `surfaces` | `id`, `origin`, `width_m`, `depth_m` |
| `obstacles` | `id`, `surface`, `at`, `width_m`, `depth_m` |
| `waypoints` | `id`, `surface`, `at`, `dwell_s` |
| `exits` | `id`, `surface`, `at`, `width_m`, optional `capacity_per_s` |
| `gates` | `id`, `surface`, `at`, `width_m`, `service_rate_per_s`, `destination` |
| `connectors` | A tagged connector object, described below |
| `portal_lanes` | `id`, `resource`, `axis`, `count` |
| `queue_footprints` | `id`, `resource`, `surface`, `head`, `tail`, `slots`, optional `width_m` and `lanes` |
| `agents` | An agent-group object, described below |
| `connector_states` | `id`, `connector`, `open`, `at_s` |
| `exit_states` | `id`, `exit`, `open`, `at_s` |
| `gate_states` | `id`, `gate`, `open`, `at_s` |
| `connector_capacity_states` | `id`, `connector`, `capacity_per_s`, `at_s` |
| `exit_capacity_states` | `id`, `exit`, `capacity_per_s`, `at_s` |
| `gate_capacity_states` | `id`, `gate`, `capacity_per_s`, `at_s` |
| `messages` | An information-message object, described below |
| `countermeasures` | A correction object, described below |

Every point (`origin`, `at`, `from`, `to`, `head`, `tail`) has numeric `x_m`,
`y_m`, and `z_m` fields. Surfaces are axis-aligned rectangles; obstacles are
rectangular no-go zones. Dimensions must be positive, origins finite, and
resource and agent positions must fit their surface and applicable clearances.
Waypoint dwell must be nonnegative. Optional service rates and clearances may
be omitted or `null`; omitted service capacity is unlimited. Width does not
implicitly determine service capacity.

All connectors, exits, and gates begin physically open. State entries set
`open` to `true` or `false` at `at_s`; time zero establishes the initial state.
Capacity changes require a resource with an existing declared baseline rate;
non-lift connector, exit, and gate rates must be positive. Same-time entries
within each event kind retain array order. Closures and information events can
trigger rerouting; capacity changes affect service without forecasting queues.

## Connectors and journeys

A connector is an externally tagged object with exactly one of the
case-sensitive keys `Stair`, `Ramp`, `Escalator`, or `Lift`:

```json
{
  "Stair": {
    "id": "down",
    "from_surface": "platform",
    "from": {"x_m": 5, "y_m": 5, "z_m": 3},
    "to_surface": "hall",
    "to": {"x_m": 5, "y_m": 5, "z_m": 0},
    "width_m": 2,
    "capacity_per_s": 1.5,
    "clearance_height_m": 2.1
  }
}
```

All four kinds require `id`, `from_surface`, `from`, `to_surface`, and `to`.
Stairs and ramps require `width_m`; escalators additionally require positive
`belt_speed_mps`. These three kinds accept optional `capacity_per_s`. Lifts
instead require `cabin_width_m`, `cabin_depth_m`, positive integer `capacity`,
and `cycle_s`. Every kind accepts optional `clearance_height_m`.

Agent groups require `id`, integer `count`, `surface`, `at`, `destination`,
`speed_mps`, `radius_m`, and `height_m`. Optional arrays are `via` (ordered
waypoint identifiers), `alternative_destinations` (exit identifiers), and
`excluded_connector_kinds` (lowercase `stair`, `ramp`, `escalator`, `lift`).
Repeated exclusions or exit candidates are invalid. Every candidate must have
a valid eligible directed journey after all required waypoints.

`release_at_s` defaults to zero. Optional `release_interval_s` releases one
ordinal agent per interval; optional positive integer `release_batch_size`
groups them into batches and requires an interval. Every authored release must
fall within the duration. Release is eligibility: the runtime can defer actual
admission until a spawn disc is clear. Waypoints can hold an agent for their
authored dwell before its next journey stage.

Optional `knowledge` enables local availability awareness and exit familiarity:
`{"observation_radius_m": 3, "known_exits": ["out"]}`. The radius is finite
and nonnegative. Familiar exits must be unique declared candidates; omitting
`known_exits` starts with all candidates familiar, while `[]` starts with none.
Omitting the whole object retains global physical-state awareness. See
[local knowledge](knowledge.md) for observation, learning, routing, and replay.

## Portals and queues

Resource references use one lowercase tag, for example
`{"connector": {"id": "down"}}`, `{"exit": {"id": "out"}}`, or
`{"gate": {"id": "fare"}}`. Portal axes are `"x"` or `"y"`. Portal lane
counts must be positive, fit the resource width, and clear obstacles for the
largest authored agent radius. A connector's lane definition applies to both
portals.

A queue footprint's `head` is its first standing slot and `tail` defines its
depth. `slots` is a positive integer and must accommodate all declared agents.
Omitting `width_m` and `lanes` creates a line; supplying both creates a
serpentine grid with at least two lanes. Slot positions and transitions must
clear boundaries, obstacles, and the largest agent radius. A footprint needs a
lift, a capacity-limited non-lift connector or exit, or a gate. It defines FIFO
positions and service reservation behavior, not an inferred flow rate.

## Information and corrections

Messages require `id`, `source`, `surface`, `origin`, `claim`, `truthful`,
`at_s`, `reach_m`, and `trust`. Sources are case-sensitive `Peer`, `Official`,
`Signage`, and `Staff`. Claims use exactly one case-sensitive proposition:

```json
{"ExitAvailability": {"exit": "out", "open": false}}
```

The other forms are `ConnectorAvailability` with `connector` and `open`, and
`GateAvailability` with `gate` and `open`. A `truthful` boolean must match
whether the claim agrees with the resource's authored physical state at the
message time. Beliefs never make a physically closed resource available.

Corrections require `id`, `corrects` (a message identifier), `source`, `surface`,
`origin`, `at_s`, `reach_m`, and `trust`. Their source must be `Official`,
`Signage`, or `Staff`; they can only correct a false message and cannot precede
it. An accepted correction restores the recipient's belief to physical state.

Both kinds accept an optional nonempty `sampling_key`; otherwise their `id`
is used. Keys must be globally unique across interventions. With a fixed seed
and agent identifier, matching explicit keys across independently authored
comparison scenarios aligns trust draws. These are authored acceptance
probabilities, not measured communication uptake.

See [execution semantics](semantics.md) for event ordering, routing, local
movement, queue behavior, and the model's geometry and calibration boundaries.

## Migrate existing experiments

The custom DSL, `.chy` input, parser, formatter implementation, and `compile`
command have been removed. The `format` command now normalizes JSON. Existing
scenario arrays, units, intervention rules, and movement behavior are retained.
Runtime and bundle contracts are now 0.44, WFC generation version is 3, and
evaluation and benchmark report versions are 2. New scenario and bundle hashes
reflect their new metadata and packaging; they are not expected to equal old
artifact hashes.

If you have an existing `run.json`, export its embedded scenario:

```console
chiyoda extract-scenario out/old-run/run.json -o migrated.json
chiyoda check migrated.json --geometry
chiyoda run migrated.json -o out/migrated-run
```

Extraction verifies the bundle and embedded scenario hashes, accepts current
schema 1 or the archived 0.29 IR shape, validates the data, and writes a new
schema 1 document without overwriting existing files. It exports inputs; it
does not claim that the new run reconstructs the old runtime contract.
Archived bundles retain their original `language_version` serialization for
hash verification. Normal replay requires the current runtime contract;
the viewer's explicit `--allow-legacy-hash-only` mode provides hash-only archival
inspection. Legacy envelopes are not accepted as ordinary scenario inputs.

If you only have `.chy` files, use a checkout of the previous revision
(`432a99a`) to run its `compile` command before upgrading. Its exported 0.29
JSON IR has the same scenario data: replace the outer `language_version` field
with `"schema_version": "1"`, then validate it with the new CLI. Conversion
does not require rewriting the scenario's fields or inventing new units.

Version 1 evaluation and benchmark archives embed DSL strings and cannot be
used directly by the new suite readers. Extract the scenarios from their saved
bundles, embed the resulting documents under each fixture or case's `scenario`
field, and create fresh version 2 baselines. Preserve old reports and hashes
as archived evidence. [Generation](generation.md) and
[benchmarks](benchmarks.md) describe the current suite formats.
