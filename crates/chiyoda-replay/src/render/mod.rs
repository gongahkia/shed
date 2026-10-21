//! Shared native/headless presentation. Rendering never changes a scenario or trace.
mod canvas;
mod chrome;
mod compare;
pub(crate) mod scene;
pub use compare::VIEWPORTS as COMPARISON_VIEWPORTS;
pub use scene::{Camera, MapConfig, VIEWPORT, WorldBounds, pick};

use super::{HEIGHT, ViewMode, WIDTH};
use canvas::Canvas;
use chiyoda_core::{AgentState, RunBundle, bundle::TraceFrame};
use serde::Serialize;
use sha2::{Digest, Sha256};

pub const BACKGROUND: u32 = 0x00f3_f5f7;
const PAPER: u32 = 0x00ff_ffff;
const INK: u32 = 0x0025_3342;
const MUTED: u32 = 0x005e_6d7c;
const BORDER: u32 = 0x00d8_dfe6;
const OBSTACLE: u32 = 0x00c5_cdd6;
const OBSTACLE_EDGE: u32 = 0x0092_9fad;
const MOVING: u32 = 0x0025_66b0;
const WAITING: u32 = 0x009b_6017;
const CONNECTOR: u32 = 0x0069_589b;
const INACTIVE: u32 = 0x0087_95a3;
const EXIT: u32 = 0x0023_7c68;
const WAYPOINT: u32 = 0x0074_6b8b;
const TRAIL: u32 = 0x00a2_bfe2;
const QUEUE: u32 = 0x009c_8cae;
pub const RENDERER_VERSION: &str = "chiyoda.replay-renderer.v5";

/// Shared seek target for every single-run and comparison projection.
pub const TIMELINE_BOUNDS: [f32; 4] = [24.0, 712.0, 1152.0, 64.0];

/// Exact semantic colours plus coverage ramps for antialiased edges. One palette
/// is used across the animation so rare status colours cannot drift per frame.
#[must_use]
pub fn gif_palette() -> Vec<u32> {
    let mut colors = vec![
        BACKGROUND,
        PAPER,
        INK,
        MUTED,
        BORDER,
        OBSTACLE,
        OBSTACLE_EDGE,
        MOVING,
        WAITING,
        CONNECTOR,
        INACTIVE,
        EXIT,
        WAYPOINT,
        QUEUE,
        TRAIL,
    ];
    for foreground in [
        INK,
        MUTED,
        OBSTACLE_EDGE,
        MOVING,
        WAITING,
        CONNECTOR,
        INACTIVE,
        EXIT,
        WAYPOINT,
        QUEUE,
        TRAIL,
    ] {
        let steps = if [INK, MUTED].contains(&foreground) {
            16
        } else {
            8
        };
        for background in [PAPER, BACKGROUND] {
            for coverage in 1..steps {
                let mut color = 0;
                for shift in [16, 8, 0] {
                    let a = (foreground >> shift) & 255;
                    let b = (background >> shift) & 255;
                    color |= ((a * coverage + b * (steps - coverage) + steps / 2) / steps) << shift;
                }
                if !colors.contains(&color) {
                    colors.push(color);
                }
            }
        }
    }
    assert!(colors.len() <= 256, "GIF palette fits its format");
    colors
}

#[derive(Debug, Serialize)]
pub struct Provenance {
    pub version: &'static str,
    pub font_sha256: String,
}
#[must_use]
pub fn provenance() -> Provenance {
    Provenance {
        version: RENDERER_VERSION,
        font_sha256: format!("{:x}", Sha256::digest(canvas::FONT_BYTES)),
    }
}

/// Scene inputs only: provenance, diagnostics and playback status stay in
/// structured evidence and terminal output, never in the visualization.
#[derive(Clone, Copy, Default)]
pub struct Options<'a> {
    pub index: usize,
    pub surface: usize,
    pub view: ViewMode,
    pub map: MapConfig,
    pub inspection: Option<&'a crate::inspection::Inspection>,
}

pub struct Renderer {
    canvas: Canvas,
}
impl Default for Renderer {
    fn default() -> Self {
        Self::new()
    }
}
impl Renderer {
    #[must_use]
    pub fn new() -> Self {
        Self {
            canvas: Canvas::new(),
        }
    }

