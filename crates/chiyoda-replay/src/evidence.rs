//! Verified annotated images with the exact evidence used to draw them.
use crate::{
    HEIGHT, ViewMode, WIDTH,
    export::{
        encode_png, ensure_new_export_targets, gif_manifest_path, rendered_surface_ids,
        write_new_artifact,
    },
    inspection::{EvidenceIndex, Inspection, Selection, frame_index},
    navigation::CameraControl,
    render::{self, MapConfig, Options, Renderer},
};
use anyhow::{Result, ensure};
use chiyoda_core::{BundleVerification, RunBundle, verify_run_bundle};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct EvidenceRequest {
    pub step: u64,
    pub selection: Selection,
    #[serde(default)]
    pub surface: Option<String>,
    #[serde(default)]
    pub view: ViewMode,
    #[serde(default)]
    pub trail_start_step: Option<u64>,
    #[serde(default)]
    pub camera: CameraControl,
}
#[derive(Debug, Serialize)]
pub struct EvidenceManifest {
    pub schema_version: &'static str,
    pub renderer: render::Provenance,
    pub image_sha256: String,
    pub render_width_pixels: usize,
    pub render_height_pixels: usize,
    pub bundle_sha256: String,
    pub scenario_sha256: String,
    pub runtime_version: String,
    pub request: EvidenceRequest,
    pub selected_surface: String,
    pub rendered_surfaces: Vec<String>,
    pub inspection: Inspection,
}
#[must_use]
pub fn sha256(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
pub fn require_reconstructed(bundle: &RunBundle) -> Result<()> {
    ensure!(
        verify_run_bundle(bundle)? == BundleVerification::Reconstructed,
        "evidence export requires reconstruction by the installed runtime"
    );
    ensure!(!bundle.trace.is_empty(), "bundle has no recorded frames");
    Ok(())
}
pub fn selected_surface(
    bundle: &RunBundle,
    requested: Option<&str>,
    inspection: Option<&Inspection>,
) -> Result<usize> {
    let surfaces = &bundle.scenario.scenario.surfaces;
    let default = inspection.and_then(|i| {
        i.person
            .as_ref()
            .map(|p| p.snapshot.surface.as_str())
            .or_else(|| {
                i.resource
                    .as_ref()
                    .and_then(|r| r.endpoints.first().map(|(id, _)| id.as_str()))
            })
    });
    if let Some(id) = requested.or(default) {
        surfaces
            .iter()
            .position(|s| s.id == id)
            .ok_or_else(|| anyhow::anyhow!("unknown authored surface `{id}`"))
    } else {
        ensure!(!surfaces.is_empty(), "no authored surfaces");
        Ok(0)
    }
}
pub fn render_evidence(
    bundle: &RunBundle,
    request: &EvidenceRequest,
) -> Result<(Vec<u8>, EvidenceManifest)> {
    require_reconstructed(bundle)?;
    request.camera.validate()?;
    let index = frame_index(bundle, request.step)?;
    let evidence = EvidenceIndex::new(bundle);
    let inspection =
        evidence.inspect(bundle, index, &request.selection, request.trail_start_step)?;
    let surface = selected_surface(bundle, request.surface.as_deref(), Some(&inspection))?;
    let mut renderer = Renderer::new();
    let mut buffer = vec![0; WIDTH * HEIGHT];
    renderer.render(
        &mut buffer,
        Some(bundle),
        Options {
            index,
            surface,
            view: request.view,
            map: MapConfig {
                camera: request.camera,
                ..MapConfig::default()
            },
            inspection: Some(&inspection),
            ..Options::default()
        },
    );
    let png = encode_png(&buffer, WIDTH, HEIGHT)?;
    let manifest = EvidenceManifest {
        schema_version: "chiyoda.replay-evidence.v1",
        renderer: render::provenance(),
        image_sha256: sha256(&png),
        render_width_pixels: WIDTH,
        render_height_pixels: HEIGHT,
        bundle_sha256: bundle.bundle_hash.clone(),
        scenario_sha256: bundle.scenario_hash.clone(),
        runtime_version: bundle.runtime_version.clone(),
        request: request.clone(),
        selected_surface: bundle.scenario.scenario.surfaces[surface].id.clone(),
        rendered_surfaces: rendered_surface_ids(bundle, surface, request.view),
        inspection,
    };
    Ok((png, manifest))
}
pub fn save_pair(output: &Path, png: &[u8], manifest: &impl Serialize) -> Result<()> {
    let sidecar = gif_manifest_path(output);
    ensure_new_export_targets(output, &sidecar)?;
    let metadata = serde_json::to_vec_pretty(manifest)?;
    let image = write_new_artifact(output, png)?;
    let description = write_new_artifact(&sidecar, &metadata)?;
    description.keep();
    image.keep();
    Ok(())
}
pub fn export_evidence(
    bundle: &RunBundle,
    request: &EvidenceRequest,
    output: &Path,
) -> Result<EvidenceManifest> {
    // Check destinations before expensive reconstruction; exclusive writes are
    // still the ownership boundary if another caller creates a target meanwhile.
    ensure_new_export_targets(output, &gif_manifest_path(output))?;
    let (png, manifest) = render_evidence(bundle, request)?;
    save_pair(output, &png, &manifest)?;
    Ok(manifest)
}
