use chiyoda_core::{
    RunOptions,
    bundle::KnowledgeSource,
    model::{Exit, ExitStateChange, InformationSource, Message, Obstacle, Point3, Proposition},
    run,
};
use chiyoda_core::{decode_scenario, encode_scenario, model::AgentKnowledge, validate};
use serde_json::json;

fn scenario() -> chiyoda_core::Scenario {
    decode_scenario(include_str!("fixtures/json-free-walk.json")).unwrap()
}

#[test]
fn knowledge_is_explicit_strict_and_retains_absent_versus_empty_familiarity() {
    let mut scenario = scenario();
    let original = encode_scenario(&scenario).unwrap();
    assert!(!original.contains("knowledge"));
    for known_exits in [None, Some(vec![]), Some(vec!["out".into()])] {
        scenario.agents[0].knowledge = Some(AgentKnowledge {
            observation_radius_m: 0.0,
            known_exits,
        });
        let encoded = encode_scenario(&scenario).unwrap();
        assert_eq!(decode_scenario(&encoded).unwrap(), scenario);
    }
    let mut input: serde_json::Value = serde_json::from_str(&original).unwrap();
    input["scenario"]["agents"][0]["knowledge"] =
        json!({"observation_radius_m": 2.0, "observaton_radius_m": 3.0});
    assert!(decode_scenario(&input.to_string()).is_err());
}

#[test]
fn invalid_knowledge_ranges_and_familiarity_are_rejected() {
    for range in [-1.0, f64::NAN, f64::INFINITY] {
        let mut scenario = scenario();
        scenario.agents[0].knowledge = Some(AgentKnowledge {
            observation_radius_m: range,
            known_exits: None,
        });
        assert!(
            validate(&scenario)
                .unwrap_err()
                .iter()
                .any(|error| { error.path == "agents[0].knowledge.observation_radius_m" })
        );
    }
    for exits in [vec!["missing".into()], vec!["out".into(), "out".into()]] {
        let mut scenario = scenario();
        scenario.agents[0].knowledge = Some(AgentKnowledge {
            observation_radius_m: 2.0,
            known_exits: Some(exits),
        });
        assert!(validate(&scenario).is_err());
    }
}

fn hall(range: f64) -> chiyoda_core::Scenario {
    let mut scenario = scenario();
    scenario.duration_s = 40.0;
    scenario.surfaces[0].depth_m = 16.0;
    scenario.agents[0].speed_mps = 1.0;
    scenario.agents[0].knowledge = Some(AgentKnowledge {
        observation_radius_m: range,
        known_exits: None,
    });
    scenario.exits[0].at.x_m = 10.0;
    scenario.exits[0].id = "primary".into();
    scenario.agents[0].destination = "primary".into();
    scenario.agents[0].alternative_destinations = vec!["fallback".into()];
    scenario.exits.push(Exit {
        id: "fallback".into(),
        surface: "floor".into(),
        at: Point3::new(1.0, 13.0, 0.0),
        width_m: 1.0,
        capacity_per_s: None,
    });
    scenario
}

fn notice(exit: &str, open: bool, at_s: f64, truthful: bool, trust: f64) -> Message {
    Message {
        id: "notice".into(),
        source: InformationSource::Official,
        surface: "floor".into(),
        origin: Point3::new(1.0, 3.0, 0.0),
        claim: Proposition::ExitAvailability {
            exit: exit.into(),
            open,
        },
        truthful,
        at_s,
        reach_m: 100.0,
        trust,
        sampling_key: None,
    }
}