    pub fn render(&mut self, buffer: &mut [u32], bundle: Option<&RunBundle>, options: Options<'_>) {
        assert_eq!(
            buffer.len(),
            WIDTH * HEIGHT,
            "replay framebuffer dimensions"
        );
        let c = &mut self.canvas;
        c.clear(BACKGROUND);
        let interval = bundle.map(|bundle| {
            let scenario = &bundle.scenario.scenario;
            let frame = &bundle.trace[options.index];
            scene::draw(
                c,
                scenario,
                frame,
                &scenario.surfaces[options.surface],
                options.view,
                MapConfig {
                    viewport: VIEWPORT,
                    ..options.map
                },
                options.inspection,
            );
            (
                bundle.trace[0].time_s,
                bundle.trace.last().expect("non-empty trace").time_s,
                frame.time_s,
            )
        });
        chrome::timeline(c, interval);
        c.copy_to(buffer);
    }
}

#[must_use]
pub fn state_counts(frame: &TraceFrame) -> [usize; 5] {
    let mut counts = [0; 5];
    for agent in &frame.agents {
        counts[match agent.state {
            AgentState::Moving => 0,
            AgentState::InTransit => 2,
            AgentState::WaitingToDepart => 3,
            AgentState::Evacuated => 4,
            _ => 1,
        }] += 1;
    }
    counts
}
#[cfg(test)]
mod tests {
    use super::*;
    fn capture(name: &str, buffer: &[u32]) {
        if let Some(directory) = std::env::var_os("CHIYODA_REPLAY_REVIEW_DIR") {
            let path = std::path::PathBuf::from(directory).join(name);
            crate::export::write_png(&path, buffer, WIDTH, HEIGHT)
                .expect("review capture writes to new path");
        }
    }
    fn fixture() -> RunBundle {
        crate::load_watch_scenario(include_str!("../fixtures/main/project.json"), 1).unwrap()
    }

    #[test]
    fn every_recorded_state_has_exactly_one_named_count() {
        let bundle = fixture();
        let mut frame = bundle.trace[0].clone();
        let agent = frame.agents[0].clone();
        frame.agents = [
            AgentState::Moving,
            AgentState::WaitingToDepart,
            AgentState::WaitingAtWaypoint,
            AgentState::WaitingForRoute,
            AgentState::WaitingForLift,
            AgentState::WaitingForConnector,
            AgentState::WaitingForGate,
            AgentState::WaitingForExit,
            AgentState::InTransit,
            AgentState::Evacuated,
        ]
        .into_iter()
        .map(|state| {
            let mut a = agent.clone();
            a.state = state;
            a
        })
        .collect();
        assert_eq!(state_counts(&frame), [1, 6, 1, 1, 1]);
    }

    #[test]
    fn empty_scene_has_only_an_unlabelled_progress_track() {
        let mut renderer = Renderer::new();
        let mut buffer = vec![0; WIDTH * HEIGHT];
        renderer.render(&mut buffer, None, Options::default());
        assert!(renderer.canvas.text_runs.is_empty());
        assert!(buffer[..712 * WIDTH].iter().all(|p| *p == BACKGROUND));
        assert!(buffer.contains(&BORDER));
        capture("empty.png", &buffer);
    }

    #[test]
    fn map_drawing_is_clipped_away_from_chrome_including_text_and_large_markers() {
        let mut canvas = Canvas::new();
        canvas.clear(BACKGROUND);
        canvas.map_clip(true);
        canvas.rect([0.0, 0.0, 1200.0, 800.0], PAPER);
        canvas.circle((916.0, 300.0), 100.0, Some(MOVING), None);
        canvas.line((0.0, 0.0), (1200.0, 800.0), INK, 20.0);
        canvas.text((900.0, 400.0), "Out of bounds", 32, MOVING, 500.0);
        let mut buffer = vec![0; WIDTH * HEIGHT];
        canvas.copy_to(&mut buffer);
        assert!(buffer.contains(&PAPER));
        for (index, color) in buffer.iter().enumerate() {
            let x = index % WIDTH;
            let y = index / WIDTH;
            if !(24..1176).contains(&x) || !(24..696).contains(&y) {
                assert_eq!(*color, BACKGROUND);
            }
        }
    }

    fn assert_only_simulation_and_timeline(renderer: &Renderer, buffer: &[u32], paired: bool) {
        assert_eq!(
            renderer.canvas.text_runs.len(),
            3,
            "only timeline timestamps"
        );
        for (at, text) in &renderer.canvas.text_runs {
            assert!(at.1 >= TIMELINE_BOUNDS[1], "unexpected scene label: {text}");
            assert!(
                text.chars()
                    .all(|c| c.is_ascii_digit() || ". s".contains(c)),
                "{text}"
            );
        }
        for (index, pixel) in buffer.iter().enumerate() {
            let x = index % WIDTH;
            let y = index / WIDTH;
            let scene = (24..1176).contains(&x)
                && (24..696).contains(&y)
                && (!paired || !(588..612).contains(&x));
            let timeline = (712..790).contains(&y);
            if !scene && !timeline {
                assert_eq!(*pixel, BACKGROUND, "chrome at ({x}, {y})");
            }
        }
    }

