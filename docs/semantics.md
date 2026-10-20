# Executable semantics 0.45

The Rust `chiyoda-core` runtime executes [JSON scenario schema 1](scenarios.md)
under runtime contract 0.45. This document is normative where it describes public
behavior;
the source and conformance tests make that behavior executable.

## State and step order

The interpreter initializes a deterministic row-major spawn layout for each
agent group. Static validation checks every generated starting coordinate
against the declared surface and the same radius-expanded obstacles used by the
navigator, and requires every generated disc on one surface to clear every
other generated disc by their combined radii plus the navigation epsilon,
regardless of release time. A simulation step at time `t` performs,
in chronological event order. Events with equal timestamps run in this order:
connector availability, exit availability, gate availability, connector
capacity, exit capacity, gate capacity, message, countermeasure. Declaration
order breaks ties within one event kind.

1. Apply each availability and capacity-state event whose declared time falls
   in `(previous step time, t]`. The final step is shortened when necessary so
   that `t` is exactly the authored duration. `connector_states`, `exit_states`,
   and `gate_states`
   alter physical availability and immediately recompute on-surface routes for
   globally aware groups. Locally aware groups retain their knowledge until
   observation or accepted information updates it.
   `connector_capacity_states`, `exit_capacity_states`, and
   `gate_capacity_states` alter only the effective service rate and emit an
   event; route selection does not forecast queues. State events at `0s` are
   applied before the initial trace; same-time declarations of one kind apply
   in declaration order. An availability change does not interrupt an agent
   already in connector transit or already evacuated through an exit; an
   arriving agent recomputes before attempting a now-closed final exit. A gate
   closure excludes that gate from future final-exit plans; it does not revoke
   passage already processed through the gate.
2. Deliver each message and countermeasure whose declared time falls in
   `(previous step time, t]` to active agents on the same surface within the
   declared reach radius. A not-yet-released group is not a recipient.
3. Update accepted connector-, exit-, or gate-availability beliefs. Each eligible recipient
   accepts an intervention when its deterministic seed-derived sample is below
   the declared trust probability. An accepted gate belief excludes only that
   gate from a selected final-exit plan. A qualifying countermeasure sets the
   corrected connector, exit, or gate belief to its current physical state and
   recomputes a route. Globally aware routing excludes physical closures regardless
   of belief; locally aware routing uses remembered availability. Physical
   service always enforces closures. If the active
   constraints leave no route, an on-surface agent waits in the traceable
   `waiting_for_route` state until a later state or information event produces
   one. The route cost includes obstacle-aware Euclidean walking from the
   agent's current point to each connector and exit plus connector transit time.
   Each rectangular obstacle is expanded by the agent radius and the shortest
   path over visible clearance corners is used. A connector with an authored
   height clearance is excluded when the agent's authored height exceeds it,
   as is a connector class explicitly excluded by the agent group. The route
   does not forecast queues, density, or later messages. When a final stage
   offers alternative exits, the runtime compares feasible nominal route costs
   and chooses the shortest; ties resolve by final-exit declaration order and
   then connector declaration order. It re-evaluates that choice only at
   initial routing or an existing reroute trigger. A controlled exit's cost
   includes the walk to its selected gate and the subsequent walk to the exit,
   but not its gate-service delay.
   The run bundle records every declared message and countermeasure's reached
   and accepted-agent counts, including zeroes. These are deterministic runtime
   observations under the authored trust rule, not measured communication uptake.
   An optional explicit sampling key replaces the intervention identifier in
   that draw. Keys are globally unique within one scenario; matching a key
   across separately authored comparison arms deliberately aligns draw streams.
4. Accrue gate, declared connector, and declared exit service tokens at their
   effective authored people-per-second rates for this step's actual elapsed
   time. A capacity state
   in the step therefore takes effect before accrual. Each resource begins
   empty and stores at most one effective rate's worth of credit, with a
   one-person minimum for rates below `1/s`; an idle resource therefore retains
   no more than one second of throughput (or one discrete person). Only whole
   tokens may be consumed.
