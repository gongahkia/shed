use super::*;
use crate::{
    bundle::canonical_hash,
    experiment::{self, ExperimentExecution, ExperimentRequest},
};
use std::{cell::RefCell, collections::BTreeMap};

/// Streaming execution persists one trial at a time; the callback may release its artifacts.
pub fn execute_with(
    request: &CampaignRequest,
    expected_plan_hash: &str,
    mut observe: impl FnMut(&TrialExecution) -> Result<(), CampaignError>,
) -> Result<CampaignReport, CampaignError> {
    let p = planning::prepare(request)?;
    if p.plan_hash != expected_plan_hash {
        return Err(CampaignError::new(
            "stale_plan",
            "campaign input, runtime or generated plan changed since preflight",
        ));
    }
    if !p.runnable {
        return Err(CampaignError::new(
            "invalid_plan",
            "campaign budget preflight failed; inspect plan issues",
        ));
    }
    drive(
        request,
        &p,
        |r, p| {
            Ok(experiment::execute(
                r,
                &p.experiment.as_ref().unwrap().input_hash,
            ))
        },
        &mut observe,
    )
}
pub fn execute(
    request: &CampaignRequest,
    expected_plan_hash: &str,
) -> Result<CampaignExecution, CampaignError> {
    let mut trials = vec![];
    let report = execute_with(request, expected_plan_hash, |e| {
        trials.push(TrialExecution {
            result: e.result.clone(),
            request: e.request.clone(),
            execution: e.execution.as_ref().map(|x| ExperimentExecution {
                report: x.report.clone(),
                artifacts: x.artifacts.clone(),
            }),
        });
        Ok(())
    })?;
    Ok(CampaignExecution { report, trials })
}
fn drive(
    request: &CampaignRequest,
    p: &CampaignPlan,
    mut obtain: impl FnMut(
        &ExperimentRequest,
        &TrialPlan,
    ) -> Result<Result<ExperimentExecution, CampaignError>, CampaignError>,
    observe: &mut impl FnMut(&TrialExecution) -> Result<(), CampaignError>,
) -> Result<CampaignReport, CampaignError> {
    let mut trials = vec![];
    let mut promoted = vec![];
    for (i, case) in p.cases.iter().enumerate() {
        if i == 1 {
            promoted = ranking::promoted(request, &trials);
        }
        for candidate in &request.candidates {
            if i > 0 && !promoted.contains(&candidate.id) {
                continue;
            }
            let trial = p
                .trials
                .iter()
                .find(|t| t.case == case.id && t.candidate == candidate.id)
                .expect("planned inventory");
            let r = planning::experiment_request(request, case, candidate);
            let result = if trial.issues.is_empty() {
                Some(obtain(r.as_ref().expect("prepared valid source"), trial)?)
            } else {
                None
            };
            let (execution, failure) = match result {
                Some(Ok(e)) => (Some(e), None),
                Some(Err(e)) => (None, Some(e)),
                None => (None, None),
            };
            let result = results::outcomes(
                request,
                trial,
                if i == 0 {
                    Phase::Screening
                } else {
                    Phase::Stress
                },
                execution.as_ref(),
                failure,
            );
            observe(&TrialExecution {
                result: result.clone(),
                request: r,
                execution,
            })?;
            trials.push(result);
        }
    }
    if p.cases.len() == 1 {
        promoted = ranking::promoted(request, &trials);
    }
    let candidates = ranking::candidates(request, &trials, &promoted);
    let evidence = ranking::evidence(request, &trials, &candidates);
    let mut report=CampaignReport{campaign_version:CAMPAIGN_VERSION.into(),runtime_version:crate::RUNTIME_VERSION.into(),name:request.name.clone(),input_hash:p.input_hash.clone(),plan_hash:p.plan_hash.clone(),objectives:request.objectives.clone(),promoted,trials,candidates,evidence_moments:evidence,charged_simulation_agent_steps:0,retained_trace_samples:0,reserved_agent_steps:p.reserved_agent_steps,reserved_evidence_agent_steps:p.reserved_evidence_agent_steps,claim_boundary:"Candidate recipes and case overrides are declared before screening. Every trial independently compares its case baseline with one candidate, including separate deterministic baseline executions charged to the budget. Positive benefit follows each declared objective direction. Screening and final ranks use exact lexicographic values; tolerances classify wins/ties/losses only. Final ranks compare equally weighted mean paired benefits over every declared case, including screening, and require complete eligible coverage. Stress regressions and exclusions remain explicit. This finite candidate/case set is not a global optimum, statistical generalization or calibrated architectural recommendation. Case-specific recipes are explicit adaptations, not a proof of the same architectural pattern.".into(),report_hash:String::new()};
    report.charged_simulation_agent_steps =
        report.trials.iter().map(|t| t.charged_agent_steps).sum();
    report.retained_trace_samples = report.trials.iter().map(|t| t.retained_trace_samples).sum();
    if report.charged_simulation_agent_steps.saturating_add(
        report
            .evidence_moments
            .iter()
            .map(|m| m.reconstruction_agent_steps)
            .sum(),
    ) > p.reserved_agent_steps
        || report.retained_trace_samples > p.reserved_trace_samples
        || report.trials.len() as u64 > p.reserved_trials
    {
        return Err(CampaignError::new(
            "budget",
            "execution exceeded a reserved bound",
        ));
    }
    report.report_hash = canonical_hash(&report);
    Ok(report)
}
impl CampaignReport {
    pub fn check_hash(&self) -> Result<(), CampaignError> {
        let mut unsigned = self.clone();
        unsigned.report_hash.clear();
        if self.campaign_version != CAMPAIGN_VERSION
            || self.runtime_version != crate::RUNTIME_VERSION
            || self.report_hash != canonical_hash(&unsigned)
        {
            return Err(CampaignError::new(
                "integrity",
                "campaign hash/version mismatch",
            ));
        }
        Ok(())
    }
    /// Rebuild selection, exclusions, costs, ranks and moments from archived trials.
    /// Failed executions lacking evidence require explicit replay to substantiate.
    pub fn verify_with(
        &self,
        request: &CampaignRequest,
        replay: bool,
        load: impl FnMut(&str, &str) -> Result<TrialExecution, CampaignError>,
    ) -> Result<(), CampaignError> {
        self.check_hash()?;
        let p = planning::prepare(request)?;
        if !p.runnable || p.plan_hash != self.plan_hash {
            return Err(CampaignError::new(
                "integrity",
                "campaign plan differs from saved request",
            ));
        }
        let load = RefCell::new(load);
        let records = RefCell::new(BTreeMap::new());
        let derived = drive(
            request,
            &p,
            |r, planned| {
                let archived = (load.borrow_mut())(&planned.case, &planned.candidate)?;
                if archived.request.as_ref() != Some(r) {
                    return Err(CampaignError::new(
                        "integrity",
                        "archived trial request differs from declared recipe",
                    ));
                }
                records.borrow_mut().insert(
                    (planned.case.clone(), planned.candidate.clone()),
                    archived.result.clone(),
                );
                if let Some(execution) = archived.execution {
                    execution.report.verify(r, &execution.artifacts, replay)?;
                    Ok(Ok(execution))
                } else if let Some(failure) = archived.result.failure {
                    if !replay {
                        return Err(CampaignError::new(
                            "missing_evidence",
                            "runtime failure requires replay for independent verification",
                        ));
                    }
                    match experiment::execute(r, &planned.experiment.as_ref().unwrap().input_hash) {
                        Err(actual) if actual == failure => Ok(Err(actual)),
                        _ => Err(CampaignError::new(
                            "integrity",
                            "runtime failure did not reproduce",
                        )),
                    }
                } else {
                    Err(CampaignError::new(
                        "missing_evidence",
                        "executed trial is absent",
                    ))
                }
            },
            &mut |e| {
                let key = (e.result.case.clone(), e.result.candidate.clone());
                let saved = if let Some(saved) = records.borrow_mut().remove(&key) {
                    saved
                } else {
                    let saved = (load.borrow_mut())(&key.0, &key.1)?;
                    if saved.execution.is_some() || saved.request != e.request {
                        return Err(CampaignError::new(
                            "integrity",
                            "rejected trial contains unexpected execution/request",
                        ));
                    }
                    saved.result
                };
                if saved != e.result {
                    return Err(CampaignError::new(
                        "integrity",
                        "trial conclusions differ from re-derived evidence",
                    ));
                }
                Ok(())
            },
        )?;
        if derived != *self {
            return Err(CampaignError::new(
                "integrity",
                "campaign promotions, coverage, costs, ranking or moments differ from re-derived evidence",
            ));
        }
        Ok(())
    }
}