fn simulate(scenario: &chiyoda_core::Scenario) -> chiyoda_core::RunBundle {
    run(
        scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap()
}

#[test]
fn a_truthful_announcement_prevents_a_trip_to_an_unseen_closure() {
    let mut scenario = hall(1.0);
    scenario.exit_states.push(ExitStateChange {
        id: "close".into(),
        exit: "primary".into(),
        open: false,
        at_s: 2.0,
    });
    let silent = simulate(&scenario);
    scenario
        .messages
        .push(notice("primary", false, 2.0, true, 1.0));
    let informed = simulate(&scenario);
    assert_eq!(silent.metrics.evacuated_agents, 1);
    assert_eq!(informed.metrics.evacuated_agents, 1);
    assert!(
        informed.metrics.clearance_time_s.unwrap() + 5.0 < silent.metrics.clearance_time_s.unwrap()
    );
    assert!(
        silent
            .events
            .iter()
            .any(|event| event.kind == "resource_observed"
                && event.detail == "exit:primary=closed"
                && event.time_s > 2.0)
    );
    assert!(
        !silent
            .events
            .iter()
            .any(|event| event.kind == "route_recomputed" && (event.time_s - 2.0).abs() < 1e-9)
    );
    assert!(informed.trace.iter().any(|frame| {
        frame.agents[0]
            .knowledge
            .as_ref()
            .unwrap()
            .availability
            .get("exit:primary")
            .is_some_and(|known| {
                !known.open
                    && known.source
                        == KnowledgeSource::Message {
                            id: "notice".into(),
                        }
            })
    }));
    assert_eq!(
        chiyoda_core::verify_run_bundle(&informed).unwrap(),
        chiyoda_core::BundleVerification::Reconstructed
    );
    scenario.agents[0].knowledge = None;
    let global_notice = simulate(&scenario);
    scenario.messages.clear();
    let global_silent = simulate(&scenario);
    assert_eq!(
        global_notice.metrics.clearance_time_s,
        global_silent.metrics.clearance_time_s
    );
    assert!(
        global_silent
            .trace
            .iter()
            .all(|frame| frame.agents[0].knowledge.is_none())
    );
}

#[test]
fn unfamiliar_exits_are_learned_only_from_observation_or_accepted_information() {
    let mut scenario = hall(0.0);
    scenario.agents[0].knowledge.as_mut().unwrap().known_exits = Some(vec![]);
    let waiting = simulate(&scenario);
    assert_eq!(waiting.metrics.evacuated_agents, 0);
    assert_eq!(
        waiting.trace.last().unwrap().agents[0].state,
        chiyoda_core::AgentState::WaitingForRoute
    );
    scenario
        .messages
        .push(notice("fallback", true, 1.0, true, 0.0));
    assert_eq!(simulate(&scenario).metrics.evacuated_agents, 0);
    scenario.messages[0].trust = 1.0;
    let learned = simulate(&scenario);
    assert_eq!(learned.metrics.evacuated_by_exit.get("fallback"), Some(&1));
    assert_eq!(
        learned.trace.last().unwrap().agents[0]
            .knowledge
            .as_ref()
            .unwrap()
            .known_exits,
        vec!["fallback"]
    );
    scenario.messages.clear();
    scenario.agents[0]
        .knowledge
        .as_mut()
        .unwrap()
        .observation_radius_m = 10.0;
    assert_eq!(simulate(&scenario).metrics.evacuated_agents, 1);
}

#[test]
fn direct_observation_overrides_a_false_claim_and_reopening_is_local() {
    let mut scenario = hall(2.0);
    scenario.agents[0].at = Point3::new(9.0, 3.0, 0.0);
    scenario.agents[0].alternative_destinations.clear();
    scenario.exit_states.push(ExitStateChange {
        id: "close".into(),
        exit: "primary".into(),
        open: false,
        at_s: 0.0,
    });
    scenario.exit_states.push(ExitStateChange {
        id: "open".into(),
        exit: "primary".into(),
        open: true,
        at_s: 3.0,
    });
    scenario
        .messages
        .push(notice("primary", true, 1.0, false, 1.0));
    let bundle = simulate(&scenario);
    let after_lie = bundle
        .trace
        .iter()
        .find(|frame| (frame.time_s - 1.0).abs() < 1e-9)
        .unwrap();
    let known = &after_lie.agents[0].knowledge.as_ref().unwrap().availability["exit:primary"];
    assert!(!known.open);
    assert_eq!(known.source, KnowledgeSource::Observation);
    assert_eq!(bundle.metrics.evacuated_agents, 1);
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "resource_observed"
                && event.detail == "exit:primary=open"
                && (event.time_s - 3.0).abs() < 1e-9)
    );
}

#[test]
fn walls_and_other_surfaces_prevent_remote_observation() {
    let mut scenario = hall(100.0);
    scenario.obstacles.push(Obstacle {
        id: "wall".into(),
        surface: "floor".into(),
        at: Point3::new(6.0, 2.0, 0.0),
        width_m: 1.0,
        depth_m: 3.0,
    });
    let bundle = simulate(&scenario);
    let initial = bundle.trace[0].agents[0].knowledge.as_ref().unwrap();
    assert!(!initial.availability.contains_key("exit:primary"));
    assert!(initial.availability.contains_key("exit:fallback"));
    scenario.surfaces.push(chiyoda_core::model::Surface {
        id: "upper".into(),
        origin: Point3::new(0.0, 0.0, 3.0),
        width_m: 12.0,
        depth_m: 16.0,
    });
    scenario.exits[1].surface = "upper".into();
    scenario.exits[1].at.z_m = 3.0;
    scenario.agents[0].alternative_destinations.clear();
    let bundle = simulate(&scenario);
    assert!(
        bundle.trace[0].agents[0]
            .knowledge
            .as_ref()
            .unwrap()
            .availability
            .is_empty()
    );
}