5. Treat each agent whose authored release time is at or before `t` as eligible
   for admission. Its generated spawn disc must be statically clear and
   dynamically clear of the current same-surface occupants. An uncleared agent
   remains outside the modeled surface in `waiting_to_depart`, emits one
   `agent_release_deferred_for_clearance` event, and is retried at later steps;
   `agent_released` records the actual admission time. Admitted agents then
   enter the on-surface snapshot before selecting any on-surface velocity.
   Locally aware occupants, including those dwelling at a waypoint, observe
   same-surface portals in range and clear line of sight after admission and
   before queue assignment and movement. Observation updates familiarity and
   availability, overriding conflicting information before route selection.
   Initial active agents also observe before the initial trace. Contact with
   an assigned portal reveals physical state even at zero observation range.
   See [local knowledge](knowledge.md) for the complete optional contract.
   The visibility-graph path supplies each agent's preferred Euler velocity.
   Visibility corners guide steering at cruising speed; authored waypoints,
   queue slots, and final targets retain their exact arrival rules. A corner
   within the navigation epsilon is skipped. On a predicted cruising conflict,
   a deterministic right-hand bias of 5% of the speed bound breaks opposing
   symmetries; the final speed remains bounded. Slowing into an actual target
   does not receive that bias.
   The runtime first constructs hard velocity half-planes for the surface
   centre bounds and nearby radius-expanded rectangular obstacles. Each wall
   retains an outside face selected by the most clearance along the intended
   step, with stable axis-order ties. A stop remains feasible. The entire
   segment stays in those half-spaces, with final static clipping as a guard.
   For every same-surface candidate whose current separation is at most the
   sum of their radii plus both speed bounds over the fixed 2.5 s horizon, the
   runtime constructs a deterministic `f64` ORCA half-plane. This conservative
   screen allows either person to choose a new bounded velocity. Mobile pairs
   each bear half the correction; a person passing an immobile neighbour bears
   all of it. Waypoint dwell, route wait, abstract connector-capacity wait, and
   standing at an assigned queue slot have a zero motion bound. Advancing to a
   newly available slot restores movement.
   Two members of the same preallocated queue grid use their FIFO tickets for
   local right of way only beyond their combined radii plus both speed bounds
   over the actual step. Within that immediate reach, even an earlier ticket
   retains the later ticket's constraint. The later ticket bears the full
   correction and receives the earlier ticket's selected, statically clipped
   same-step velocity. Other pairs use immutable snapshot velocities. Exact
   co-location uses a stable identifier-derived direction.
   The speed-bounded program chooses the velocity closest to the preference.
   If the 2.5 s agent constraints are infeasible, it emits
   `local_avoidance_constraint_fallback` and retries the same stable neighbours
   over the actual step (or the original horizon if shorter). If still
   infeasible, it relaxes agent constraints while preserving every hard static
   bound. Failure in the hard prefix returns a stop.
   All on-surface segments are then resolved together before service or
   evacuation transitions. An analytic swept-disc guard shortens conflicting
   segments, or lets one safe escape proceed while the other stops, choosing
   the option with most total travel and stable pair-order ties. After 32
   scaling passes, a bounded stop propagation resolves remaining dependencies.
   The guarded endpoint determines target arrival and stored velocity.
   Connector landings must clear every complete planned on-surface interval
   as well as current occupants; an unavailable landing retains transit.
   Authored portal lanes remain exact. Legacy endpoint placement still selects
   a candidate, but that candidate must also satisfy static and interval
   clearance. Release and landing admission retain a 1e-8 m numerical margin.
   These movement choices are uncalibrated reference assumptions. The guard
   checks linear same-surface disc motion, not physical pedestrian dynamics or
   connector interiors. The independent boundary and swept audits remain in
   place and retain their existing transition exclusions.
   For an eligible line `queue_footprints`, capacity denial records the normal
   queue-entry audit and gives the agent a monotonic deterministic FIFO ticket.
   An immutable per-step queue snapshot ranks tickets, using the agent
   identifier only as a stable tie-breaker, and each entrant walks to its
   authored standing slot. A queue grid instead gives each eligible
   on-surface request a monotonic ticket and distinct standing-slot target
   before local motion, recording `queue_slot_preallocated` with the exact
   resource and ticket. Physical arrival records its queue-entry audit and
   turns that ticket into a modeled wait. An unarrived grid ticket does neither.
   A grid follows its validated serpentine FIFO path, beginning at its exact
   authored front position. Only an agent that reaches the head slot can reserve service;
   it consumes an existing token for a non-lift connector, gate, or exit, or
   reserves one lift cabin place. It emits `queue_service_reserved`, leaves the
   footprint, and then physically approaches its resource without a second
   token or capacity check. A lift place remains occupied from reservation
   through transit; an on-surface reroute that changes the next resource
   relinquishes it before another agent may reserve the cabin. Static validation
   requires enough slots for every declared agent, so this contract has no
   unlocated overflow. Resources without a footprint retain declaration-ordered
   abstract service actions: an agent's clearance disc may contact a
   zero-geometry service target rather than requiring centre-point arrival.
   This avoids numerical deadlock without relaxing clearance during the
   approach. With `release_at_s = T`, `release_interval_s = I`, and
   `release_batch_size = N`, ordinal agents share one release time by integer
   batch: `T + I * floor(ordinal / N)`. Omitted batch size is one; omitted
   interval releases the whole group at `T`. Each time is eligibility rather than an
   unconditional surface materialization: dynamic spawn clearance may delay
   individual admission. Release occurs after information delivery in the same
   step.
