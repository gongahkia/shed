use super::{BACKGROUND, BORDER, INK, MUTED, MapConfig, Renderer, chrome, scene};
use crate::{
    HEIGHT, WIDTH,
    comparison::{ComparisonEvidence, ComparisonRequest},
    inspection::Inspection,
};
use chiyoda_core::RunBundle;

pub const VIEWPORTS: [[f32; 4]; 2] = [[32.0, 82.0, 548.0, 470.0], [620.0, 82.0, 548.0, 470.0]];

impl Renderer {
    #[allow(clippy::too_many_lines)] // both panels use one shared camera, timeline and measured comparison
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
        c.line((600.0, 16.0), (600.0, 688.0), BORDER, 1.0);
        for (bundle, frame, label, viewport) in [
            (baseline, &evidence.baseline, "BASELINE", VIEWPORTS[0]),
            (candidate, &evidence.candidate, "CANDIDATE", VIEWPORTS[1]),
        ] {
            let left = viewport[0];
            let run_label = if label == "BASELINE" {
                request.baseline_label.as_deref()
            } else {
                request.candidate_label.as_deref()
            }
            .unwrap_or(&bundle.scenario.scenario.name);
            let held = if frame.held_for_s > 0.000_000_1 {
                format!(" · held {:.2} s", frame.held_for_s)
            } else {
                String::new()
            };
            chrome::heading(
                c,
                left,
                548.0,
                &format!("{label} / {run_label}"),
                &format!(
                    "{} · recorded {:.2} s · step {}{held}",
                    frame.surface, frame.recorded_time_s, frame.recorded_step
                ),
            );
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
            chrome::outcomes(
                c,
                left,
                548.0,
                &chrome::Outcomes {
                    states: frame.state_counts,
                    population: frame.population,
                    completed: frame.full_run_completed,
                    people: frame.full_run_people,
                    duration_s: frame.full_run_duration_s,
                    clearance_s: frame.full_run_clearance_s,
                },
            );
            if let Some(inspection) = &frame.inspection {
                selection_line(c, (left, 660.0), inspection);
            }
            if let Some(reason) = &frame.selection_unavailable {
                c.text((left, 660.0), reason, 11, MUTED, 548.0);
            }
        }
        chrome::timeline(
            c,
            evidence.common_start_s,
            evidence.common_end_s,
            request.time_s,
            "Shared time",
        );
        c.copy_to(buffer);
    }
}
fn selection_line(c: &mut super::canvas::Canvas, at: (f32, f32), inspection: &Inspection) {
    let detail = if let Some(person) = &inspection.person {
        person.whole_run.as_ref().map_or_else(
            || "detailed accounting unavailable".to_owned(),
            |t| {
                format!(
                    "whole-run waits {:.2} s · low progress {} spans",
                    t.time.service_wait_s(),
                    t.low_progress_total
                )
            },
        )
    } else if let Some(resource) = &inspection.resource {
        format!(
            "{} queue entries through frame",
            resource.queue_entries_through_frame
        )
    } else {
        String::new()
    };
    c.text(
        at,
        &format!("{} · {detail}", inspection.selection.id()),
        11,
        INK,
        548.0,
    );
}
