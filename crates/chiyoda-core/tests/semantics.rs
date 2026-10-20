use chiyoda_core::{
    RunBundle, RunOptions, bundle_hash, decode_scenario, encode_scenario, generator, run, validate,
};

#[test]
fn generated_source_is_parseable_and_valid() {
    let scenario = generator::scenario(73).expect("generator must preserve the scenario contract");
    assert_eq!(scenario.surfaces.len(), 2);
    assert_eq!(scenario.connectors.len(), 2);
    assert_eq!(scenario.exits.len(), 2);
    assert_eq!(scenario.exit_states.len(), 1);
    assert_eq!(
        scenario.agents[0].alternative_destinations,
        vec!["plaza".to_owned()]
    );
    assert_eq!(scenario.messages.len(), 1);
    assert_eq!(scenario.countermeasures.len(), 1);
}

#[test]
fn generated_source_is_valid_across_a_seed_range() {
    for seed in 0..1024 {
        generator::scenario(seed).unwrap_or_else(|error| {
            panic!("generator must preserve the scenario contract for seed {seed}: {error}")
        });
    }
}

#[test]
fn json_rejects_removed_walking_profile_fields() {
    let mut document = serde_json::to_value(chiyoda_core::CanonicalScenario::from(
        generator::scenario(73).unwrap(),
    ))
    .unwrap();
    document["scenario"]["walking_profile"] = serde_json::json!({"speed_mps": 1.2});
    assert!(
        decode_scenario(&document.to_string())
            .unwrap_err()
            .to_string()
            .contains("walking_profile")
    );
}

#[test]
fn fractional_final_step_ends_exactly_at_the_authored_duration() {
    let scenario = decode_scenario(include_str!(
        "fixtures/semantics/fractional_final_step_ends_exactly_at_the_authored_duration.json"
    ))
    .expect("fractional-step fixture parses");
    validate(&scenario).expect("fractional-step fixture validates");

    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("fractional-step fixture runs");

    assert!(
        bundle
            .metrics
            .clearance_time_s
            .is_some_and(|time_s| time_s.total_cmp(&1.5).is_eq())
    );
    assert!(
        bundle
            .trace
            .iter()
            .all(|frame| frame.time_s <= scenario.duration_s)
    );
    assert_eq!(bundle.trace.last().map(|frame| frame.step), Some(2));
    assert!(
        bundle
            .trace
            .last()
            .is_some_and(|frame| frame.time_s.total_cmp(&1.5).is_eq())
    );
    assert!(
        bundle
            .events
            .iter()
            .all(|event| event.time_s <= scenario.duration_s)
    );
}

#[test]
fn json_rejects_trailing_input_duplicate_release_fields_and_duplicate_exits() {
    let input = generator::json(73).unwrap();
    assert!(decode_scenario(&format!("{input} trailing")).is_err());
    let duplicate = input.replacen(
        "\"release_at_s\": 0.0",
        "\"release_at_s\": 0.0, \"release_at_s\": 0.0",
        1,
    );
    assert!(
        decode_scenario(&duplicate)
            .unwrap_err()
            .to_string()
            .contains("duplicate")
    );
    let mut scenario = generator::scenario(73).unwrap();
    let destination = scenario.agents[0].destination.clone();
    scenario.agents[0]
        .alternative_destinations
        .push(destination);
    assert!(validate(&scenario).is_err());
}

#[test]
fn json_rejects_unknown_waypoint_fields() {
    let mut document = serde_json::to_value(chiyoda_core::CanonicalScenario::from(
        generator::scenario(73).unwrap(),
    ))
    .unwrap();
    document["scenario"]["waypoints"] = serde_json::json!([{ "id": "hall", "surface": "concourse", "at": {"x_m": 2.0, "y_m": 2.0, "z_m": 0.0}, "dwell_s": 1.0, "trailing": true }]);
    assert!(
        decode_scenario(&document.to_string())
            .unwrap_err()
            .to_string()
            .contains("trailing")
    );
}

#[test]
fn json_round_trips_to_the_same_typed_scenario() {
    let scenario = generator::scenario(73).expect("valid generator output");
    let formatted = encode_scenario(&scenario).unwrap();
    let reparsed = decode_scenario(&formatted).expect("formatted source parses");
    assert_eq!(scenario, reparsed);
    assert_eq!(formatted, encode_scenario(&reparsed).unwrap());
}

#[test]
fn scenarios_without_optional_geometry_preserve_the_pre_024_json_shape() {
    let source = include_str!(
        "fixtures/semantics/scenarios_without_optional_geometry_preserve_the_pre_024_json_shape.json"
    );
    let scenario = decode_scenario(source).expect("source without optional geometry parses");
    validate(&scenario).expect("source without optional geometry validates");
    assert!(scenario.portal_lanes.is_empty());
    assert!(scenario.queue_footprints.is_empty());
    let encoded = serde_json::to_value(&scenario).expect("scenario serializes");
    assert!(
        encoded.get("portal_lanes").is_none(),
        "an omitted optional declaration must not rewrite historical canonical IR"
    );
    assert!(
        encoded.get("queue_footprints").is_none(),
        "an omitted optional declaration must not rewrite historical canonical IR"
    );
    let decoded: chiyoda_core::Scenario =
        serde_json::from_value(encoded).expect("pre-0.24 canonical IR remains readable");
    assert_eq!(decoded, scenario);
}

#[test]
fn json_round_trips_ramps_and_escalators() {
    let source = include_str!("fixtures/semantics/json_round_trips_ramps_and_escalators.json");
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let formatted = encode_scenario(&scenario).unwrap();
    assert_eq!(
        decode_scenario(&formatted).expect("formatted source parses"),
        scenario
    );
}

#[test]
fn portal_lanes_round_trip_and_replace_point_connector_landings() {
    let source = include_str!(
        "fixtures/semantics/portal_lanes_round_trip_and_replace_point_connector_landings.json"
    );
    let scenario = decode_scenario(source).expect("portal-lane source parses");
    validate(&scenario).expect("portal-lane source validates");
    assert_eq!(
        decode_scenario(&encode_scenario(&scenario).unwrap()).expect("formatted source parses"),
        scenario
    );

    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("portal-lane source runs");
    let arrival = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 2.0).abs() < f64::EPSILON)
        .expect("connector landing is traced")
        .agents
        .iter()
        .find(|agent| agent.id == "traveller:0")
        .expect("traveller remains in trace");
    assert_eq!(arrival.surface, "lower");
    assert!((arrival.y_m - 4.5).abs() < 1e-9 || (arrival.y_m - 5.5).abs() < 1e-9);
    assert!((arrival.y_m - 5.0).abs() > 1e-9);
}

#[test]
fn authored_portal_landing_waits_for_a_clear_lane() {
    let source =
        include_str!("fixtures/semantics/authored_portal_landing_waits_for_a_clear_lane.json");
    let scenario = decode_scenario(source).expect("portal-wait source parses");
    validate(&scenario).expect("portal-wait source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("portal-wait source runs");
    let delayed = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 2.0).abs() < f64::EPSILON)
        .expect("blocked landing is traced")
        .agents
        .iter()
        .find(|agent| agent.id == "traveller:0")
        .expect("traveller remains in trace");
    assert_eq!(delayed.state, chiyoda_core::AgentState::InTransit);
    let arrival = bundle
        .events
        .iter()
        .find(|event| event.kind == "connector_arrival" && event.subject == "traveller:0")
        .expect("traveller lands after the blocker moves");
    assert!(arrival.time_s >= 4.0);
}

