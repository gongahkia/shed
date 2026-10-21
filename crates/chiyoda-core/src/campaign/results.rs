use super::*;
use crate::experiment::{
    AgentDifference, EvidenceReference, ExperimentExecution, ExperimentRequest, Finding,
};
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Screening,
    Stress,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TrialStatus {
    Rejected,
    RuntimeFailed,
    Executed,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ObjectiveValue {
    pub metric: Metric,
    pub baseline: Option<f64>,
    pub candidate: Option<f64>,
    /// Positive is better under the declared direction.
    pub benefit: Option<f64>,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum MomentSelection {
    Agent { id: String },
    Resource { resource_kind: String, id: String },
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EvidenceMoment {
    pub case: String,
    pub candidate: String,
    pub arm: String,
    pub selection: MomentSelection,
    pub step: u64,
    pub reason: String,
    pub source_bundle_hash: String,
    pub reconstruction_agent_steps: u64,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TrialResult {
    pub case: String,
    pub candidate: String,
    pub phase: Phase,
    pub status: TrialStatus,
    pub eligible: bool,
    pub reasons: Vec<Issue>,
    pub same_population_inputs: Option<bool>,
    pub experiment_report_hash: Option<String>,
    pub objective_values: Vec<ObjectiveValue>,
    pub baseline_observations: BTreeMap<String, f64>,
    pub candidate_observations: BTreeMap<String, f64>,
    pub baseline_checks_passed: Option<bool>,
    pub candidate_checks_passed: Option<bool>,
    pub affected_people_total: usize,
    pub affected_people: Vec<AgentDifference>,
    pub findings: Vec<Finding>,
    pub moments: Vec<EvidenceMoment>,
    pub charged_agent_steps: u64,
    pub retained_trace_samples: u64,
    pub failure: Option<CampaignError>,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ObjectiveSummary {
    pub metric: Metric,
    pub paired_cases: usize,
    pub mean_benefit: Option<f64>,
    pub worst_benefit: Option<f64>,
    pub wins: usize,
    pub ties: usize,
    pub losses: usize,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CandidateResult {
    pub candidate: String,
    pub promoted: bool,
    pub screening_rank: Option<usize>,
    pub final_rank: Option<usize>,
    pub status: String,
    pub expected_cases: usize,
    pub executed_cases: usize,
    pub eligible_cases: usize,
    pub objectives: Vec<ObjectiveSummary>,
    pub exclusion_reasons: Vec<String>,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CampaignReport {
    pub campaign_version: String,
    pub runtime_version: String,
    pub name: String,
    pub input_hash: String,
    pub plan_hash: String,
    pub objectives: Vec<Objective>,
    pub promoted: Vec<String>,
    pub trials: Vec<TrialResult>,
    pub candidates: Vec<CandidateResult>,
    pub evidence_moments: Vec<EvidenceMoment>,
    pub charged_simulation_agent_steps: u64,
    pub retained_trace_samples: u64,
    pub reserved_agent_steps: u64,
    pub reserved_evidence_agent_steps: u64,
    pub claim_boundary: String,
    pub report_hash: String,
}
#[derive(Debug)]
pub struct TrialExecution {
    pub result: TrialResult,
    pub request: Option<ExperimentRequest>,
    pub execution: Option<ExperimentExecution>,
}
#[derive(Debug)]
pub struct CampaignExecution {
    pub report: CampaignReport,
    pub trials: Vec<TrialExecution>,
}

pub(super) fn metric(metric: Metric, values: &BTreeMap<String, f64>) -> Option<f64> {
    let value = match metric {
        Metric::CompletedFraction => values.get("completed_fraction").copied(),
        Metric::UnfinishedPeople => values
            .get("people")
            .zip(values.get("completed_people"))
            .map(|(n, c)| n - c),
        Metric::ServiceWaitPerPersonS => values.get("service_wait_per_person_s").copied(),
        Metric::ClearanceTimeS => values.get("clearance_time_s").copied(),
        Metric::LowProgressPerPersonS => values.get("low_progress_span_per_person_s").copied(),
        // Never optimize a completed-only mean by dropping slow unfinished people.
        Metric::MeanCompletedJourneyS => {
            if values.get("people") == values.get("completed_people") {
                values.get("mean_elapsed_journey_s").copied()
            } else {
                None
            }
        }
    };
    value.filter(|v| v.is_finite())
}
pub(super) fn benefit(objective: &Objective, a: f64, b: f64) -> f64 {
    match objective.direction {
        Direction::Minimize => a - b,
        Direction::Maximize => b - a,
    }
}
pub(super) fn prefix(reference: &mut EvidenceReference, case: &str, candidate: &str) {
    reference.artifact = format!("trials/{case}/{candidate}/{}", reference.artifact);
}
pub(super) fn outcomes(
    request: &CampaignRequest,
    trial: &TrialPlan,
    phase: Phase,
    execution: Option<&ExperimentExecution>,
    failure: Option<CampaignError>,
) -> TrialResult {
    let mut r = TrialResult {
        case: trial.case.clone(),
        candidate: trial.candidate.clone(),
        phase,
        status: TrialStatus::Rejected,
        eligible: false,
        reasons: trial.issues.clone(),
        same_population_inputs: None,
        experiment_report_hash: None,
        objective_values: vec![],
        baseline_observations: BTreeMap::new(),
        candidate_observations: BTreeMap::new(),
        baseline_checks_passed: None,
        candidate_checks_passed: None,
        affected_people_total: 0,
        affected_people: vec![],
        findings: vec![],
        moments: vec![],
        charged_agent_steps: 0,
        retained_trace_samples: 0,
        failure,
    };
    if !r.reasons.is_empty() {
        return r;
    }
    if let Some(p) = &trial.experiment {
        r.charged_agent_steps = p.estimated_agent_steps;
    }
    let Some(execution) = execution else {
        r.status = TrialStatus::RuntimeFailed;
        r.reasons.push(policy::issue(
            "runtime",
            "trial",
            "execution failed; reserved work is conservatively charged",
        ));
        return r;
    };
    r.status = TrialStatus::Executed;
    r.experiment_report_hash = Some(execution.report.report_hash.clone());
    r.retained_trace_samples = trial.experiment.as_ref().unwrap().retained_trace_samples;
    let before = &execution.report.arms[0];
    let after = &execution.report.arms[1];
    r.baseline_observations = before.observations.clone();
    r.candidate_observations = after.observations.clone();
    r.baseline_checks_passed = Some(before.evaluation.passed);
    r.candidate_checks_passed = Some(after.evaluation.passed);
    r.same_population_inputs = Some(execution.report.comparisons[0].same_population_inputs);
    for (arm, result) in [("baseline", before), ("candidate", after)] {
        if !result.measured {
            r.reasons.push(policy::issue(
                "invalid_trajectory",
                arm,
                "run is not measurable under experiment assertions",
            ));
        }
        let audit = result
            .evaluation
            .metrics
            .as_ref()
            .and_then(|m| m.movement_metrics.as_ref());
        if audit
            .and_then(|m| m.on_surface_clearance_audit.as_ref())
            .is_none_or(|a| {
                !a.maximum_disc_overlap_m.is_finite() || a.maximum_disc_overlap_m > 1e-8
            })
            || audit
                .and_then(|m| m.swept_on_surface_clearance_audit.as_ref())
                .is_none_or(|a| {
                    !a.maximum_swept_disc_overlap_m.is_finite()
                        || a.maximum_swept_disc_overlap_m > 1e-8
                })
        {
            r.reasons.push(policy::issue(
                "reference_overlap",
                arm,
                "missing or overlapping boundary/swept reference-disc audit; excluded from ranking",
            ));
        }
    }
    if !after.evaluation.passed {
        r.reasons.push(policy::issue(
            "performance_checks",
            "candidate",
            "candidate failed declared evaluation checks; measurements remain retained",
        ));
    }
    for o in &request.objectives {
        let a = metric(o.metric, &before.observations);
        let b = metric(o.metric, &after.observations);
        if a.is_none() || b.is_none() {
            r.reasons.push(policy::issue(
                "missing_objective",
                "objectives",
                format!("{:?} is unavailable in one or both arms", o.metric),
            ));
        }
        r.objective_values.push(ObjectiveValue {
            metric: o.metric,
            baseline: a,
            candidate: b,
            benefit: a.zip(b).map(|(a, b)| benefit(o, a, b)),
        });
    }
    for b in &request.constraints.outcomes {
        if metric(b.metric, &after.observations)
            .is_none_or(|v| b.min.is_some_and(|min| v < min) || b.max.is_some_and(|max| v > max))
        {
            r.reasons.push(policy::issue(
                "outcome_bound",
                "constraints.outcomes",
                format!("{:?} is absent or outside its required range", b.metric),
            ));
        }
    }
    r.eligible = r.reasons.is_empty();
    let mut people = execution.report.comparisons[0].agents.clone();
    people.sort_by(|a, b| {
        affected_score(b)
            .total_cmp(&affected_score(a))
            .then(a.agent.cmp(&b.agent))
    });
    r.affected_people_total = people.len();
    for mut person in people.into_iter().take(5) {
        for e in &mut person.evidence {
            prefix(e, &trial.case, &trial.candidate);
        }
        r.affected_people.push(person);
    }
    for arm in &execution.report.arms {
        for f in &arm.findings {
            let mut f = f.clone();
            for e in &mut f.evidence {
                prefix(e, &trial.case, &trial.candidate);
            }
            r.findings.push(f);
        }
    }
    r.moments = moments(execution, &r);
    r
}
fn affected_score(p: &AgentDifference) -> f64 {
    (if p.baseline_completed != p.candidate_completed {
        1_000_000.
    } else {
        0.
    }) + p.elapsed_difference_s.unwrap_or(0.).abs()
        + p.service_wait_difference_s.abs()
        + p.low_progress_span_difference_s.abs()
}
fn moments(execution: &ExperimentExecution, result: &TrialResult) -> Vec<EvidenceMoment> {
    let mut moments = vec![];
    for a in &execution.artifacts {
        let (Some(bundle), Some(journeys)) = (&a.bundle, &a.journeys) else {
            continue;
        };
        let Some(person) = result
            .affected_people
            .first()
            .and_then(|p| journeys.agents.iter().find(|a| a.agent == p.agent))
            .or_else(|| journeys.agents.first())
        else {
            continue;
        };
        let step = person
            .low_progress
            .iter()
            .max_by(|a, b| {
                (a.end_time_s - a.start_time_s).total_cmp(&(b.end_time_s - b.start_time_s))
            })
            .map_or(bundle.trace.last().unwrap().step, |w| w.end_step);
        moments.push(EvidenceMoment{case:result.case.clone(),candidate:result.candidate.clone(),arm:a.id.clone(),selection:MomentSelection::Agent{id:person.agent.clone()},step,reason:"Affected person; end of longest recorded low-progress span, otherwise final frame. Whole-run evidence remains separately labelled.".into(),source_bundle_hash:bundle.bundle_hash.clone(),reconstruction_agent_steps:u64::from(bundle.metrics.total_agents).saturating_mul(crate::integration_step_count(a.scenario.scenario.duration_s,a.scenario.scenario.timestep_s))});
    }
    for a in &execution.artifacts {
        let (Some(bundle), Some(journeys)) = (&a.bundle, &a.journeys) else {
            continue;
        };
        if let Some(resource) = journeys
            .resources
            .iter()
            .filter(|r| r.wait_agent_s > 0.)
            .max_by(|a, b| a.wait_agent_s.total_cmp(&b.wait_agent_s))
        {
            let kind = match resource.kind.as_str() {
                "exit" => "exit",
                "gate" => "gate",
                "connector" | "lift" => "connector",
                _ => continue,
            };
            moments.push(EvidenceMoment{case:result.case.clone(),candidate:result.candidate.clone(),arm:a.id.clone(),selection:MomentSelection::Resource{resource_kind:kind.into(),id:resource.resource.clone()},step:bundle.trace.last().unwrap().step,reason:"Largest whole-run resource wait audit in this arm; final frame is not claimed as the peak queue moment.".into(),source_bundle_hash:bundle.bundle_hash.clone(),reconstruction_agent_steps:u64::from(bundle.metrics.total_agents).saturating_mul(crate::integration_step_count(a.scenario.scenario.duration_s,a.scenario.scenario.timestep_s))});
        }
    }
    moments
}
