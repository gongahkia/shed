use super::{BACKGROUND, MapConfig, Renderer, chrome, scene};
use crate::{
    HEIGHT, WIDTH,
    comparison::{ComparisonEvidence, ComparisonRequest},
};
use chiyoda_core::RunBundle;

/// Baseline on the left, candidate on the right, with no panel chrome.
pub const VIEWPORTS: [[f32; 4]; 2] = [[24.0, 24.0, 564.0, 672.0], [612.0, 24.0, 564.0, 672.0]];

impl Renderer {
    pub fn render_comparison(
        &mut self,
        buffer: &mut [u32],
        baseline: &RunBundle,
        candidate: &RunBundle,
        request: &ComparisonRequest,
        evidence: &ComparisonEvidence,
    ) {
        assert_eq!(buffer.len(), WIDTH * HEIGHT);
        let c = &mut self.canvas;
        c.clear(BACKGROUND);
        for (bundle, frame, viewport) in [
            (baseline, &evidence.baseline, VIEWPORTS[0]),
            (candidate, &evidence.candidate, VIEWPORTS[1]),
        ] {
            let surface = bundle
                .scenario
                .scenario
                .surfaces
                .iter()
                .find(|s| s.id == frame.surface)
                .expect("prepared surface");
            let index = bundle
                .trace
                .iter()
                .position(|f| f.step == frame.recorded_step)
                .expect("prepared frame");
            scene::draw(
                c,
                &bundle.scenario.scenario,
                &bundle.trace[index],
                surface,
                request.view,
                MapConfig {
                    viewport,
                    camera: request.camera,
                    bounds: Some(evidence.common_camera_bounds),
                },
                frame.inspection.as_ref(),
            );
        }
        chrome::timeline(
            c,
            Some((
                evidence.common_start_s,
                evidence.common_end_s,
                request.time_s,
            )),
        );
        c.copy_to(buffer);
    }
}