    #[test]
    fn every_projection_and_selection_keeps_only_the_scene_and_timeline() {
        use crate::{
            comparison,
            inspection::{EvidenceIndex, ResourceKind, Selection},
            navigation::CameraControl,
        };
        let bundle = fixture();
        let before = serde_json::to_vec(&bundle).unwrap();
        let evidence = EvidenceIndex::new(&bundle);
        let mut renderer = Renderer::new();
        let mut buffer = vec![0; WIDTH * HEIGHT];
        for (name, selection) in [
            ("plain", None),
            (
                "person",
                Some(Selection::Agent {
                    id: bundle.trace[0].agents[0].id.clone(),
                }),
            ),
            (
                "resource",
                Some(Selection::Resource {
                    resource_kind: ResourceKind::Exit,
                    id: "street".into(),
                }),
            ),
        ] {
            for view in [ViewMode::Surface, ViewMode::Overview] {
                let inspection = selection
                    .as_ref()
                    .map(|s| evidence.inspect(&bundle, 1, s, None).unwrap());
                renderer.render(
                    &mut buffer,
                    Some(&bundle),
                    Options {
                        index: 1,
                        view,
                        inspection: inspection.as_ref(),
                        ..Options::default()
                    },
                );
                assert_only_simulation_and_timeline(&renderer, &buffer, false);
                capture(&format!("{name}-{}.png", view.label()), &buffer);
                let request = comparison::ComparisonRequest {
                    time_s: bundle.trace[1].time_s,
                    surface: None,
                    view,
                    selection: selection.clone(),
                    baseline_label: Some("This label belongs only in JSON".into()),
                    candidate_label: Some("This label also belongs only in JSON".into()),
                    camera: CameraControl::default(),
                };
                let paired =
                    comparison::prepare(&bundle, &bundle, &request, &evidence, &evidence).unwrap();
                renderer.render_comparison(&mut buffer, &bundle, &bundle, &request, &paired);
                assert_only_simulation_and_timeline(&renderer, &buffer, true);
                capture(&format!("compare-{name}-{}.png", view.label()), &buffer);
            }
        }
        assert_eq!(serde_json::to_vec(&bundle).unwrap(), before);
    }

    #[test]
    fn single_and_comparison_share_the_same_timeline_coordinates() {
        use crate::{comparison, inspection::EvidenceIndex, navigation::CameraControl};
        let bundle = fixture();
        let index = EvidenceIndex::new(&bundle);
        let request = comparison::ComparisonRequest {
            time_s: bundle.trace[1].time_s,
            surface: None,
            view: ViewMode::Surface,
            selection: None,
            baseline_label: None,
            candidate_label: None,
            camera: CameraControl::default(),
        };
        let evidence = comparison::prepare(&bundle, &bundle, &request, &index, &index).unwrap();
        let mut renderer = Renderer::new();
        let mut single = vec![0; WIDTH * HEIGHT];
        let mut paired = single.clone();
        renderer.render(
            &mut single,
            Some(&bundle),
            Options {
                index: 1,
                ..Options::default()
            },
        );
        renderer.render_comparison(&mut paired, &bundle, &bundle, &request, &evidence);
        assert_eq!(&single[712 * WIDTH..], &paired[712 * WIDTH..]);
    }

    #[test]
    fn dense_long_labels_and_both_projections_render_without_mutating_trace_evidence() {
        let mut bundle = fixture();
        bundle.scenario.scenario.name = "Long scenario 名称 ".repeat(30);
        let before = serde_json::to_vec(&bundle).unwrap();
        let mut renderer = Renderer::new();
        let mut buffer = vec![0; WIDTH * HEIGHT];
        for view in [ViewMode::Surface, ViewMode::Overview] {
            for index in [0, bundle.trace.len() - 1] {
                renderer.render(
                    &mut buffer,
                    Some(&bundle),
                    Options {
                        index,
                        view,
                        ..Options::default()
                    },
                );
                assert!(buffer.contains(&PAPER));
            }
        }
        assert_eq!(serde_json::to_vec(&bundle).unwrap(), before);
    }
}
