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
