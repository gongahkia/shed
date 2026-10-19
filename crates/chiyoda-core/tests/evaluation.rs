use chiyoda_core::{
    evaluation::{
        self, EvaluationOptions, ExpectedReachability, Fixture, SuiteConfig, SuiteReport,
        Thresholds,
    },
    generator::spaces::{FloorConstraint, SpaceConfig},
};

const SOURCE: &str = "scenario \"fixture\"\nseed 1\nduration 10s\ntimestep 200ms\nsurface floor at (0m, 0m, 0m) size (10m, 10m)\nexit out on floor at (9m, 5m, 0m) width 1m\nagents person count 1 on floor at (1m, 5m, 0m) to out speed 1m/s radius 0.3m height 1.7m\n";

#[test]
fn authored_cases_run_assertions_thresholds_and_full_reconstruction() {
    let options = EvaluationOptions {
        thresholds: Thresholds {
            min_evacuated_fraction: Some(1.0),
            max_clearance_time_s: Some(10.0),
            max_disc_overlap_m: Some(0.0),
            max_swept_disc_overlap_m: Some(0.0),
            max_avoidance_fallback_steps: Some(0),
            ..Thresholds::default()
        },
        verify_replay: true,
    };
    let case =
        evaluation::evaluate_source("simple", SOURCE, ExpectedReachability::Reachable, &options);
    assert!(case.report.passed, "{:?}", case.report.failures);
    assert!(case.report.replay_verified);
    assert_eq!(case.report.metrics.as_ref().unwrap().evacuated_agents, 1);
    assert_eq!(case.bundle.unwrap().options["trace_every_steps"], "1");
}

#[test]
fn partial_runs_fail_clearance_thresholds_even_when_an_agent_has_evacuated() {
    let source = format!(
        "{}agents near count 1 on floor at (8m, 5m, 0m) to out speed 1m/s radius 0.3m height 1.7m\n",
        SOURCE.replace("duration 10s", "duration 2s")
    );
    let options = EvaluationOptions {
        thresholds: Thresholds {
            min_evacuated_fraction: Some(1.0),
            max_clearance_time_s: Some(100.0),
            ..Thresholds::default()
        },
        verify_replay: false,
    };
    let case = evaluation::evaluate_source(
        "partial",
        &source,
        ExpectedReachability::Reachable,
        &options,
    );
    assert!(!case.report.passed);
    let metrics = case.report.metrics.as_ref().unwrap();
    assert_eq!(metrics.evacuated_agents, 1);
    assert!(metrics.last_exit_time_s.is_some());
    assert!(metrics.clearance_time_s.is_none());
    assert!(
        case.report
            .failures
            .iter()
            .any(|failure| failure.check == "max_clearance_time_s")
    );
    assert!(
        case.report
            .failures
            .iter()
            .any(|failure| failure.check == "min_evacuated_fraction")
    );
}

#[test]
fn deliberately_unreachable_fixtures_are_distinct_from_invalid_or_failed_cases() {
    let blocked = format!("{SOURCE}obstacle wall on floor at (4m, 0m, 0m) size (2m, 10m)\n");
    let expected = evaluation::evaluate_source(
        "blocked",
        &blocked,
        ExpectedReachability::Unreachable,
        &EvaluationOptions::default(),
    );
    assert!(expected.report.passed);
    assert!(expected.bundle.is_none());
    assert!(expected.report.metrics.is_none());
    let unexpected = evaluation::evaluate_source(
        "blocked",
        &blocked,
        ExpectedReachability::Reachable,
        &EvaluationOptions::default(),
    );
    assert!(!unexpected.report.passed);
    assert_eq!(unexpected.report.failures[0].check, "reachability");
    assert!(
        !evaluation::evaluate_source(
            "bad",
            "not source",
            ExpectedReachability::Unreachable,
            &EvaluationOptions::default()
        )
        .report
        .passed
    );
    assert!(
        !evaluation::evaluate_source(
            "clear",
            SOURCE,
            ExpectedReachability::Unreachable,
            &EvaluationOptions::default()
        )
        .report
        .passed
    );
}

#[test]
fn exhausted_generation_is_retained_in_suite_failures_and_rejection_counts() {
    let generation = SpaceConfig {
        max_attempts: 2,
        pins: vec![FloorConstraint {
            floor: 0,
            x: 0,
            y: 2,
            allowed_tiles: vec!["solid".into()],
        }],
        ..SpaceConfig::default()
    };
    let config = SuiteConfig {
        generation,
        seeds: vec![11],
        ..SuiteConfig::default()
    };
    let case = evaluation::evaluate_seed(11, &config.generation, &config.evaluation);
    assert!(case.source.is_none());
    let report = SuiteReport::from_cases(config, vec![case.report]).unwrap();
    assert_eq!(report.failed, 1);
    assert_eq!(report.total_generation_attempts, 2);
    assert_eq!(report.rejected_generation_attempts, 2);
    assert_eq!(report.rejection_counts["contradiction"], 2);
    assert_eq!(report.unique_layouts, 0);
}

#[test]
fn generated_case_coverage_and_reports_are_deterministic_and_account_for_fixtures() {
    let config = SuiteConfig {
        generation: SpaceConfig {
            width: 4,
            height: 4,
            floors: 1,
            agents_per_floor: 1,
            duration_s: 0.2,
            timestep_s: 0.2,
            ..SpaceConfig::default()
        },
        seeds: vec![73],
        fixtures: vec![Fixture {
            name: "authored".into(),
            source: SOURCE.into(),
            expected: ExpectedReachability::Reachable,
        }],
        ..SuiteConfig::default()
    };
    let generated = evaluation::evaluate_seed(73, &config.generation, &config.evaluation);
    assert!(generated.report.passed, "{:?}", generated.report.failures);
    assert_eq!(generated.report.coverage.tiles.values().sum::<u64>(), 16);
    let fixture = evaluation::evaluate_source(
        "authored",
        SOURCE,
        ExpectedReachability::Reachable,
        &config.evaluation,
    );
    let report = SuiteReport::from_cases(
        config.clone(),
        vec![generated.report.clone(), fixture.report],
    )
    .unwrap();
    assert_eq!(report.passed, 2);
    assert_eq!(report.unique_layouts, 1);
    assert_eq!(report.coverage.cases_with_queues["lift"], 0);
    assert_eq!(
        generated.report,
        evaluation::evaluate_seed(73, &config.generation, &config.evaluation).report
    );
    assert!(SuiteReport::from_cases(config, vec![generated.report]).is_err());
    let encoded = serde_json::to_string(&report).unwrap();
    assert_eq!(report, serde_json::from_str(&encoded).unwrap());
}

#[test]
fn suite_validation_catches_duplicate_cases_and_invalid_thresholds() {
    assert!(
        SuiteConfig {
            seeds: vec![1, 1],
            ..SuiteConfig::default()
        }
        .validate()
        .is_err()
    );
    assert!(
        SuiteConfig {
            seeds: vec![],
            fixtures: vec![],
            ..SuiteConfig::default()
        }
        .validate()
        .is_err()
    );
    assert!(
        Thresholds {
            min_evacuated_fraction: Some(1.01),
            ..Thresholds::default()
        }
        .validate()
        .is_err()
    );
    assert!(serde_json::from_str::<Thresholds>(r#"{"max_clearence_time_s": 10}"#).is_err());
}
