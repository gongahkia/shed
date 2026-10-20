use chiyoda_core::{
    benchmark::BenchmarkSuite, bundle::canonical_hash, study::StudyConfig, validate,
};
use serde_json::Value;

fn suite() -> BenchmarkSuite {
    serde_json::from_str(include_str!(
        "../../../examples/benchmarks/wayfinding-pairs.json"
    ))
    .unwrap()
}

#[test]
fn frozen_wayfinding_pairs_preserve_verified_inputs_and_the_original_checks() {
    let suite = suite();
    suite.validate().unwrap();
    let origin: Value = serde_json::from_str(include_str!(
        "../../../examples/benchmarks/wayfinding-pairs.origin.json"
    ))
    .unwrap();
    let study: StudyConfig = serde_json::from_str(include_str!(
        "../../../examples/benchmarks/wayfinding-study.json"
    ))
    .unwrap();
    study.validate().unwrap();
    assert_eq!(suite.cases.len(), 10);
    assert_eq!(
        origin["report_hash"],
        "af6ce2f02d5067eab88525c105cdb5fe6a3ea46e7e9b173f6312774f7f25e1ef"
    );
    for (case, archived) in suite.cases.iter().zip(origin["cases"].as_array().unwrap()) {
        validate(&case.scenario.scenario).unwrap();
        assert_eq!(case.id, archived["id"].as_str().unwrap());
        assert_eq!(
            canonical_hash(&case.scenario),
            archived["scenario_hash"].as_str().unwrap()
        );
        assert_eq!(case.evaluation, study.evaluation);
        assert!(case.evaluation.verify_replay);
        assert_eq!(case.evaluation.thresholds.min_evacuated_fraction, Some(1.0));
    }
    for pair in suite.cases.as_chunks::<2>().0 {
        let mut silent = pair[0].scenario.clone();
        let mut announced = pair[1].scenario.clone();
        assert!(silent.scenario.messages.is_empty());
        assert_eq!(announced.scenario.messages.len(), 1);
        silent.scenario.messages.clear();
        announced.scenario.messages.clear();
        assert_eq!(silent, announced);
    }
    for pair in origin["cases"].as_array().unwrap().as_chunks::<2>().0 {
        assert_eq!(pair[0]["passed"], false);
        assert_eq!(pair[1]["passed"], true);
        assert_eq!(pair[0]["context"], pair[1]["context"]);
        assert!(pair[0]["metrics"]["clearance_time_s"].is_null());
        assert!(pair[1]["metrics"]["clearance_time_s"].is_number());
    }
}

#[test]
#[ignore = "full ten-case fixed-input simulation and replay; run explicitly with an optimized test profile"]
fn all_frozen_wayfinding_cases_finish_and_replay_without_reference_disc_overlap() {
    let suite = suite();
    for index in 0..suite.cases.len() {
        let case = chiyoda_core::benchmark::run_case(&suite, index)
            .unwrap()
            .result;
        assert!(
            case.evaluation.passed,
            "{}: {:?}",
            case.id, case.evaluation.failures
        );
        assert!(case.evaluation.replay_verified);
        let metrics = case.evaluation.metrics.unwrap();
        assert_eq!(
            metrics.evacuated_agents, metrics.total_agents,
            "{}",
            case.id
        );
        let motion = metrics.movement_metrics.unwrap();
        assert!(
            motion
                .on_surface_clearance_audit
                .unwrap()
                .maximum_disc_overlap_m
                <= 0.0,
            "{}",
            case.id
        );
        assert!(
            motion
                .swept_on_surface_clearance_audit
                .unwrap()
                .maximum_swept_disc_overlap_m
                <= 0.0,
            "{}",
            case.id
        );
    }
}
