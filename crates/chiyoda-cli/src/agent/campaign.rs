//! Campaign transport uses the same bounded core for one-shot calls and MCP.
mod artifacts;
use super::{
    ToolError, ToolResult,
    files::{self, Workspace},
};
use chiyoda_core::{
    bundle::canonical_hash,
    campaign::{self, CampaignPlan, CampaignReport, CampaignRequest},
};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};
#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct PlanInput {
    /// Workspace-relative `CampaignRequest` JSON. Source scenarios are embedded for portability.
    config: String,
    /// Return one prepared case with its authored IDs/geometry before designing overrides.
    case: Option<String>,
}
#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct RunInput {
    config: String,
    expected_plan_hash: String,
    output: String,
}
#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct VerifyInput {
    campaign: String,
    #[serde(default)]
    replay: bool,
}
#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct QueryInput {
    campaign: String,
    view: View,
    candidate: Option<String>,
    case: Option<String>,
    #[serde(default)]
    offset: usize,
    #[serde(default = "page_size")]
    limit: usize,
}
const fn page_size() -> usize {
    20
}
#[derive(Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
enum View {
    Summary,
    Candidates,
    Trials,
    Evidence,
}
fn request(w: &Workspace, path: &str) -> Result<CampaignRequest, ToolError> {
    files::read(&w.path(path)?)
}
pub(super) fn plan(w: &Workspace, input: Value) -> ToolResult {
    let i: PlanInput = decode(input)?;
    let p = campaign::plan(&request(w, &i.config)?)?;
    if let Some(id) = i.case {
        let case = p.cases.iter().find(|c| c.id == id).ok_or_else(|| {
            ToolError::new(
                "unknown_id",
                "case is absent or preparation was blocked by budget",
            )
        })?;
        return Ok(
            json!({"plan_hash":p.plan_hash,"input_hash":p.input_hash,"runnable":p.runnable,"case":case}),
        );
    }
    Ok(plan_summary(&p))
}
fn decode<T: serde::de::DeserializeOwned>(input: Value) -> Result<T, ToolError> {
    serde_json::from_value(input).map_err(|e| ToolError::new("arguments", e.to_string()))
}
fn plan_summary(p: &CampaignPlan) -> Value {
    json!({"campaign_version":p.campaign_version,"runtime_version":p.runtime_version,"input_hash":p.input_hash,"plan_hash":p.plan_hash,"runnable":p.runnable,"reserved_trials":p.reserved_trials,"reserved_agent_steps":p.reserved_agent_steps,"reserved_evidence_agent_steps":p.reserved_evidence_agent_steps,"reserved_trace_samples":p.reserved_trace_samples,"reserved_generation_attempts":p.reserved_generation_attempts,"reserved_generation_decisions":p.reserved_generation_decisions,"issues":p.issues,
    "cases":p.cases.iter().map(|c|json!({"id":c.id,"source_hash":c.source_hash,"scenario_hash":c.scenario.as_ref().map(canonical_hash),"issues":c.issues,"generation_manifest_hash":c.generation.as_ref().map(canonical_hash)})).collect::<Vec<_>>(),
    "trials":p.trials.iter().map(|t|json!({"case":t.case,"candidate":t.candidate,"eligible_preflight":t.issues.is_empty(),"issues":t.issues,"estimated_agent_steps":t.experiment.as_ref().map(|e|e.estimated_agent_steps),"retained_trace_samples":t.experiment.as_ref().map(|e|e.retained_trace_samples),"changed_fields":t.experiment.as_ref().map(|e|&e.arms[1].changed_fields),"same_population_inputs":t.experiment.as_ref().map(|e|e.arms[1].same_population_inputs)})).collect::<Vec<_>>()})
}
pub(super) fn run(workspace: &Workspace, input: Value) -> ToolResult {
    let input: RunInput = decode(input)?;
    let request = request(workspace, &input.config)?;
    let prepared = campaign::prepare_run(&request)?;
    let plan = prepared.plan();
    if plan.plan_hash != input.expected_plan_hash {
        return Err(ToolError::new(
            "stale_plan",
            "campaign input, runtime or generated plan changed since planning",
        ));
    }
    if !plan.runnable {
        let mut error = ToolError::new(
            "invalid_plan",
            "campaign budget preflight failed; no simulations were executed",
        );
        error.details = Some(plan_summary(plan));
        return Err(error);
    }
    let root = workspace.new_directory(&input.output)?;
    let mut execute: ToolResult = (|| {
        artifacts::write_bounded(&root.join("request.json"), &request)?;
        artifacts::write_bounded(&root.join("plan.json"), &plan)?;
        for c in &plan.cases {
            let directory = root.join("cases").join(&c.id);
            std::fs::create_dir_all(&directory)?;
            artifacts::write_bounded(&directory.join("prepared.json"), c)?;
            if let Some(s) = &c.scenario {
                artifacts::write_bounded(&directory.join("scenario.json"), s)?;
            }
            if let Some(g) = &c.generation {
                artifacts::write_bounded(&directory.join("generation.json"), g)?;
            }
        }
        let report = prepared.execute_with(&input.expected_plan_hash, |trial| {
            artifacts::save_trial(&root, trial).map_err(|error| campaign::CampaignError {
                code: error.code,
                message: error.message,
            })
        })?;
        let images = artifacts::render(workspace, &input.output, &report)?;
        artifacts::markdown(&root, &report, &images)?;
        // The report is the publication marker. A partial directory retains its ledger/failure.
        artifacts::write_bounded(&root.join("report.json"), &report)?;
        let mut result = summary(&report);
        result["output"] = input.output.clone().into();
        result["evidence_images"] = artifacts::summary(&images);
        Ok(result)
    })();
    if let Err(error) = &mut execute {
        error.details =
            Some(json!({"output":input.output,"complete":false,"failure_artifact":"failure.json"}));
        let _ = artifacts::write_bounded(&root.join("failure.json"), error);
    }
    execute
}
pub(super) fn load(
    w: &Workspace,
    path: &str,
) -> Result<(CampaignRequest, CampaignReport), ToolError> {
    let r: CampaignRequest = files::read(&w.path(&format!("{path}/request.json"))?)?;
    let report: CampaignReport = files::read(&w.path(&format!("{path}/report.json"))?)?;
    report.check_hash()?;
    let portable = |s: &str| {
        !s.is_empty()
            && s.len() <= 64
            && s.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    };
    if report.input_hash != canonical_hash(&r)
        || report
            .trials
            .iter()
            .any(|t| !portable(&t.case) || !portable(&t.candidate))
        || report.evidence_moments.iter().any(|m| {
            !portable(&m.case)
                || !portable(&m.candidate)
                || (m.arm != "baseline" && m.arm != m.candidate)
        })
    {
        return Err(ToolError::new(
            "integrity",
            "invalid campaign input linkage or artifact identifiers",
        ));
    }
    Ok((r, report))
}
fn summary(r: &CampaignReport) -> Value {
    let candidates: Vec<Value> = r
        .candidates
        .iter()
        .map(|c| {
            let mut value = serde_json::to_value(c).expect("candidate");
            value.as_object_mut().unwrap().remove("exclusion_reasons");
            value["exclusion_reasons_total"] = c.exclusion_reasons.len().into();
            let mut codes = std::collections::BTreeMap::<String, usize>::new();
            for trial in r.trials.iter().filter(|t| t.candidate == c.candidate) {
                for issue in &trial.reasons {
                    *codes.entry(issue.code.clone()).or_default() += 1;
                }
            }
            value["ineligible_reason_counts"] = serde_json::to_value(codes).expect("reason counts");
            value
        })
        .collect();
    json!({"campaign_version":r.campaign_version,"report_hash":r.report_hash,"plan_hash":r.plan_hash,"name":r.name,"promoted":r.promoted,"objectives":r.objectives,"screening_case":r.screening_case,"declared_stress_cases":r.declared_stress_cases,"candidates":candidates,"executed_trials":r.trials.iter().filter(|t|t.status==campaign::TrialStatus::Executed).count(),"rejected_trials":r.trials.iter().filter(|t|t.status==campaign::TrialStatus::Rejected).count(),"runtime_failed_trials":r.trials.iter().filter(|t|t.status==campaign::TrialStatus::RuntimeFailed).count(),"charged_simulation_agent_steps":r.charged_simulation_agent_steps,"reserved_agent_steps":r.reserved_agent_steps,"reserved_evidence_agent_steps":r.reserved_evidence_agent_steps,"retained_trace_samples":r.retained_trace_samples,"report":"report.json","claim_boundary":r.claim_boundary})
}
pub(super) fn query(w: &Workspace, input: Value) -> ToolResult {
    let i: QueryInput = decode(input)?;
    if !(1..=200).contains(&i.limit) || i.offset > 1_000_000 {
        return Err(ToolError::new(
            "arguments",
            "limit 1..200 and offset at most 1000000",
        ));
    }
    let (request, r) = load(w, &i.campaign)?;
    if i.candidate
        .as_ref()
        .is_some_and(|c| !r.candidates.iter().any(|t| t.candidate == *c))
        || i.case.as_ref().is_some_and(|c| {
            request.screening.id != *c && !request.stress_cases.iter().any(|t| t.id == *c)
        })
    {
        return Err(ToolError::new(
            "unknown_id",
            "candidate/case is not present in this campaign",
        ));
    }
    if matches!(i.view, View::Summary) {
        if i.candidate.is_some() || i.case.is_some() {
            return Err(ToolError::new(
                "arguments",
                "summary does not accept case/candidate filters",
            ));
        }
        return Ok(summary(&r));
    }
    let rows:Vec<Value>=match i.view {
        View::Candidates=>{if i.case.is_some(){return Err(ToolError::new("arguments","candidate rankings span cases and cannot filter by case"));}r.candidates.iter().enumerate().filter(|(_,c)|i.candidate.as_ref().is_none_or(|id|*id==c.candidate)).map(|(n,c)|json!({"value":c,"source":{"artifact":"report.json","pointer":format!("/candidates/{n}")}})).collect()},
        View::Trials=>r.trials.iter().enumerate().filter(|(_,t)|i.candidate.as_ref().is_none_or(|c|*c==t.candidate)&&i.case.as_ref().is_none_or(|c|*c==t.case)).map(|(n,t)|json!({"value":t,"experiment":format!("{}/trials/{}/{}",i.campaign,t.case,t.candidate),"source":{"artifact":"report.json","pointer":format!("/trials/{n}")}})).collect(),
        View::Evidence=>artifacts::read_index(w,&i.campaign,&r)?.images.into_iter().enumerate().filter(|(_,e)|i.candidate.as_ref().is_none_or(|c|*c==e.moment.candidate)&&i.case.as_ref().is_none_or(|c|*c==e.moment.case)).map(|(n,e)|json!({"value":e,"source":{"artifact":"evidence-index.json","pointer":format!("/images/{n}")}})).collect(),
        View::Summary=>unreachable!(),
    };
    let total = rows.len();
    let next =
        (i.offset.saturating_add(i.limit) < total).then_some(i.offset.saturating_add(i.limit));
    Ok(
        json!({"campaign":i.campaign,"total":total,"offset":i.offset,"limit":i.limit,"next_offset":next,"items":rows.into_iter().skip(i.offset).take(i.limit).collect::<Vec<_>>(),"verification":"request/report hashes; use campaign_verify to re-derive conclusions and verify linked artifacts"}),
    )
}
pub(super) fn verify(w: &Workspace, input: Value) -> ToolResult {
    let i: VerifyInput = decode(input)?;
    let (r, report) = load(w, &i.campaign)?;
    let saved_plan: CampaignPlan = files::read(&w.path(&format!("{}/plan.json", i.campaign))?)?;
    let prepared = campaign::prepare_run(&r)?;
    let plan = prepared.plan();
    if *plan != saved_plan {
        return Err(ToolError::new(
            "integrity",
            "saved campaign plan differs from request",
        ));
    }
    for c in &plan.cases {
        let saved: campaign::PreparedCase =
            files::read(&w.path(&format!("{}/cases/{}/prepared.json", i.campaign, c.id))?)?;
        if saved != *c {
            return Err(ToolError::new(
                "integrity",
                "prepared case or WFC provenance changed",
            ));
        }
        if let Some(s) = &c.scenario {
            let saved: chiyoda_core::CanonicalScenario =
                files::read(&w.path(&format!("{}/cases/{}/scenario.json", i.campaign, c.id))?)?;
            if saved != *s {
                return Err(ToolError::new("integrity", "prepared scenario changed"));
            }
        }
        if let Some(g) = &c.generation {
            let saved: Value =
                files::read(&w.path(&format!("{}/cases/{}/generation.json", i.campaign, c.id))?)?;
            if saved != *g {
                return Err(ToolError::new("integrity", "generation provenance changed"));
            }
        }
    }
    prepared.verify_with(&report, i.replay, |case, candidate| {
        artifacts::load_trial(w, &i.campaign, case, candidate).map_err(|e| {
            campaign::CampaignError {
                code: e.code,
                message: e.message,
            }
        })
    })?;
    let images = artifacts::verify_images(w, &i.campaign, &report, i.replay)?;
    Ok(
        json!({"verified":true,"campaign_version":campaign::CAMPAIGN_VERSION,"report_hash":report.report_hash,"trials":report.trials.len(),"images":images,"verification":if i.replay {"rederived_evidence_runs_and_image_pixels"}else{"rederived_evidence_and_image_hash_source_links"},"verification_work":"Explicit verification is additional work outside the original execution budget."}),
    )
}