6. Board an available connector, process a gate token, process an exit token,
   advance from a reached next required waypoint or final exit stage, or mark
   the agent evacuated. A
   waypoint with dwell holds the agent until its authored expiry time.
   Stairs and ramps use endpoint distance divided by agent speed;
   escalators add their declared belt speed; lifts enforce cabin capacity
   during their declared cycle. A non-lift connector with an authored service
   rate consumes one deterministic service token before boarding; a connector
   without that declaration remains unlimited. An exit with an authored service
   rate likewise consumes one token after any gate processing; an undeclared
   exit capacity remains unlimited. The trace distinguishes waiting for a lift,
   an authored non-lift connector capacity, a gate capacity, and an authored
   exit capacity. At the completed integration boundary, the runtime audits
   every unordered pair of agents that simultaneously occupies one surface. A
   pair contributes when its horizontal centre distance is smaller than the
   sum of its authored reference-disc radii by more than the navigation
   epsilon. Separately, it analytically minimizes the horizontal separation of
   each eligible pair's linearly interpolated start/end paths over the completed
   interval. A swept pair is eligible only when both agents occupied the same
   surface at both boundaries, so release, portal, transit, surface-change, and
   evacuation transitions are excluded. Both audits exclude transit,
   unreleased, and evacuated states; neither reports physical contact.

State-event and service ties resolve by declaration and generated-agent order.
This is intentional: the complete order is recorded in canonical source and
must not be replaced by unordered map iteration. Local-motion neighbor lines
sort by stable generated agent identifier rather than mutable spatial-index
cell order.

## What this does not mean

The `0.45` local-motion law, nominal routing cost and alternative-exit
selection, scheduled-release and service-capacity semantics, operational-state
transitions, escalator walking-rider assumption, and seeded information
acceptance law are reference semantics, not calibrated behavioral claims. A
valid source program or deterministic trace does not
demonstrate crowd-flow accuracy, accessible egress fidelity, message
effectiveness, operational-state fidelity, or operational safety.

`clearance_time_s` is emitted only if every released agent has evacuated by the
configured end time. If at least one agent evacuated but one or more agents
remain, `last_exit_time_s` records the final observed evacuation instead. The
two measurements must not be substituted for each other: a partial run has no
system clearance time.

Current bundles also retain `movement_metrics`, an audit record for the
local-motion layer. `agents_with_local_clearance_adjustments` counts distinct
agents whose planned ordinary on-surface movement or legacy connector endpoint
placement was changed or deferred by that layer.
`local_clearance_adjustment_steps` counts those individual attempted-position
adjustments larger than the navigation clearance epsilon;
`cumulative_local_clearance_adjustment_m` sums the Euclidean
distance from planned to resolved position, and
`maximum_local_clearance_adjustment_m` is the largest one-attempt distance.
The plan is the navigator's corner-clamped Euler step, so cruising-corner
steering and passing bias also contribute; these values are not pure lost
travel or delay.
Zero-geometry service admission remains excluded for resources without a
footprint. Ordinary on-surface motion uses snapshot ORCA, including an
authored queue slot's approach and advancement. Where `portal_lanes` is
declared, exact connector lane arrivals are retained in transit until clear
rather than adjusted; gate and exit lanes are target locations only. This
telemetry is not a count of contacts or collisions, a density measure, a
physical crowd model, an observed delay, or calibration evidence.

