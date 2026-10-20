# Local knowledge and exit familiarity

People can react to a closure when they see it, rather than receiving every
physical change automatically. This lets a communication experiment distinguish
an early warning from information that the recipient already has.

Add `knowledge` to an agent group:

```json
"knowledge": {
  "observation_radius_m": 3,
  "known_exits": ["primary"]
}
```

The radius must be finite and nonnegative. `known_exits` names a unique subset
of the group's `destination` and `alternative_destinations`. Omit the list to
start familiar with every declared candidate, or supply `[]` to start familiar
with none. Familiarity does not assert that an exit is physically open.
Omitting `knowledge` entirely retains global physical-state awareness. Groups
with different knowledge assumptions can occupy the same scenario.

## Observe, learn, and choose

A locally aware person initially assumes resource availability is open until
they receive evidence. They retain their latest learned state for each exit,
gate, and connector. A remote physical change does not update that memory or
trigger a route change. Remembered closures remain in memory after an unseen
reopening, until observation or accepted information updates them.

Observation occurs at initialization for active agents and at each integration
boundary after scheduled events and release admission, before movement. It
also continues during an authored waypoint dwell. The observer and portal
must occupy the same surface, with the portal centre within the authored
radius. An obstacle rectangle blocks the straight line of sight; people do
not occlude it. Either end of a connector can reveal that connector's state,
without making a directed connection traversable backwards.

Physical contact also reveals state when the person's reference disc reaches
the assigned portal or portal lane, including with a zero observation radius.
A closed resource never processes, boards, or evacuates a person. A person
who discovers a closure replans, or waits in `waiting_for_route` if no known
route remains. Contact with a portal can reveal a later reopening while they
wait there. Waiting at a distant queue slot alone is not portal contact.

An accepted message changes remembered availability. An accepted exit claim
also makes that exit familiar if it is one of the group's declared candidates,
even when the claim says it is closed. An accepted correction restores the
claimed resource to its physical state at correction time. Messages and
corrections reach locally aware people during waypoint dwells as well as
ordinary on-surface movement and route waits. Unreleased, in-transit, and
evacuated agents remain ineligible.

Trust draws retain their deterministic seed, agent ID, and sampling-key rule.
An unaccepted message does not change local knowledge or familiarity. Direct
observation after scheduled information overrides an accepted claim about a
currently observable resource. A false claim that a closed exit is open can
cause a wasted journey, but cannot permit evacuation through it.

The person chooses the shortest eligible nominal route to a familiar declared
exit, subject to required waypoints, mobility exclusions, and remembered
closures. Declaration order still breaks equal route costs. Seeing an exit
outside the declared candidates does not add a new goal. With no familiar
reachable goal, the person waits; the runtime does not invent a destination
or silently treat the wait as a successful evacuation. Geometric preflight
still checks the physical journey independently of knowledge.

## Try the difference

These scenarios share geometry, people, seed, and an exit closure at 2 s. Only
the second supplies an official warning at that time:

```console
cargo run --locked -p chiyoda -- run examples/knowledge/unseen-closure.json -o out/unseen-closure --trace-every 1
cargo run --locked -p chiyoda -- run examples/knowledge/announced-closure.json -o out/announced-closure --trace-every 1
cargo run --locked -p chiyoda -- replay out/announced-closure/run.json
```

Without the warning, the person approaches the primary exit before seeing its
closure and turning towards the alternative. With the warning, they choose
the alternative earlier. Removing `knowledge` makes physical closures globally
known, so a truthful warning about the same closure need not improve movement.

The generated-space example compares silence, a prompt announcement, and a
delayed announcement under both awareness models, across two sizes and two
seeds:

```console
cargo run --release --locked -p chiyoda -- study run --config examples/studies/local-awareness.json -o out/local-awareness
cargo run --release --locked -p chiyoda -- study verify out/local-awareness/report.json --replay
```

A study load accepts the same `knowledge` object and applies it to every
person. Policy arms retain identical knowledge assumptions, positions,
release times, physical operations, and population hashes. Use separate loads
to compare awareness assumptions while keeping the population count fixed.
Generated exit candidates are `primary` and `alternative`.

## Inspect decisions

Each local agent's trace snapshot has a `knowledge` object:

```json
{
  "known_exits": ["primary", "alternative"],
  "availability": {
    "exit:primary": {
      "open": false,
      "learned_at_s": 2,
      "source": {"message": {"id": "notice"}}
    }
  }
}
```

Sources are `"observation"`, `{"message":{"id":"..."}}`, or
`{"countermeasure":{"id":"..."}}`. An unchanged observation retains its
last recorded evidence time; it is not a continuous last-seen timestamp.
An absent resource key means no availability evidence has been retained.
`resource_observed` events identify new or revised direct evidence. Local
`route_recomputed` events include the selected destination and blocked
resources. Existing information events and delivery metrics identify reached
and accepted announcements. Sample the trace every integration step when
inspecting brief belief changes; the event log is retained at every cadence.

Knowledge is included in scenario and bundle hashes and checked by complete
deterministic reconstruction. Runtime contract 0.44 introduced these knowledge
rules; 0.45 retains them while changing local motion. Authentic 0.43 and 0.44
bundles remain hash-verifiable archives; current reconstruction requires a fresh
run. JSON scenario schema 1
remains the input format, with this optional addition.

## Scope of the model

This is partial knowledge of resource availability and final-exit familiarity.
Agents still use the authored geometry, connector graph, and nominal route
costs. They do not build a map through exploration, infer congestion from
visible queues, follow neighbours, forget evidence, or learn new goals outside
their declared candidates. Required waypoints can author a search journey;
there is no autonomous roaming when a person has no known route.

Messages keep their existing one-shot, same-surface radius delivery rule.
Their source label does not create a persistent sign, wall attenuation,
peer-to-peer forwarding, or an implicit broadcast to future arrivals. A local
observation uses wall occlusion; message delivery does not. Schedule additional
interventions explicitly when an experiment needs them.

These knowledge, visibility, and acceptance rules are authored experimental
assumptions, not calibrated human perception or wayfinding. They let the
simulation express why communication can help, harm, or have no effect under
stated assumptions.
