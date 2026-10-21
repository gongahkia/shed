//! Reproducible scenario evaluation. Generation supplies inputs; geometric
//! preflight, trajectory assertions, thresholds, and replay judge them.

mod assertions;
mod report;

pub use report::{Coverage, SuiteReport};

use crate::{
    CanonicalScenario, RunBundle, RunOptions,
    bundle::{RunMetrics, canonical_hash},
    decode_scenario,
    generator::spaces::{
        self, GenerationAttempt, GenerationManifest, SpaceConfig, SpaceGenerationError,
    },
    reachability::{self, ReachabilityReport},
    run, validate, verify_run_bundle,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

pub const EVALUATION_VERSION: &str = "2";

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(default, deny_unknown_fields)]
pub struct Thresholds {
    pub min_evacuated_fraction: Option<f64>,
    pub max_clearance_time_s: Option<f64>,
    pub max_queue_wait_agent_seconds: Option<f64>,
    pub max_avoidance_fallback_steps: Option<u64>,
    pub max_disc_overlap_m: Option<f64>,
    pub max_swept_disc_overlap_m: Option<f64>,
}

impl Thresholds {
    pub fn validate(&self) -> Result<(), String> {
        if self
            .min_evacuated_fraction
            .is_some_and(|value| !value.is_finite() || !(0.0..=1.0).contains(&value))
        {
            return Err("min_evacuated_fraction must be in [0,1]".into());
        }
        for (name, value) in [
            ("max_clearance_time_s", self.max_clearance_time_s),
            (
                "max_queue_wait_agent_seconds",
                self.max_queue_wait_agent_seconds,
            ),
            ("max_disc_overlap_m", self.max_disc_overlap_m),
            ("max_swept_disc_overlap_m", self.max_swept_disc_overlap_m),
        ] {
            if value.is_some_and(|value| !value.is_finite() || value < 0.0) {
                return Err(format!("{name} must be finite and nonnegative"));
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(default, deny_unknown_fields)]
pub struct EvaluationOptions {
    pub thresholds: Thresholds,
    /// Reconstruction executes the scenario a second time and compares the
    /// complete bundle. Disabling it is recorded in every report.
    pub verify_replay: bool,
}

impl Default for EvaluationOptions {
    fn default() -> Self {
        Self {
            thresholds: Thresholds::default(),
            verify_replay: true,
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExpectedReachability {
    #[default]
    Reachable,
    Unreachable,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Fixture {
    pub name: String,
    pub scenario: CanonicalScenario,
    #[serde(default)]
    pub expected: ExpectedReachability,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct SuiteConfig {
    pub generation: SpaceConfig,
    pub seeds: Vec<u64>,
    pub evaluation: EvaluationOptions,
    pub fixtures: Vec<Fixture>,
}

impl Default for SuiteConfig {
    fn default() -> Self {
        Self {
            generation: SpaceConfig::default(),
            seeds: (0..8).collect(),
            evaluation: EvaluationOptions::default(),
            fixtures: vec![],
        }
    }
}

impl SuiteConfig {
    pub fn validate(&self) -> Result<(), String> {
        self.generation
            .validate()
            .map_err(|error| error.to_string())?;
        self.evaluation.thresholds.validate()?;
        if self.seeds.len() + self.fixtures.len() == 0
            || self.seeds.len() + self.fixtures.len() > 256
        {
            return Err("a suite must contain 1..=256 generated cases and fixtures".into());
        }
        if self.seeds.iter().collect::<BTreeSet<_>>().len() != self.seeds.len() {
            return Err("suite seeds must be unique".into());
        }
        let mut names: BTreeSet<_> = self
            .seeds
            .iter()
            .map(|seed| format!("seed-{seed}"))
            .collect();
        for fixture in &self.fixtures {
            fixture
                .scenario
                .check_schema()
                .map_err(|error| error.to_string())?;
            if fixture.name.trim().is_empty() || !names.insert(fixture.name.clone()) {
                return Err("case names must be nonempty and unique".into());
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CheckFailure {
    pub check: String,
    pub detail: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CaseReport {
    pub name: String,
    pub seed: Option<u64>,
    pub expected: ExpectedReachability,
    pub passed: bool,
    pub failures: Vec<CheckFailure>,
    pub generation_attempts: Vec<GenerationAttempt>,
    pub reachability: Option<ReachabilityReport>,
    pub metrics: Option<RunMetrics>,
    pub replay_verified: bool,
    pub scenario_hash: Option<String>,
    pub bundle_hash: Option<String>,
    /// Hash of tile arrangements only, excluding seeds, names, and search stats.
    pub layout_hash: Option<String>,
    pub coverage: Coverage,
}

impl CaseReport {
    fn new(name: String, seed: Option<u64>, expected: ExpectedReachability) -> Self {
        Self {
            name,
            seed,
            expected,
            passed: false,
            failures: vec![],
            generation_attempts: vec![],
            reachability: None,
            metrics: None,
            replay_verified: false,
            scenario_hash: None,
            bundle_hash: None,
            layout_hash: None,
            coverage: Coverage::default(),
        }
    }

    fn fail(&mut self, check: &str, detail: impl Into<String>) {
        self.failures.push(CheckFailure {
            check: check.into(),
            detail: detail.into(),
        });
    }
}

/// The CLI persists these artifacts case by case, so large suites need not
/// retain every full trajectory in memory.
#[derive(Debug)]
pub struct CaseEvaluation {
    pub report: CaseReport,
    pub input_json: Option<String>,
    pub generation: Option<GenerationManifest>,
    pub bundle: Option<RunBundle>,
}

#[must_use]
pub fn evaluate_seed(
    seed: u64,
    config: &SpaceConfig,
    options: &EvaluationOptions,
) -> CaseEvaluation {
    let name = format!("seed-{seed}");
    match spaces::generate(seed, config) {
        Ok(space) => {
            let mut case =
                evaluate_json(&name, &space.json, ExpectedReachability::Reachable, options);
            case.report.seed = Some(seed);
            case.report
                .generation_attempts
                .clone_from(&space.manifest.attempts);
            case.report.layout_hash = Some(canonical_hash(
                &space
                    .manifest
                    .floors
                    .iter()
                    .map(|floor| &floor.solution.cells)
                    .collect::<Vec<_>>(),
            ));
            case.report.coverage.add_layout(&space.manifest);
            case.generation = Some(space.manifest);
            case
        }
        Err(error) => {
            let mut report = CaseReport::new(name, Some(seed), ExpectedReachability::Reachable);
            report.fail("generation", error.to_string());
            if let SpaceGenerationError::Exhausted { attempts } = error {
                report.generation_attempts = attempts;
            }
            CaseEvaluation {
                report,
                input_json: None,
                generation: None,
                bundle: None,
            }
        }
    }
}

/// Authored fixtures complement WFC cases. An expected-unreachable fixture
/// passes only when it is valid source and geometric preflight rejects a
/// journey; it is not run. Simulation-metric thresholds apply only to reachable cases.
#[must_use]
pub fn evaluate_json(
    name: &str,
    source: &str,
    expected: ExpectedReachability,
    options: &EvaluationOptions,
) -> CaseEvaluation {
    let mut case = CaseEvaluation {
        report: CaseReport::new(name.into(), None, expected),
        input_json: Some(source.into()),
        generation: None,
        bundle: None,
    };
    if let Err(error) = options.thresholds.validate() {
        case.report.fail("configuration", error);
        return case;
    }
    let scenario = match decode_scenario(source) {
        Ok(scenario) => scenario,
        Err(error) => {
            case.report.fail("parse", error.to_string());
            return case;
        }
    };
    if let Err(errors) = validate(&scenario) {
        case.report.fail("validation", format!("{errors:?}"));
        return case;
    }
    case.report.scenario_hash = Some(canonical_hash(&CanonicalScenario::from(scenario.clone())));
    for connector in &scenario.connectors {
        *case
            .report
            .coverage
            .connector_kinds
            .entry(connector.kind().as_str().into())
            .or_default() += 1;
    }
    let reachability = match reachability::check(&scenario) {
        Ok(report) => report,
        Err(error) => {
            case.report.fail("reachability", error.to_string());
            return case;
        }
    };
    let reachable = reachability.is_reachable();
    case.report.reachability = Some(reachability);
    if expected == ExpectedReachability::Unreachable {
        if reachable {
            case.report.fail(
                "expected_unreachable",
                "all declared journeys have geometric paths",
            );
        }
        case.report.passed = case.report.failures.is_empty();
        return case;
    }
    if !reachable {
        case.report.fail(
            "reachability",
            "one or more declared journeys have no radius-clear geometric path",
        );
        return case;
    }
    let bundle = match run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    ) {
        Ok(bundle) => bundle,
        Err(error) => {
            case.report.fail("runtime", error.to_string());
            return case;
        }
    };
    finish_bundle(&bundle, options, &mut case.report);
    case.bundle = Some(bundle);
    case
}

/// Recheck saved full-step evidence without executing a new source run.
/// Optional reconstruction independently checks the recorded dynamics.
#[must_use]
pub fn evaluate_bundle(name: &str, bundle: &RunBundle, options: &EvaluationOptions) -> CaseReport {
    let mut report = CaseReport::new(name.into(), None, ExpectedReachability::Reachable);
    if let Err(error) = options.thresholds.validate() {
        report.fail("configuration", error);
        return report;
    }
    if !bundle.verifies_hash()
        || bundle.scenario_hash != canonical_hash(&bundle.scenario)
        || bundle.scenario.check_schema().is_err()
    {
        report.fail(
            "integrity",
            "bundle or current scenario hash/schema is invalid",
        );
        return report;
    }
    report.scenario_hash = Some(bundle.scenario_hash.clone());
    for connector in &bundle.scenario.scenario.connectors {
        *report
            .coverage
            .connector_kinds
            .entry(connector.kind().as_str().into())
            .or_default() += 1;
    }
    match reachability::check(&bundle.scenario.scenario) {
        Ok(reachability) => {
            if !reachability.is_reachable() {
                report.fail(
                    "reachability",
                    "one or more declared journeys have no radius-clear geometric path",
                );
            }
            report.reachability = Some(reachability);
        }
        Err(error) => report.fail("reachability", error.to_string()),
    }
    if report.failures.is_empty() {
        finish_bundle(bundle, options, &mut report);
    }
    report
}

fn finish_bundle(bundle: &RunBundle, options: &EvaluationOptions, report: &mut CaseReport) {
    assertions::check(bundle, &options.thresholds, report);
    if options.verify_replay {
        match verify_run_bundle(bundle) {
            Ok(crate::BundleVerification::Reconstructed) => report.replay_verified = true,
            Ok(crate::BundleVerification::HashOnlyLegacy) => report.fail(
                "replay",
                "current evaluation unexpectedly produced a legacy bundle",
            ),
            Err(error) => report.fail("replay", error.to_string()),
        }
    }
    report.coverage.add_metrics(&bundle.metrics);
    report.metrics = Some(bundle.metrics.clone());
    report.bundle_hash = Some(bundle.bundle_hash.clone());
    report.passed = report.failures.is_empty();
}
