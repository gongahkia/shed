use chiyoda_core::{
    RunOptions,
    bundle::bundle_hash,
    decode_scenario,
    journeys::{self, JourneyOptions},
    run,
};

fn free_walk() -> chiyoda_core::Scenario {
    decode_scenario(include_str!("fixtures/json-free-walk.json")).unwrap()
}

#[test]
fn free_walk_distance_and_exclusive_time_budget_include_the_terminal_interval() {
    let bundle = run(
        &free_walk(),
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let report = journeys::analyze(&bundle, &JourneyOptions::default()).unwrap();
    let person = &report.agents[0];
    assert!((person.walking_distance_m - 9.84).abs() < 1e-8);
    assert!((person.time.moving_s - 8.2).abs() < 1e-8);
    assert!((person.time.evacuated_s - 3.8).abs() < 1e-8);
    assert!((person.time.total_s() - 12.0).abs() < 1e-8);
    assert_eq!(person.evacuated_at_s, bundle.metrics.clearance_time_s);
    assert_eq!(report.summary().completed, 1);
    report.verify(&bundle).unwrap();
}

#[test]
fn a_fractional_unfinished_journey_retains_missing_completion_and_all_observed_time() {
    let mut scenario = free_walk();
    scenario.duration_s = 1.1;
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let report = journeys::analyze(&bundle, &JourneyOptions::default()).unwrap();
    let person = &report.agents[0];
    assert!(person.elapsed_journey_s.is_none());
    assert!(person.evacuated_at_s.is_none());
    assert!((person.time.moving_s - 1.1).abs() < 1e-8);
    assert!((person.walking_distance_m - 1.32).abs() < 1e-8);
    assert_eq!(report.summary().completed, 0);
}

#[test]
fn every_service_mechanism_reconciles_person_time_with_runtime_resource_audits() {
    for source in [
        include_str!("../src/benchmark/fixtures/gate-queue.json"),
        include_str!("../src/benchmark/fixtures/exit-queue.json"),
        include_str!("../src/benchmark/fixtures/lift-queue.json"),
        include_str!("../src/benchmark/fixtures/queue-grid.json"),
    ] {
        let bundle = run(
            &decode_scenario(source).unwrap(),
            RunOptions {
                trace_every_steps: 1,
            },
        )
        .unwrap();
        let report = journeys::analyze(&bundle, &JourneyOptions::default()).unwrap();
        let resource_wait: f64 = report
            .resources
            .iter()
            .map(|resource| resource.wait_agent_s)
            .sum();
        assert!(resource_wait > 0.0);
        assert!((report.summary().service_wait_agent_s - resource_wait).abs() < 1e-6);
        assert!(
            report
                .resources
                .iter()
                .any(|resource| !resource.agents.is_empty())
        );
        for person in &report.agents {
            assert!((person.time.total_s() - report.observed_duration_s).abs() < 1e-7);
        }
        report.verify(&bundle).unwrap();
    }
}

#[test]
fn sparse_missing_and_reordered_trace_frames_are_rejected_even_after_resealing() {
    let bundle = run(
        &free_walk(),
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    for kind in 0..3 {
        let mut broken = bundle.clone();
        match kind {
            0 => {
                broken
                    .options
                    .insert("trace_every_steps".into(), "2".into());
            }
            1 => {
                broken.trace.remove(2);
            }
            _ => {
                broken.trace.swap(2, 3);
            }
        }
        broken.bundle_hash = bundle_hash(&broken);
        assert!(journeys::analyze(&broken, &JourneyOptions::default()).is_err());
    }
}

#[test]
fn low_progress_hotspots_partition_spans_and_preserve_people_and_replay_steps() {
    let scenario =
        decode_scenario(include_str!("fixtures/movement/corner-counterflow.json")).unwrap();
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let options = JourneyOptions {
        low_progress_window_s: 0.2,
        max_low_progress_travel_m: 0.1,
        ..JourneyOptions::default()
    };
    let report = journeys::analyze(&bundle, &options).unwrap();
    assert!(!report.hotspots.is_empty());
    let sum: f64 = report
        .hotspots
        .iter()
        .map(|cell| cell.low_progress_agent_s)
        .sum();
    assert!((sum - report.summary().low_progress_span_agent_s).abs() < 1e-7);
    assert!(
        report
            .hotspots
            .iter()
            .all(|cell| cell.start_step < cell.end_step && !cell.agents.is_empty())
    );
    assert!(
        report
            .agents
            .iter()
            .any(|person| person.swept_guard_steps > 0)
    );
    let mut tampered = report.clone();
    tampered.agents[0].walking_distance_m += 1.0;
    tampered.report_hash.clear();
    tampered.report_hash = chiyoda_core::bundle::canonical_hash(&tampered);
    assert!(tampered.verify(&bundle).is_err());
}

#[test]
fn invalid_analysis_options_fail_without_changing_the_source_bundle() {
    let bundle = run(
        &free_walk(),
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    for options in [
        JourneyOptions {
            low_progress_window_s: 0.0,
            ..JourneyOptions::default()
        },
        JourneyOptions {
            max_low_progress_travel_m: -1.0,
            ..JourneyOptions::default()
        },
        JourneyOptions {
            hotspot_cell_size_m: f64::NAN,
            ..JourneyOptions::default()
        },
    ] {
        assert!(journeys::analyze(&bundle, &options).is_err());
    }
    assert!(bundle.verifies_hash());
}

#[test]
fn nominal_reference_prices_obstacles_waypoints_dwell_and_mobility() {
    let mut scenario = free_walk();
    scenario.obstacles.push(chiyoda_core::model::Obstacle {
        id: "detour".into(),
        surface: "floor".into(),
        at: chiyoda_core::model::Point3::new(5.0, 1.0, 0.0),
        width_m: 1.0,
        depth_m: 4.0,
    });
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let report = journeys::analyze(&bundle, &JourneyOptions::default()).unwrap();
    let reference = report.agents[0].nominal_reference.as_ref().unwrap();
    assert!(reference.centre_route_duration_s > 10.0 / 1.2);
    assert_eq!(reference.exit, "out");
    let mut value = serde_json::to_value(free_walk()).unwrap();
    value["waypoints"] = serde_json::json!([{"id":"stop","surface":"floor","at":{"x_m":5,"y_m":3,"z_m":0},"dwell_s":2}]);
    value["agents"][0]["via"] = serde_json::json!(["stop"]);
    let scenario: chiyoda_core::Scenario = serde_json::from_value(value).unwrap();
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let report = journeys::analyze(&bundle, &JourneyOptions::default()).unwrap();
    let reference = report.agents[0].nominal_reference.as_ref().unwrap();
    assert!((reference.centre_route_duration_s - 10.0 / 1.2).abs() < 1e-8);
    assert!((reference.authored_dwell_s - 2.0).abs() < 1e-8);
    assert!(report.agents[0].time.waypoint_pause_s >= 2.0 - 1e-8);
}

#[test]
fn isolated_execution_keeps_original_ordinal_spawn_release_and_information_draws() {
    let mut value = serde_json::to_value(free_walk()).unwrap();
    value["agents"][0]["count"] = serde_json::json!(3);
    value["agents"][0]["release_at_s"] = serde_json::json!(1.0);
    value["agents"][0]["release_interval_s"] = serde_json::json!(0.4);
    value["messages"] = serde_json::json!([{"id":"notice","source":"Official","surface":"floor","origin":{"x_m":1,"y_m":3,"z_m":0},"claim":{"ExitAvailability":{"exit":"out","open":true}},"truthful":true,"at_s":2,"reach_m":100,"trust":0.4,"sampling_key":"same-draw"}]);
    let scenario: chiyoda_core::Scenario = serde_json::from_value(value).unwrap();
    let options = RunOptions {
        trace_every_steps: 1,
    };
    let ordinary = run(&scenario, options).unwrap();
    let isolated = chiyoda_core::runtime::run_isolated(&scenario, options, "person:2").unwrap();
    assert_eq!(isolated.scenario, ordinary.scenario);
    assert_eq!(
        isolated.trace[0].agents,
        vec![ordinary.trace[0].agents[2].clone()]
    );
    assert_eq!(isolated.metrics.total_agents, 1);
    let message = |bundle: &chiyoda_core::RunBundle| {
        bundle
            .events
            .iter()
            .find(|event| event.kind == "message_received" && event.subject == "person:2")
            .unwrap()
            .clone()
    };
    assert_eq!(message(&ordinary), message(&isolated));
    assert_eq!(isolated.options["isolated_agent"], "person:2");
    assert!(ordinary.options.get("isolated_agent").is_none());
    assert_eq!(
        chiyoda_core::verify_run_bundle(&isolated).unwrap(),
        chiyoda_core::BundleVerification::Reconstructed
    );
    let report = journeys::analyze(&isolated, &JourneyOptions::default()).unwrap();
    assert!((report.agents[0].scheduled_release_s - 1.8).abs() < 1e-8);
    assert!(chiyoda_core::runtime::run_isolated(&scenario, options, "person:99").is_err());
}

#[test]
fn optional_controls_are_bounded_reconstructible_and_never_impute_missing_completion() {
    let mut scenario = free_walk();
    scenario.agents[0].count = 3;
    scenario.duration_s = 1.1;
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let options = JourneyOptions {
        isolated_controls: Some(journeys::IsolationOptions {
            agents: vec!["absent:0".into()],
            all_agents: true,
            max_agents: 2,
            max_simulated_agent_steps: 12,
            ..journeys::IsolationOptions::default()
        }),
        ..JourneyOptions::default()
    };
    let analysis = journeys::analyze_with_controls(&bundle, &options).unwrap();
    assert_eq!(analysis.isolated_runs.len(), 1);
    let controls = &analysis.report.isolated_controls;
    assert_eq!(controls.len(), 4);
    assert_eq!(controls[0].status, journeys::ControlStatus::AgentNotFound);
    assert_eq!(controls[1].status, journeys::ControlStatus::Incomplete);
    assert_eq!(controls[2].status, journeys::ControlStatus::BudgetLimit);
    assert!(
        controls
            .iter()
            .all(|control| control.crowded_minus_isolated_elapsed_s.is_none())
    );
    assert!(controls[1].replay_verified);
    assert_eq!(analysis.report.summary().controls_requested, 4);
    assert_eq!(analysis.report.summary().controls_run, 1);
    analysis
        .report
        .verify_evidence(&bundle, &analysis.isolated_runs)
        .unwrap();
    assert!(analysis.report.verify_evidence(&bundle, &[]).is_err());
    let mut modified = analysis.isolated_runs.clone();
    modified[0].scenario.scenario.seed += 1;
    modified[0].bundle_hash = bundle_hash(&modified[0]);
    assert!(analysis.report.verify_evidence(&bundle, &modified).is_err());
}

#[test]
fn paired_completed_controls_report_elapsed_differences_with_their_sample_population() {
    let scenario = free_walk();
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let options = JourneyOptions {
        isolated_controls: Some(journeys::IsolationOptions {
            all_agents: true,
            ..journeys::IsolationOptions::default()
        }),
        ..JourneyOptions::default()
    };
    let analysis = journeys::analyze_with_controls(&bundle, &options).unwrap();
    let control = &analysis.report.isolated_controls[0];
    assert_eq!(control.status, journeys::ControlStatus::Completed);
    assert!(control.crowded_minus_isolated_elapsed_s.unwrap().abs() < 1e-8);
    assert_eq!(analysis.report.summary().control_pairs_completed, 1);
    analysis.report.verify(&bundle).unwrap();
}
