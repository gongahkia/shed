use super::{STUDY_VERSION, StudyCaseResult, StudyConfig, runner::case_name};
use crate::{RUNTIME_VERSION, bundle::canonical_hash};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GroupKey {
    pub pattern: String,
    pub scale: String,
    pub load: String,
    pub policy: String,
}

impl From<&StudyCaseResult> for GroupKey {
    fn from(case: &StudyCaseResult) -> Self {
        Self {
            pattern: case.key.pattern.clone(),
            scale: case.key.scale.clone(),
            load: case.key.load.clone(),
            policy: case.key.policy.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Distribution {
    pub samples: u32,
    pub missing: u32,
    pub mean: f64,
    pub min: f64,
    pub median: f64,
    /// Nearest-rank percentile, with no distributional confidence claim.
    pub p95: f64,
    pub max: f64,
}

impl Distribution {
    fn new(mut values: Vec<f64>, requested: usize) -> Option<Self> {
        if values.is_empty() {
            return None;
        }
        values.sort_by(f64::total_cmp);
        let count = values.len();
        let median = if count.is_multiple_of(2) {
            f64::midpoint(values[count / 2 - 1], values[count / 2])
        } else {
            values[count / 2]
        };
        Some(Self {
            samples: u32::try_from(count).expect("bounded study"),
            missing: u32::try_from(requested - count).expect("bounded study"),
            mean: values.iter().sum::<f64>()
                / f64::from(u32::try_from(count).expect("bounded study")),
            min: values[0],
            median,
            p95: values[(count * 95).div_ceil(100) - 1],
            max: values[count - 1],
        })
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GroupSummary {
    pub key: GroupKey,
    pub requested: u32,
    pub passed: u32,
    pub failed: u32,
    pub measured: u32,
    pub fully_evacuated: u32,
    pub replay_verified: u32,
    pub unique_layouts: u32,
    pub failures: BTreeMap<String, u32>,
    pub metrics: BTreeMap<String, Distribution>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StudyComparison {
    /// Policy, pattern, scale, or load. The first declared value is the reference.
    pub axis: String,
    pub baseline: GroupKey,
    pub current: GroupKey,
    pub requested_pairs: u32,
    pub measured_pairs: u32,
    /// Per-seed current-minus-baseline differences. Each metric has its own
    /// sample count; clearance differences require both runs to finish.
    pub deltas: BTreeMap<String, Distribution>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StudyReport {
    pub study_version: String,
    pub runtime_version: String,
    pub config_hash: String,
    pub config: StudyConfig,
    pub requested: u32,
    pub passed: u32,
    pub failed: u32,
    /// Counts layout searches once, regardless of load/policy fanout.
    pub requested_layouts: u32,
    pub generated_layouts: u32,
    pub unique_layouts: u32,
    pub generation_attempts: u32,
    pub rejected_generation_attempts: u32,
    pub groups: Vec<GroupSummary>,
    pub comparisons: Vec<StudyComparison>,
    pub cases: Vec<StudyCaseResult>,
    pub report_hash: String,
}

impl StudyReport {
    pub fn from_cases(config: StudyConfig, cases: Vec<StudyCaseResult>) -> Result<Self, String> {
        config.validate()?;
        validate_cases(&config, &cases)?;
        let groups = groups(&cases);
        let comparisons = comparisons(&config, &cases, &groups);
        let mut layouts = BTreeMap::new();
        for case in &cases {
            layouts
                .entry((&case.key.pattern, &case.key.scale, case.key.seed))
                .or_insert(case);
        }
        let unique_layouts = layouts
            .values()
            .filter_map(|case| case.layout_hash.as_ref())
            .collect::<BTreeSet<_>>()
            .len();
        let attempts: Vec<_> = layouts
            .values()
            .flat_map(|case| &case.generation_attempts)
            .collect();
        let passed = cases.iter().filter(|case| case.passed).count();
        let mut report = Self {
            study_version: STUDY_VERSION.into(),
            runtime_version: RUNTIME_VERSION.into(),
            config_hash: canonical_hash(&config),
            requested: count(cases.len()),
            passed: count(passed),
            failed: count(cases.len() - passed),
            requested_layouts: count(layouts.len()),
            generated_layouts: count(
                layouts
                    .values()
                    .filter(|case| case.layout_hash.is_some())
                    .count(),
            ),
            unique_layouts: count(unique_layouts),
            generation_attempts: count(attempts.len()),
            rejected_generation_attempts: count(
                attempts
                    .iter()
                    .filter(|attempt| attempt.rejection.is_some())
                    .count(),
            ),
            config,
            groups,
            comparisons,
            cases,
            report_hash: String::new(),
        };
        report.report_hash = canonical_hash(&report);
        Ok(report)
    }

    pub fn verify(&self) -> Result<(), String> {
        if self.study_version != STUDY_VERSION || self.runtime_version.trim().is_empty() {
            return Err("unsupported study report version or empty runtime version".into());
        }
        let mut reconstructed = Self::from_cases(self.config.clone(), self.cases.clone())?;
        reconstructed
            .runtime_version
            .clone_from(&self.runtime_version);
        reconstructed.report_hash.clear();
        reconstructed.report_hash = canonical_hash(&reconstructed);
        if reconstructed != *self {
            return Err("study report summary, config, or hash does not match its cases".into());
        }
        Ok(())
    }
}

fn count(value: usize) -> u32 {
    u32::try_from(value).expect("bounded study counts")
}

fn validate_cases(config: &StudyConfig, cases: &[StudyCaseResult]) -> Result<(), String> {
    if cases.len() != config.case_count().expect("validated config") {
        return Err("study must include every requested case".into());
    }
    let mut controls = BTreeMap::new();
    let mut layouts = BTreeMap::new();
    for (coordinate, case) in config.coordinates().into_iter().zip(cases) {
        if case.key != config.key(coordinate) {
            return Err("study cases must match the configured matrix in order".into());
        }
        if case.setup_failure.is_some() {
            if case.evaluation.is_some()
                || case.context.is_some()
                || case.input_sha256.is_some()
                || case.passed
            {
                return Err("setup failure cannot contain a simulated result".into());
            }
        } else {
            let evaluation = case
                .evaluation
                .as_ref()
                .ok_or("evaluated case is missing evaluation")?;
            let context = case
                .context
                .as_ref()
                .ok_or("evaluated case is missing context")?;
            if evaluation.name != case_name(coordinate)
                || evaluation.passed != case.passed
                || evaluation.passed != evaluation.failures.is_empty()
                || case.input_sha256.is_none()
                || context.floors.len() != config.scales[coordinate[1]].floors as usize
                || context.total_people == 0
                || context.total_people
                    != context.floors.iter().map(|floor| floor.people).sum::<u32>()
                || evaluation.metrics.as_ref().is_some_and(|metrics| {
                    metrics.total_agents != context.total_people
                        || metrics.evacuated_agents > metrics.total_agents
                })
            {
                return Err(
                    "study case evaluation or population accounting is inconsistent".into(),
                );
            }
            let key = (
                &case.key.pattern,
                &case.key.scale,
                case.key.seed,
                &case.key.load,
            );
            if controls
                .insert(key, context)
                .is_some_and(|previous| previous != context)
            {
                return Err("communication policies must share identical controlled inputs".into());
            }
        }
        let layout_key = (&case.key.pattern, &case.key.scale, case.key.seed);
        let provenance = (
            &case.layout_hash,
            &case.generation_hash,
            &case.tile_counts,
            &case.generation_attempts,
        );
        if layouts
            .insert(layout_key, provenance)
            .is_some_and(|previous| previous != provenance)
        {
            return Err(
                "loads and policies must share the same generated layout and attempt log".into(),
            );
        }
    }
    Ok(())
}

fn groups(cases: &[StudyCaseResult]) -> Vec<GroupSummary> {
    let mut order = Vec::new();
    let mut grouped: BTreeMap<GroupKey, Vec<&StudyCaseResult>> = BTreeMap::new();
    for case in cases {
        let key = GroupKey::from(case);
        if !grouped.contains_key(&key) {
            order.push(key.clone());
        }
        grouped.entry(key).or_default().push(case);
    }
    order
        .into_iter()
        .map(|key| {
            let cases = &grouped[&key];
            let observations: Vec<_> = cases.iter().map(|case| case.observations()).collect();
            let mut values = BTreeMap::<String, Vec<f64>>::new();
            for observation in &observations {
                for (metric, value) in observation {
                    values.entry(metric.clone()).or_default().push(*value);
                }
            }
            let mut failures = BTreeMap::new();
            for case in cases {
                if let Some(failure) = &case.setup_failure {
                    *failures.entry(failure.stage.clone()).or_default() += 1;
                }
                if let Some(evaluation) = &case.evaluation {
                    for failure in &evaluation.failures {
                        *failures.entry(failure.check.clone()).or_default() += 1;
                    }
                }
            }
            let passed = cases.iter().filter(|case| case.passed).count();
            GroupSummary {
                key,
                requested: count(cases.len()),
                passed: count(passed),
                failed: count(cases.len() - passed),
                measured: count(
                    observations
                        .iter()
                        .filter(|values| !values.is_empty())
                        .count(),
                ),
                fully_evacuated: count(
                    observations
                        .iter()
                        .filter(|values| values.contains_key("clearance_time_s"))
                        .count(),
                ),
                replay_verified: count(
                    cases
                        .iter()
                        .filter(|case| {
                            case.evaluation
                                .as_ref()
                                .is_some_and(|evaluation| evaluation.replay_verified)
                        })
                        .count(),
                ),
                unique_layouts: count(
                    cases
                        .iter()
                        .filter_map(|case| case.layout_hash.as_ref())
                        .collect::<BTreeSet<_>>()
                        .len(),
                ),
                failures,
                metrics: values
                    .into_iter()
                    .filter_map(|(metric, values)| {
                        Distribution::new(values, cases.len())
                            .map(|distribution| (metric, distribution))
                    })
                    .collect(),
            }
        })
        .collect()
}

fn comparisons(
    config: &StudyConfig,
    cases: &[StudyCaseResult],
    groups: &[GroupSummary],
) -> Vec<StudyComparison> {
    let indexed: BTreeMap<_, _> = cases
        .iter()
        .map(|case| ((GroupKey::from(case), case.key.seed), case.observations()))
        .collect();
    let mut results = Vec::new();
    for group in groups {
        for (axis, reference) in [
            ("pattern", &config.patterns[0].id),
            ("scale", &config.scales[0].id),
            ("load", &config.loads[0].id),
            ("policy", &config.policies[0].id),
        ] {
            let mut baseline = group.key.clone();
            let value = match axis {
                "pattern" => &mut baseline.pattern,
                "scale" => &mut baseline.scale,
                "load" => &mut baseline.load,
                _ => &mut baseline.policy,
            };
            if value == reference {
                continue;
            }
            value.clone_from(reference);
            let mut values = BTreeMap::<String, Vec<f64>>::new();
            let mut measured_pairs = 0;
            for seed in &config.seeds {
                let before = &indexed[&(baseline.clone(), *seed)];
                let after = &indexed[&(group.key.clone(), *seed)];
                if !before.is_empty() && !after.is_empty() {
                    measured_pairs += 1;
                }
                for (metric, current) in after {
                    if let Some(previous) = before.get(metric) {
                        values
                            .entry(metric.clone())
                            .or_default()
                            .push(current - previous);
                    }
                }
            }
            results.push(StudyComparison {
                axis: axis.into(),
                baseline,
                current: group.key.clone(),
                requested_pairs: count(config.seeds.len()),
                measured_pairs,
                deltas: values
                    .into_iter()
                    .filter_map(|(metric, values)| {
                        Distribution::new(values, config.seeds.len())
                            .map(|distribution| (metric, distribution))
                    })
                    .collect(),
            });
        }
    }
    results
}
