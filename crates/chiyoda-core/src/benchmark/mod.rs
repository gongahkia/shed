//! Fixed-input regression benchmarks. These contracts test the reference
//! runtime; their limits are not empirical pedestrian-model calibration.

pub mod comparison;
pub mod diagnostics;
pub mod profiles;

use crate::{
    RunBundle,
    bundle::canonical_hash,
    evaluation::{
        self, CaseEvaluation, CaseReport, CheckFailure, EvaluationOptions, ExpectedReachability,
    },
};
use diagnostics::{Diagnosis, DiagnosticOptions};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

pub const BENCHMARK_VERSION: &str = "1";

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum QueueMechanism {
    #[default]
    Lift,
    Connector,
    Gate,
    Exit,
}

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct BehaviorChecks {
    pub min_queued_agents: BTreeMap<QueueMechanism, u32>,
    pub min_queue_wait_agent_seconds: BTreeMap<QueueMechanism, f64>,
    /// Named audit events ensure the intended operation actually occurred.
    pub min_events: BTreeMap<String, u32>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BenchmarkCase {
    pub id: String,
    pub description: String,
    /// Exact source is embedded, never regenerated during a benchmark run.
    pub source: String,
    #[serde(default)]
    pub expected: ExpectedReachability,
    #[serde(default)]
    pub evaluation: EvaluationOptions,
    #[serde(default)]
    pub checks: BehaviorChecks,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BenchmarkSuite {
    pub benchmark_version: String,
    pub name: String,
    #[serde(default)]
    pub diagnostics: DiagnosticOptions,
    pub cases: Vec<BenchmarkCase>,
}

impl BenchmarkSuite {
    pub fn validate(&self) -> Result<(), BenchmarkError> {
        if self.benchmark_version != BENCHMARK_VERSION {
            return Err(invalid("unsupported benchmark version"));
        }
        if self.name.trim().is_empty() || self.cases.is_empty() || self.cases.len() > 256 {
            return Err(invalid("suites need a name and 1..=256 cases"));
        }
        self.diagnostics.validate().map_err(invalid)?;
        let mut ids = BTreeSet::new();
        for case in &self.cases {
            if case.id.trim().is_empty() || !ids.insert(&case.id) {
                return Err(invalid("case identifiers must be nonempty and unique"));
            }
            case.evaluation.thresholds.validate().map_err(invalid)?;
            if case
                .checks
                .min_queue_wait_agent_seconds
                .values()
                .any(|value| !value.is_finite() || *value < 0.0)
            {
                return Err(invalid(
                    "minimum queue waits must be finite and nonnegative",
                ));
            }
            if case.source.len() > 4 * 1024 * 1024 {
                return Err(invalid("a benchmark source must not exceed 4 MiB"));
            }
            if case
                .checks
                .min_events
                .keys()
                .any(|kind| kind.trim().is_empty())
            {
                return Err(invalid("event checks must name nonempty audit event kinds"));
            }
            if case.expected == ExpectedReachability::Unreachable
                && case.checks != BehaviorChecks::default()
            {
                return Err(invalid(
                    "unreachable fixtures cannot require simulated queues or events",
                ));
            }
        }
        Ok(())
    }

    /// Runtime versions are deliberately absent: fixed inputs are compared
    /// across runtime revisions. Analysis and expectation changes are inputs.
    #[must_use]
    pub fn input_hash(&self, case: &BenchmarkCase) -> String {
        canonical_hash(&(
            &case.source,
            case.expected,
            &case.evaluation,
            &case.checks,
            &self.diagnostics,
        ))
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CaseResult {
    pub id: String,
    pub input_hash: String,
    pub evaluation: CaseReport,
    pub diagnosis: Diagnosis,
}

#[derive(Debug)]
pub struct CaseRun {
    pub result: CaseResult,
    pub artifacts: CaseEvaluation,
}

/// Run one definition with the same full-step assertions and reconstruction
/// used by ordinary evaluations, then check the benchmark-specific behavior.
pub fn run_case(suite: &BenchmarkSuite, index: usize) -> Result<CaseRun, BenchmarkError> {
    suite.validate()?;
    let case = suite
        .cases
        .get(index)
        .ok_or_else(|| invalid("case index is out of bounds"))?;
    let mut artifacts =
        evaluation::evaluate_source(&case.id, &case.source, case.expected, &case.evaluation);
    if let Some(bundle) = &artifacts.bundle {
        check_behavior(case, bundle, &mut artifacts.report);
    }
    let diagnosis = artifacts
        .bundle
        .as_ref()
        .map_or_else(Diagnosis::default, |bundle| {
            diagnostics::analyze(bundle, &suite.diagnostics)
        });
    let result = CaseResult {
        id: case.id.clone(),
        input_hash: suite.input_hash(case),
        evaluation: artifacts.report.clone(),
        diagnosis,
    };
    Ok(CaseRun { result, artifacts })
}

fn check_behavior(case: &BenchmarkCase, bundle: &RunBundle, report: &mut CaseReport) {
    for (mechanism, minimum) in &case.checks.min_queued_agents {
        let observed = match mechanism {
            QueueMechanism::Lift => bundle.metrics.queued_for_lift_agents,
            QueueMechanism::Connector => bundle.metrics.queued_for_connector_agents,
            QueueMechanism::Gate => bundle.metrics.queued_for_gate_agents,
            QueueMechanism::Exit => bundle.metrics.queued_for_exit_agents,
        };
        if observed < *minimum {
            report.failures.push(CheckFailure {
                check: format!("min_queued_agents.{mechanism:?}"),
                detail: format!("observed {observed}, required >= {minimum}"),
            });
        }
    }
    for (mechanism, minimum) in &case.checks.min_queue_wait_agent_seconds {
        let observed = bundle
            .metrics
            .queue_metrics
            .as_ref()
            .map(|queue| match mechanism {
                QueueMechanism::Lift => queue.lift.cumulative_wait_agent_seconds,
                QueueMechanism::Connector => queue.connector.cumulative_wait_agent_seconds,
                QueueMechanism::Gate => queue.gate.cumulative_wait_agent_seconds,
                QueueMechanism::Exit => queue.exit.cumulative_wait_agent_seconds,
            });
        if observed.is_none_or(|value| value < *minimum) {
            report.failures.push(CheckFailure {
                check: format!("min_queue_wait_agent_seconds.{mechanism:?}"),
                detail: format!("observed {observed:?}, required >= {minimum}"),
            });
        }
    }
    for (kind, minimum) in &case.checks.min_events {
        let observed = bundle
            .events
            .iter()
            .filter(|event| &event.kind == kind)
            .count();
        if observed < *minimum as usize {
            report.failures.push(CheckFailure {
                check: format!("min_events.{kind}"),
                detail: format!("observed {observed}, required >= {minimum}"),
            });
        }
    }
    report.passed = report.failures.is_empty();
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BenchmarkReport {
    pub benchmark_version: String,
    pub runtime_version: String,
    pub label: Option<String>,
    pub suite: BenchmarkSuite,
    pub suite_hash: String,
    pub cases: Vec<CaseResult>,
    pub passed: u32,
    pub failed: u32,
    pub report_hash: String,
}

impl BenchmarkReport {
    pub fn new(suite: BenchmarkSuite, cases: Vec<CaseResult>) -> Result<Self, BenchmarkError> {
        Self::new_labeled(suite, cases, None)
    }

    pub fn new_labeled(
        suite: BenchmarkSuite,
        cases: Vec<CaseResult>,
        label: Option<String>,
    ) -> Result<Self, BenchmarkError> {
        let passed = u32::try_from(cases.iter().filter(|case| case.evaluation.passed).count())
            .map_err(|_| invalid("too many cases"))?;
        let count = u32::try_from(cases.len()).map_err(|_| invalid("too many cases"))?;
        let mut report = Self {
            benchmark_version: BENCHMARK_VERSION.into(),
            runtime_version: crate::RUNTIME_VERSION.into(),
            label,
            suite_hash: canonical_hash(&suite),
            suite,
            cases,
            passed,
            failed: count - passed,
            report_hash: String::new(),
        };
        report.validate_contents()?;
        report.report_hash = canonical_hash(&report);
        Ok(report)
    }

    pub fn verify(&self) -> Result<(), BenchmarkError> {
        self.validate_contents()?;
        let mut unsigned = self.clone();
        unsigned.report_hash.clear();
        if canonical_hash(&unsigned) != self.report_hash {
            return Err(invalid("benchmark report hash does not match its contents"));
        }
        Ok(())
    }

    fn validate_contents(&self) -> Result<(), BenchmarkError> {
        self.suite.validate()?;
        if self
            .label
            .as_ref()
            .is_some_and(|label| label.trim().is_empty())
        {
            return Err(invalid("run labels must be nonempty when present"));
        }
        if self.benchmark_version != BENCHMARK_VERSION
            || self.runtime_version.trim().is_empty()
            || self.suite_hash != canonical_hash(&self.suite)
        {
            return Err(invalid(
                "benchmark report versions or suite hash are invalid",
            ));
        }
        if self.cases.len() != self.suite.cases.len() {
            return Err(invalid("report must include every suite case"));
        }
        for (definition, result) in self.suite.cases.iter().zip(&self.cases) {
            if result.id != definition.id
                || result.evaluation.name != definition.id
                || result.input_hash != self.suite.input_hash(definition)
                || result.evaluation.expected != definition.expected
            {
                return Err(invalid(
                    "case identity or fixed-input hash does not match the suite",
                ));
            }
            if result.evaluation.passed != result.evaluation.failures.is_empty() {
                return Err(invalid("case status disagrees with its failures"));
            }
            if result.evaluation.passed
                && definition.expected == ExpectedReachability::Reachable
                && result.evaluation.metrics.is_none()
            {
                return Err(invalid("a passing simulated case requires metrics"));
            }
            if result.evaluation.passed
                && definition.expected == ExpectedReachability::Reachable
                && definition.evaluation.verify_replay
                && !result.evaluation.replay_verified
            {
                return Err(invalid(
                    "a passing replay-checked case requires reconstruction evidence",
                ));
            }
            result.diagnosis.validate().map_err(invalid)?;
            if let Some(metrics) = &result.evaluation.metrics {
                validate_metrics(metrics)?;
            }
        }
        let passed = self
            .cases
            .iter()
            .filter(|case| case.evaluation.passed)
            .count();
        if passed != self.passed as usize || self.cases.len() - passed != self.failed as usize {
            return Err(invalid("benchmark summary disagrees with case outcomes"));
        }
        Ok(())
    }
}

fn validate_metrics(metrics: &crate::bundle::RunMetrics) -> Result<(), BenchmarkError> {
    for metric in comparison::Metric::ALL {
        comparison::metric_value(metrics, metric)?;
    }
    let finite = |value: f64| value.is_finite() && value >= 0.0;
    if [
        metrics.clearance_time_s,
        metrics.last_exit_time_s,
        metrics.mean_exit_time_s,
    ]
    .into_iter()
    .flatten()
    .any(|value| !finite(value))
    {
        return Err(invalid("timing metrics must be finite and nonnegative"));
    }
    if let Some(queue) = &metrics.queue_metrics {
        let mut resources = vec![&queue.lift, &queue.connector, &queue.gate, &queue.exit];
        if let Some(by_resource) = &queue.by_resource {
            resources.extend(
                by_resource
                    .lifts
                    .values()
                    .chain(by_resource.connectors.values())
                    .chain(by_resource.gates.values())
                    .chain(by_resource.exits.values()),
            );
        }
        if resources.iter().any(|queue| {
            !finite(queue.cumulative_wait_agent_seconds)
                || queue.ever_queued_agents > metrics.total_agents
                || queue.peak_waiting_agents > metrics.total_agents
        }) {
            return Err(invalid("invalid queue telemetry"));
        }
    }
    if metrics.movement_metrics.as_ref().is_some_and(|movement| {
        !finite(movement.cumulative_local_clearance_adjustment_m)
            || !finite(movement.maximum_local_clearance_adjustment_m)
    }) {
        return Err(invalid("invalid movement adjustment telemetry"));
    }
    Ok(())
}

#[derive(Debug, thiserror::Error)]
pub enum BenchmarkError {
    #[error("invalid benchmark: {0}")]
    Invalid(String),
}

pub(crate) fn invalid(message: impl Into<String>) -> BenchmarkError {
    BenchmarkError::Invalid(message.into())
}
