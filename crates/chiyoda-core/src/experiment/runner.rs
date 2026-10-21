use super::{EXPERIMENT_VERSION, ExperimentError, ExperimentPlan, ExperimentRequest, planning};
use crate::{
    CanonicalScenario, RUNTIME_VERSION, RunBundle,
    bundle::canonical_hash,
    evaluation::{self, CaseReport, ExpectedReachability},
    journeys::{self, AgentJourney, JourneyOptions, JourneyReport, JourneySummary},
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EvidenceReference {
    pub arm: String,
    /// Relative to the experiment directory, independent of machine paths.
    pub artifact: String,
    pub pointer: String,
    pub replay_step: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Finding {
    pub code: String,
    pub message: String,
    pub evidence: Vec<EvidenceReference>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ArmResult {
    pub id: String,
    pub evaluation: CaseReport,
    pub journeys: Option<JourneySummary>,
    /// Threshold failures remain measurable; invalid trajectories do not.
    pub measured: bool,
    pub observations: BTreeMap<String, f64>,
    pub findings: Vec<Finding>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MetricDifference {
    pub baseline: Option<f64>,
    pub candidate: Option<f64>,
    /// Absent if either arm lacks the metric; incomplete clearance is never zero.
    pub candidate_minus_baseline: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AgentDifference {
    pub agent: String,
    pub baseline_completed: bool,
    pub candidate_completed: bool,
    pub baseline_elapsed_s: Option<f64>,
    pub candidate_elapsed_s: Option<f64>,
    pub elapsed_difference_s: Option<f64>,
    pub walking_distance_difference_m: f64,
    pub service_wait_difference_s: f64,
    pub route_wait_difference_s: f64,
    pub low_progress_span_difference_s: f64,
    pub evidence: Vec<EvidenceReference>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Comparison {
    pub candidate: String,
    pub same_population_inputs: bool,
    pub metrics: BTreeMap<String, MetricDifference>,
    pub matched_people: u32,
    pub both_completed: u32,
    pub agents: Vec<AgentDifference>,
    pub findings: Vec<Finding>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExperimentReport {
    pub experiment_version: String,
    pub runtime_version: String,
    pub name: String,
    pub input_hash: String,
    pub plan: ExperimentPlan,
    pub arms: Vec<ArmResult>,
    pub comparisons: Vec<Comparison>,
    pub report_hash: String,
}

#[derive(Debug, Clone)]
pub struct ArmArtifacts {
    pub id: String,
    pub scenario: CanonicalScenario,
    pub bundle: Option<RunBundle>,
    pub journeys: Option<JourneyReport>,
}

#[derive(Debug)]
pub struct ExperimentExecution {
    pub report: ExperimentReport,
    pub artifacts: Vec<ArmArtifacts>,
}

/// Run a previously inspected input. A changed baseline, intervention, budget,
/// threshold or replay option invalidates the expected input hash.
pub fn execute(
    request: &ExperimentRequest,
    expected_input_hash: &str,
) -> Result<ExperimentExecution, ExperimentError> {
    let (plan, scenarios) = planning::prepare(request)?;
    if expected_input_hash != plan.input_hash {
        return Err(ExperimentError::new(
            "stale_plan",
            "input differs from the planned input; inspect the new plan before executing",
        ));
    }
    if !plan.runnable {
        return Err(ExperimentError::new(
            "invalid_plan",
            "one or more arms or total budgets failed preflight; inspect the plan issues",
        ));
    }
    let mut artifacts = vec![];
    let mut arms = vec![];
    for (arm, scenario) in plan.arms.iter().zip(scenarios) {
        let source = serde_json::to_string(&scenario).expect("scenario JSON");
        let evaluated = evaluation::evaluate_json(
            &arm.id,
            &source,
            ExpectedReachability::Reachable,
            &request.options.evaluation,
        );
        let journeys = evaluated
            .bundle
            .as_ref()
            .map(|bundle| journeys::analyze(bundle, &JourneyOptions::default()))
            .transpose()
            .map_err(|error| ExperimentError::new("analysis", error))?;
        arms.push(arm_result(&arm.id, evaluated.report, journeys.as_ref()));
        artifacts.push(ArmArtifacts {
            id: arm.id.clone(),
            scenario,
            bundle: evaluated.bundle,
            journeys,
        });
    }
    Ok(ExperimentExecution {
        report: derive(request, plan, arms, &artifacts),
        artifacts,
    })
}

fn arm_result(id: &str, evaluation: CaseReport, journeys: Option<&JourneyReport>) -> ArmResult {
    let allowed = [
        "min_evacuated_fraction",
        "max_clearance_time_s",
        "max_queue_wait_agent_seconds",
        "max_avoidance_fallback_steps",
        "max_disc_overlap_m",
        "max_swept_disc_overlap_m",
    ];
    let measured = evaluation.metrics.is_some()
        && journeys.is_some()
        && evaluation
            .failures
            .iter()
            .all(|failure| allowed.contains(&failure.check.as_str()));
    let mut observations = BTreeMap::new();
    if measured {
        let metrics = evaluation.metrics.as_ref().expect("measured");
        observations.insert("people".into(), f64::from(metrics.total_agents));
        observations.insert(
            "completed_people".into(),
            f64::from(metrics.evacuated_agents),
        );
        observations.insert(
            "completed_fraction".into(),
            f64::from(metrics.evacuated_agents) / f64::from(metrics.total_agents),
        );
        if let Some(time) = metrics.clearance_time_s {
            observations.insert("clearance_time_s".into(), time);
        }
        observations.extend(journeys.expect("measured").summary().observations());
    }
    ArmResult {
        id: id.into(),
        evaluation,
        journeys: journeys.map(JourneyReport::summary),
        measured,
        observations,
        findings: journeys.map_or_else(Vec::new, |report| findings(id, report)),
    }
}

fn reference(arm: &str, pointer: String, replay_step: Option<u64>) -> EvidenceReference {
    EvidenceReference {
        arm: arm.into(),
        artifact: format!("arms/{arm}/journeys.json"),
        pointer,
        replay_step,
    }
}

fn findings(id: &str, report: &JourneyReport) -> Vec<Finding> {
    let mut findings = vec![];
    let mut hotspots: Vec<_> = report.hotspots.iter().enumerate().collect();
    hotspots.sort_by(|left, right| {
        right
            .1
            .low_progress_agent_s
            .total_cmp(&left.1.low_progress_agent_s)
    });
    for (index, hotspot) in hotspots.into_iter().take(5) {
        findings.push(Finding {
            code: "observed_low_progress".into(),
            message: format!("{} people recorded {:.3} agent-seconds of low-progress spans near ({:.2}, {:.2}) on {}. These spans overlap moving exposure.", hotspot.agents.len(), hotspot.low_progress_agent_s, hotspot.x_m, hotspot.y_m, hotspot.surface),
            evidence: vec![reference(id, format!("/hotspots/{index}"), Some(hotspot.start_step))],
        });
    }
    let mut resources: Vec<_> = report
        .resources
        .iter()
        .enumerate()
        .filter(|(_, resource)| resource.wait_agent_s > 0.0)
        .collect();
    resources.sort_by(|left, right| right.1.wait_agent_s.total_cmp(&left.1.wait_agent_s));
    for (index, resource) in resources.into_iter().take(5) {
        findings.push(Finding {
            code: "observed_service_wait".into(), message: format!("{} {} recorded {:.3} agent-seconds of service wait, with a peak of {} waiting people.", resource.kind, resource.resource, resource.wait_agent_s, resource.peak_waiting_agents),
            evidence: vec![reference(id, format!("/resources/{index}"), None)],
        });
    }
    findings
}

fn derive(
    request: &ExperimentRequest,
    plan: ExperimentPlan,
    arms: Vec<ArmResult>,
    artifacts: &[ArmArtifacts],
) -> ExperimentReport {
    let baseline = &arms[0];
    let mut comparisons = vec![];
    for (index, arm) in arms.iter().enumerate().skip(1) {
        let keys: BTreeSet<_> = baseline
            .observations
            .keys()
            .chain(arm.observations.keys())
            .cloned()
            .collect();
        let metrics = keys
            .into_iter()
            .map(|key| {
                let before = baseline.observations.get(&key).copied();
                let after = arm.observations.get(&key).copied();
                (
                    key,
                    MetricDifference {
                        baseline: before,
                        candidate: after,
                        candidate_minus_baseline: before
                            .zip(after)
                            .map(|(before, after)| after - before),
                    },
                )
            })
            .collect();
        let agents = if plan.arms[index].same_population_inputs && baseline.measured && arm.measured
        {
            pair_agents(&artifacts[0], &artifacts[index])
        } else {
            vec![]
        };
        let both_completed = agents
            .iter()
            .filter(|agent| agent.elapsed_difference_s.is_some())
            .count();
        let findings = vec![Finding {
            code: "comparison_scope".into(),
            message: if plan.arms[index].same_population_inputs {
                format!(
                    "{} matched people; {both_completed} completed in both arms. Elapsed-time differences exist only for those completed pairs. Differences describe these interventions under the recorded model.",
                    agents.len()
                )
            } else {
                "Population inputs differ; aggregate outcomes use their own denominators and individual journey differences are omitted.".into()
            },
            evidence: vec![],
        }];
        comparisons.push(Comparison {
            candidate: arm.id.clone(),
            same_population_inputs: plan.arms[index].same_population_inputs,
            metrics,
            matched_people: u32::try_from(agents.len()).expect("bounded population"),
            both_completed: u32::try_from(both_completed).expect("bounded population"),
            agents,
            findings,
        });
    }
    let mut report = ExperimentReport {
        experiment_version: EXPERIMENT_VERSION.into(),
        runtime_version: RUNTIME_VERSION.into(),
        name: request.name.clone(),
        input_hash: plan.input_hash.clone(),
        plan,
        arms,
        comparisons,
        report_hash: String::new(),
    };
    report.report_hash = canonical_hash(&report);
    report
}

fn low_progress(agent: &AgentJourney) -> f64 {
    agent
        .low_progress
        .iter()
        .map(|window| window.end_time_s - window.start_time_s)
        .sum()
}

fn pair_agents(before: &ArmArtifacts, after: &ArmArtifacts) -> Vec<AgentDifference> {
    let before_journeys = before.journeys.as_ref().expect("measured");
    let after_journeys = after.journeys.as_ref().expect("measured");
    let indexed: BTreeMap<_, _> = before_journeys
        .agents
        .iter()
        .enumerate()
        .map(|(index, agent)| (&agent.agent, (index, agent)))
        .collect();
    after_journeys
        .agents
        .iter()
        .enumerate()
        .filter_map(|(index, agent)| {
            let (before_index, baseline) = indexed.get(&agent.agent)?;
            Some(AgentDifference {
                agent: agent.agent.clone(),
                baseline_completed: baseline.evacuated_at_s.is_some(),
                candidate_completed: agent.evacuated_at_s.is_some(),
                baseline_elapsed_s: baseline.elapsed_journey_s,
                candidate_elapsed_s: agent.elapsed_journey_s,
                elapsed_difference_s: baseline
                    .elapsed_journey_s
                    .zip(agent.elapsed_journey_s)
                    .map(|(before, after)| after - before),
                walking_distance_difference_m: agent.walking_distance_m
                    - baseline.walking_distance_m,
                service_wait_difference_s: agent.time.service_wait_s()
                    - baseline.time.service_wait_s(),
                route_wait_difference_s: agent.time.route_wait_s - baseline.time.route_wait_s,
                low_progress_span_difference_s: low_progress(agent) - low_progress(baseline),
                evidence: vec![
                    reference(&before.id, format!("/agents/{before_index}"), None),
                    reference(&after.id, format!("/agents/{index}"), None),
                ],
            })
        })
        .collect()
}

impl ExperimentReport {
    pub fn check_hash(&self) -> Result<(), ExperimentError> {
        let mut content = self.clone();
        content.report_hash.clear();
        if self.experiment_version != EXPERIMENT_VERSION
            || self.runtime_version != RUNTIME_VERSION
            || self.report_hash != canonical_hash(&content)
        {
            return Err(ExperimentError::new(
                "integrity",
                "experiment version, runtime or report hash does not match",
            ));
        }
        Ok(())
    }

    /// Rebuild analysis and comparisons from saved evidence. With `replay`,
    /// also reconstruct each run; this does not rewrite original replay flags.
    pub fn verify(
        &self,
        request: &ExperimentRequest,
        artifacts: &[ArmArtifacts],
        replay: bool,
    ) -> Result<(), ExperimentError> {
        self.check_hash()?;
        let (plan, scenarios) = planning::prepare(request)?;
        if !plan.runnable
            || self.plan != plan
            || artifacts.len() != plan.arms.len()
            || self.arms.len() != artifacts.len()
        {
            return Err(ExperimentError::new(
                "integrity",
                "request, plan or arm inventory disagrees with report",
            ));
        }
        let mut arms = vec![];
        for (((arm, scenario), artifact), saved) in plan
            .arms
            .iter()
            .zip(&scenarios)
            .zip(artifacts)
            .zip(&self.arms)
        {
            if artifact.id != arm.id || artifact.scenario != *scenario || saved.id != arm.id {
                return Err(ExperimentError::new(
                    "integrity",
                    "arm identity or scenario differs from prepared input",
                ));
            }
            let (Some(bundle), Some(journeys)) = (&artifact.bundle, &artifact.journeys) else {
                return Err(ExperimentError::new(
                    "missing_evidence",
                    "an arm has no run or journey evidence; its failure remains recorded but cannot be independently re-derived",
                ));
            };
            if bundle.scenario != *scenario
                || bundle.bundle_version != crate::bundle::BUNDLE_VERSION
                || bundle.runtime_version != RUNTIME_VERSION
                || bundle.options.contains_key("isolated_agent")
                || bundle.options.contains_key("execution_scope")
                || bundle.options.get("trace_every_steps").map(String::as_str) != Some("1")
            {
                return Err(ExperimentError::new(
                    "integrity",
                    "run scenario, runtime or execution scope differs from experiment",
                ));
            }
            journeys
                .verify_evidence(bundle, &[])
                .map_err(|error| ExperimentError::new("integrity", error))?;
            let mut options = request.options.evaluation.clone();
            options.verify_replay = replay;
            let mut evaluated = evaluation::evaluate_bundle(&arm.id, bundle, &options);
            if replay && !evaluated.replay_verified {
                return Err(ExperimentError::new("replay", "run reconstruction failed"));
            }
            if saved.evaluation.replay_verified != request.options.evaluation.verify_replay {
                return Err(ExperimentError::new(
                    "integrity",
                    "original replay status disagrees with requested execution",
                ));
            }
            evaluated.replay_verified = saved.evaluation.replay_verified;
            arms.push(arm_result(&arm.id, evaluated, Some(journeys)));
        }
        if derive(request, plan, arms, artifacts) != *self {
            return Err(ExperimentError::new(
                "integrity",
                "saved experiment conclusions differ from re-derived evidence",
            ));
        }
        Ok(())
    }
}