#[test]
fn contact_discovers_closed_exits_even_with_zero_observation_range() {
    let mut scenario = hall(0.0);
    scenario.exit_states.push(ExitStateChange {
        id: "close".into(),
        exit: "primary".into(),
        open: false,
        at_s: 0.0,
    });
    scenario
        .messages
        .push(notice("primary", true, 1.0, false, 1.0));
    let bundle = simulate(&scenario);
    assert_eq!(bundle.metrics.evacuated_by_exit.get("fallback"), Some(&1));
    assert!(bundle.events.iter().any(|event| event.kind == "resource_observed" && event.detail == "exit:primary=closed"));
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| event.kind == "evacuated" && event.detail == "primary")
    );
    assert!(
        bundle.trace.last().unwrap().agents[0]
            .knowledge
            .as_ref()
            .unwrap()
            .availability["exit:fallback"]
            .open
    );
}

#[test]
fn closed_gates_and_connectors_are_learned_at_contact_and_reopen_without_global_updates() {
    let mut gate = hall(0.0);
    gate.agents[0].alternative_destinations.clear();
    gate.gates = serde_json::from_value(json!([{
        "id": "fare", "surface": "floor", "at": {"x_m": 5, "y_m": 3, "z_m": 0},
        "width_m": 1, "service_rate_per_s": 1, "destination": "primary"
    }]))
    .unwrap();
    gate.gate_states = serde_json::from_value(json!([
        {"id": "close", "gate": "fare", "open": false, "at_s": 0},
        {"id": "open", "gate": "fare", "open": true, "at_s": 6}
    ]))
    .unwrap();
    let mut connector = hall(0.0);
    connector.agents[0].alternative_destinations.clear();
    let mut upper = connector.surfaces[0].clone();
    upper.id = "upper".into();
    upper.origin.z_m = 3.0;
    connector.surfaces.push(upper);
    connector.exits[0].surface = "upper".into();
    connector.exits[0].at.z_m = 3.0;
    connector.connectors = serde_json::from_value(json!([{"Stair": {
        "id": "up", "from_surface": "floor", "from": {"x_m": 5, "y_m": 3, "z_m": 0},
        "to_surface": "upper", "to": {"x_m": 5, "y_m": 3, "z_m": 3}, "width_m": 1,
        "capacity_per_s": null, "clearance_height_m": null
    }}]))
    .unwrap();
    connector.connector_states = serde_json::from_value(json!([
        {"id": "close", "connector": "up", "open": false, "at_s": 0},
        {"id": "open", "connector": "up", "open": true, "at_s": 6}
    ]))
    .unwrap();
    for (scenario, key, event_kind) in [
        (gate, "gate:fare", "gate_processed"),
        (connector, "connector:up", "connector_departure"),
    ] {
        let bundle = simulate(&scenario);
        assert_eq!(
            bundle.metrics.evacuated_agents, 1,
            "{key}: {:?}",
            bundle.events
        );
        assert!(
            bundle
                .events
                .iter()
                .any(|event| event.kind == "resource_observed"
                    && event.detail == format!("{key}=closed"))
        );
        assert!(
            bundle
                .events
                .iter()
                .any(|event| event.kind == "resource_observed"
                    && event.detail == format!("{key}=open")
                    && event.time_s >= 6.0)
        );
        assert!(
            !bundle
                .events
                .iter()
                .any(|event| event.kind == event_kind && event.time_s < 6.0)
        );
    }
}

