use super::{
    ArmPlan, EXPERIMENT_VERSION, ExperimentError, ExperimentPlan, ExperimentRequest, Issue,
    ScenarioInspection, changes,
};
use crate::{
    CanonicalScenario, RUNTIME_VERSION, bundle::canonical_hash, integration_step_count,
    reachability, validate,
};
use std::collections::BTreeSet;

pub(super) fn issue(code: &str, path: &str, message: impl Into<String>) -> Issue {
    Issue {
        code: code.into(),
        path: path.into(),
        message: message.into(),
    }
}

/// Inspect without simulating. Bounds are checked before population expansion
/// or geometric decomposition; invalid candidates remain useful tool results.
#[must_use]
pub fn inspect(document: &CanonicalScenario) -> ScenarioInspection {
    let scenario = &document.scenario;
    let people = scenario
        .agents
        .iter()
        .map(|group| u64::from(group.count))
        .sum();
    let steps = integration_step_count(scenario.duration_s, scenario.timestep_s);
    let mut issues = vec![];
    if let Err(error) = document.check_schema() {
        issues.push(issue("schema", "schema_version", error.to_string()));
    }
    if people > 1024 || people == 0 {
        issues.push(issue(
            "resource_limit",
            "scenario.agents",
            "experiments require 1..=1024 people per arm",
        ));
    }
    if steps == 0 || steps > 10_000 {
        issues.push(issue(
            "resource_limit",
            "scenario.timestep_s",
            "experiments require 1..=10000 integration steps per arm",
        ));
    }
    let objects: usize = serde_json::to_value(scenario)
        .expect("scenario object")
        .as_object()
        .expect("scenario object")
        .values()
        .filter_map(serde_json::Value::as_array)
        .map(Vec::len)
        .sum();
    if objects > 1024 {
        issues.push(issue(
            "resource_limit",
            "scenario",
            "experiments permit at most 1024 declared objects and interventions per arm",
        ));
    }
    // Full journey evidence requires distinct nanosecond-quantized timestamps,
    // including a potentially shorter final integration interval.
    #[allow(clippy::cast_precision_loss)]
    let prior = (steps.saturating_sub(1) as f64) * scenario.timestep_s;
    if scenario.duration_s > 86_400.0
        || scenario.timestep_s < 1e-9
        || (steps > 0 && (scenario.duration_s * 1e9).round() <= (prior * 1e9).round())
    {
        issues.push(issue(
            "clock",
            "scenario.timestep_s",
            "full-step evidence requires a resolvable cadence and duration at most 86400 seconds",
        ));
    }
    if issues.is_empty()
        && let Err(errors) = validate(scenario)
    {
        issues.extend(
            errors
                .into_iter()
                .map(|error| issue("validation", &error.path, error.message)),
        );
    }
    let reachability = if issues.is_empty() {
        match reachability::check(scenario) {
            Ok(report) => {
                if !report.is_reachable() {
                    issues.push(issue(
                        "reachability",
                        "scenario.agents",
                        "one or more declared journeys have no radius-clear geometric path",
                    ));
                }
                Some(report)
            }
            Err(error) => {
                issues.push(issue("reachability", "scenario", error.to_string()));
                None
            }
        }
    } else {
        None
    };
    ScenarioInspection {
        experiment_version: EXPERIMENT_VERSION.into(),
        scenario_hash: canonical_hash(document),
        scenario: document.clone(),
        people,
        integration_steps: steps,
        issues,
        reachability,
    }
}

pub fn plan(request: &ExperimentRequest) -> Result<ExperimentPlan, ExperimentError> {
    prepare(request).map(|(plan, _)| plan)
}

