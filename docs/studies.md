# Architectural pattern and communication studies

A study describes architectural rules, people, and communication policies,
then runs the same experiment across different layouts and sizes. WFC supplies
concrete layouts. The pedestrian runtime supplies movement, routing, queues,
information responses, and measured outcomes.

The study matrix is **pattern × scale × seed × load × policy**. Every requested
case appears in the final report, including generation failures, impossible
populations, invalid policies, and incomplete evacuations.

## Run a study

```console
cargo run --release --locked -p chiyoda -- study template -o out/study-config.json
cargo run --release --locked -p chiyoda -- study check --config out/study-config.json
cargo run --release --locked -p chiyoda -- study run --config out/study-config.json -o out/architecture-study
cargo run --release --locked -p chiyoda -- study verify out/architecture-study/report.json --replay
```

Read `out/architecture-study/report.md` for the summary and individual replay
links. `report.json` holds complete distributions, comparisons, input hashes,
and case reports. `observations.csv` has one row per requested case. Empty
numeric cells mean unavailable observations, never zero.

The template compares fixed-density populations under an exit closure, with
and without an official announcement. A second example compares an uncorrected
rumor with prompt and delayed staff corrections:

```console
cargo run --release --locked -p chiyoda -- study run --config examples/studies/communication.json -o out/communication-study
```

The template and run destinations must be new. A malformed configuration is
rejected before the run directory is created. `study check` validates the
matrix, numerical limits, and resolved generator configurations; generation
and scenario-dependent policy validation occur during execution. A failed
case does not stop later cases. Any case failure makes `study run` exit nonzero
after it writes the complete report. Archive verification also works for
reports containing failures.

## Define the experiment

Inputs use JSON with `"study_version": "1"`. Unknown fields and unsupported
versions are rejected. Patterns, scales, loads, policies, and seeds must all be
nonempty. Axis IDs are unique within their axis and contain 1–80 ASCII letters,
digits, underscores, or hyphens. Seed order and axis order are preserved.

`environment` contains common tile size, passage width, agent radius and speed,
duration, timestep, connector classes, search budgets, and baseline service
parameters. The template expands these defaults so the assumptions can be
reviewed. No preset perturbations are injected into studies.

### Patterns