#[test]
fn agents_do_not_observe_or_receive_messages_before_release() {
    let mut scenario = hall(100.0);
    scenario.agents[0].release_at_s = 5.0;
    scenario.agents[0].knowledge.as_mut().unwrap().known_exits = Some(vec![]);
    scenario
        .messages
        .push(notice("primary", true, 1.0, true, 1.0));
    let bundle = simulate(&scenario);
    assert_eq!(
        bundle.metrics.information_delivery["notice"].received_agents,
        0
    );
    assert!(
        bundle
            .trace
            .iter()
            .filter(|frame| frame.time_s < 5.0)
            .all(|frame| frame.agents[0]
                .knowledge
                .as_ref()
                .unwrap()
                .availability
                .is_empty())
    );
    assert_eq!(bundle.metrics.evacuated_agents, 1);
    assert!(
        bundle
            .events
            .iter()
            .filter(|event| event.kind == "resource_observed")
            .all(|event| event.time_s >= 5.0)
    );
}

#[test]
fn knowledge_is_hashed_reconstructed_and_old_json_bundles_remain_inspectable() {
    let mut scenario = hall(100.0);
    scenario.duration_s = 1.0;
    let mut bundle = simulate(&scenario);
    bundle.trace[0].agents[0]
        .knowledge
        .as_mut()
        .unwrap()
        .availability
        .get_mut("exit:primary")
        .unwrap()
        .open = false;
    bundle.bundle_hash = chiyoda_core::bundle_hash(&bundle);
    assert!(chiyoda_core::verify_run_bundle(&bundle).is_err());
    let archived: chiyoda_core::RunBundle =
        serde_json::from_str(include_str!("fixtures/legacy-0.43-run.json")).unwrap();
    assert_eq!(
        archived.bundle_hash,
        "8899aa4f41b74ac15883a452a3e0e06142838765642ce677fa449c1a109ff8be"
    );
    assert_eq!(
        chiyoda_core::verify_run_bundle(&archived).unwrap(),
        chiyoda_core::BundleVerification::HashOnlyLegacy
    );
    assert!(
        archived
            .trace
            .iter()
            .all(|frame| frame.agents[0].knowledge.is_none())
    );
}

#[test]
fn corrections_restore_local_beliefs_and_information_reaches_people_during_a_dwell() {
    let mut scenario = hall(0.0);
    scenario
        .messages
        .push(notice("primary", false, 1.0, false, 1.0));
    scenario.countermeasures = serde_json::from_value(json!([{
        "id": "correction", "corrects": "notice", "source": "Staff", "surface": "floor",
        "origin": {"x_m": 1, "y_m": 3, "z_m": 0}, "at_s": 1.2, "reach_m": 100, "trust": 1
    }]))
    .unwrap();
    let corrected = simulate(&scenario);
    assert_eq!(corrected.metrics.evacuated_by_exit.get("primary"), Some(&1));
    assert!(corrected.trace.iter().any(|frame| {
        frame.agents[0]
            .knowledge
            .as_ref()
            .unwrap()
            .availability
            .get("exit:primary")
            .is_some_and(|known| {
                known.open
                    && known.source
                        == KnowledgeSource::Countermeasure {
                            id: "correction".into(),
                        }
            })
    }));

    scenario.countermeasures.clear();
    scenario.messages.clear();
    scenario.waypoints = serde_json::from_value(json!([{
        "id": "stop", "surface": "floor", "at": {"x_m": 2, "y_m": 3, "z_m": 0}, "dwell_s": 5
    }]))
    .unwrap();
    scenario.agents[0].via.push("stop".into());
    scenario.agents[0].knowledge.as_mut().unwrap().known_exits = Some(vec!["primary".into()]);
    scenario.exit_states.push(ExitStateChange {
        id: "close".into(),
        exit: "primary".into(),
        open: false,
        at_s: 0.0,
    });
    scenario
        .messages
        .push(notice("primary", false, 2.0, true, 1.0));
    let mut alternative = notice("fallback", true, 3.0, true, 1.0);
    alternative.id = "learn-fallback".into();
    scenario.messages.push(alternative);
    let dwelling = simulate(&scenario);
    assert_eq!(
        dwelling.metrics.information_delivery["notice"].accepted_agents,
        1
    );
    assert_eq!(
        dwelling.metrics.information_delivery["learn-fallback"].accepted_agents,
        1
    );
    assert_eq!(dwelling.metrics.evacuated_by_exit.get("fallback"), Some(&1));
    assert!(dwelling.trace.iter().any(|frame| frame.time_s > 3.0
        && frame.time_s < 5.0
        && frame.agents[0].state == chiyoda_core::AgentState::WaitingAtWaypoint));
}

