use super::{BACKGROUND, BORDER, INK, MOVING, MUTED, MapConfig, PAPER, Renderer, scene};
use crate::{
    HEIGHT, WIDTH,
    comparison::{ComparisonEvidence, ComparisonRequest},
    inspection::Inspection,
};
use chiyoda_core::RunBundle;
impl Renderer {
    #[allow(clippy::too_many_arguments, clippy::too_many_lines)] // both panels use one shared camera, timeline and measured comparison
    pub fn render_comparison(
        &mut self,
        buffer: &mut [u32],
        baseline: &RunBundle,
        candidate: &RunBundle,
        request: &ComparisonRequest,
        evidence: &ComparisonEvidence,
        interactive: bool,
        speed: f64,
    ) {
        assert_eq!(buffer.len(), WIDTH * HEIGHT);
        let c = &mut self.canvas;
        c.clear(BACKGROUND);
        c.rect([0.0, 0.0, 1200.0, 80.0], PAPER);
        c.text((32.0, 38.0), "Chiyoda / compare", 20, INK, 350.0);
        c.text(
            (32.0, 59.0),
            "RECONSTRUCTED RUNS · SHARED CAMERA & CLOCK",
            10,
            MUTED,
            560.0,
        );
        c.text(
            (860.0, 39.0),
            &format!("Shared time {:.2} s", request.time_s),
            18,
            INK,
            310.0,
        );
        c.text(
            (860.0, 60.0),
            "Each panel retains its recorded timestamp",
            11,
            MUTED,
            310.0,
        );
        c.line((600.0, 96.0), (600.0, 688.0), BORDER, 1.0);
        for (bundle, frame, label, viewport) in [
            (
                baseline,
                &evidence.baseline,
                "BASELINE",
                [32.0, 162.0, 548.0, 390.0],
            ),
            (
                candidate,
                &evidence.candidate,
                "CANDIDATE",
                [620.0, 162.0, 548.0, 390.0],
            ),
        ] {
            let left = viewport[0];
            c.text(
                (left, 113.0),
                &format!("{label} / {}", bundle.scenario.scenario.name),
                16,
                INK,
                548.0,
            );
            let held = if frame.held_for_s > 0.000_000_1 {
                format!(" · held {:.2} s", frame.held_for_s)
            } else {
                String::new()
            };
            c.text(
                (left, 140.0),
                &format!(
                    "{} · recorded {:.2} s · step {}{held}",
                    frame.surface, frame.recorded_time_s, frame.recorded_step
                ),
                12,
                MUTED,
                548.0,
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
            c.text(
                (left, 609.0),
                &format!(
                    "Recorded frame: {} / {} completed · {} moving · {} waiting",
                    frame.state_counts[4],
                    frame.population,
                    frame.state_counts[0],
                    frame.state_counts[1]
                ),
                12,
                INK,
                548.0,
            );
            let clearance = frame
                .full_run_clearance_s
                .map_or_else(|| "unavailable".to_owned(), |v| format!("{v:.2} s"));
            c.text(
                (left, 633.0),
                &format!(
                    "Whole run ({:.1} s): {} / {} completed · clearance {clearance}",
                    frame.full_run_duration_s, frame.full_run_completed, frame.full_run_people
                ),
                11,
                MUTED,
                548.0,
            );
            if let Some(inspection) = &frame.inspection {
                selection_line(c, (left, 660.0), inspection);
            }
            if let Some(reason) = &frame.selection_unavailable {
                c.text((left, 660.0), reason, 11, MUTED, 548.0);
            }
        }
        let pairing = if evidence.same_population_inputs {
            "Same population inputs · individual pairing available"
        } else {
            "Population changed · individual pairing disabled"
        };
        c.text(
            (32.0, 689.0),
            &format!(
                "{pairing} · completed difference at shared time: {:+}",
                evidence.completed_at_shared_time_difference
            ),
            12,
            INK,
            1136.0,
        );
        c.line((0.0, 704.0), (1200.0, 704.0), BORDER, 1.0);
        let start = evidence.common_start_s;
        let end = evidence.common_end_s;
        #[allow(clippy::cast_possible_truncation)]
        let progress = if end > start {
            ((request.time_s - start) / (end - start)) as f32
        } else {
            0.0
        };
        c.text((32.0, 725.0), &format!("{start:.1} s"), 11, MUTED, 150.0);
        let end_label = format!("{end:.1} s");
        c.text(
            (1168.0 - c.text_width(&end_label, 11), 725.0),
            &end_label,
            11,
            MUTED,
            150.0,
        );
        c.line((32.0, 740.0), (1168.0, 740.0), BORDER, 3.0);
        c.line(
            (32.0, 740.0),
            (32.0 + 1136.0 * progress, 740.0),
            MOVING,
            3.0,
        );
        c.circle(
            (32.0 + 1136.0 * progress, 740.0),
            4.5,
            Some(MOVING),
            Some((PAPER, 1.0)),
        );
        let footer = if interactive {
            format!(
                "Space play / pause · arrows frame · click timeline · wheel zoom · right-drag pan · R fit · [ ] speed ({speed:.2}×)"
            )
        } else {
            "Verified comparison · counts are recorded states · full-run outcomes retain their own durations".to_owned()
        };
        c.text((32.0, 782.0), &footer, 12, MUTED, 1136.0);
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
