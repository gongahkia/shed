use super::policy::issue;
use super::*;
use crate::{
    RUNTIME_VERSION,
    bundle::canonical_hash,
    experiment::{self, ExperimentOptions, ExperimentRequest, Variant},
    generator::spaces,
};
use std::collections::BTreeSet;

pub(super) fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
fn error(message: impl Into<String>) -> CampaignError {
    CampaignError::new("configuration", message)
}
pub(super) fn changes<'a>(candidate: &'a Candidate, case: &str) -> &'a [Change] {
    candidate
        .case_changes
        .get(case)
        .unwrap_or(&candidate.changes)
}
pub(super) fn experiment_request(
    request: &CampaignRequest,
    case: &PreparedCase,
    candidate: &Candidate,
) -> Option<ExperimentRequest> {
    Some(ExperimentRequest {
        experiment_version: experiment::EXPERIMENT_VERSION.into(),
        name: format!("{} / {} / {}", request.name, case.id, candidate.id),
        baseline: case.scenario.clone()?,
        variants: vec![Variant {
            id: candidate.id.clone(),
            changes: changes(candidate, &case.id).to_vec(),
        }],
        options: ExperimentOptions {
            evaluation: request.options.evaluation.clone(),
            max_agent_steps: 2_000_000,
            max_trace_samples: 2_000_000,
        },
    })
}
fn validate(request: &CampaignRequest) -> Result<(), CampaignError> {
    if request.campaign_version != CAMPAIGN_VERSION {
        return Err(CampaignError::new(
            "version",
            "unsupported campaign version",
        ));
    }
    if request.name.trim().is_empty()
        || request.name.len() > 100
        || request.candidates.is_empty()
        || request.candidates.len() > 32
        || request.stress_cases.len() > 16
    {
        return Err(error(
            "name 1..100 bytes, candidates 1..32, stress cases 0..16",
        ));
    }
    let mut ids = BTreeSet::new();
    for case in std::iter::once(&request.screening).chain(&request.stress_cases) {
        if !valid_id(&case.id)
            || !ids.insert(case.id.clone())
            || !(1..=8).contains(&case.population_multiplier)
        {
            return Err(error(
                "case IDs must be unique portable IDs; population multipliers 1..8",
            ));
        }
    }
    let mut candidates = BTreeSet::new();
    for c in &request.candidates {
        if !valid_id(&c.id)
            || c.id == "baseline"
            || !candidates.insert(&c.id)
            || c.changes.len() > 64
            || c.case_changes.len() > 17
            || c.case_changes
                .iter()
                .any(|(id, edits)| !ids.contains(id) || edits.len() > 64)
        {
            return Err(error(
                "unique portable candidate IDs other than baseline, at most 64 edits per recipe and only declared case overrides",
            ));
        }
    }
    if request.objectives.is_empty() || request.objectives.len() > 6 {
        return Err(error("declare 1..6 ordered objectives"));
    }
    let mut metrics = BTreeSet::new();
    for o in &request.objectives {
        if !metrics.insert(o.metric) || !o.tolerance.is_finite() || o.tolerance < 0. {
            return Err(error(
                "objectives must be unique with finite nonnegative tolerances",
            ));
        }
    }
    let b = &request.options.budget;
    if !(1..=32).contains(&b.max_candidates)
        || !(1..=256).contains(&b.max_trials)
        || !(1..=2_000_000).contains(&b.max_agent_steps)
        || !(1..=2_000_000).contains(&b.max_trace_samples)
        || b.max_evidence_images > 16
        || b.max_generation_attempts > 1024
        || b.max_generation_decisions > 32_000_000
        || request.promotion.max_candidates > 32
    {
        return Err(error(
            "campaign limits: 32 candidates, 256 trials, 2000000 agent-steps/snapshots, 16 images, 1024 generation attempts and 32000000 generation decisions",
        ));
    }
    if request.constraints.allowed_edits.len() > 64
        || request.constraints.bounds.len() > 64
        || request.constraints.outcomes.len() > 6
    {
        return Err(error(
            "at most 64 permissions/bounds and 6 outcome constraints",
        ));
    }
    for p in &request.constraints.allowed_edits {
        if p.targets.len() > 1024
            || (p.kind == EditKind::ReplaceInformation) != p.targets.is_empty()
            || p.targets.iter().any(|id| id.is_empty() || id.len() > 256)
        {
            return Err(error(
                "edit permissions need explicit target IDs or *; replace_information needs an empty target list",
            ));
        }
    }
    for b in &request.constraints.bounds {
        if !b.min.is_finite() || !b.max.is_finite() || b.min > b.max || b.target.is_empty() {
            return Err(error(
                "numeric bounds need a target and finite ordered endpoints",
            ));
        }
    }
    for b in &request.constraints.outcomes {
        if b.min.is_none() && b.max.is_none()
            || b.min.into_iter().chain(b.max).any(|v| !v.is_finite())
            || b.min.zip(b.max).is_some_and(|(min, max)| min > max)
        {
            return Err(error("outcome bounds need finite ordered endpoints"));
        }
    }
    request
        .options
        .evaluation
        .thresholds
        .validate()
        .map_err(error)?;
    Ok(())
}
fn prepare_case(case: &CaseSpec) -> PreparedCase {
    let mut p = PreparedCase {
        id: case.id.clone(),
        source_hash: canonical_hash(case),
        scenario: None,
        generation: None,
        issues: vec![],
    };
    let source = match &case.source {
        CaseSource::Authored { scenario } => Some(scenario.clone()),
        CaseSource::Generated { seed, config } => match spaces::generate(*seed, config) {
            Ok(space) => {
                p.generation =
                    Some(serde_json::to_value(space.manifest).expect("generation manifest"));
                Some(CanonicalScenario::from(space.scenario))
            }
            Err(e) => {
                p.generation = Some(
                    serde_json::json!({"error":e.to_string(),"attempts":match &e {spaces::SpaceGenerationError::Exhausted{attempts}=>attempts.clone(),spaces::SpaceGenerationError::InvalidConfig(_)=>vec![]}}),
                );
                p.issues.push(issue("generation", "source", e.to_string()));
                None
            }
        },
    };
    if let Some(mut doc) = source {
        for group in &mut doc.scenario.agents {
            let Some(count) = group.count.checked_mul(case.population_multiplier) else {
                p.issues.push(issue(
                    "population",
                    "population_multiplier",
                    "population count overflow",
                ));
                return p;
            };
            group.count = count;
        }
        p.issues.extend(experiment::inspect(&doc).issues);
        p.scenario = Some(doc);
    }
    p
}
/// Full preflight, including bounded deterministic WFC generation, without simulation.
pub fn plan(request: &CampaignRequest) -> Result<CampaignPlan, CampaignError> {
    prepare(request)
}
pub(super) fn prepare(request: &CampaignRequest) -> Result<CampaignPlan, CampaignError> {
    validate(request)?;
    let mut p = CampaignPlan {
        campaign_version: CAMPAIGN_VERSION.into(),
        runtime_version: RUNTIME_VERSION.into(),
        input_hash: canonical_hash(request),
        plan_hash: String::new(),
        runnable: false,
        reserved_trials: 0,
        reserved_agent_steps: 0,
        reserved_trace_samples: 0,
        reserved_evidence_agent_steps: 0,
        reserved_generation_attempts: 0,
        reserved_generation_decisions: 0,
        cases: vec![],
        trials: vec![],
        issues: vec![],
    };
    let budget = &request.options.budget;
    for c in std::iter::once(&request.screening).chain(&request.stress_cases) {
        if let CaseSource::Generated { config, .. } = &c.source {
            p.reserved_generation_attempts = p
                .reserved_generation_attempts
                .saturating_add(u64::from(config.max_attempts));
            p.reserved_generation_decisions = p.reserved_generation_decisions.saturating_add(
                u64::from(config.max_attempts).saturating_mul(u64::from(config.max_decisions)),
            );
            if config.max_attempts > 64
                || config.max_backtracks > 10000
                || config.max_decisions > 100000
            {
                p.issues.push(issue("generation_budget","source.config","per case at most 64 attempts, 10000 backtracks and 100000 decisions per attempt"));
            }
        }
    }
    let count = u64::try_from(request.candidates.len()).expect("bounded");
    let promoted = u64::from(request.promotion.max_candidates).min(count);
    p.reserved_trials =
        count + promoted * u64::try_from(request.stress_cases.len()).expect("bounded");
    for (used, max, path) in [
        (count, u64::from(budget.max_candidates), "max_candidates"),
        (
            p.reserved_trials,
            u64::from(budget.max_trials),
            "max_trials",
        ),
        (
            p.reserved_generation_attempts,
            budget.max_generation_attempts,
            "max_generation_attempts",
        ),
        (
            p.reserved_generation_decisions,
            budget.max_generation_decisions,
            "max_generation_decisions",
        ),
    ] {
        if used > max {
            p.issues.push(issue(
                "budget",
                path,
                format!("requires {used}; budget is {max}"),
            ));
        }
    }
    // Do not invoke the solver at all if generation/trial inventory exceeds its cap.
    if !p.issues.is_empty() {
        p.plan_hash = canonical_hash(&p);
        return Ok(p);
    }
    p.cases = std::iter::once(&request.screening)
        .chain(&request.stress_cases)
        .map(prepare_case)
        .collect();
    let mut maximum_image_work = 0;
    for (case_index, case) in p.cases.iter().enumerate() {
        let mut costs = vec![];
        for candidate in &request.candidates {
            let mut trial = TrialPlan {
                case: case.id.clone(),
                candidate: candidate.id.clone(),
                experiment: None,
                issues: case.issues.clone(),
            };
            if let Some(r) = experiment_request(request, case, candidate) {
                let (exp, scenarios) = experiment::prepare(&r)?;
                trial.issues.extend(exp.issues.clone());
                for arm in &exp.arms {
                    trial.issues.extend(arm.issues.clone());
                    maximum_image_work =
                        maximum_image_work.max(arm.people.saturating_mul(arm.integration_steps));
                }
                trial.issues.extend(policy::check(
                    &request.constraints,
                    changes(candidate, &case.id),
                    scenarios.get(1).map(|s| &s.scenario),
                    exp.arms[1].same_population_inputs,
                ));
                if trial.issues.is_empty() {
                    costs.push((exp.estimated_agent_steps, exp.retained_trace_samples));
                }
                trial.experiment = Some(exp);
            }
            p.trials.push(trial);
        }
        // Selected candidates are unknown at preflight. Independently reserve the
        // most expensive top-K simulation and storage totals in every stress case.
        let take = if case_index == 0 {
            request.candidates.len()
        } else {
            usize::try_from(promoted).expect("bounded")
        };
        let mut steps: Vec<_> = costs.iter().map(|c| c.0).collect();
        steps.sort_unstable_by(|a, b| b.cmp(a));
        let mut samples: Vec<_> = costs.iter().map(|c| c.1).collect();
        samples.sort_unstable_by(|a, b| b.cmp(a));
        p.reserved_agent_steps = p
            .reserved_agent_steps
            .saturating_add(steps.into_iter().take(take).sum());
        p.reserved_trace_samples = p
            .reserved_trace_samples
            .saturating_add(samples.into_iter().take(take).sum());
    }
    p.reserved_evidence_agent_steps =
        maximum_image_work.saturating_mul(u64::from(budget.max_evidence_images));
    p.reserved_agent_steps = p
        .reserved_agent_steps
        .saturating_add(p.reserved_evidence_agent_steps);
    for (used, max, path) in [
        (
            p.reserved_agent_steps,
            budget.max_agent_steps,
            "max_agent_steps",
        ),
        (
            p.reserved_trace_samples,
            budget.max_trace_samples,
            "max_trace_samples",
        ),
    ] {
        if used > max {
            p.issues.push(issue(
                "budget",
                path,
                format!("requires {used}; budget is {max}"),
            ));
        }
    }
    p.runnable = p.issues.is_empty();
    p.plan_hash = canonical_hash(&p);
    Ok(p)
}