#[test]
fn validation_rejects_invalid_portal_lane_geometry_and_resources() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_invalid_portal_lane_geometry_and_resources.json"
    );
    let scenario = decode_scenario(source).expect("invalid portal-lane source parses");
    let errors = validate(&scenario).expect_err("invalid portal lanes are rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "portal_lanes[0].count")
    );
    assert!(errors.iter().any(|error| {
        error.path == "portal_lanes[0].from.lanes[0]" || error.path == "portal_lanes[0].to.lanes[0]"
    }));
    assert!(errors.iter().any(|error| {
        error.path == "portal_lanes[1].resource" && error.message.contains("unknown gate")
    }));
}

#[test]
fn authored_queue_footprint_advances_fifo_slots_before_service() {
    let source = include_str!(
        "fixtures/semantics/authored_queue_footprint_advances_fifo_slots_before_service.json"
    );
    let scenario = decode_scenario(source).expect("queue-footprint source parses");
    validate(&scenario).expect("queue-footprint source validates");
    assert_eq!(
        decode_scenario(&encode_scenario(&scenario).unwrap()).expect("formatted source parses"),
        scenario
    );

    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("queue-footprint source runs");
    let tail_waiter = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 5.0).abs() < f64::EPSILON)
        .expect("queue positions are traced")
        .agents
        .iter()
        .find(|agent| {
            agent.state == chiyoda_core::AgentState::WaitingForExit
                && (agent.x_m - 14.0).abs() < 1e-9
        })
        .expect("tail agent remains in trace");
    assert_eq!(tail_waiter.state, chiyoda_core::AgentState::WaitingForExit);
    let reservations: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "queue_service_reserved")
        .collect();
    assert_eq!(reservations.len(), 2);
    assert_eq!(reservations[0].detail, "exit:street");
    assert!(reservations[0].time_s < reservations[1].time_s);
    let entries: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "queue_entered_exit")
        .collect();
    assert_eq!(entries.len(), 2);
    assert_eq!(reservations[0].subject, entries[0].subject);
    assert_eq!(reservations[1].subject, entries[1].subject);
    assert_eq!(bundle.metrics.evacuated_agents, 2);
}

#[test]
fn authored_lift_queue_footprint_reserves_cabin_capacity_before_boarding() {
    let source = include_str!(
        "fixtures/semantics/authored_lift_queue_footprint_reserves_cabin_capacity_before_boarding.json"
    );
    let scenario = decode_scenario(source).expect("lift queue-footprint source parses");
    validate(&scenario).expect("lift queue-footprint source validates");
    assert_eq!(
        decode_scenario(&encode_scenario(&scenario).unwrap()).expect("formatted source parses"),
        scenario
    );

    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("lift queue-footprint source runs");
    let lift_wait_positions: Vec<_> = bundle
        .trace
        .iter()
        .flat_map(|frame| {
            frame.agents.iter().filter_map(|agent| {
                (agent.state == chiyoda_core::AgentState::WaitingForLift).then_some((
                    frame.time_s,
                    agent.x_m,
                    agent.y_m,
                ))
            })
        })
        .collect();
    assert!(
        lift_wait_positions
            .iter()
            .any(|(_, x_m, _)| (*x_m - 12.0).abs() < 1e-9),
        "waiting lift positions: {lift_wait_positions:?}"
    );
    let reservations: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "queue_service_reserved")
        .collect();
    assert_eq!(reservations.len(), 2);
    assert_eq!(reservations[0].detail, "connector:lift_a");
    assert!(reservations[1].time_s - reservations[0].time_s >= 8.0);
    let entry = bundle
        .events
        .iter()
        .find(|event| event.kind == "queue_entered_lift")
        .expect("lift queue entry is audited");
    assert_eq!(reservations[0].subject, entry.subject);
    assert!(entry.time_s <= reservations[0].time_s);
    assert_eq!(bundle.metrics.evacuated_agents, 3);
}

#[test]
fn queue_grid_round_trips_and_preserves_serpentine_fifo_adjacency() {
    let source = include_str!(
        "fixtures/semantics/queue_grid_round_trips_and_preserves_serpentine_fifo_adjacency.json"
    );
    let scenario = decode_scenario(source).expect("queue-grid source parses");
    validate(&scenario).expect("queue-grid source validates");
    assert_eq!(
        decode_scenario(&encode_scenario(&scenario).unwrap())
            .expect("formatted queue-grid source parses"),
        scenario
    );

    let grid = &scenario.queue_footprints[0];
    assert_eq!(grid.position(0), grid.head);
    assert_eq!(
        grid.position(1),
        chiyoda_core::model::Point3 {
            x_m: 3.0,
            y_m: 6.0,
            z_m: 0.0,
        }
    );
    assert_eq!(
        grid.position(2),
        chiyoda_core::model::Point3 {
            x_m: 3.0,
            y_m: 5.0,
            z_m: 0.0,
        }
    );
    assert_eq!(
        grid.position(3),
        chiyoda_core::model::Point3 {
            x_m: 27.0,
            y_m: 5.0,
            z_m: 0.0,
        }
    );
}

#[test]
fn queue_grid_preallocates_fifo_slots_and_requires_physical_entry_for_service() {
    let source = include_str!(
        "fixtures/semantics/queue_grid_preallocates_fifo_slots_and_requires_physical_entry_for_service.json"
    );
    let scenario = decode_scenario(source).expect("queue-grid service source parses");
    validate(&scenario).expect("queue-grid service source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("queue-grid service source runs");

    let first_motion_step = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 0.5).abs() < f64::EPSILON)
        .expect("every integration step is traced");
    assert!(
        first_motion_step
            .agents
            .iter()
            .all(|agent| agent.state == chiyoda_core::AgentState::Moving)
    );
    let entries: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "queue_entered_exit")
        .collect();
    let assignments: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "queue_slot_preallocated")
        .collect();
    let reservations: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "queue_service_reserved")
        .collect();
    assert_eq!(assignments.len(), 4);
    assert_eq!(entries.len(), 4);
    assert_eq!(reservations.len(), 4);
    for (ordinal, assignment) in assignments.iter().enumerate() {
        let expected_subject = format!("passengers:{ordinal}");
        assert_eq!(assignment.subject, expected_subject);
        assert!((assignment.time_s - 0.5).abs() < f64::EPSILON);
        assert_eq!(assignment.detail, format!("exit:street:{ordinal}"));
        assert!(entries.iter().any(|entry| {
            entry.subject == assignment.subject && entry.time_s >= assignment.time_s
        }));
    }
    for (ordinal, reservation) in reservations.iter().enumerate() {
        let expected_subject = format!("passengers:{ordinal}");
        assert_eq!(reservation.subject, expected_subject);
        assert!(entries.iter().any(|entry| {
            entry.subject == reservation.subject && entry.time_s <= reservation.time_s
        }));
    }
    assert_eq!(bundle.metrics.evacuated_agents, 4);
}

#[test]
fn validation_rejects_queue_grid_with_unsafe_lanes() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_queue_grid_with_unsafe_lanes.json");
    let scenario = decode_scenario(source).expect("invalid queue-grid source parses");
    let errors = validate(&scenario).expect_err("unsafe queue-grid lanes are rejected");
    assert!(errors.iter().any(|error| {
        error.path == "queue_footprints[0].lanes" && error.message.contains("lane spacing")
    }));
}

#[test]
fn validation_rejects_incomplete_or_obstructed_queue_footprints() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_incomplete_or_obstructed_queue_footprints.json"
    );
    let scenario = decode_scenario(source).expect("invalid queue-footprint source parses");
    let errors = validate(&scenario).expect_err("invalid queue footprints are rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "queue_footprints[0].slots")
    );
    assert!(errors.iter().any(|error| {
        error.path == "queue_footprints[1].from" && error.message.contains("segment")
    }));
    assert!(errors.iter().any(|error| {
        error.path == "queue_footprints[2].resource" && error.message.contains("authored capacity")
    }));
}

