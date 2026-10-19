use chiyoda_core::benchmark::comparison::{
    self, CaseVerdict, ComparisonPolicy, Metric, MetricVerdict, Tolerance,
};
use chiyoda_core::benchmark::{self, BenchmarkReport, profiles};

fn simple_report() -> BenchmarkReport {
    let mut suite = profiles::suite("smoke").unwrap();
    suite.cases.truncate(1);
    suite.cases[0].source = suite.cases[0].source.replace("count 1", "count 2");
    let case = benchmark::run_case(&suite, 0).unwrap().result;
    BenchmarkReport::new(suite, vec![case]).unwrap()
}

fn reseal(report: BenchmarkReport) -> BenchmarkReport {
    BenchmarkReport::new_labeled(report.suite, report.cases, report.label).unwrap()
}

#[test]
fn embedded_profiles_have_fixed_inputs_and_explicit_passing_expectations() {
    for name in profiles::NAMES {
        profiles::suite(name).unwrap().validate().unwrap();
    }
    let suite = profiles::suite("all").unwrap();
    let mut cases = Vec::new();
    let mut failures = Vec::new();
    for index in 0..suite.cases.len() {
        let run = benchmark::run_case(&suite, index).unwrap();
        if !run.result.evaluation.passed {
            failures.push(format!(
                "{}: {:?}; metrics: {:?}",
                run.result.id, run.result.evaluation.failures, run.result.evaluation.metrics
            ));
        }
        assert!(run.result.evaluation.replay_verified);
        cases.push(run.result);
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    let report = BenchmarkReport::new(suite, cases).unwrap();
    report.verify().unwrap();
    let encoded = serde_json::to_string(&report).unwrap();
    let decoded: BenchmarkReport = serde_json::from_str(&encoded).unwrap();
    decoded.verify().unwrap();
    assert_eq!(report, decoded);
}

#[test]
fn reports_require_every_case_and_matching_inputs_and_detect_tampering() {
    let mut suite = profiles::suite("smoke").unwrap();
    suite.cases.truncate(1);
    let case = benchmark::run_case(&suite, 0).unwrap().result;
    let mut report = BenchmarkReport::new(suite.clone(), vec![case.clone()]).unwrap();
    report.cases[0].evaluation.replay_verified = false;
    assert!(report.verify().is_err());
    assert!(BenchmarkReport::new(suite.clone(), vec![]).is_err());
    suite.cases[0].source.push_str("# changed input\n");
    assert!(BenchmarkReport::new(suite, vec![case]).is_err());
    assert!(profiles::suite("unknown").is_err());
}

#[test]
fn comparisons_measure_regressions_improvements_and_allowed_noise() {
    let baseline = simple_report();
    let same = comparison::compare(&baseline, &baseline, &ComparisonPolicy::default()).unwrap();
    assert_eq!(same.unchanged, 1);
    assert!(same.is_success());
    let mut current = baseline.clone();
    let old_time = current.cases[0]
        .evaluation
        .metrics
        .as_ref()
        .unwrap()
        .clearance_time_s
        .unwrap();
    current.cases[0]
        .evaluation
        .metrics
        .as_mut()
        .unwrap()
        .clearance_time_s = Some(old_time + 0.5);
    let current = reseal(current);
    let regression =
        comparison::compare(&baseline, &current, &ComparisonPolicy::default()).unwrap();
    assert_eq!(regression.regressed, 1);
    assert!(!regression.is_success());
    assert!(
        regression.cases[0]
            .metrics
            .iter()
            .any(|metric| metric.metric == Metric::ClearanceTimeS
                && metric.verdict == MetricVerdict::Regressed)
    );
    let mut policy = ComparisonPolicy::default();
    policy.metrics.insert(
        Metric::ClearanceTimeS,
        Tolerance {
            absolute: 0.5,
            relative: 0.0,
        },
    );
    assert!(
        comparison::compare(&baseline, &current, &policy)
            .unwrap()
            .is_success()
    );
    assert_eq!(
        comparison::compare(&current, &baseline, &ComparisonPolicy::default())
            .unwrap()
            .improved,
        1
    );
}

#[test]
fn changed_contracts_and_missing_cases_cannot_be_counted_as_improvements() {
    let baseline = simple_report();
    let mut changed = baseline.clone();
    changed.suite.cases[0]
        .evaluation
        .thresholds
        .max_clearance_time_s = Some(100.0);
    changed.cases[0].input_hash = changed.suite.input_hash(&changed.suite.cases[0]);
    let changed = reseal(changed);
    assert_eq!(
        comparison::compare(&baseline, &changed, &ComparisonPolicy::default())
            .unwrap()
            .incompatible,
        1
    );
    let mut added = baseline.clone();
    let mut definition = added.suite.cases[0].clone();
    definition.id = "another".into();
    added.suite.cases.push(definition);
    added
        .cases
        .push(benchmark::run_case(&added.suite, 1).unwrap().result);
    let added = reseal(added);
    assert_eq!(
        comparison::compare(&baseline, &added, &ComparisonPolicy::default())
            .unwrap()
            .incompatible,
        1
    );
    assert_eq!(
        comparison::compare(&added, &baseline, &ComparisonPolicy::default())
            .unwrap()
            .incompatible,
        1
    );
    let mut corrupt = baseline.clone();
    corrupt.cases[0].input_hash = "wrong".into();
    assert!(comparison::compare(&baseline, &corrupt, &ComparisonPolicy::default()).is_err());
}

#[test]
fn failed_runs_partial_clearance_and_missing_telemetry_remain_visible() {
    let baseline = simple_report();
    let mut partial = baseline.clone();
    let result = &mut partial.cases[0].evaluation;
    result.passed = false;
    result
        .failures
        .push(chiyoda_core::evaluation::CheckFailure {
            check: "min_evacuated_fraction".into(),
            detail: "incomplete".into(),
        });
    let metrics = result.metrics.as_mut().unwrap();
    metrics.evacuated_agents = 1;
    metrics.evacuated_by_exit.insert("out".into(), 1);
    metrics.remaining_by_state.insert("moving".into(), 1);
    metrics.mean_exit_time_s = Some(1.0);
    metrics.clearance_time_s = None;
    metrics.last_exit_time_s = Some(1.0);
    let partial = reseal(partial);
    let comparison =
        comparison::compare(&baseline, &partial, &ComparisonPolicy::default()).unwrap();
    assert_eq!(comparison.cases[0].verdict, CaseVerdict::Regressed);
    let clearance = comparison.cases[0]
        .metrics
        .iter()
        .find(|metric| metric.metric == Metric::ClearanceTimeS)
        .unwrap();
    assert!(clearance.current.is_none());
    assert!(comparison::compare(&partial, &baseline, &ComparisonPolicy::default()).is_err());
    let mut missing = baseline.clone();
    missing.cases[0]
        .evaluation
        .metrics
        .as_mut()
        .unwrap()
        .movement_metrics = None;
    let missing = reseal(missing);
    assert_eq!(
        comparison::compare(&baseline, &missing, &ComparisonPolicy::default())
            .unwrap()
            .incompatible,
        1
    );
    let mut invalid = baseline;
    invalid.cases[0]
        .evaluation
        .metrics
        .as_mut()
        .unwrap()
        .mean_exit_time_s = Some(f64::NAN);
    assert!(BenchmarkReport::new(invalid.suite, invalid.cases).is_err());
}

#[test]
fn expected_unreachable_cases_have_no_simulation_metrics_or_replay_requirement() {
    let mut suite = profiles::suite("smoke").unwrap();
    suite.cases.truncate(1);
    suite.cases[0].source = include_str!("../../../examples/generation/blocked.chy").into();
    suite.cases[0].expected = chiyoda_core::evaluation::ExpectedReachability::Unreachable;
    let result = benchmark::run_case(&suite, 0).unwrap().result;
    assert!(result.evaluation.passed);
    assert!(!result.evaluation.replay_verified);
    let report = BenchmarkReport::new(suite, vec![result]).unwrap();
    let comparison = comparison::compare(&report, &report, &ComparisonPolicy::default()).unwrap();
    assert!(comparison.is_success());
    assert!(comparison.cases[0].metrics.is_empty());
}