#[test]
fn contact_with_a_lane_notices_a_reopening_and_keeps_physical_service_closed_until_then() {
    let mut scenario = hall(0.0);
    scenario.agents[0].alternative_destinations.clear();
    scenario.exits[0].width_m = 3.0;
    scenario.portal_lanes = serde_json::from_value(json!([{
        "id": "exit-lanes", "resource": {"exit": {"id": "primary"}}, "axis": "y", "count": 3
    }]))
    .unwrap();
    scenario.exit_states = vec![
        ExitStateChange {
            id: "close".into(),
            exit: "primary".into(),
            open: false,
            at_s: 0.0,
        },
        ExitStateChange {
            id: "open".into(),
            exit: "primary".into(),
            open: true,
            at_s: 15.0,
        },
    ];
    let bundle = simulate(&scenario);
    assert_eq!(bundle.metrics.evacuated_agents, 1);
    assert!(bundle.metrics.clearance_time_s.unwrap() >= 15.0);
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "resource_observed"
                && event.detail == "exit:primary=open"
                && event.time_s >= 15.0)
    );
}

#[test]
fn discovering_a_goal_during_a_waypoint_dwell_preserves_the_authored_hold() {
    let mut scenario = hall(0.0);
    scenario.agents[0].knowledge.as_mut().unwrap().known_exits = Some(vec![]);
    scenario.agents[0].via.push("search-point".into());
    scenario.waypoints = serde_json::from_value(json!([{
        "id": "search-point", "surface": "floor", "at": {"x_m": 2, "y_m": 3, "z_m": 0}, "dwell_s": 5
    }]))
    .unwrap();
    scenario
        .messages
        .push(notice("fallback", true, 2.0, true, 1.0));
    let bundle = simulate(&scenario);
    assert_eq!(bundle.metrics.evacuated_by_exit.get("fallback"), Some(&1));
    let reached = bundle
        .events
        .iter()
        .find(|event| event.kind == "waypoint_reached")
        .unwrap()
        .time_s;
    assert!(
        bundle
            .trace
            .iter()
            .filter(|frame| frame.time_s > reached && frame.time_s < reached + 5.0)
            .all(|frame| frame.agents[0].state == chiyoda_core::AgentState::WaitingAtWaypoint)
    );
    assert_eq!(
        bundle.metrics.information_delivery["notice"].accepted_agents,
        1
    );
}

#[test]
fn queue_service_cannot_be_reserved_through_an_unseen_physical_closure() {
    let mut scenario = hall(0.0);
    scenario.agents[0].alternative_destinations.clear();
    scenario.exits[0].capacity_per_s = Some(1.0);
    scenario.queue_footprints = serde_json::from_value(json!([{
        "id": "queue", "resource": {"exit": {"id": "primary"}}, "surface": "floor",
        "head": {"x_m": 5, "y_m": 3, "z_m": 0}, "tail": {"x_m": 3, "y_m": 3, "z_m": 0},
        "slots": 2, "width_m": 1.5, "lanes": 2
    }]))
    .unwrap();
    scenario.exit_states = vec![
        ExitStateChange {
            id: "close".into(),
            exit: "primary".into(),
            open: false,
            at_s: 2.0,
        },
        ExitStateChange {
            id: "open".into(),
            exit: "primary".into(),
            open: true,
            at_s: 10.0,
        },
    ];
    let bundle = simulate(&scenario);
    assert_eq!(bundle.metrics.evacuated_agents, 1);
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "queue_slot_preallocated" && event.time_s < 2.0)
    );
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "queue_service_reserved" && event.time_s >= 10.0)
    );
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| event.kind == "queue_service_reserved" && event.time_s < 10.0)
    );
}

#[test]
fn global_and_local_awareness_can_share_one_space_without_leaking_closure_events() {
    let mut scenario = hall(0.0);
    let mut global = scenario.agents[0].clone();
    global.id = "global".into();
    global.at.y_m = 5.0;
    global.knowledge = None;
    scenario.agents.push(global);
    scenario.exit_states.push(ExitStateChange {
        id: "close".into(),
        exit: "primary".into(),
        open: false,
        at_s: 2.0,
    });
    let bundle = simulate(&scenario);
    assert!(
        bundle
            .events
            .iter()
            .any(|event| event.kind == "route_recomputed"
                && event.subject == "global:0"
                && (event.time_s - 2.0).abs() < 1e-9)
    );
    assert!(
        !bundle
            .events
            .iter()
            .any(|event| event.kind == "route_recomputed"
                && event.subject == "person:0"
                && (event.time_s - 2.0).abs() < 1e-9)
    );
    assert_eq!(bundle.metrics.evacuated_agents, 2);
}