#[test]
fn capacity_state_changes_round_trip_log_and_limit_the_effective_rate() {
    let source = include_str!(
        "fixtures/semantics/capacity_state_changes_round_trip_log_and_limit_the_effective_rate.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("capacity-state source validates");
    assert_eq!(scenario.connector_service_rate_at("down", 0.0), Some(0.5));
    assert_eq!(scenario.connector_service_rate_at("down", 3.0), Some(1.0));
    assert_eq!(scenario.exit_capacity_at("street", 1.0), Some(2.0));
    assert_eq!(scenario.exit_capacity_at("street", 2.0), Some(0.1));
    assert_eq!(scenario.gate_service_rate_at("side_gate", 0.0), Some(1.0));
    assert_eq!(scenario.gate_service_rate_at("side_gate", 1.0), Some(0.5));
    let formatted = encode_scenario(&scenario).unwrap();
    assert_eq!(
        decode_scenario(&formatted).expect("formatted source parses"),
        scenario
    );

    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert_eq!(bundle.metrics.evacuated_agents, 2);
    let initial_physical_events: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.time_s == 0.0 && event.subject == "down")
        .map(|event| event.kind.as_str())
        .collect();
    assert_eq!(
        initial_physical_events,
        vec!["connector_state_changed", "connector_capacity_changed"]
    );
    let gate_events_at_one_second: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| {
            (event.time_s - 1.0).abs() < 1e-9
                && matches!(
                    event.kind.as_str(),
                    "gate_state_changed" | "gate_capacity_changed"
                )
        })
        .map(|event| event.kind.as_str())
        .collect();
    assert_eq!(
        gate_events_at_one_second,
        vec!["gate_state_changed", "gate_capacity_changed"]
    );
    assert!(bundle.events.iter().any(|event| {
        event.kind == "connector_capacity_changed" && event.subject == "down" && event.time_s == 0.0
    }));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "gate_capacity_changed"
            && event.subject == "side_gate"
            && (event.time_s - 1.0).abs() < 1e-9
    }));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "exit_capacity_changed"
            && event.subject == "street"
            && (event.time_s - 2.0).abs() < 1e-9
    }));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "connector_capacity_changed"
            && event.subject == "down"
            && (event.time_s - 3.0).abs() < 1e-9
    }));
}

#[test]
fn validation_rejects_capacity_changes_without_a_constrained_resource() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_capacity_changes_without_a_constrained_resource.json"
    );
    let scenario = decode_scenario(source).expect("source parses before semantic validation");
    let errors = validate(&scenario).expect_err("invalid capacity-state resources are rejected");
    assert!(errors.iter().any(|error| {
        error.path == "connector_capacity_states[0].connector"
            && error.message.contains("authored baseline capacity")
    }));
    assert!(errors.iter().any(|error| {
        error.path == "exit_capacity_states[0].exit"
            && error.message.contains("authored baseline capacity")
    }));
    assert!(
        errors
            .iter()
            .any(|error| error.path == "gate_capacity_states[0].gate")
    );
}

#[test]
fn gate_closure_reroutes_to_an_open_alternative_exit() {
    let source =
        include_str!("fixtures/semantics/gate_closure_reroutes_to_an_open_alternative_exit.json");
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("gate-state source validates");
    assert!(!scenario.gate_open_at("east_gate", 2.0));
    assert!(scenario.gate_open_at("west_gate", 2.0));
    assert_eq!(
        decode_scenario(&encode_scenario(&scenario).unwrap()).expect("formatted source parses"),
        scenario
    );

    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert_eq!(bundle.metrics.evacuated_by_exit.get("west"), Some(&1));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "gate_state_changed"
            && event.subject == "east_gate"
            && (event.time_s - 2.0).abs() < 1e-9
    }));
    assert!(
        bundle
            .events
            .iter()
            .any(|event| { event.kind == "gate_processed" && event.detail == "west_gate" })
    );
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| { event.kind == "gate_processed" && event.detail == "east_gate" })
    );
}

#[test]
fn a_false_gate_closure_message_reroutes_until_its_correction() {
    let source = include_str!(
        "fixtures/semantics/a_false_gate_closure_message_reroutes_until_its_correction.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("gate-information source validates");
    assert_eq!(
        decode_scenario(&encode_scenario(&scenario).unwrap()).expect("formatted source parses"),
        scenario
    );

    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert_eq!(bundle.metrics.evacuated_by_exit.get("primary"), Some(&1));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "route_recomputed"
            && (event.time_s - 1.0).abs() < 1e-9
            && event.detail == "gate:primary_gate"
    }));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "route_recomputed"
            && (event.time_s - 2.0).abs() < 1e-9
            && event.detail.is_empty()
    }));
    assert!(
        bundle
            .events
            .iter()
            .any(|event| { event.kind == "gate_processed" && event.detail == "primary_gate" })
    );
    assert_eq!(
        bundle.metrics.information_delivery["false_gate_closure"].accepted_agents,
        1
    );
    assert_eq!(
        bundle.metrics.information_delivery["correction"].accepted_agents,
        1
    );
}

#[test]
fn an_initially_closed_gate_recovers_when_it_reopens() {
    let source =
        include_str!("fixtures/semantics/an_initially_closed_gate_recovers_when_it_reopens.json");
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("gate-state source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let before_reopening = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 1.0).abs() < 1e-9)
        .expect("trace contains one-second frame");
    assert_eq!(
        before_reopening.agents[0].state,
        chiyoda_core::AgentState::WaitingForRoute
    );
    assert!(bundle.events.iter().any(|event| {
        event.kind == "gate_state_changed"
            && event.subject == "fare_gate"
            && (event.time_s - 2.0).abs() < 1e-9
    }));
    assert_eq!(bundle.metrics.evacuated_agents, 1);
}

#[test]
fn gate_closure_does_not_revoke_a_completed_gate_passage() {
    let source = include_str!(
        "fixtures/semantics/gate_closure_does_not_revoke_a_completed_gate_passage.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("gate-state source validates");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(bundle.events.iter().any(|event| {
        event.kind == "gate_processed" && event.detail == "fare_gate" && event.time_s < 2.0
    }));
    assert_eq!(bundle.metrics.evacuated_agents, 1);
}

#[test]
fn validation_rejects_a_gate_state_for_an_unknown_gate() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_a_gate_state_for_an_unknown_gate.json");
    let scenario = decode_scenario(source).expect("source parses before semantic validation");
    let errors = validate(&scenario).expect_err("unknown gate state must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "gate_states[0].gate")
    );
}

#[test]
fn json_preserves_high_precision_authored_values() {
    let source =
        include_str!("fixtures/semantics/json_preserves_high_precision_authored_values.json");
    let scenario = decode_scenario(source).expect("source parses");
    let formatted = encode_scenario(&scenario).unwrap();

    assert!(formatted.contains("1.1477832456710213"));
    assert_eq!(
        decode_scenario(&formatted).expect("formatted source parses"),
        scenario
    );
}

#[test]
fn connector_exclusion_routes_agents_to_an_eligible_connector() {
    let source = include_str!(
        "fixtures/semantics/connector_exclusion_routes_agents_to_an_eligible_connector.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("eligible route validates");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(
        bundle.events.iter().any(|event| {
            event.kind == "connector_boarding" && event.detail == "accessible_ramp"
        })
    );
}