pub(super) fn prepare(
    request: &ExperimentRequest,
) -> Result<(ExperimentPlan, Vec<CanonicalScenario>), ExperimentError> {
    validate_request(request)?;
    let options = &request.options;
    let base_population = changes::population_hash(&request.baseline.scenario);
    let mut arms = vec![];
    let mut scenarios = vec![];
    for (id, edits) in std::iter::once(("baseline", &[][..])).chain(
        request
            .variants
            .iter()
            .map(|variant| (variant.id.as_str(), variant.changes.as_slice())),
    ) {
        let mut document = request.baseline.clone();
        let mut edit_issues = vec![];
        for (index, edit) in edits.iter().enumerate() {
            if let Err(error) = changes::apply(&mut document.scenario, edit) {
                edit_issues.push(issue(
                    &error.code,
                    &format!("changes[{index}]"),
                    error.message,
                ));
            }
        }
        let inspected = inspect(&document);
        edit_issues.extend(inspected.issues);
        let population_hash = changes::population_hash(&document.scenario);
        arms.push(ArmPlan {
            id: id.into(),
            scenario_hash: inspected.scenario_hash,
            people: inspected.people,
            integration_steps: inspected.integration_steps,
            same_population_inputs: population_hash == base_population,
            population_hash,
            changed_fields: changes::differences(&request.baseline.scenario, &document.scenario),
            issues: edit_issues,
            reachability: inspected.reachability,
        });
        scenarios.push(document);
    }
    let multiplier = if options.evaluation.verify_replay {
        2
    } else {
        1
    };
    let estimated_agent_steps = arms
        .iter()
        .map(|arm| {
            arm.people
                .saturating_mul(arm.integration_steps)
                .saturating_mul(multiplier)
        })
        .fold(0_u64, u64::saturating_add);
    let retained_trace_samples = arms
        .iter()
        .map(|arm| {
            arm.people
                .saturating_mul(arm.integration_steps.saturating_add(1))
        })
        .fold(0_u64, u64::saturating_add);
    let mut issues = vec![];
    if estimated_agent_steps > options.max_agent_steps {
        issues.push(issue("budget", "options.max_agent_steps", format!("requires {estimated_agent_steps} agent-steps including requested replays; budget is {}", options.max_agent_steps)));
    }
    if retained_trace_samples > options.max_trace_samples {
        issues.push(issue(
            "budget",
            "options.max_trace_samples",
            format!(
                "requires {retained_trace_samples} retained snapshots; budget is {}",
                options.max_trace_samples
            ),
        ));
    }
    Ok((
        ExperimentPlan {
            experiment_version: EXPERIMENT_VERSION.into(),
            runtime_version: RUNTIME_VERSION.into(),
            input_hash: canonical_hash(request),
            runnable: issues.is_empty() && arms.iter().all(|arm| arm.issues.is_empty()),
            estimated_agent_steps,
            retained_trace_samples,
            arms,
            issues,
        },
        scenarios,
    ))
}

fn validate_request(request: &ExperimentRequest) -> Result<(), ExperimentError> {
    if request.experiment_version != EXPERIMENT_VERSION {
        return Err(ExperimentError::new(
            "version",
            format!("expected experiment_version `{EXPERIMENT_VERSION}`"),
        ));
    }
    if request.name.trim().is_empty()
        || request.name.len() > 256
        || request.variants.is_empty()
        || request.variants.len() > 15
    {
        return Err(ExperimentError::new(
            "configuration",
            "name must contain 1..=256 bytes and experiments require 1..=15 variants",
        ));
    }
    let mut ids = BTreeSet::from(["baseline"]);
    for variant in &request.variants {
        if variant.id.is_empty()
            || variant.id.len() > 64
            || !variant
                .id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
            || !ids.insert(&variant.id)
            || variant.changes.len() > 64
        {
            return Err(ExperimentError::new(
                "configuration",
                "variant IDs must be unique portable identifiers other than `baseline`; at most 64 changes per variant",
            ));
        }
    }
    let options = &request.options;
    if !(1..=2_000_000).contains(&options.max_agent_steps)
        || !(1..=2_000_000).contains(&options.max_trace_samples)
    {
        return Err(ExperimentError::new(
            "configuration",
            "agent-step and retained-snapshot budgets must be in 1..=2000000",
        ));
    }
    options
        .evaluation
        .thresholds
        .validate()
        .map_err(|error| ExperimentError::new("configuration", error))?;
    Ok(())
}
