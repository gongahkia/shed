//! Synchronized comparisons retain each run's actual sampling and population scope.
use crate::{
    HEIGHT, ViewMode, WIDTH,
    evidence::{require_reconstructed, save_pair, sha256},
    export::encode_png,
    inspection::{EvidenceIndex, Inspection, Selection},
    navigation::{CameraControl, index_at_time},
    render::{self, Camera, WorldBounds},
};
use anyhow::{Result, ensure};
use chiyoda_core::{RunBundle, bundle::canonical_hash};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ComparisonRequest {
    pub time_s: f64,
    #[serde(default)]
    pub surface: Option<String>,
    #[serde(default)]
    pub view: ViewMode,
    #[serde(default)]
    pub selection: Option<Selection>,
    #[serde(default)]
    pub baseline_label: Option<String>,
    #[serde(default)]
    pub candidate_label: Option<String>,
    #[serde(default)]
    pub camera: CameraControl,
}
#[derive(Debug, Serialize)]
pub struct FrameEvidence {
    pub bundle_sha256: String,
    pub scenario_sha256: String,
    pub recorded_step: u64,
    pub recorded_time_s: f64,
    pub held_for_s: f64,
    pub surface: String,
    pub state_counts: [usize; 5],
    pub population: usize,
    pub full_run_completed: u32,
    pub full_run_people: u32,
    pub full_run_duration_s: f64,
    pub full_run_clearance_s: Option<f64>,
    pub inspection: Option<Inspection>,
    pub selection_unavailable: Option<String>,
}
#[derive(Debug, Serialize)]
pub struct ComparisonEvidence {
    pub comparison_version: &'static str,
    pub requested_time_s: f64,
    pub common_start_s: f64,
    pub common_end_s: f64,
    pub same_population_inputs: bool,
    pub state_count_order: [&'static str; 5],
    pub common_camera_bounds: WorldBounds,
    pub baseline: FrameEvidence,
    pub candidate: FrameEvidence,
    pub completed_at_shared_time_difference: i64,
    pub full_run_completed_difference: i64,
    pub full_run_clearance_difference_s: Option<f64>,
    pub paired_completed_elapsed_difference_s: Option<f64>,
    pub claim_boundary: &'static str,
}
/// Physical traits, goals, releases, original group order and seed must agree.
/// Knowledge can be the experimental intervention, matching experiment pairing.
#[must_use]
pub fn same_population(a: &RunBundle, b: &RunBundle) -> bool {
    let key = |bundle: &RunBundle| {
        let mut groups = bundle.scenario.scenario.agents.clone();
        for group in &mut groups {
            group.knowledge = None;
        }
        canonical_hash(&(
            bundle.scenario.scenario.seed,
            groups,
            bundle.options.get("isolated_agent"),
        ))
    };
    key(a) == key(b)
}
pub fn interval(a: &RunBundle, b: &RunBundle) -> Result<(f64, f64)> {
    let first_a = a
        .trace
        .first()
        .ok_or_else(|| anyhow::anyhow!("empty baseline trace"))?;
    let first_b = b
        .trace
        .first()
        .ok_or_else(|| anyhow::anyhow!("empty candidate trace"))?;
    let start = first_a.time_s.max(first_b.time_s);
    let end = a
        .trace
        .last()
        .unwrap()
        .time_s
        .min(b.trace.last().unwrap().time_s);
    ensure!(start <= end, "runs do not share a recorded time interval");
    Ok((start, end))
}
pub fn surfaces(a: &RunBundle, b: &RunBundle, selected: Option<&str>) -> Result<(usize, usize)> {
    let floor = selected
        .or_else(|| {
            a.scenario
                .scenario
                .surfaces
                .iter()
                .find(|s| b.scenario.scenario.surfaces.iter().any(|t| t.id == s.id))
                .map(|s| s.id.as_str())
        })
        .ok_or_else(|| {
            anyhow::anyhow!("comparison requires an authored surface ID present in both runs")
        })?;
    let find = |bundle: &RunBundle| {
        bundle
            .scenario
            .scenario
            .surfaces
            .iter()
            .position(|s| s.id == floor)
            .ok_or_else(|| anyhow::anyhow!("surface `{floor}` is not present in both runs"))
    };
    Ok((find(a)?, find(b)?))
}
fn frame_evidence(
    bundle: &RunBundle,
    frame: usize,
    surface: usize,
    request: &ComparisonRequest,
    index: &EvidenceIndex,
) -> FrameEvidence {
    let trace = &bundle.trace[frame];
    let selection = request
        .selection
        .as_ref()
        .map(|s| index.inspect(bundle, frame, s, None));
    let (inspection, selection_unavailable) = match selection {
        Some(Ok(i)) => (Some(i), None),
        Some(Err(e)) => (None, Some(e.to_string())),
        None => (None, None),
    };
    FrameEvidence {
        bundle_sha256: bundle.bundle_hash.clone(),
        scenario_sha256: bundle.scenario_hash.clone(),
        recorded_step: trace.step,
        recorded_time_s: trace.time_s,
        held_for_s: request.time_s - trace.time_s,
        surface: bundle.scenario.scenario.surfaces[surface].id.clone(),
        state_counts: render::state_counts(trace),
        population: trace.agents.len(),
        full_run_completed: bundle.metrics.evacuated_agents,
        full_run_people: bundle.metrics.total_agents,
        full_run_duration_s: bundle.scenario.scenario.duration_s,
        full_run_clearance_s: bundle.metrics.clearance_time_s,
        inspection,
        selection_unavailable,
    }
}
pub fn prepare(
    a: &RunBundle,
    b: &RunBundle,
    request: &ComparisonRequest,
    a_index: &EvidenceIndex,
    b_index: &EvidenceIndex,
) -> Result<ComparisonEvidence> {
    request.camera.validate()?;
    let (start, end) = interval(a, b)?;
    ensure!(
        request.time_s >= start && request.time_s <= end,
        "comparison time must lie in the common recorded interval"
    );
    let (a_surface, b_surface) = surfaces(a, b, request.surface.as_deref())?;
    let a_frame = index_at_time(&a.trace, request.time_s)?;
    let b_frame = index_at_time(&b.trace, request.time_s)?;
    let bounds = Camera::bounds(
        &a.scenario.scenario,
        &a.scenario.scenario.surfaces[a_surface],
        request.view,
    )
    .union(Camera::bounds(
        &b.scenario.scenario,
        &b.scenario.scenario.surfaces[b_surface],
        request.view,
    ));
    let paired = same_population(a, b);
    let baseline = frame_evidence(a, a_frame, a_surface, request, a_index);
    let candidate = frame_evidence(b, b_frame, b_surface, request, b_index);
    let elapsed = |f: &FrameEvidence| {
        f.inspection
            .as_ref()?
            .person
            .as_ref()?
            .whole_run
            .as_ref()?
            .completed_elapsed_journey_s
    };
    let paired_elapsed = if paired {
        elapsed(&baseline)
            .zip(elapsed(&candidate))
            .map(|(a, b)| b - a)
    } else {
        None
    };
    Ok(ComparisonEvidence {
        comparison_version: "1",
        requested_time_s: request.time_s,
        common_start_s: start,
        common_end_s: end,
        same_population_inputs: paired,
        state_count_order: [
            "moving",
            "waiting",
            "in_transit",
            "not_released",
            "completed",
        ],
        common_camera_bounds: bounds,
        completed_at_shared_time_difference: i64::try_from(candidate.state_counts[4])?
            - i64::try_from(baseline.state_counts[4])?,
        full_run_completed_difference: i64::from(candidate.full_run_completed)
            - i64::from(baseline.full_run_completed),
        full_run_clearance_difference_s: baseline
            .full_run_clearance_s
            .zip(candidate.full_run_clearance_s)
            .map(|(a, b)| b - a),
        paired_completed_elapsed_difference_s: paired_elapsed,
        baseline,
        candidate,
        claim_boundary: "Panels share one authored-coordinate camera and simulation clock. Each shows the last recorded state at or before that clock, with its actual timestamp and hold duration. State counts describe those recorded frames. Full-run outcomes describe each run's own duration; unavailable clearance and unfinished elapsed journeys remain null. Changed population inputs disable individual pairing. Differences are model observations, not calibrated causal effects.",
    })
}
#[derive(Debug, Serialize)]
pub struct ComparisonManifest {
    pub schema_version: &'static str,
    pub renderer: render::Provenance,
    pub image_sha256: String,
    pub render_width_pixels: usize,
    pub render_height_pixels: usize,
    pub request: ComparisonRequest,
    pub comparison: ComparisonEvidence,
}
pub fn render_comparison(
    a: &RunBundle,
    b: &RunBundle,
    request: &ComparisonRequest,
) -> Result<(Vec<u8>, ComparisonManifest)> {
    require_reconstructed(a)?;
    require_reconstructed(b)?;
    let ai = EvidenceIndex::new(a);
    let bi = EvidenceIndex::new(b);
    let comparison = prepare(a, b, request, &ai, &bi)?;
    let mut renderer = render::Renderer::new();
    let mut buffer = vec![0; WIDTH * HEIGHT];
    renderer.render_comparison(&mut buffer, a, b, request, &comparison);
    let png = encode_png(&buffer, WIDTH, HEIGHT)?;
    let manifest = ComparisonManifest {
        schema_version: "chiyoda.replay-comparison.v1",
        renderer: render::provenance(),
        image_sha256: sha256(&png),
        render_width_pixels: WIDTH,
        render_height_pixels: HEIGHT,
        request: request.clone(),
        comparison,
    };
    Ok((png, manifest))
}
pub fn export_comparison(
    a: &RunBundle,
    b: &RunBundle,
    request: &ComparisonRequest,
    output: &Path,
) -> Result<ComparisonManifest> {
    crate::export::ensure_new_export_targets(output, &crate::export::gif_manifest_path(output))?;
    let (png, manifest) = render_comparison(a, b, request)?;
    save_pair(output, &png, &manifest)?;
    Ok(manifest)
}
