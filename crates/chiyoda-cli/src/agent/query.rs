use super::{
    ToolError, ToolResult,
    files::{self, Workspace},
};
use chiyoda_core::{RunBundle, experiment::ExperimentReport, journeys::JourneyReport};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct QueryInput {
    experiment: String,
    view: View,
    arm: Option<String>,
    agent: Option<String>,
    /// Required only for `trace`; must be an exact recorded integration step.
    step: Option<u64>,
    #[serde(default)]
    offset: usize,
    #[serde(default = "page_size")]
    limit: usize,
}

const fn page_size() -> usize {
    50
}

#[derive(Deserialize, JsonSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum View {
    Summary,
    Changes,
    Agents,
    Comparisons,
    Hotspots,
    Resources,
    LowProgress,
    Events,
    Trace,
}

pub(super) fn summary(report: &ExperimentReport) -> Value {
    let mut value = serde_json::to_value(report).expect("report JSON");
    for comparison in value["comparisons"].as_array_mut().expect("comparisons") {
        comparison
            .as_object_mut()
            .expect("comparison")
            .remove("agents");
    }
    value["agent_details"] =
        "Use paginated comparisons or agents queries for individual evidence.".into();
    value
}

fn page(rows: Vec<Value>, input: &QueryInput) -> Value {
    let total = rows.len();
    let end = input.offset.saturating_add(input.limit).min(total);
    let next = (end < total).then_some(end);
    let items: Vec<_> = rows
        .into_iter()
        .skip(input.offset)
        .take(input.limit)
        .collect();
    json!({"total":total,"offset":input.offset,"limit":input.limit,"next_offset":next,"items":items})
}

fn row(value: impl serde::Serialize, artifact: &str, pointer: String) -> Value {
    json!({"value":value,"source":{"artifact":artifact,"pointer":Value::String(pointer)}})
}

pub(super) fn execute(workspace: &Workspace, input: &QueryInput) -> ToolResult {
    if !(1..=200).contains(&input.limit) || input.offset > 1_000_000 {
        return Err(ToolError::new(
            "arguments",
            "query limit must be in 1..=200 and offset at most 1000000",
        ));
    }
    if input.step.is_some() && input.view != View::Trace {
        return Err(ToolError::new(
            "arguments",
            "step applies only to trace queries",
        ));
    }
    if input.agent.is_some()
        && !matches!(
            input.view,
            View::Agents | View::Comparisons | View::LowProgress | View::Events | View::Trace
        )
    {
        return Err(ToolError::new(
            "arguments",
            "agent filter does not apply to this view",
        ));
    }
    let report = files::load_report(workspace, &input.experiment)?;
    if input.view == View::Summary {
        if input.arm.is_some() || input.offset != 0 {
            return Err(ToolError::new(
                "arguments",
                "summary has no arm or offset; use a paginated detail view",
            ));
        }
        return Ok(summary(&report));
    }
    let id = input
        .arm
        .as_deref()
        .ok_or_else(|| ToolError::new("arguments", "this evidence view requires an arm ID"))?;
    let (arm_index, arm) = report
        .arms
        .iter()
        .enumerate()
        .find(|(_, arm)| arm.id == id)
        .ok_or_else(|| ToolError::new("unknown_id", "unknown arm ID"))?;
    if input.view == View::Changes {
        return Ok(page(
            report.plan.arms[arm_index]
                .changed_fields
                .iter()
                .enumerate()
                .map(|(index, field)| {
                    row(
                        field,
                        "report.json",
                        format!("/plan/arms/{arm_index}/changed_fields/{index}"),
                    )
                })
                .collect(),
            input,
        ));
    }
    if input.view == View::Comparisons {
        return comparisons(&report, input, id);
    }
    if arm.journeys.is_none() {
        return Err(ToolError::new(
            "missing_evidence",
            "this arm has no journey evidence; inspect its evaluation failures in the summary",
        ));
    }
    let artifact = format!("arms/{id}/journeys.json");
    let journeys: JourneyReport =
        files::read(&workspace.path(&format!("{}/{artifact}", input.experiment))?)?;
    journeys
        .check_hash()
        .map_err(|error| ToolError::new("integrity", error))?;
    if arm.journeys.as_ref() != Some(&journeys.summary())
        || arm.evaluation.bundle_hash.as_ref() != Some(&journeys.source_bundle_hash)
    {
        return Err(ToolError::new(
            "integrity",
            "journey evidence differs from the experiment arm",
        ));
    }
    if input
        .agent
        .as_ref()
        .is_some_and(|id| !journeys.agents.iter().any(|agent| agent.agent == *id))
    {
        return Err(ToolError::new(
            "unknown_id",
            "unknown pedestrian ID in this arm",
        ));
    }
    if input.view == View::Trace {
        return trace(workspace, input, &journeys, id);
    }
    Ok(page(evidence_rows(&journeys, &artifact, input), input))
}