#[test]
fn alternative_exit_is_selected_when_the_primary_route_is_closed() {
    let source = include_str!(
        "fixtures/semantics/alternative_exit_is_selected_when_the_primary_route_is_closed.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("all declared final exits are statically reachable");
    let bundle = run(&scenario, RunOptions::default()).expect("fallback route runs");
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "evacuated" && event.detail == "fallback")
    );
}

#[test]
fn alternative_exit_is_reselected_when_a_connector_closes() {
    let source = include_str!(
        "fixtures/semantics/alternative_exit_is_reselected_when_a_connector_closes.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("rerouted fallback runs");
    assert!(bundle.events.iter().any(|event| {
        event.kind == "connector_state_changed"
            && event.subject == "down"
            && (event.time_s - 1.0).abs() < 1e-9
    }));
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "evacuated" && event.detail == "fallback")
    );
}

#[test]
fn exit_closure_reroutes_an_agent_to_an_alternative_exit() {
    let source = include_str!(
        "fixtures/semantics/exit_closure_reroutes_an_agent_to_an_alternative_exit.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("rerouted fallback runs");
    let encoded = serde_json::to_value(&bundle).expect("bundle serializes to JSON");
    assert!(encoded["scenario"]["scenario"].get("exit_states").is_some());
    let round_trip: RunBundle = serde_json::from_value(encoded).expect("bundle deserializes");
    assert!(round_trip.verifies_hash());
    assert!(bundle.events.iter().any(|event| {
        event.kind == "exit_state_changed"
            && event.subject == "primary"
            && event.detail == "closure: closed"
    }));
    assert_eq!(
        bundle.metrics.evacuated_by_exit.get("fallback"),
        Some(&1),
        "the metric must attribute the rerouted evacuation to its final exit"
    );
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "evacuated" && event.detail == "fallback")
    );
}

#[test]
fn accepted_exit_misinformation_reroutes_and_a_correction_restores_the_physical_exit() {
    let source = include_str!(
        "fixtures/semantics/accepted_exit_misinformation_reroutes_and_a_correction_restores_the_physical_exit.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(bundle.events.iter().any(|event| {
        event.kind == "message_received"
            && event.detail.contains("exit `primary` closed")
            && event.detail.contains("accepted=true")
    }));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "route_recomputed"
            && event.subject == "passengers:0"
            && event.detail.contains("exit:primary")
    }));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "countermeasure_received"
            && event.subject == "passengers:0"
            && event.detail.contains("accepted=true")
    }));
    let message_delivery = bundle
        .metrics
        .information_delivery
        .get("false_primary_closure")
        .expect("message delivery is recorded");
    assert_eq!(message_delivery.received_agents, 1);
    assert_eq!(message_delivery.accepted_agents, 1);
    let correction_delivery = bundle
        .metrics
        .information_delivery
        .get("correction")
        .expect("countermeasure delivery is recorded");
    assert_eq!(correction_delivery.received_agents, 1);
    assert_eq!(correction_delivery.accepted_agents, 1);
    let mut legacy_value = serde_json::to_value(&bundle).expect("bundle serializes");
    legacy_value["metrics"]
        .as_object_mut()
        .expect("metrics is an object")
        .remove("information_delivery");
    legacy_value["bundle_hash"] = serde_json::Value::String(String::new());
    let legacy_unsigned: RunBundle =
        serde_json::from_value(legacy_value.clone()).expect("legacy bundle deserializes");
    legacy_value["bundle_hash"] = serde_json::Value::String(bundle_hash(&legacy_unsigned));
    let legacy: RunBundle = serde_json::from_value(legacy_value).expect("legacy bundle reloads");
    assert!(legacy.metrics.information_delivery.is_empty());
    assert!(legacy.verifies_hash());
    assert!(
        bundle
            .events
            .iter()
            .any(|event| { event.kind == "evacuated" && event.detail == "primary" })
    );
}

#[test]
fn an_exit_closure_during_transit_reroutes_on_arrival() {
    let source =
        include_str!("fixtures/semantics/an_exit_closure_during_transit_reroutes_on_arrival.json");
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(bundle.events.iter().any(|event| {
        event.kind == "route_recomputed"
            && event.subject == "passengers:0"
            && event.detail.contains("exit:primary")
    }));
    assert!(
        bundle
            .events
            .iter()
            .any(|event| { event.kind == "evacuated" && event.detail == "fallback" })
    );
}

#[test]
fn exit_metrics_attribute_each_completed_evacuation_to_its_final_exit() {
    let source = include_str!(
        "fixtures/semantics/exit_metrics_attribute_each_completed_evacuation_to_its_final_exit.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("both groups evacuate");
    assert_eq!(bundle.metrics.evacuated_agents, 2);
    assert_eq!(bundle.metrics.evacuated_by_exit.get("west"), Some(&1));
    assert_eq!(bundle.metrics.evacuated_by_exit.get("east"), Some(&1));
    assert_eq!(
        bundle.metrics.clearance_time_s,
        bundle.metrics.last_exit_time_s
    );
    assert_eq!(
        bundle.metrics.evacuated_by_exit.values().sum::<u32>(),
        bundle.metrics.evacuated_agents
    );
}

#[test]
fn final_state_metrics_explain_agents_remaining_at_the_time_limit() {
    let source = include_str!(
        "fixtures/semantics/final_state_metrics_explain_agents_remaining_at_the_time_limit.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert_eq!(bundle.metrics.evacuated_agents, 0);
    assert_eq!(bundle.metrics.remaining_by_state.get("moving"), Some(&1));
    assert_eq!(bundle.metrics.clearance_time_s, None);
    assert_eq!(bundle.metrics.last_exit_time_s, None);
    let mut legacy_value = serde_json::to_value(&bundle).expect("bundle serializes");
    legacy_value["metrics"]
        .as_object_mut()
        .expect("metrics is an object")
        .remove("remaining_by_state");
    legacy_value["metrics"]
        .as_object_mut()
        .expect("metrics is an object")
        .remove("last_exit_time_s");
    legacy_value["bundle_hash"] = serde_json::Value::String(String::new());
    let legacy_unsigned: RunBundle =
        serde_json::from_value(legacy_value.clone()).expect("legacy bundle deserializes");
    legacy_value["bundle_hash"] = serde_json::Value::String(bundle_hash(&legacy_unsigned));
    let legacy: RunBundle = serde_json::from_value(legacy_value).expect("legacy bundle reloads");
    assert!(legacy.metrics.remaining_by_state.is_empty());
    assert_eq!(legacy.metrics.last_exit_time_s, None);
    assert!(legacy.verifies_hash());
}

#[test]
fn partial_evacuation_records_last_exit_but_not_system_clearance() {
    let source = include_str!(
        "fixtures/semantics/partial_evacuation_records_last_exit_but_not_system_clearance.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");

    assert_eq!(bundle.metrics.total_agents, 2);
    assert_eq!(bundle.metrics.evacuated_agents, 1);
    assert_eq!(bundle.metrics.clearance_time_s, None);
    assert_eq!(bundle.metrics.last_exit_time_s, Some(3.0));
}

#[test]
fn exit_reopening_recovers_agents_waiting_for_a_route() {
    let source =
        include_str!("fixtures/semantics/exit_reopening_recovers_agents_waiting_for_a_route.json");
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("waiting route recovers");
    assert!(bundle.trace.iter().any(|frame| {
        frame
            .agents
            .iter()
            .any(|agent| agent.state == chiyoda_core::AgentState::WaitingForRoute)
    }));
    assert!(bundle.events.iter().any(|event| {
        event.kind == "exit_state_changed"
            && event.subject == "street"
            && event.detail == "reopen: open"
    }));
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "evacuated" && event.detail == "street")
    );
}