Each `patterns` entry has an `id` and a `preset`: `concourse`, `corridors`, or
`bottleneck`. Optional `modules`, `forbidden_neighbors`, and `pins` use the
[generation rules](generation.md#author-a-rule-pattern). A pattern can override
`passage_width_m` while the other environmental assumptions stay shared.

Module weights influence the search; they do not guarantee a motif's frequency.
Inspect each manifest's `tile_counts`, catalog, and solved floor maps when
deciding whether the generated family represents the intended pattern. Pins
are absolute grid coordinates and must fit every requested scale.

All generated walkable modules must be connected. The generator reserves
paths between its resource locations and repairs disconnected layouts during
bounded retries. These constraints shape the accepted distribution; the
requested seeds are not a uniform or exhaustive sample of possible buildings.
Search failures and rejected attempts remain visible in the report.

### Scales and service capacity

Every scale declares its dimensions and its capacity assumption explicitly:

```json
{
  "id": "large",
  "width": 8,
  "height": 8,
  "floors": 1,
  "service_rate_multiplier": 1.0
}
```

Width and height are tile counts. At 6 m per tile, this is a 48 m by 48 m floor.
The multiplier applies to exit discharge, gate service, and non-lift connector
rates. Lift cycle time is divided by the multiplier; cabin passenger count is
unchanged. Scheduled capacity changes receive the same multiplier.

`1.0` keeps baseline service limits as the space grows. A multiplier of `4.0`
tests four times the service rate. Choose the relationship deliberately rather
than assuming that wider geometry automatically creates discharge capacity.

The current WFC generator supplies two ground-floor exits, one primary-exit
gate, and two downward connectors per upper floor. Scale changes do not add
exits, gates, or connector locations. Those counts and positions are generator
assumptions, visible in every saved scenario. This workflow does not yet vary
arbitrary exit placement or accept imported building geometry as a pattern.

### Population and placement

A load chooses a count per floor:

```json
{"id": "fixed", "population": {"kind": "fixed_per_floor", "agents": 24}}
```

Or a density:

```json
{
  "id": "busy",
  "population": {"kind": "density", "agents_per_m2": 0.02, "basis": "gross"},
  "placement": "distributed"
}
```

Density is resolved separately for every floor and rounded up to a whole
person. `gross` uses the complete rectangular floor area; `walkable` subtracts
the generated, nonoverlapping wall rectangles before radius expansion. Gross
and walkable area, actual population, and both resulting densities are saved
per floor. Equal walkable density can produce different headcounts across
patterns; equal gross density controls headcount for equally sized floors.

`distributed` is the default. It selects clear points from a deterministic,
shuffled lattice with 2.2 agent radii between neighbors. Points clear the same
conservative rectangular obstacle expansion used by navigation. This is a
spatial sampling rule, not an observed occupancy distribution. With the same
seed and geometry, larger loads take a longer prefix of the same point order.

`arrival_hall` places a deterministic grid inside the original reserved arrival
hall. A population that does not fit fails setup; it is never reduced, moved to
a different layout, or silently resampled.

Optional `release_interval_s` releases one person per interval on each floor,
starting at zero. All policies share these positions, IDs, and release times.
The final release must fit within the configured duration.

### Operations and communication

`operations` contains common `exit_states`, `gate_states`, `connector_states`,
and their three corresponding `*_capacity_states` arrays. Their representation
is the same as in a [scenario](scenarios.md). Operation times are absolute
seconds and are held constant across the study.

Each policy contains `id`, `messages`, and `countermeasures`, using the existing
scenario information model. Empty arrays give a policy with no announcements.
You can vary message timing, range, source, trust, claims, and corrections.
Surface IDs and resource IDs must exist at every requested scale where that
policy runs. Message origins and ranges are in metres, not grid fractions.
An origin can also be `{"anchor": "arrival_hall"}`. This resolves to the
original reserved arrival location on the message's declared surface, before
the study places its population. The anchor therefore stays fixed across
loads and policies while adapting to each generated layout's dimensions.
Explicit points must be walkable; a fixed coordinate such as `(0, 0)` can lie
inside a generated wall. The evaluated scenario stores the resolved point.
Copy messages to additional surfaces when modeling announcements on other
floors; a message on one surface does not broadcast across the building.

Keep `sampling_key` the same for equivalent interventions when comparing their
timing or trust. This shares the deterministic per-agent trust draw. Policy
labels never change the scenario seed, agent IDs, or random draws.

Physical availability and communication follow the existing runtime semantics.
Physical closures can already trigger route changes without a message. An
announcement about an already known closure may have no movement effect;
delivery and acceptance telemetry makes that outcome inspectable. False
availability claims and their corrections can change perceived routes.
Information is delivered once at its scheduled simulation step to eligible
agents. An announcement at time zero can precede agent admission and reach
nobody; the rumor example starts at 0.2 s. Delivery and acceptance counts should
be inspected before attributing an outcome to a communication policy.

## Read the results

Each pattern/scale/load/policy group reports the requested, passing, measured,
and fully evacuated case counts. Metric distributions include available
samples, missing samples, mean, minimum, median, nearest-rank p95, and maximum.
The p95 is descriptive; two seeds do not establish a reliable tail estimate.

Recorded observations include evacuation fraction, people, gross and walkable
density, absolute mean exit time, full clearance time, queue wait per person,
information delivery/acceptance counts, and reference-disc overlap telemetry.
Information counts are deliveries, so an individual can appear in several
interventions. Queue wait per person divides accumulated waits across modeled
service mechanisms by the entire initial population, including people who
remain at the time limit.

Threshold failures remain measured outcomes. Invalid trajectories, failed
scenario validation, runtime errors, and failed replay reconstruction are
excluded from metric distributions. Their cases and reasons are retained.
Clearance time is absent for incomplete evacuations. Its distribution and its
comparisons include only completed runs; always read evacuation fraction and
completion counts alongside clearance.
Mean exit time also describes completed agents only; it can decrease while
more people remain unfinished. It must be read with the evacuation fraction.

The report compares each nonreference value against the **first declared**
pattern, scale, load, or policy, holding other axes fixed. Differences are
current minus reference, matched by root seed, with separate sample counts for
each metric. A clearance difference requires both members to finish.

Policy comparisons reuse the exact generated geometry, services, population,
release times, operations, and simulation seed. These controls are hashed and
verified. Pattern and scale comparisons necessarily change geometry. Sharing
a root seed across different rule sets does not make the resulting layouts
equivalent, and WFC search/rejection can bias their distributions.

Larger spaces also create longer journeys. The report includes mean direct
distance to the nearest exit and mean release time as context. Direct distance
is a straight-line lower bound including elevation; it is not an obstacle-aware
free-flow prediction or a congestion adjustment. The report does not label a
pattern detrimental from clearance time alone, establish statistical
significance, or make calibrated real-building safety claims.

## Artifacts, verification, and limits

Every numbered `case-0000/` directory has a `report.json`. A successful layout
also has `generation.json` and `generated-scenario.json`: these describe the
original geometry with one placeholder agent per floor and baseline generator
services. **`scenario.json` is the actual evaluated input**, with the selected
population, services, operations, and policy. Simulated cases retain `run.json`,
including cases that fail thresholds or trajectory checks.

Setup failures retain their available generated geometry and a reason. Failed
generation has no scenario or manifest; its bounded attempt log is in the case
report. `progress.json` is updated after every saved case. The generator is
called once per pattern/scale/seed, including when it fails. Population setup
is performed once per load, and policies never trigger a replacement layout.
Report generation attempt counts are deduplicated across loads and policies.

`study verify REPORT` checks the report hash and reconstructed summaries,
matrix completeness, matching case/config files, input bytes, manifest and
bundle hashes, archived population preparation, and the exact policy applied.
`--replay` additionally reconstructs every available run. Verification is an
integrity/reproducibility check, not evidence that the model is calibrated or
that each case passed its declared thresholds.

Study version 1 permits at most 4,096 requested cases, 1,024 people per floor,
200,000 placement candidates per floor, and 2,000,000 agent integration steps
per case. Counts are never silently capped. The generator still supports
4–16 tiles per axis and 1–4 floors. Search and geometric preflight retain their
existing limits. Large cases can produce substantial traces: the CLI streams
cases instead of accumulating complete traces for the whole study. Replay
verification temporarily reconstructs a second bundle for the current case.

Simulation movement rules and existing evaluation/benchmark commands are
unchanged. Changes to study placement, service scaling, comparison, or sampling
semantics require a study version change. The current runtime's known crowd
overlap and symmetric-crossing limitations remain observable in studies.