fn evidence_rows(journeys: &JourneyReport, artifact: &str, input: &QueryInput) -> Vec<Value> {
    let matches_agent = |id: &str| input.agent.as_deref().is_none_or(|wanted| wanted == id);
    match input.view {
        View::Agents => journeys.agents.iter().enumerate().filter(|(_, agent)| matches_agent(&agent.agent)).map(|(index, agent)| {
            row(json!({"agent":agent.agent,"group":agent.group,"completed":agent.evacuated_at_s.is_some(),"scheduled_release_s":agent.scheduled_release_s,
                "admitted_at_s":agent.admitted_at_s,"evacuated_at_s":agent.evacuated_at_s,"elapsed_journey_s":agent.elapsed_journey_s,
                "final_state":agent.final_state,"walking_distance_m":agent.walking_distance_m,"time":agent.time,"nominal_reference":agent.nominal_reference,
                "low_progress_windows":agent.low_progress.len(),"low_progress_span_s":agent.low_progress.iter().map(|window|window.end_time_s-window.start_time_s).sum::<f64>(),
                "decision_events":agent.decisions.len()}), artifact, format!("/agents/{index}"))
        }).collect(),
        View::Hotspots => journeys.hotspots.iter().enumerate().map(|(index, hotspot)| row(hotspot, artifact, format!("/hotspots/{index}"))).collect(),
        View::Resources => journeys.resources.iter().enumerate().map(|(index, resource)| row(resource, artifact, format!("/resources/{index}"))).collect(),
        View::LowProgress => journeys.agents.iter().enumerate().filter(|(_, agent)| matches_agent(&agent.agent))
            .flat_map(|(agent_index, agent)| agent.low_progress.iter().enumerate().map(move |(index, window)| row(json!({"agent":agent.agent,"window":window}), artifact, format!("/agents/{agent_index}/low_progress/{index}")))).collect(),
        View::Events => {
            let mut events: Vec<_> = journeys.agents.iter().enumerate().filter(|(_, agent)| matches_agent(&agent.agent))
                .flat_map(|(agent_index, agent)| agent.decisions.iter().enumerate().map(move |(index, decision)| (decision.event.time_s, agent.agent.as_str(), agent_index, index, decision))).collect();
            events.sort_by(|left, right| left.0.total_cmp(&right.0).then(left.1.cmp(right.1)).then(left.3.cmp(&right.3)));
            events.into_iter().map(|(_, agent, agent_index, index, event)| row(json!({"agent":agent,"decision":event}), artifact, format!("/agents/{agent_index}/decisions/{index}"))).collect()
        }
        View::Summary | View::Changes | View::Comparisons | View::Trace => unreachable!("handled above"),
    }
}

fn trace(
    workspace: &Workspace,
    input: &QueryInput,
    journeys: &JourneyReport,
    id: &str,
) -> ToolResult {
    let matches_agent = |id: &str| input.agent.as_deref().is_none_or(|wanted| wanted == id);

    let step = input
        .step
        .ok_or_else(|| ToolError::new("arguments", "trace queries require an exact step"))?;
    let bundle_artifact = format!("arms/{id}/run.json");
    let bundle: RunBundle =
        files::read(&workspace.path(&format!("{}/{bundle_artifact}", input.experiment))?)?;
    if !bundle.verifies_hash()
        || bundle.bundle_hash != journeys.source_bundle_hash
        || bundle.scenario_hash != chiyoda_core::bundle::canonical_hash(&bundle.scenario)
    {
        return Err(ToolError::new(
            "integrity",
            "run evidence differs from the experiment arm",
        ));
    }
    let (index, frame) = bundle
        .trace
        .iter()
        .enumerate()
        .find(|(_, frame)| frame.step == step)
        .ok_or_else(|| {
            ToolError::new(
                "step_not_recorded",
                "requested integration step is not recorded",
            )
        })?;
    let mut result = page(
        frame
            .agents
            .iter()
            .enumerate()
            .filter(|(_, agent)| matches_agent(&agent.id))
            .map(|(agent_index, agent)| {
                row(
                    agent,
                    &bundle_artifact,
                    format!("/trace/{index}/agents/{agent_index}"),
                )
            })
            .collect(),
        input,
    );
    result["step"] = step.into();
    result["time_s"] = frame.time_s.into();
    result["replay"] = json!({"program":"chiyoda-replay","args":[format!("{}/{bundle_artifact}",input.experiment),"--paused","--at-step",step.to_string()]});
    Ok(result)
}

fn comparisons(report: &ExperimentReport, input: &QueryInput, id: &str) -> ToolResult {
    let (index, comparison) = report
        .comparisons
        .iter()
        .enumerate()
        .find(|(_, comparison)| comparison.candidate == id)
        .ok_or_else(|| {
            ToolError::new(
                "arguments",
                "comparison details require a candidate arm, not baseline",
            )
        })?;
    let rows = comparison
        .agents
        .iter()
        .enumerate()
        .filter(|(_, agent)| input.agent.as_ref().is_none_or(|id| agent.agent == *id))
        .map(|(agent_index, agent)| {
            row(
                agent,
                "report.json",
                format!("/comparisons/{index}/agents/{agent_index}"),
            )
        })
        .collect();
    let mut result = page(rows, input);
    result["same_population_inputs"] = comparison.same_population_inputs.into();
    result["matched_people"] = comparison.matched_people.into();
    result["both_completed"] = comparison.both_completed.into();
    result["metrics"] = serde_json::to_value(&comparison.metrics).expect("metrics JSON");
    Ok(result)
}