#[test]
fn alternative_exit_selection_prices_the_gate_path_to_a_final_exit() {
    let source = include_str!(
        "fixtures/semantics/alternative_exit_selection_prices_the_gate_path_to_a_final_exit.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "evacuated" && event.detail == "fallback")
    );
}

#[test]
fn alternative_exit_ties_respect_declaration_order() {
    let source =
        include_str!("fixtures/semantics/alternative_exit_ties_respect_declaration_order.json");
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "evacuated" && event.detail == "first")
    );
}

#[test]
fn validation_rejects_an_unknown_alternative_exit() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_an_unknown_alternative_exit.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("unknown alternative exits must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "agents[0].alternative_destinations[0]")
    );
}

#[test]
fn validation_rejects_an_exit_state_for_an_unknown_exit() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_an_exit_state_for_an_unknown_exit.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("unknown exit states must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "exit_states[0].exit")
    );
}

#[test]
fn validation_rejects_a_route_when_all_connector_classes_are_excluded() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_a_route_when_all_connector_classes_are_excluded.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("ineligible route must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "agents[0].destination")
    );
}

#[test]
fn runtime_is_reproducible_and_information_recomputes_routes() {
    let mut scenario = generator::scenario(73).expect("valid generated scenario");
    scenario.exit_states.clear();
    let first = run(
        &scenario,
        RunOptions {
            trace_every_steps: 20,
        },
    )
    .expect("run one");
    let second = run(
        &scenario,
        RunOptions {
            trace_every_steps: 20,
        },
    )
    .expect("run two");

    assert_eq!(first.bundle_hash, second.bundle_hash);
    assert!(first.verifies_hash());
    let encoded = serde_json::to_value(&first).expect("bundle serializes to JSON");
    assert!(
        encoded["scenario"]["scenario"].get("exit_states").is_none(),
        "an empty state collection must preserve the existing bundle encoding"
    );
    let serialized = serde_json::to_string(&first).expect("bundle serializes");
    let round_trip: RunBundle = serde_json::from_str(&serialized).expect("bundle deserializes");
    assert!(round_trip.verifies_hash());
    assert!(
        first
            .events
            .iter()
            .any(|event| event.kind == "route_recomputed")
    );
}

#[test]
fn route_selection_minimizes_nominal_travel_time_not_connector_hops() {
    let source = include_str!(
        "fixtures/semantics/route_selection_minimizes_nominal_travel_time_not_connector_hops.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let boardings: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "connector_boarding")
        .map(|event| event.detail.as_str())
        .collect();
    assert_eq!(boardings, ["upper_stair", "lower_stair"]);
}

#[test]
fn escalator_belt_speed_is_used_by_nominal_route_selection() {
    let source = include_str!(
        "fixtures/semantics/escalator_belt_speed_is_used_by_nominal_route_selection.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(
        bundle.events.iter().any(|event| {
            event.kind == "connector_boarding" && event.detail == "down_escalator"
        })
    );
}

#[test]
fn validation_rejects_non_positive_escalator_belt_speed() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_non_positive_escalator_belt_speed.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("zero belt speed must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "connectors[0].belt_speed_mps")
    );
}

#[test]
fn connector_clearance_routes_taller_agents_to_an_accessible_connector() {
    let source = include_str!(
        "fixtures/semantics/connector_clearance_routes_taller_agents_to_an_accessible_connector.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("height-aware route validates");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(bundle.events.iter().any(|event| {
        event.kind == "connector_boarding" && event.detail == "accessible_backup"
    }));
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| event.kind == "connector_boarding" && event.detail == "low_primary")
    );
}

#[test]
fn validation_rejects_a_route_without_sufficient_connector_clearance() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_a_route_without_sufficient_connector_clearance.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("height-blocked route must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "agents[0].destination")
    );
}

#[test]
fn validation_rejects_non_positive_connector_clearance() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_non_positive_connector_clearance.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("zero clearance must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "connectors[0].clearance_height_m")
    );
}

#[test]
fn connector_state_change_closes_a_route_even_when_a_message_is_untrusted() {
    let source = include_str!(
        "fixtures/semantics/connector_state_change_closes_a_route_even_when_a_message_is_untrusted.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("truthful closure validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert!(bundle.events.iter().any(|event| {
        event.kind == "connector_state_changed"
            && event.subject == "primary"
            && event.detail == "primary_closure: closed"
    }));
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "connector_boarding" && event.detail == "backup")
    );
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| event.kind == "connector_boarding" && event.detail == "primary")
    );
}

#[test]
fn physical_reopening_recovers_an_agent_waiting_for_a_route() {
    let source = include_str!(
        "fixtures/semantics/physical_reopening_recovers_an_agent_waiting_for_a_route.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert_eq!(
        bundle.trace[1].agents[0].state,
        chiyoda_core::AgentState::WaitingForRoute
    );
    let boarding = bundle
        .events
        .iter()
        .find(|event| event.kind == "connector_boarding")
        .expect("agent boards after the reopening");
    assert_eq!(boarding.detail, "primary");
    assert!((boarding.time_s - 3.0).abs() < 1e-9);
}

#[test]
fn initial_physical_closure_waits_for_a_scheduled_reopening() {
    let source = include_str!(
        "fixtures/semantics/initial_physical_closure_waits_for_a_scheduled_reopening.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("future reopening makes the run executable");
    assert_eq!(
        bundle.trace[0].agents[0].state,
        chiyoda_core::AgentState::WaitingForRoute
    );
    assert!(bundle.events.iter().any(|event| {
        event.kind == "connector_state_changed"
            && event.time_s == 0.0
            && event.detail == "primary_initially_closed: closed"
    }));
    assert!(
        bundle.events.iter().any(|event| {
            event.kind == "connector_boarding" && (event.time_s - 3.0).abs() < 1e-9
        })
    );
}

#[test]
fn validation_rejects_a_truthful_message_that_disagrees_with_physical_state() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_a_truthful_message_that_disagrees_with_physical_state.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("incorrect truth label must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "messages[0].truthful")
    );
}

#[test]
fn validation_checks_exit_message_truth_and_references() {
    let source =
        include_str!("fixtures/semantics/validation_checks_exit_message_truth_and_references.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("invalid exit claims must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "messages[0].truthful")
    );
    assert!(errors.iter().any(|error| {
        error.path == "messages[1].claim" && error.message.contains("unknown exit `missing`")
    }));
}

#[test]
fn validation_checks_gate_message_truth_and_references() {
    let source =
        include_str!("fixtures/semantics/validation_checks_gate_message_truth_and_references.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("invalid gate claims must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "messages[0].truthful")
    );
    assert!(errors.iter().any(|error| {
        error.path == "messages[1].claim" && error.message.contains("unknown gate `absent`")
    }));
}

#[test]
fn validation_rejects_a_countermeasure_that_precedes_its_message() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_a_countermeasure_that_precedes_its_message.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("a correction cannot precede its message");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "countermeasures[0].at_s")
    );
}

#[test]
fn validation_rejects_a_peer_countermeasure() {
    let mut scenario = generator::scenario(73).unwrap();
    scenario.countermeasures[0].source = chiyoda_core::model::InformationSource::Peer;
    assert!(
        validate(&scenario)
            .unwrap_err()
            .iter()
            .any(|error| error.path == "countermeasures[0].source")
    );
}

