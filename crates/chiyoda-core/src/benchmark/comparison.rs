//! Compare verified reports by case identity and exact fixed-input contracts.
//! Missing cases, changed inputs, and unavailable required telemetry are
//! incompatible comparisons, never silently counted as improvements.

use super::{BenchmarkError, BenchmarkReport, CaseResult, invalid};
use crate::{
    bundle::{RunMetrics, canonical_hash},
    evaluation::ExpectedReachability,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Metric {
    EvacuatedFraction,
    ClearanceTimeS,
    QueueWaitAgentSeconds,
    AvoidanceFallbackSteps,
    DiscOverlapM,
    SweptDiscOverlapM,
}

impl Metric {
    pub const ALL: [Self; 6] = [
        Self::EvacuatedFraction,
        Self::ClearanceTimeS,
        Self::QueueWaitAgentSeconds,
        Self::AvoidanceFallbackSteps,
        Self::DiscOverlapM,
        Self::SweptDiscOverlapM,
    ];

    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::EvacuatedFraction => "evacuated_fraction",
            Self::ClearanceTimeS => "clearance_time_s",
            Self::QueueWaitAgentSeconds => "queue_wait_agent_seconds",
            Self::AvoidanceFallbackSteps => "avoidance_fallback_steps",
            Self::DiscOverlapM => "disc_overlap_m",
            Self::SweptDiscOverlapM => "swept_disc_overlap_m",
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Tolerance {
    pub absolute: f64,
    /// Fraction of the absolute baseline value: 0.05 means five percent.
    pub relative: f64,
}

impl Tolerance {
    fn validate(self) -> Result<(), BenchmarkError> {
        if [self.absolute, self.relative]
            .iter()
            .any(|value| !value.is_finite() || *value < 0.0)
        {
            return Err(invalid(
                "comparison tolerances must be finite and nonnegative",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ComparisonPolicy {
    pub default_tolerance: Tolerance,
    pub metrics: BTreeMap<Metric, Tolerance>,
}

impl ComparisonPolicy {
    pub fn validate(&self) -> Result<(), BenchmarkError> {
        self.default_tolerance.validate()?;
        for tolerance in self.metrics.values() {
            tolerance.validate()?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum MetricValue {
    Count(u64),
    Number(f64),
}

impl MetricValue {
    // Counts stay integers until after subtraction; a one-count regression
    // above 2^53 must not disappear through rounding the two operands first.
    #[allow(clippy::cast_precision_loss)]
    fn number(self) -> f64 {
        match self {
            Self::Count(value) => value as f64,
            Self::Number(value) => value,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MetricVerdict {
    Unchanged,
    Improved,
    Regressed,
    Unavailable,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MetricComparison {
    pub metric: Metric,
    pub baseline: Option<MetricValue>,
    pub current: Option<MetricValue>,
    /// Signed current minus baseline, in this metric's own units.
    pub delta: Option<f64>,
    pub relative_change: Option<f64>,
    pub allowed_deterioration: Option<f64>,
    pub verdict: MetricVerdict,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CaseVerdict {
    Unchanged,
    Improved,
    Regressed,
    Incompatible,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CaseComparison {
    pub id: String,
    pub baseline_index: Option<usize>,
    pub current_index: Option<usize>,
    pub verdict: CaseVerdict,
    pub reasons: Vec<String>,
    pub metrics: Vec<MetricComparison>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ComparisonReport {
    pub benchmark_version: String,
    pub baseline_report_hash: String,
    pub current_report_hash: String,
    pub baseline_runtime_version: String,
    pub baseline_label: Option<String>,
    pub current_label: Option<String>,
    pub current_runtime_version: String,
    pub policy: ComparisonPolicy,
    pub policy_hash: String,
    pub unchanged: u32,
    pub improved: u32,
    pub regressed: u32,
    pub incompatible: u32,
    pub cases: Vec<CaseComparison>,
}

impl ComparisonReport {
    #[must_use]
    pub fn is_success(&self) -> bool {
        self.regressed == 0 && self.incompatible == 0
    }
}

pub fn compare(
    baseline: &BenchmarkReport,
    current: &BenchmarkReport,
    policy: &ComparisonPolicy,
) -> Result<ComparisonReport, BenchmarkError> {
    baseline.verify()?;
    current.verify()?;
    policy.validate()?;
    if baseline.failed != 0 {
        return Err(invalid("a baseline must have no failed benchmark cases"));
    }
    let baseline_cases: BTreeMap<_, _> = baseline
        .cases
        .iter()
        .enumerate()
        .map(|(index, case)| (case.id.as_str(), (index, case)))
        .collect();
    let current_cases: BTreeMap<_, _> = current
        .cases
        .iter()
        .enumerate()
        .map(|(index, case)| (case.id.as_str(), (index, case)))
        .collect();
    let mut ids: Vec<_> = baseline.cases.iter().map(|case| case.id.as_str()).collect();
    let known: BTreeSet<_> = ids.iter().copied().collect();
    ids.extend(
        current
            .cases
            .iter()
            .map(|case| case.id.as_str())
            .filter(|id| !known.contains(id)),
    );
    let cases = ids
        .iter()
        .map(|id| {
            compare_case(
                id,
                baseline_cases.get(id).copied(),
                current_cases.get(id).copied(),
                policy,
            )
        })
        .collect::<Result<Vec<_>, _>>()?;
    let count = |verdict| {
        u32::try_from(cases.iter().filter(|case| case.verdict == verdict).count())
            .expect("bounded case count")
    };
    Ok(ComparisonReport {
        benchmark_version: super::BENCHMARK_VERSION.into(),
        baseline_report_hash: baseline.report_hash.clone(),
        current_report_hash: current.report_hash.clone(),
        baseline_runtime_version: baseline.runtime_version.clone(),
        baseline_label: baseline.label.clone(),
        current_label: current.label.clone(),
        current_runtime_version: current.runtime_version.clone(),
        policy: policy.clone(),
        policy_hash: canonical_hash(policy),
        unchanged: count(CaseVerdict::Unchanged),
        improved: count(CaseVerdict::Improved),
        regressed: count(CaseVerdict::Regressed),
        incompatible: count(CaseVerdict::Incompatible),
        cases,
    })
}

fn compare_case(
    id: &str,
    baseline: Option<(usize, &CaseResult)>,
    current: Option<(usize, &CaseResult)>,
    policy: &ComparisonPolicy,
) -> Result<CaseComparison, BenchmarkError> {
    let mut comparison = CaseComparison {
        id: id.into(),
        baseline_index: baseline.map(|(index, _)| index),
        current_index: current.map(|(index, _)| index),
        verdict: CaseVerdict::Unchanged,
        reasons: vec![],
        metrics: vec![],
    };
    let (Some((_, baseline)), Some((_, current))) = (baseline, current) else {
        comparison.verdict = CaseVerdict::Incompatible;
        comparison.reasons.push(
            if baseline.is_none() {
                "case was added; no baseline exists"
            } else {
                "case is missing from the current run"
            }
            .into(),
        );
        return Ok(comparison);
    };
    if baseline.input_hash != current.input_hash {
        comparison.verdict = CaseVerdict::Incompatible;
        comparison
            .reasons
            .push("source, expectations, replay settings, or diagnostic options changed".into());
        return Ok(comparison);
    }
    if !current.evaluation.passed {
        comparison.verdict = CaseVerdict::Regressed;
        comparison.reasons.extend(
            current
                .evaluation
                .failures
                .iter()
                .map(|failure| format!("{}: {}", failure.check, failure.detail)),
        );
    }
    if baseline.evaluation.expected == ExpectedReachability::Unreachable {
        return Ok(comparison);
    }
    let (Some(before), Some(after)) = (&baseline.evaluation.metrics, &current.evaluation.metrics)
    else {
        comparison.verdict = CaseVerdict::Regressed;
        comparison
            .reasons
            .push("current simulation metrics are absent".into());
        return Ok(comparison);
    };
    for metric in Metric::ALL {
        let left = metric_value(before, metric)?;
        let right = metric_value(after, metric)?;
        if metric != Metric::ClearanceTimeS && (left.is_none() || right.is_none()) {
            comparison.verdict = CaseVerdict::Incompatible;
            comparison.reasons.push(format!(
                "{} telemetry is absent in one or both runs",
                metric.as_str()
            ));
            continue;
        }
        let result = compare_metric(
            metric,
            left,
            right,
            policy
                .metrics
                .get(&metric)
                .copied()
                .unwrap_or(policy.default_tolerance),
        )?;
        match result.verdict {
            MetricVerdict::Regressed if comparison.verdict != CaseVerdict::Incompatible => {
                comparison.verdict = CaseVerdict::Regressed;
            }
            MetricVerdict::Improved if comparison.verdict == CaseVerdict::Unchanged => {
                comparison.verdict = CaseVerdict::Improved;
            }
            _ => {}
        }
        comparison.metrics.push(result);
    }
    Ok(comparison)
}

pub(super) fn metric_value(
    metrics: &RunMetrics,
    metric: Metric,
) -> Result<Option<MetricValue>, BenchmarkError> {
    let movement = metrics.movement_metrics.as_ref();
    let value = match metric {
        Metric::EvacuatedFraction => {
            if metrics.evacuated_agents > metrics.total_agents {
                return Err(invalid("evacuated population exceeds total population"));
            }
            Some(MetricValue::Number(if metrics.total_agents == 0 {
                1.0
            } else {
                f64::from(metrics.evacuated_agents) / f64::from(metrics.total_agents)
            }))
        }
        Metric::ClearanceTimeS => {
            if metrics.clearance_time_s.is_some()
                && metrics.evacuated_agents != metrics.total_agents
            {
                return Err(invalid(
                    "partial evacuation cannot have system clearance time",
                ));
            }
            metrics.clearance_time_s.map(MetricValue::Number)
        }
        Metric::QueueWaitAgentSeconds => metrics.queue_metrics.as_ref().map(|queue| {
            MetricValue::Number(
                queue.lift.cumulative_wait_agent_seconds
                    + queue.connector.cumulative_wait_agent_seconds
                    + queue.gate.cumulative_wait_agent_seconds
                    + queue.exit.cumulative_wait_agent_seconds,
            )
        }),
        Metric::AvoidanceFallbackSteps => movement
            .and_then(|movement| movement.local_avoidance_constraint_fallback_steps)
            .map(MetricValue::Count),
        Metric::DiscOverlapM => movement
            .and_then(|movement| movement.on_surface_clearance_audit.as_ref())
            .map(|audit| MetricValue::Number(audit.maximum_disc_overlap_m)),
        Metric::SweptDiscOverlapM => movement
            .and_then(|movement| movement.swept_on_surface_clearance_audit.as_ref())
            .map(|audit| MetricValue::Number(audit.maximum_swept_disc_overlap_m)),
    };
    if value.is_some_and(|value| !value.number().is_finite() || value.number() < 0.0) {
        return Err(invalid(format!("invalid {} metric", metric.as_str())));
    }
    Ok(value)
}

fn compare_metric(
    metric: Metric,
    baseline: Option<MetricValue>,
    current: Option<MetricValue>,
    tolerance: Tolerance,
) -> Result<MetricComparison, BenchmarkError> {
    let mut result = MetricComparison {
        metric,
        baseline,
        current,
        delta: None,
        relative_change: None,
        allowed_deterioration: None,
        verdict: MetricVerdict::Unchanged,
    };
    let (Some(before), Some(after)) = (baseline, current) else {
        result.verdict = match (baseline, current) {
            (None, None) => MetricVerdict::Unavailable,
            (Some(_), None) => MetricVerdict::Regressed,
            (None, Some(_)) => MetricVerdict::Improved,
            _ => unreachable!(),
        };
        return Ok(result);
    };
    let delta = difference(before, after);
    let allowance = tolerance.absolute + tolerance.relative * before.number().abs();
    if !delta.is_finite() || !allowance.is_finite() {
        return Err(invalid(
            "metric difference or tolerance calculation overflowed",
        ));
    }
    result.delta = Some(delta);
    if before.number() != 0.0 {
        let relative = delta / before.number();
        if relative.is_finite() {
            result.relative_change = Some(relative);
        }
    }
    result.allowed_deterioration = Some(allowance);
    let deterioration = if metric == Metric::EvacuatedFraction {
        -delta
    } else {
        delta
    };
    let boundary = tolerance_boundary(before, after, delta, allowance);
    if !boundary.is_finite() {
        return Err(invalid("tolerance boundary overflowed"));
    }
    result.verdict = if deterioration > boundary {
        MetricVerdict::Regressed
    } else if deterioration < -boundary {
        MetricVerdict::Improved
    } else {
        MetricVerdict::Unchanged
    };
    Ok(result)
}

fn tolerance_boundary(before: MetricValue, after: MetricValue, delta: f64, allowance: f64) -> f64 {
    if allowance == 0.0 {
        return 0.0;
    }
    // A declared nonzero decimal boundary tolerates representational roundoff.
    // Integer operands have already been subtracted exactly, so their scale
    // must be the delta rather than a potentially enormous baseline count.
    let scale = match (before, after) {
        (MetricValue::Count(_), MetricValue::Count(_)) => delta.abs().max(allowance),
        _ => before
            .number()
            .abs()
            .max(after.number().abs())
            .max(allowance),
    };
    allowance + f64::EPSILON * scale * 4.0
}

#[allow(clippy::cast_precision_loss)] // subtract counts exactly before converting the signed display delta
fn difference(before: MetricValue, after: MetricValue) -> f64 {
    match (before, after) {
        (MetricValue::Count(before), MetricValue::Count(after)) => {
            (i128::from(after) - i128::from(before)) as f64
        }
        _ => after.number() - before.number(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tolerances_handle_zero_baselines_counts_and_metric_direction() {
        let number = |value| Some(MetricValue::Number(value));
        let tolerance = Tolerance {
            absolute: 1.0,
            relative: 0.1,
        };
        assert_eq!(
            compare_metric(
                Metric::ClearanceTimeS,
                number(10.0),
                number(12.0),
                tolerance
            )
            .unwrap()
            .verdict,
            MetricVerdict::Unchanged
        );
        assert_eq!(
            compare_metric(
                Metric::ClearanceTimeS,
                number(10.0),
                number(12.01),
                tolerance
            )
            .unwrap()
            .verdict,
            MetricVerdict::Regressed
        );
        assert_eq!(
            compare_metric(
                Metric::EvacuatedFraction,
                number(1.0),
                number(0.5),
                Tolerance::default()
            )
            .unwrap()
            .verdict,
            MetricVerdict::Regressed
        );
        assert_eq!(
            compare_metric(
                Metric::ClearanceTimeS,
                number(8.2),
                number(8.3),
                Tolerance {
                    absolute: 0.1,
                    relative: 0.0
                }
            )
            .unwrap()
            .verdict,
            MetricVerdict::Unchanged
        );
        let zero = compare_metric(
            Metric::DiscOverlapM,
            number(0.0),
            number(0.001),
            Tolerance {
                absolute: 0.0,
                relative: 1.0,
            },
        )
        .unwrap();
        assert_eq!(zero.verdict, MetricVerdict::Regressed);
        assert!(zero.relative_change.is_none());
        let count = compare_metric(
            Metric::AvoidanceFallbackSteps,
            Some(MetricValue::Count(1 << 54)),
            Some(MetricValue::Count((1 << 54) + 1)),
            Tolerance::default(),
        )
        .unwrap();
        assert_eq!(count.delta, Some(1.0));
        assert_eq!(count.verdict, MetricVerdict::Regressed);
    }

    #[test]
    fn absence_of_clearance_remains_absent_instead_of_using_last_exit_time() {
        let number = |value| Some(MetricValue::Number(value));
        assert_eq!(
            compare_metric(
                Metric::ClearanceTimeS,
                number(10.0),
                None,
                Tolerance::default()
            )
            .unwrap()
            .verdict,
            MetricVerdict::Regressed
        );
        assert_eq!(
            compare_metric(
                Metric::ClearanceTimeS,
                None,
                number(10.0),
                Tolerance::default()
            )
            .unwrap()
            .verdict,
            MetricVerdict::Improved
        );
        assert_eq!(
            compare_metric(Metric::ClearanceTimeS, None, None, Tolerance::default())
                .unwrap()
                .verdict,
            MetricVerdict::Unavailable
        );
        assert!(
            Tolerance {
                absolute: f64::NAN,
                relative: 0.0
            }
            .validate()
            .is_err()
        );
        assert!(
            serde_json::from_str::<ComparisonPolicy>(
                r#"{"metrics":{"clearence_time_s":{"absolute":1}}}"#
            )
            .is_err()
        );
    }
}
