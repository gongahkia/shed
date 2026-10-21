//! Bounded workspace rendering; stdio attaches the exact hash-checked PNG.
use super::{
    ToolError, ToolResult,
    files::{self, Workspace},
};
use base64::{Engine, engine::general_purpose::STANDARD};
use chiyoda_core::RunBundle;
use chiyoda_replay::evidence::{EvidenceRequest, render_evidence, save_pair, sha256};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};
#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct RenderInput {
    /// Workspace-relative verified run.json, e.g. experiment/arms/baseline/run.json.
    bundle: String,
    /// New workspace-relative PNG destination. Neither image nor sidecar may exist.
    output: String,
    request: EvidenceRequest,
}
pub(super) fn execute(workspace: &Workspace, input: &RenderInput) -> ToolResult {
    if !std::path::Path::new(&input.output)
        .extension()
        .is_some_and(|e| e.eq_ignore_ascii_case("png"))
    {
        return Err(ToolError::new(
            "arguments",
            "output must be a new .png artifact",
        ));
    }
    let source = workspace.path(&input.bundle)?;
    let target = workspace.path(&input.output)?;
    chiyoda_replay::export::ensure_new_export_targets(
        &target,
        &chiyoda_replay::export::gif_manifest_path(&target),
    )
    .map_err(|e| ToolError::new("artifact_io", e.to_string()))?;
    let bundle: RunBundle = files::read(&source)?;
    let people = if bundle.options.contains_key("isolated_agent") {
        1
    } else {
        bundle
            .scenario
            .scenario
            .agents
            .iter()
            .map(|g| u64::from(g.count))
            .sum::<u64>()
    };
    let steps = chiyoda_core::integration_step_count(
        bundle.scenario.scenario.duration_s,
        bundle.scenario.scenario.timestep_s,
    );
    let samples = bundle.trace.iter().try_fold(0u64, |sum, f| {
        sum.checked_add(u64::try_from(f.agents.len()).unwrap_or(u64::MAX))
    });
    if people > 10000
        || steps.saturating_mul(people) > 2_000_000
        || samples.is_none_or(|s| s > 2_000_000)
    {
        return Err(ToolError::new(
            "resource_limit",
            "render evidence is bounded to 10000 people and 2000000 agent-steps / recorded samples",
        ));
    }
    let (png, manifest) = render_evidence(&bundle, &input.request)
        .map_err(|e| ToolError::new("replay_evidence", e.to_string()))?;
    let mut inspection = serde_json::to_value(&manifest.inspection).expect("inspection JSON");
    let points = inspection["trail"].as_array().map_or(0, Vec::len);
    inspection["trail"] = json!({"recorded_points":points,"artifact_pointer":"/inspection/trail","note":"Consecutive positions are retained in the full evidence sidecar."});
    inspection["events"]=json!(manifest.inspection.events.iter().take(20).map(|event|json!({"index":event.index,"time_s":event.event.time_s,"kind":event.event.kind,"replay_step":event.replay_step,"source_pointer":event.source_pointer,"artifact_pointer":format!("/inspection/events/{}",manifest.inspection.events.iter().position(|e|e.index==event.index).unwrap())})).collect::<Vec<_>>());
    let metadata = serde_json::to_vec_pretty(&manifest).expect("manifest JSON");
    let receipt = json!({"evidence_version":"1","bundle_artifact":input.bundle,"bundle_sha256":manifest.bundle_sha256,"scenario_sha256":manifest.scenario_sha256,"selected_surface":manifest.selected_surface,"rendered_surfaces":manifest.rendered_surfaces,"recorded_step":manifest.inspection.recorded_step,"recorded_time_s":manifest.inspection.recorded_time_s,"image":{"artifact":input.output,"sha256":manifest.image_sha256,"mime_type":"image/png","width":1200,"height":800},"evidence_artifact":format!("{}.json",input.output),"evidence_sha256":sha256(&metadata),"inspection":inspection});
    if serde_json::to_vec(&receipt).expect("receipt").len() > 1024 * 1024 {
        return Err(ToolError::new(
            "resource_limit",
            "inspection receipt exceeds 1 MiB",
        ));
    }
    save_pair(&target, &png, &manifest)
        .map_err(|e| ToolError::new("artifact_io", e.to_string()))?;
    Ok(receipt)
}
pub(super) fn image_content(
    workspace: &Workspace,
    name: &str,
    result: &Value,
) -> Result<Option<Value>, ToolError> {
    let (path, expected_hash) = match name {
        "chiyoda_render_evidence" => (
            result["image"]["artifact"]
                .as_str()
                .ok_or_else(|| ToolError::new("integrity", "render receipt omits image artifact"))?
                .to_owned(),
            &result["image"]["sha256"],
        ),
        "chiyoda_campaign_run" => {
            let Some(receipt) = result["evidence_images"]["images"]
                .as_array()
                .and_then(|items| items.iter().find_map(|i| i["receipt"].as_object()))
                .map(|r| &r["image"])
            else {
                return Ok(None);
            };
            (
                format!(
                    "{}/{}",
                    result["output"].as_str().unwrap(),
                    receipt["artifact"].as_str().unwrap()
                ),
                &receipt["sha256"],
            )
        }
        "chiyoda_campaign_query" => {
            let Some(receipt) = result["items"]
                .as_array()
                .and_then(|items| items.iter().find_map(|i| i["value"]["receipt"].as_object()))
                .map(|r| &r["image"])
            else {
                return Ok(None);
            };
            (
                format!(
                    "{}/{}",
                    result["campaign"].as_str().unwrap(),
                    receipt["artifact"].as_str().unwrap()
                ),
                &receipt["sha256"],
            )
        }
        _ => return Ok(None),
    };
    let bytes = files::read_bytes(&workspace.path(&path)?, 4 * 1024 * 1024)?;
    if sha256(&bytes) != *expected_hash || !bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Err(ToolError::new(
            "integrity",
            "rendered PNG differs from its evidence receipt",
        ));
    }
    Ok(Some(
        json!({"type":"image","mimeType":"image/png","data":STANDARD.encode(bytes)}),
    ))
}