#[test]
fn rectangular_obstacles_are_never_crossed_by_on_surface_motion() {
    let source = include_str!(
        "fixtures/semantics/rectangular_obstacles_are_never_crossed_by_on_surface_motion.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert_eq!(bundle.metrics.evacuated_agents, 1);
    let obstacle = &scenario.obstacles[0];
    assert!(bundle.trace.iter().all(|frame| {
        frame.agents.iter().all(|agent| {
            !obstacle.contains(chiyoda_core::model::Point3 {
                x_m: agent.x_m,
                y_m: agent.y_m,
                z_m: agent.z_m,
            })
        })
    }));
}

#[test]
fn route_selection_includes_obstacle_aware_walking_time() {
    let source = include_str!(
        "fixtures/semantics/route_selection_includes_obstacle_aware_walking_time.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    assert!(
        bundle
            .events
            .iter()
            .any(|event| { event.kind == "connector_boarding" && event.detail == "clear_far" })
    );
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| { event.kind == "connector_boarding" && event.detail == "blocked_near" })
    );
}

#[test]
fn validation_rejects_exit_inside_an_obstacle() {
    let source = include_str!("fixtures/semantics/validation_rejects_exit_inside_an_obstacle.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("an occupied exit must be rejected");
    assert!(errors.iter().any(|error| error.path == "exits[0].at"));
}

#[test]
fn validation_rejects_an_intermediate_spawn_inside_an_obstacle() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_an_intermediate_spawn_inside_an_obstacle.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("intermediate spawn must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "agents[0].spawn[1]")
    );
}

#[test]
fn validation_rejects_spawn_without_navigation_clearance() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_spawn_without_navigation_clearance.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("spawn clearance must be rejected");
    assert!(errors.iter().any(|error| {
        error.path == "agents[0].at" && error.message.contains("does not clear obstacle")
    }));
}

#[test]
fn validation_rejects_overlapping_deterministic_spawns_across_groups() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_overlapping_deterministic_spawns_across_groups.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("overlapping spawn discs must be rejected");

    assert!(errors.iter().any(|error| {
        error.path == "agents[1].at"
            && error
                .message
                .contains("overlaps deterministic spawn `agents[0].at`")
    }));
}

#[test]
fn released_agents_become_active_after_their_declared_time() {
    let source = include_str!(
        "fixtures/semantics/released_agents_become_active_after_their_declared_time.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert!((bundle.trace[1].time_s - 1.0).abs() < 1e-9);
    assert_eq!(
        bundle.trace[1].agents[0].state,
        chiyoda_core::AgentState::WaitingToDepart
    );
    assert!((bundle.trace[2].time_s - 2.0).abs() < 1e-9);
    assert_eq!(
        bundle.trace[2].agents[0].state,
        chiyoda_core::AgentState::Moving
    );
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "agent_released" && (event.time_s - 2.0).abs() < 1e-9)
    );
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| event.kind == "message_received")
    );
    assert_eq!(bundle.metrics.evacuated_agents, 1);
}

#[test]
fn scheduled_release_waits_for_dynamic_spawn_clearance() {
    let source =
        include_str!("fixtures/semantics/scheduled_release_waits_for_dynamic_spawn_clearance.json");
    let scenario = decode_scenario(source).expect("release-clearance fixture parses");
    validate(&scenario).expect("release-clearance fixture validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("release-clearance fixture runs");

    assert!(bundle.events.iter().any(|event| {
        event.kind == "agent_release_deferred_for_clearance"
            && event.subject == "delayed:0"
            && (event.time_s - 0.2).abs() < 1e-9
    }));
    let release_time_s = bundle
        .events
        .iter()
        .find(|event| event.kind == "agent_released" && event.subject == "delayed:0")
        .map(|event| event.time_s)
        .expect("delayed agent is eventually admitted");
    assert!(release_time_s > 0.2);
    let at_authored_release = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 0.2).abs() < 1e-9)
        .expect("trace includes the authored release boundary");
    assert_eq!(
        at_authored_release
            .agents
            .iter()
            .find(|agent| agent.id == "delayed:0")
            .expect("delayed agent is traced")
            .state,
        chiyoda_core::AgentState::WaitingToDepart
    );
}

#[test]
fn release_cadence_staggers_agents_deterministically_and_round_trips() {
    let source = include_str!(
        "fixtures/semantics/release_cadence_staggers_agents_deterministically_and_round_trips.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("cadence source validates");
    assert_eq!(scenario.agents[0].release_interval_s, Some(2.0));
    let formatted = encode_scenario(&scenario).unwrap();
    assert!(formatted.contains("\"release_interval_s\": 2.0"));
    assert_eq!(
        decode_scenario(&formatted).expect("formatted source parses"),
        scenario
    );

    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let release_times: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "agent_released")
        .map(|event| event.time_s)
        .collect();
    assert_eq!(release_times, vec![1.0, 3.0, 5.0]);
    let at_two_seconds = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 2.0).abs() < 1e-9)
        .expect("trace contains two-second frame");
    assert_eq!(
        at_two_seconds
            .agents
            .iter()
            .filter(|agent| agent.state == chiyoda_core::AgentState::WaitingToDepart)
            .count(),
        2
    );
}

#[test]
fn release_batches_admit_clear_agents_at_each_cadence_instant() {
    let source = include_str!(
        "fixtures/semantics/release_batches_admit_clear_agents_at_each_cadence_instant.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("batched cadence source validates");
    assert_eq!(scenario.agents[0].release_batch_size, Some(2));
    let formatted = encode_scenario(&scenario).unwrap();
    assert!(formatted.contains("\"release_batch_size\": 2"));
    assert_eq!(
        decode_scenario(&formatted).expect("formatted source parses"),
        scenario
    );

    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let release_times: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "agent_released")
        .map(|event| event.time_s)
        .collect();
    // The 0.45 motion law leaves one eligible spawn occupied through t=4.
    // Eligibility is unchanged; actual admission still waits for clearance.
    assert_eq!(release_times, vec![1.0, 1.0, 3.0, 5.0, 5.0]);
    let spawn_points: Vec<_> = scenario.agents[0].spawn_positions().collect();
    for event in bundle
        .events
        .iter()
        .filter(|event| event.kind == "agent_released")
    {
        let ordinal: usize = event.subject.rsplit(':').next().unwrap().parse().unwrap();
        let previous = bundle
            .trace
            .iter()
            .rev()
            .find(|frame| frame.time_s < event.time_s)
            .unwrap();
        assert!(
            previous
                .agents
                .iter()
                .filter(|agent| !matches!(
                    agent.state,
                    chiyoda_core::AgentState::WaitingToDepart
                        | chiyoda_core::AgentState::Evacuated
                        | chiyoda_core::AgentState::InTransit
                ))
                .all(|other| {
                    let point = chiyoda_core::model::Point3::new(other.x_m, other.y_m, other.z_m);
                    spawn_points[ordinal].distance(point)
                        >= scenario.agents[0].radius_m * 2.0 - 2e-9
                })
        );
    }
    assert!(bundle.events.iter().any(|event| {
        event.kind == "agent_release_deferred_for_clearance" && (event.time_s - 3.0).abs() < 1e-9
    }));
}

#[test]
fn validation_rejects_a_zero_release_batch_and_validation_rejects_an_unpaired_batch() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_a_zero_release_batch_and_validation_rejects_an_unpaired_batch.json"
    );
    let scenario = decode_scenario(source).expect("source parses before semantic validation");
    let errors = validate(&scenario).expect_err("zero batch must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "agents[0].release_batch_size")
    );

    let mut scenario = scenario;
    scenario.agents[0].release_interval_s = None;
    scenario.agents[0].release_batch_size = Some(2);
    assert!(
        validate(&scenario)
            .unwrap_err()
            .iter()
            .any(|error| error.path == "agents[0].release_batch_size")
    );
}

#[test]
fn validation_rejects_agent_release_after_the_scenario() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_agent_release_after_the_scenario.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("release outside duration must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "agents[0].release_at_s")
    );
}

