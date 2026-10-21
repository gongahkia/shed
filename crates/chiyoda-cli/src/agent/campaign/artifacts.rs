use super::{CampaignReport, ToolError, Workspace, files};
use chiyoda_core::{bundle::canonical_hash, campaign};
use chiyoda_core::{
    campaign::{EvidenceMoment, MomentSelection, TrialExecution, TrialResult},
    experiment::{ArmArtifacts, ExperimentExecution, ExperimentReport, ExperimentRequest},
};
use chiyoda_replay::evidence::{EvidenceRequest, sha256};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{fmt::Write as _, path::Path};
#[derive(Debug, Serialize, Deserialize)]
pub(super) struct ImageResult {
    pub moment: EvidenceMoment,
    pub receipt: Option<Value>,
    pub error: Option<Value>,
}
#[derive(Debug, Serialize, Deserialize)]
pub(super) struct ImageIndex {
    campaign_report_hash: String,
    pub images: Vec<ImageResult>,
    reconstruction_agent_steps_charged: u64,
    index_hash: String,
}
const MAX_JSON_BYTES: usize = 256 * 1024 * 1024;
pub(super) fn write_bounded<T: Serialize>(path: &Path, value: &T) -> Result<(), ToolError> {
    let mut bytes = serde_json::to_vec_pretty(value)
        .map_err(|e| ToolError::new("artifact_io", e.to_string()))?;
    bytes.push(b'\n');
    if bytes.len() > MAX_JSON_BYTES {
        return Err(ToolError::new(
            "resource_limit",
            "campaign JSON artifact exceeds the 256 MiB read/write limit",
        ));
    }
    std::fs::write(path, bytes)?;
    Ok(())
}
pub(super) fn summary(index: &ImageIndex) -> Value {
    let first = index.images.iter().find(|i| i.receipt.is_some());
    json!({"artifact":"evidence-index.json","index_hash":index.index_hash,"total_images":index.images.len(),"successful":index.images.iter().filter(|i|i.receipt.is_some()).count(),"failed":index.images.iter().filter(|i|i.error.is_some()).count(),"reconstruction_agent_steps_charged":index.reconstruction_agent_steps_charged,"images":first.into_iter().collect::<Vec<_>>(),"scope":"First successful image receipt; query evidence pages for the full inventory and retained failures."})
}
fn integrity(message: &str) -> ToolError {
    ToolError::new("integrity", message)
}
pub(super) fn save_trial(root: &Path, t: &TrialExecution) -> Result<(), ToolError> {
    let path = root
        .join("trials")
        .join(&t.result.case)
        .join(&t.result.candidate);
    std::fs::create_dir_all(&path)?;
    if let Some(r) = &t.request {
        write_bounded(&path.join("request.json"), r)?;
    }
    if let Some(execution) = &t.execution {
        files::save(&path, t.request.as_ref().unwrap(), execution)?;
        for arm in &execution.artifacts {
            for name in ["scenario.json", "run.json", "journeys.json"] {
                let file = path.join("arms").join(&arm.id).join(name);
                if file.exists() && std::fs::metadata(&file)?.len() > MAX_JSON_BYTES as u64 {
                    return Err(ToolError::new(
                        "resource_limit",
                        "trial JSON artifact exceeds the 256 MiB archival limit; partial evidence is retained",
                    ));
                }
            }
        }
    }
    write_bounded(&path.join("trial.json"), &t.result)?;
    Ok(())
}
pub(super) fn load_trial(
    w: &Workspace,
    root: &str,
    case: &str,
    candidate: &str,
) -> Result<TrialExecution, ToolError> {
    let prefix = format!("{root}/trials/{case}/{candidate}");
    let result: TrialResult = files::read(&w.path(&format!("{prefix}/trial.json"))?)?;
    let request_path = w.path(&format!("{prefix}/request.json"))?;
    let request: Option<ExperimentRequest> = if request_path.exists() {
        Some(files::read(&request_path)?)
    } else {
        None
    };
    let execution = if result.status == campaign::TrialStatus::Executed {
        let report: ExperimentReport = files::read(&w.path(&format!("{prefix}/report.json"))?)?;
        let mut artifacts = vec![];
        // Load only the inventory declared by the recipe, never report-supplied paths.
        let r = request
            .as_ref()
            .ok_or_else(|| integrity("executed trial omits its request"))?;
        for id in std::iter::once("baseline").chain(r.variants.iter().map(|v| v.id.as_str())) {
            if id != "baseline" && id != candidate {
                return Err(integrity("unexpected trial arm identifier"));
            }
            let arm = report
                .arms
                .iter()
                .find(|a| a.id == id)
                .ok_or_else(|| integrity("trial report omits a declared arm"))?;
            artifacts.push(ArmArtifacts {
                id: id.into(),
                scenario: files::read(&w.path(&format!("{prefix}/arms/{id}/scenario.json"))?)?,
                bundle: if arm.evaluation.bundle_hash.is_some() {
                    Some(files::read(
                        &w.path(&format!("{prefix}/arms/{id}/run.json"))?,
                    )?)
                } else {
                    None
                },
                journeys: if arm.journeys.is_some() {
                    Some(files::read(
                        &w.path(&format!("{prefix}/arms/{id}/journeys.json"))?,
                    )?)
                } else {
                    None
                },
            });
        }
        Some(ExperimentExecution { report, artifacts })
    } else {
        None
    };
    Ok(TrialExecution {
        result,
        request,
        execution,
    })
}
fn evidence_request(m: &EvidenceMoment) -> EvidenceRequest {
    let selection = match &m.selection {
        MomentSelection::Agent { id } => json!({"kind":"agent","id":id}),
        MomentSelection::Resource { resource_kind, id } => {
            json!({"kind":"resource","resource_kind":resource_kind,"id":id})
        }
    };
    serde_json::from_value(json!({"step":m.step,"selection":selection}))
        .expect("known evidence selection")
}
pub(super) fn render(
    w: &Workspace,
    output: &str,
    report: &CampaignReport,
) -> Result<ImageIndex, ToolError> {
    let mut index = ImageIndex {
        campaign_report_hash: report.report_hash.clone(),
        images: vec![],
        reconstruction_agent_steps_charged: 0,
        index_hash: String::new(),
    };
    for (n, moment) in report.evidence_moments.iter().enumerate() {
        let image = format!("evidence/{n:03}.png");
        let bundle = format!(
            "trials/{}/{}/arms/{}/run.json",
            moment.case, moment.candidate, moment.arm
        );
        let input:super::super::replay::RenderInput=serde_json::from_value(json!({"bundle":format!("{output}/{bundle}"),"output":format!("{output}/{image}"),"request":evidence_request(moment)})).expect("render input");
        index.reconstruction_agent_steps_charged += moment.reconstruction_agent_steps;
        let (receipt, error) = match super::super::replay::execute(w, &input) {
            Ok(r) if r["bundle_sha256"] == moment.source_bundle_hash => (
                Some(
                    json!({"image":{"artifact":image,"sha256":r["image"]["sha256"],"mime_type":"image/png","width":1200,"height":800},"evidence_artifact":format!("{image}.json"),"evidence_sha256":r["evidence_sha256"],"bundle_artifact":bundle,"bundle_sha256":r["bundle_sha256"]}),
                ),
                None,
            ),
            Ok(_) => (
                None,
                Some(
                    json!({"code":"integrity","message":"rendered source differs from the campaign moment"}),
                ),
            ),
            Err(e) => (None, Some(serde_json::to_value(e).expect("render error"))),
        };
        index.images.push(ImageResult {
            moment: moment.clone(),
            receipt,
            error,
        });
    }
    index.index_hash = canonical_hash(&index);
    write_bounded(&w.path(&format!("{output}/evidence-index.json"))?, &index)?;
    Ok(index)
}
pub(super) fn read_index(
    w: &Workspace,
    root: &str,
    report: &CampaignReport,
) -> Result<ImageIndex, ToolError> {
    let mut index: ImageIndex = files::read(&w.path(&format!("{root}/evidence-index.json"))?)?;
    let saved = index.index_hash.clone();
    index.index_hash.clear();
    if saved != canonical_hash(&index)
        || index.campaign_report_hash != report.report_hash
        || index.images.len() != report.evidence_moments.len()
        || index
            .images
            .iter()
            .zip(&report.evidence_moments)
            .any(|(i, m)| i.moment != *m || i.receipt.is_some() == i.error.is_some())
        || index.reconstruction_agent_steps_charged
            != report
                .evidence_moments
                .iter()
                .map(|m| m.reconstruction_agent_steps)
                .sum::<u64>()
    {
        return Err(integrity(
            "image inventory/hash/source linkage differs from campaign evidence moments",
        ));
    }
    for (n, item) in index.images.iter().enumerate() {
        if let Some(receipt) = &item.receipt {
            let image = format!("evidence/{n:03}.png");
            let valid_hash = |value: &Value| {
                value
                    .as_str()
                    .is_some_and(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
            };
            if !receipt["image"].is_object()
                || receipt["image"]["artifact"] != image
                || receipt["evidence_artifact"] != format!("{image}.json")
                || receipt["bundle_artifact"]
                    != format!(
                        "trials/{}/{}/arms/{}/run.json",
                        item.moment.case, item.moment.candidate, item.moment.arm
                    )
                || receipt["bundle_sha256"] != item.moment.source_bundle_hash
                || !valid_hash(&receipt["image"]["sha256"])
                || !valid_hash(&receipt["evidence_sha256"])
            {
                return Err(integrity(
                    "malformed image receipt or unexpected artifact/source path",
                ));
            }
        }
    }
    index.index_hash = saved;
    Ok(index)
}
pub(super) fn verify_images(
    w: &Workspace,
    root: &str,
    report: &CampaignReport,
    replay: bool,
) -> Result<Value, ToolError> {
    let index = read_index(w, root, report)?;
    let mut verified = 0;
    let mut failed = 0;
    for (n, item) in index.images.iter().enumerate() {
        let Some(receipt) = &item.receipt else {
            failed += 1;
            continue;
        };
        let image = format!("evidence/{n:03}.png");
        let sidecar = format!("{image}.json");
        let bundle = format!(
            "trials/{}/{}/arms/{}/run.json",
            item.moment.case, item.moment.candidate, item.moment.arm
        );
        if receipt["image"]["artifact"] != image
            || receipt["evidence_artifact"] != sidecar
            || receipt["bundle_artifact"] != bundle
            || receipt["bundle_sha256"] != item.moment.source_bundle_hash
        {
            return Err(integrity(
                "image receipt has unexpected artifact paths or source",
            ));
        }
        let png = files::read_bytes(&w.path(&format!("{root}/{image}"))?, 4 * 1024 * 1024)?;
        let meta = files::read_bytes(&w.path(&format!("{root}/{sidecar}"))?, 8 * 1024 * 1024)?;
        let value: Value =
            serde_json::from_slice(&meta).map_err(|e| ToolError::new("input", e.to_string()))?;
        if receipt["image"]["sha256"] != sha256(&png)
            || receipt["evidence_sha256"] != sha256(&meta)
            || value["image_sha256"] != sha256(&png)
            || value["bundle_sha256"] != item.moment.source_bundle_hash
            || value["inspection"]["source_bundle_hash"] != item.moment.source_bundle_hash
            || value["request"]
                != serde_json::to_value(evidence_request(&item.moment)).expect("request")
        {
            return Err(integrity(
                "image bytes, sidecar or evidence request changed",
            ));
        }
        if replay {
            if value["renderer"]
                != serde_json::to_value(chiyoda_replay::render::provenance()).expect("renderer")
            {
                return Err(ToolError::new(
                    "renderer_mismatch",
                    "saved image uses a different renderer; verify without replay for source/hash checks, or use its recorded renderer revision for exact pixel reconstruction",
                ));
            }
            let source: chiyoda_core::RunBundle =
                files::read(&w.path(&format!("{root}/{bundle}"))?)?;
            let (expected, manifest) =
                chiyoda_replay::evidence::render_evidence(&source, &evidence_request(&item.moment))
                    .map_err(|e| ToolError::new("replay_evidence", e.to_string()))?;
            if png != expected || value != serde_json::to_value(manifest).expect("manifest") {
                return Err(integrity(
                    "annotated pixels or measurements differ from reconstructed source",
                ));
            }
        }
        verified += 1;
    }
    Ok(
        json!({"successful":verified,"failed_exports_retained":failed,"pixels_rederived":replay,"all_requested_exports_succeeded":failed==0}),
    )
}
#[allow(clippy::too_many_lines)] // one derived narrative keeps candidate, trial and evidence sections together
pub(super) fn markdown(
    root: &Path,
    r: &CampaignReport,
    images: &ImageIndex,
) -> Result<(), ToolError> {
    let mut text = format!(
        "# Campaign: {}\n\n{}\n\n## Candidates\n\n| Candidate | Screening rank | Final mean-benefit rank | Status | Eligible / expected cases |\n|---|---:|---:|---|---:|\n",
        r.name.replace('\n', " "),
        r.claim_boundary
    );
    for c in &r.candidates {
        let _ = writeln!(
            text,
            "| {} | {} | {} | {} | {} / {} |",
            c.candidate,
            c.screening_rank
                .map_or_else(|| "unavailable".into(), |n| n.to_string()),
            c.final_rank
                .map_or_else(|| "unavailable".into(), |n| n.to_string()),
            c.status,
            c.eligible_cases,
            c.expected_cases
        );
    }
    text.push_str("\n## Objective evidence\n\nPositive benefit follows the declared objective direction. Each case receives equal weight. Missing/invalid cases exclude a final rank; wins do not erase losses.\n\n| Candidate | Metric | Paired cases | Mean benefit | Worst benefit | Wins / ties / losses |\n|---|---|---:|---:|---:|---|\n");
    for c in &r.candidates {
        for o in &c.objectives {
            let number =
                |n: Option<f64>| n.map_or_else(|| "unavailable".into(), |v| format!("{v:.4}"));
            let _ = writeln!(
                text,
                "| {} | {:?} | {} | {} | {} | {} / {} / {} |",
                c.candidate,
                o.metric,
                o.paired_cases,
                number(o.mean_benefit),
                number(o.worst_benefit),
                o.wins,
                o.ties,
                o.losses
            );
        }
    }
    text.push_str("\n## Trials and explanations\n\n");
    for t in &r.trials {
        let _ = writeln!(
            text,
            "### {} / {}\n\n{:?}; eligible: {}. [Trial evidence](trials/{}/{}/trial.json).\n",
            t.case, t.candidate, t.status, t.eligible, t.case, t.candidate
        );
        for reason in &t.reasons {
            let _ = writeln!(text, "- {}: {}", reason.code, reason.message);
        }
        if t.status == campaign::TrialStatus::Executed {
            let _ = writeln!(
                text,
                "\n[Controlled comparison](trials/{}/{}/report.md). Baseline checks passed: {}; candidate checks passed: {}.\n",
                t.case,
                t.candidate,
                t.baseline_checks_passed
                    .map_or_else(|| "unavailable".into(), |v| v.to_string()),
                t.candidate_checks_passed
                    .map_or_else(|| "unavailable".into(), |v| v.to_string())
            );
        }
        for p in &t.affected_people {
            let _ = writeln!(
                text,
                "- Person `{}`: completed {} → {}; completed-pair elapsed difference {} s; service-wait difference {:.3} s; low-progress-span difference {:.3} s.",
                p.agent,
                p.baseline_completed,
                p.candidate_completed,
                p.elapsed_difference_s
                    .map_or_else(|| "unavailable".into(), |v| format!("{v:.3}")),
                p.service_wait_difference_s,
                p.low_progress_span_difference_s
            );
        }
        for f in &t.findings {
            let _ = writeln!(text, "- {}", f.message);
            for e in &f.evidence {
                let _ = writeln!(
                    text,
                    "  [Evidence]({}) `{}`; replay step {}.",
                    e.artifact,
                    e.pointer,
                    e.replay_step
                        .map_or_else(|| "unavailable".into(), |v| v.to_string())
                );
            }
        }
        text.push('\n');
    }
    text.push_str("## Annotated moments\n\n");
    for e in &images.images {
        if let Some(receipt) = &e.receipt {
            let _ = writeln!(
                text,
                "- {} / {} / {} / step {}: [PNG]({}) · [exact measurements]({}). {}",
                e.moment.case,
                e.moment.candidate,
                e.moment.arm,
                e.moment.step,
                receipt["image"]["artifact"].as_str().unwrap(),
                receipt["evidence_artifact"].as_str().unwrap(),
                e.moment.reason
            );
        } else {
            let _ = writeln!(
                text,
                "- {} / {} / {}: export failed; see evidence-index.json for the retained error.",
                e.moment.case, e.moment.candidate, e.moment.arm
            );
        }
    }
    text.push_str("\n[Canonical report](report.json) · [budget and full preflight](plan.json) · [input and permissions](request.json) · [image hashes and failures](evidence-index.json).\n");
    std::fs::write(root.join("report.md"), text)?;
    Ok(())
}
