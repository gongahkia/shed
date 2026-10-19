use chiyoda_core::benchmark::{self, BenchmarkReport, profiles};

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