#[test]
fn validation_rejects_a_release_cadence_that_extends_past_duration() {
    let source = include_str!(
        "fixtures/semantics/validation_rejects_a_release_cadence_that_extends_past_duration.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("final release outside duration must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "agents[0].final_release_at_s")
    );
}

#[test]
fn authored_connector_capacity_creates_a_deterministic_queue() {
    let source = include_str!(
        "fixtures/semantics/authored_connector_capacity_creates_a_deterministic_queue.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let boarding = bundle
        .events
        .iter()
        .find(|event| event.kind == "connector_boarding")
        .expect("agent boards once a token is accrued");
    assert!((boarding.time_s - 2.0).abs() < 1e-9);
    assert_eq!(
        bundle.trace[1].agents[0].state,
        chiyoda_core::AgentState::WaitingForConnector
    );
    assert_eq!(bundle.metrics.queued_for_connector_agents, 1);
    let queue_metrics = bundle
        .metrics
        .queue_metrics
        .as_ref()
        .expect("current run records queue telemetry");
    assert_eq!(queue_metrics.connector.ever_queued_agents, 1);
    assert!((queue_metrics.connector.cumulative_wait_agent_seconds - 1.0).abs() < 1e-9);
    assert_eq!(queue_metrics.connector.peak_waiting_agents, 1);
    let attributed = queue_metrics
        .by_resource
        .as_ref()
        .expect("current run attributes queue telemetry to resources");
    assert_eq!(attributed.connectors.len(), 1);
    assert_eq!(attributed.connectors["down"], queue_metrics.connector);
    assert!(bundle.events.iter().any(|event| {
        event.kind == "queue_entered_connector"
            && event.subject == "passengers:0"
            && event.detail == "down"
    }));
}

#[test]
fn validation_rejects_non_positive_connector_capacity() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_non_positive_connector_capacity.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("zero connector capacity must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "connectors[0].capacity_per_s")
    );
}

#[test]
fn authored_exit_capacity_creates_a_deterministic_queue() {
    let source = include_str!(
        "fixtures/semantics/authored_exit_capacity_creates_a_deterministic_queue.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let evacuation = bundle
        .events
        .iter()
        .find(|event| event.kind == "evacuated")
        .expect("agent evacuates once a token is accrued");
    assert!((evacuation.time_s - 2.0).abs() < 1e-9);
    assert_eq!(
        bundle.trace[1].agents[0].state,
        chiyoda_core::AgentState::WaitingForExit
    );
    assert_eq!(bundle.metrics.queued_for_exit_agents, 1);
    let queue_metrics = bundle
        .metrics
        .queue_metrics
        .as_ref()
        .expect("current run records queue telemetry");
    assert_eq!(queue_metrics.exit.ever_queued_agents, 1);
    assert!((queue_metrics.exit.cumulative_wait_agent_seconds - 1.0).abs() < 1e-9);
    assert_eq!(queue_metrics.exit.peak_waiting_agents, 1);
    let attributed = queue_metrics
        .by_resource
        .as_ref()
        .expect("current run attributes queue telemetry to resources");
    assert_eq!(attributed.exits.len(), 1);
    assert_eq!(attributed.exits["street"], queue_metrics.exit);
    assert!(bundle.events.iter().any(|event| {
        event.kind == "queue_entered_exit"
            && event.subject == "passengers:0"
            && event.detail == "street"
    }));
}

#[test]
fn validation_rejects_non_positive_exit_capacity() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_non_positive_exit_capacity.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("zero exit capacity must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.path == "exits[0].capacity_per_s")
    );
}

#[test]
fn information_trust_probability_respects_zero_and_one_extremes() {
    let source = include_str!(
        "fixtures/semantics/information_trust_probability_respects_zero_and_one_extremes.json"
    );
    let untrusted = run(
        &decode_scenario(source).expect("source parses"),
        RunOptions::default(),
    )
    .expect("run succeeds");
    assert!(
        untrusted
            .events
            .iter()
            .any(|event| { event.kind == "connector_boarding" && event.detail == "primary" })
    );
    assert!(
        !untrusted
            .events
            .iter()
            .any(|event| event.kind == "route_recomputed")
    );
    assert_eq!(
        untrusted.metrics.information_delivery["closure"].received_agents,
        1
    );
    assert_eq!(
        untrusted.metrics.information_delivery["closure"].accepted_agents,
        0
    );

    let trusted_source = source.replace("\"trust\": 0.0", "\"trust\": 1.0");
    let trusted = run(
        &decode_scenario(&trusted_source).expect("source parses"),
        RunOptions::default(),
    )
    .expect("run succeeds");
    assert!(
        trusted
            .events
            .iter()
            .any(|event| { event.kind == "connector_boarding" && event.detail == "backup" })
    );
    assert!(
        trusted
            .events
            .iter()
            .any(|event| event.kind == "route_recomputed")
    );
    assert_eq!(
        trusted.metrics.information_delivery["closure"].received_agents,
        1
    );
    assert_eq!(
        trusted.metrics.information_delivery["closure"].accepted_agents,
        1
    );
}

#[test]
fn explicit_sampling_key_preserves_trust_draws_across_renamed_interventions() {
    let source = include_str!(
        "fixtures/semantics/explicit_sampling_key_preserves_trust_draws_across_renamed_interventions.json"
    );
    let candidate_source = source.replace("baseline_notice", "candidate_notice");
    let baseline = run(
        &decode_scenario(source).expect("baseline parses"),
        RunOptions::default(),
    )
    .expect("baseline runs");
    let candidate = run(
        &decode_scenario(&candidate_source).expect("candidate parses"),
        RunOptions::default(),
    )
    .expect("candidate runs");

    assert_eq!(
        baseline.metrics.information_delivery["baseline_notice"].accepted_agents,
        candidate.metrics.information_delivery["candidate_notice"].accepted_agents
    );
}

#[test]
fn required_waypoint_is_reached_before_the_final_exit() {
    let source =
        include_str!("fixtures/semantics/required_waypoint_is_reached_before_the_final_exit.json");
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    let waypoint = bundle
        .events
        .iter()
        .find(|event| event.kind == "waypoint_reached")
        .expect("journey reaches waypoint");
    let evacuation = bundle
        .events
        .iter()
        .find(|event| event.kind == "evacuated")
        .expect("journey reaches exit");
    assert_eq!(waypoint.detail, "checkpoint");
    assert!(waypoint.time_s < evacuation.time_s);
}

#[test]
fn validation_rejects_unknown_journey_waypoint() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_unknown_journey_waypoint.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("unknown waypoint must be rejected");
    assert!(errors.iter().any(|error| error.path == "agents[0].via[0]"));
}

#[test]
fn validation_rejects_an_unreachable_required_waypoint() {
    let source =
        include_str!("fixtures/semantics/validation_rejects_an_unreachable_required_waypoint.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("unreachable waypoint must be rejected");
    assert!(errors.iter().any(|error| error.path == "agents[0].via[0]"));
}

#[test]
fn ordered_waypoints_are_reached_in_source_order() {
    let source =
        include_str!("fixtures/semantics/ordered_waypoints_are_reached_in_source_order.json");
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");
    let reached: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "waypoint_reached")
        .map(|event| event.detail.as_str())
        .collect();
    assert_eq!(reached, ["first", "second"]);
    assert_eq!(bundle.metrics.evacuated_agents, 1);
}

#[test]
fn waypoint_dwell_holds_an_agent_before_its_next_stage() {
    let source =
        include_str!("fixtures/semantics/waypoint_dwell_holds_an_agent_before_its_next_stage.json");
    let scenario = decode_scenario(source).expect("source parses");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert_eq!(
        bundle.trace[3].agents[0].state,
        chiyoda_core::AgentState::WaitingAtWaypoint
    );
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "waypoint_wait_started")
    );
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "waypoint_wait_ended")
    );
    assert_eq!(bundle.metrics.evacuated_agents, 1);
}