Runtime contract `0.31`, retained in `0.43`, additionally records
`local_avoidance_constraint_fallback_steps`: each step in which the
speed-bounded ORCA half-planes were infeasible and the documented fallback was
selected. In 0.45 this includes the immediate-horizon retry, even when that
retry finds a feasible velocity. Every such count is cross-checked
against one `local_avoidance_constraint_fallback` event whose subject is an
agent present in the initial trace and whose detail fixes the reason. The
counter and its event audit are omitted from earlier bundles rather than
backfilled as zeroes. They report solver infeasibility, not contacts,
collisions, physical safety, or calibration evidence. A non-zero counter can
arise from dense competing motion under a finite speed bound; it does not by
itself identify an implementation fault or an initial overlap.

Runtime contract `0.45` additionally records `swept_motion_guard_steps` and one
`local_motion_limited_for_clearance` event for each individual segment shortened
by more than 1e-10 m by the simultaneous guard. Earlier bundles omit this field.
A fallback and a guard adjustment can occur on the same step; neither counter
is an overlap count. Current reconstruction verifies both counters and events.

Runtime contract `0.36` additionally records
`on_surface_clearance_audit`. `agents_with_disc_overlaps` counts distinct
agents that appeared in at least one overlapping pair at a completed
integration boundary; `disc_overlap_pair_steps` counts the unordered pairs at
those boundaries, so the same pair may contribute repeatedly; and
`maximum_disc_overlap_m` is the largest positive sum-of-radii minus horizontal
centre-distance. Bundle verification requires all three fields, zeroes them
together when no pair-step exists, and bounds them by the declared agent
population, radii, and integration schedule. Current-runtime bundle
verification also deterministically reconstructs the run. The audit is
reference-runtime provenance, not swept-time collision detection, observed
contact, a density measurement, or a physical-safety claim. It is omitted from
earlier bundles rather than backfilled as zero.

Runtime contract `0.37` additionally records
`swept_on_surface_clearance_audit`. Its
`agents_with_swept_disc_overlaps` counts distinct agents that participated in
at least one positive-overlap pair along an eligible same-surface interval;
`swept_disc_overlap_pair_steps` counts those unordered pairs once per interval;
and `maximum_swept_disc_overlap_m` is the greatest positive sum-of-radii minus
analytic minimum horizontal centre-distance. The audit calculates closest
approach from the two linear paths rather than sampling intermediate times. It
is an audit of the discrete reference trajectory, not continuous physical
collision detection: it does not model motion within a step beyond linear
interpolation, transitions between surfaces, contact, density, physical safety,
or calibration. Bundle verification requires complete, population-, radius-,
and step-schedule-bounded telemetry, and earlier bundles omit it rather than
backfill zeroes.

Current bundles also retain discrete `queue_metrics` for lift, non-lift
connector, gate, and exit capacity queues. `ever_queued_agents` is the number
of agents that entered a queue at least once; it mirrors the older top-level
exposure count. `cumulative_wait_agent_seconds` adds the actual reference-step
duration for each agent that was in that queue during the preceding reference
step, and
`peak_waiting_agents` is the largest count seen at a step boundary. These are
internally consistent telemetry for this discrete runtime, not observed queue
lengths, delays, throughput measurements, or calibrated flow values. A
`queue_footprints` makes the waiter's simulated standing position explicit; it
does not make that structural model an observation of a real queue.

