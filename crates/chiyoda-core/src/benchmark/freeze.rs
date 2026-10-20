//! Convert archived evaluation sources to a portable fixed-input suite.

use super::{
    BENCHMARK_VERSION, BehaviorChecks, BenchmarkCase, BenchmarkError, BenchmarkSuite,
    EvaluationOrigin, invalid,
};
use crate::{
    CanonicalScenario,
    bundle::canonical_hash,
    decode_scenario,
    evaluation::{EVALUATION_VERSION, SuiteReport},
    validate,
};

/// Sources must be in the evaluation's case order. Each is checked against the
/// archived canonical scenario hash, including cases that failed assertions.
/// Generation failures cannot be frozen because no scenario was produced.
pub fn from_evaluation(
    report: &SuiteReport,
    sources: &[String],
    name: &str,
) -> Result<BenchmarkSuite, BenchmarkError> {
    if report.evaluation_version != EVALUATION_VERSION || sources.len() != report.cases.len() {
        return Err(invalid(
            "unsupported evaluation version or incomplete source list",
        ));
    }
    let mut reconstructed =
        SuiteReport::from_cases(report.config.clone(), report.cases.clone()).map_err(invalid)?;
    reconstructed
        .runtime_version
        .clone_from(&report.runtime_version);
    if reconstructed != *report {
        return Err(invalid(
            "evaluation summary does not agree with its config and case reports",
        ));
    }
    let mut cases = Vec::new();
    for (case, source) in report.cases.iter().zip(sources) {
        let scenario =
            decode_scenario(source).map_err(|error| invalid(format!("{}: {error}", case.name)))?;
        validate(&scenario).map_err(|errors| invalid(format!("{}: {errors:?}", case.name)))?;
        let hash = canonical_hash(&CanonicalScenario::from(scenario.clone()));
        if case.scenario_hash.as_deref() != Some(hash.as_str()) {
            return Err(invalid(format!(
                "{}: source does not match the archived scenario hash",
                case.name
            )));
        }
        cases.push(BenchmarkCase {
            id: case.name.clone(),
            description: format!("Frozen evaluation case {}", case.name),
            scenario: CanonicalScenario::from(scenario),
            expected: case.expected,
            evaluation: report.config.evaluation.clone(),
            checks: BehaviorChecks::default(),
        });
    }
    let suite = BenchmarkSuite {
        benchmark_version: BENCHMARK_VERSION.into(),
        name: name.into(),
        origin: Some(EvaluationOrigin {
            evaluation_version: report.evaluation_version.clone(),
            runtime_version: report.runtime_version.clone(),
            report_hash: canonical_hash(report),
        }),
        diagnostics: super::diagnostics::DiagnosticOptions::default(),
        journey_diagnostics: None,
        cases,
    };
    suite.validate()?;
    Ok(suite)
}