#[test]
fn lift_capacity_serializes_boarding() {
    let source = include_str!("fixtures/semantics/lift_capacity_serializes_boarding.json");
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let boardings: Vec<_> = bundle
        .events
        .iter()
        .filter(|event| event.kind == "connector_boarding")
        .collect();
    assert_eq!(boardings.len(), 2);
    assert!(boardings[1].time_s > boardings[0].time_s);
    assert_eq!(bundle.metrics.queued_for_lift_agents, 1);
    let queue_metrics = bundle
        .metrics
        .queue_metrics
        .as_ref()
        .expect("current run records queue telemetry");
    let attributed = queue_metrics
        .by_resource
        .as_ref()
        .expect("current run attributes queue telemetry to resources");
    assert_eq!(attributed.lifts.len(), 1);
    assert_eq!(attributed.lifts["lift_a"], queue_metrics.lift);
    assert!(
        bundle
            .events
            .iter()
            .any(|event| { event.kind == "queue_entered_lift" && event.detail == "lift_a" })
    );
}

#[test]
fn gate_service_rate_creates_a_deterministic_queue() {
    let source =
        include_str!("fixtures/semantics/gate_service_rate_creates_a_deterministic_queue.json");
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert!(bundle.metrics.queued_for_gate_agents > 0);
    assert!(bundle.trace.iter().any(|frame| {
        frame
            .agents
            .iter()
            .any(|agent| agent.state == chiyoda_core::AgentState::WaitingForGate)
    }));
    let queue_metrics = bundle
        .metrics
        .queue_metrics
        .as_ref()
        .expect("current run records queue telemetry");
    assert_eq!(
        queue_metrics.gate.ever_queued_agents,
        bundle.metrics.queued_for_gate_agents
    );
    assert!(queue_metrics.gate.cumulative_wait_agent_seconds > 0.0);
    assert!(queue_metrics.gate.peak_waiting_agents > 0);
    let attributed = queue_metrics
        .by_resource
        .as_ref()
        .expect("current run attributes queue telemetry to resources");
    assert_eq!(attributed.gates.len(), 1);
    assert_eq!(attributed.gates["fare_gate"], queue_metrics.gate);
    let gate_queue_entries = bundle
        .events
        .iter()
        .filter(|event| event.kind == "queue_entered_gate" && event.detail == "fare_gate")
        .count();
    assert_eq!(
        u32::try_from(gate_queue_entries).expect("event count fits u32"),
        queue_metrics.gate.ever_queued_agents
    );
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "gate_processed")
    );
}

#[test]
fn ten_thousand_agents_execute_a_spatially_indexed_step() {
    let source = include_str!(
        "fixtures/semantics/ten_thousand_agents_execute_a_spatially_indexed_step.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("spawn extent validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    assert_eq!(bundle.metrics.total_agents, 10_000);
    assert_eq!(bundle.trace.len(), 2);
}

#[test]
fn local_separation_keeps_converging_agents_outside_their_authored_radii() {
    let source = include_str!(
        "fixtures/semantics/local_separation_keeps_converging_agents_outside_their_authored_radii.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let agents = &bundle.trace.last().expect("final trace frame").agents;
    let north = agents
        .iter()
        .find(|agent| agent.id == "north:0")
        .expect("north agent is traced");
    let south = agents
        .iter()
        .find(|agent| agent.id == "south:0")
        .expect("south agent is traced");

    let separation = (north.x_m - south.x_m).hypot(north.y_m - south.y_m);
    assert!(
        separation >= 0.6,
        "agents must remain at least the sum of their authored radii apart"
    );
    let movement = bundle
        .metrics
        .movement_metrics
        .as_ref()
        .expect("current bundles expose local-clearance telemetry");
    assert_eq!(movement.agents_with_local_clearance_adjustments, 2);
    assert_eq!(movement.local_clearance_adjustment_steps, 2);
    assert!(movement.cumulative_local_clearance_adjustment_m >= 0.6);
    assert!(
        movement.maximum_local_clearance_adjustment_m
            <= movement.cumulative_local_clearance_adjustment_m
    );
}

#[test]
fn local_separation_allows_abstract_connector_access() {
    let source =
        include_str!("fixtures/semantics/local_separation_allows_abstract_connector_access.json");
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let bundle = run(&scenario, RunOptions::default()).expect("run succeeds");

    assert_eq!(
        bundle
            .events
            .iter()
            .filter(|event| event.kind == "connector_boarding" && event.detail == "central_stair")
            .count(),
        24,
        "clearance-separated agents must be able to enter an abstract connector service point"
    );
}

#[test]
fn reciprocal_local_motion_prevents_a_swept_crossing_between_waypoints() {
    let source = include_str!(
        "fixtures/semantics/reciprocal_local_motion_prevents_a_swept_crossing_between_waypoints.json"
    );
    let scenario = decode_scenario(source).expect("source parses");
    validate(&scenario).expect("source validates");
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .expect("run succeeds");
    let start = &bundle.trace[0].agents;
    let end = &bundle.trace[1].agents;
    let north_start = start
        .iter()
        .find(|agent| agent.id == "northbound:0")
        .expect("north agent is traced");
    let north_end = end
        .iter()
        .find(|agent| agent.id == "northbound:0")
        .expect("north agent is traced");
    let south_start = start
        .iter()
        .find(|agent| agent.id == "southbound:0")
        .expect("south agent is traced");
    let south_end = end
        .iter()
        .find(|agent| agent.id == "southbound:0")
        .expect("south agent is traced");
    let relative_start = (
        north_start.x_m - south_start.x_m,
        north_start.y_m - south_start.y_m,
    );
    let relative_motion = (
        (north_end.x_m - north_start.x_m) - (south_end.x_m - south_start.x_m),
        (north_end.y_m - north_start.y_m) - (south_end.y_m - south_start.y_m),
    );
    let motion_squared = relative_motion
        .0
        .mul_add(relative_motion.0, relative_motion.1.powi(2));
    let nearest_time = if motion_squared > 0.0 {
        (-(relative_start.0 * relative_motion.0 + relative_start.1 * relative_motion.1)
            / motion_squared)
            .clamp(0.0, 1.0)
    } else {
        0.0
    };
    let nearest_x = relative_start.0 + relative_motion.0 * nearest_time;
    let nearest_y = relative_start.1 + relative_motion.1 * nearest_time;
    assert!(
        nearest_x.hypot(nearest_y) >= 0.6 - 1e-9,
        "the two one-step trajectories must not pass through one another"
    );
    let swept_audit = bundle
        .metrics
        .movement_metrics
        .as_ref()
        .expect("current runs retain local-motion telemetry")
        .swept_on_surface_clearance_audit
        .as_ref()
        .expect("current runs retain the analytic interval audit");
    assert_eq!(swept_audit.agents_with_swept_disc_overlaps, 0);
    assert_eq!(swept_audit.swept_disc_overlap_pair_steps, 0);
    assert!(swept_audit.maximum_swept_disc_overlap_m.abs() < f64::EPSILON);
}

#[test]
fn validation_rejects_unreachable_agents() {
    let source = include_str!("fixtures/semantics/validation_rejects_unreachable_agents.json");
    let scenario = decode_scenario(source).expect("source parses");
    let errors = validate(&scenario).expect_err("topology must be rejected");
    assert!(
        errors
            .iter()
            .any(|error| error.message.contains("unreachable"))
    );
}