Current bundles additionally retain `queue_metrics.by_resource`. Its `lifts`,
`connectors`, `gates`, and `exits` maps contain every authored resource whose
semantics can create the corresponding wait state: every lift and gate, plus
only non-lift connectors and exits with an authored capacity. An unreached
constrained resource is retained with zero values. These entries attribute the
same discrete wait observations to the resource that denied the agent at that
step. Aggregate cumulative wait equals the sum of its resource entries, but an
aggregate mechanism exposure may be lower than the resource-entry total when
one agent queued at multiple resources. An aggregate mechanism peak is not
reconstructed from resource peaks: it is the maximum simultaneous count across
all resources of that mechanism at a step boundary. The breakdown does not by
itself identify a real-world bottleneck or a measured service rate; an authored
footprint is separately visible in canonical source.

Runtime contract `0.31`, retained in `0.43`, also records an event when an agent first enters each
such modeled resource queue. Its event kind is one of `queue_entered_lift`,
`queue_entered_connector`, `queue_entered_gate`, or `queue_entered_exit`; its
subject is the generated agent identifier and its detail is the exact authored
resource identifier. For a grid, the event time is physical arrival at the
preallocated standing slot; for a line footprint or a resource without geometry
it remains the capacity-denial step. Repeated queue attempts by the same agent
at the same resource do not add events, while a later queue entry at a
different resource does. Bundle verification requires each
resource's events and each mechanism's unique-agent union to equal the
corresponding `ever_queued_agents` telemetry. A footprint-enabled queue also
emits one `queue_service_reserved` event when each head agent reserves a
service token or lift cabin place. For a lift footprint its detail remains
`connector:ID`, but its required prior entry audit is `queue_entered_lift`.
These are runtime audit trails, not observations of a physical queue.

Runtime contract `0.41`, retained in `0.43`, additionally records `queue_slot_preallocated` for
each grid ticket before local motion. Its subject is the generated agent
identifier and detail is `KIND:RESOURCE:TICKET`. It is not queue entry, a wait,
or service admission; its purpose is to make the distinction between an
assigned future slot and physical queue arrival inspectable from a run bundle.

## Reproducibility contract

`chiyoda run` writes `scenario.json` and `run.json`. A run bundle contains its
canonical scenario, runtime version, options, trace, event log, metrics,
scenario hash, and SHA-256 bundle hash. The bundle hash is computed from the
entire bundle with the hash field blank. For a bundle compatible with the
installed runtime contract, `chiyoda replay` and `chiyoda-replay` rebuild the
complete run from the embedded scenario and options before interpreting a
trace. An incompatible legacy bundle is rejected by default; an explicit
hash-only inspection mode is available for archival review.

Given identical scenario data, schema/runtime version, options, and supported Linux
environment, the reference runtime is expected to emit an identical bundle
hash. This is tested for the current implementation; it does not establish
cross-version or cross-architecture numerical equivalence.

Runtime contract 0.43 changes scenario packaging to JSON schema 1 while
preserving the 0.42 movement rules. Legacy `language_version` envelopes are
retained only when deserializing archived bundles, so their hashes stay
verifiable. They are rejected as current scenario inputs.

Runtime contract 0.44 adds opt-in local availability knowledge, exit familiarity,
and source-attributed knowledge snapshots. Omitted knowledge retains the
previous movement and routing behavior. Earlier bundle contracts, including
0.43, remain hash-verifiable archives rather than current reconstructed runs.

Runtime contract 0.45 changes local motion, static constraints, simultaneous
segment resolution, and dynamic release/landing clearance as described above.
The JSON scenario schema and WFC geometry rules remain unchanged. Authentic
0.44 bundles retain their original hashes for explicit archival inspection;
current reconstruction requires a fresh run. The frozen
[wayfinding investigation](movement-investigation.md) separates the original
outcomes from reruns under the changed motion contract.

Journey analysis version 1 derives per-person evidence from complete recorded
integration traces without changing the ordinary 0.45 execution. Its optional
isolated execution scope uses bundle contract `0.45-isolated.1`, retains the
complete original scenario, and records the selected original generated ID in
`isolated_agent`. Only that person is materialized, with the original spawn,
release eligibility, and information draws. Current replay reconstructs that
scope; older ordinary-only binaries identify the bundle contract as
incompatible. Ordinary 0.45 bundle hashes and reconstruction remain unchanged.
See [journey diagnostics](journeys.md) for observation, reference, and control
assumptions.
