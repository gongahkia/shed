//! Shared native/headless presentation. Rendering never changes a scenario or trace.
mod canvas;
mod chrome;
mod compare;
mod inspector;
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
const GRID: u32 = 0x00e9_edf1;
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
const ERROR: u32 = 0x00b4_3435;
pub const RENDERER_VERSION: &str = "chiyoda.replay-renderer.v4";

/// Shared seek target for single-run and comparison playback.
pub const TIMELINE_BOUNDS: [f32; 4] = [32.0, 724.0, 1136.0, 28.0];

#[must_use]
pub fn single_viewport(inspector_visible: bool) -> [f32; 4] {
    if inspector_visible {
        [32.0, 82.0, 852.0, 470.0]
    } else {
        VIEWPORT
    }
}

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
        GRID,
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
        ERROR,
        0x00fc_eced,
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
        ERROR,
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Source {
    Reconstructed,
    LegacyHashOnly,
    LivePreview,
}
impl Source {
    fn label(self) -> &'static str {
        match self {
            Self::Reconstructed => "Reconstructed bundle",
            Self::LegacyHashOnly => "Legacy · hash only",
            Self::LivePreview => "Live preview · unpersisted",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Playback {
    Playing,
    Paused,
    Export,
}
#[derive(Clone, Copy)]
pub struct Options<'a> {
    pub index: usize,
    pub surface: usize,
    pub view: ViewMode,
    pub source: Source,
    pub playback: Playback,
    pub speed: f64,
    pub details: bool,
    pub watch_status: Option<&'a str>,
    pub map: MapConfig,
    pub inspection: Option<&'a crate::inspection::Inspection>,
}
impl Default for Options<'_> {
    fn default() -> Self {
        Self {
            index: 0,
            surface: 0,
            view: ViewMode::Surface,
            source: Source::Reconstructed,
            playback: Playback::Export,
            speed: 1.0,
            details: true,
            watch_status: None,
            map: MapConfig::default(),
            inspection: None,
        }
    }
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

    #[allow(clippy::too_many_lines)] // one presentation pass retains map, inspection and watch status ordering
    pub fn render(&mut self, buffer: &mut [u32], bundle: Option<&RunBundle>, options: Options<'_>) {
        assert_eq!(
            buffer.len(),
            WIDTH * HEIGHT,
            "replay framebuffer dimensions"
        );
        let c = &mut self.canvas;
        c.clear(BACKGROUND);
        if let Some(bundle) = bundle {
            let scenario = &bundle.scenario.scenario;
            let surface = &scenario.surfaces[options.surface];
            let frame = &bundle.trace[options.index];
            let inspection = options.inspection.filter(|_| options.details);
            let viewport = single_viewport(inspection.is_some());
            let location = if options.view == ViewMode::Surface {
                format!(
                    "{} · {:.1} × {:.1} m · elevation {:.1} m",
                    surface.id, surface.width_m, surface.depth_m, surface.origin.z_m
                )
            } else {
                format!("Overview · {} surfaces", scenario.surfaces.len())
            };
            let source = if options.source == Source::Reconstructed {
                String::new()
            } else {
                format!("{} · ", options.source.label())
            };
            chrome::heading(
                c,
                32.0,
                viewport[2],
                &scenario.name,
                &format!(
                    "{source}{location} · recorded {:.2} s · step {}",
                    frame.time_s, frame.step
                ),
            );
            scene::draw(
                c,
                scenario,
                frame,
                surface,
                options.view,
                MapConfig {
                    viewport,
                    ..options.map
                },
                options.inspection,
            );
            chrome::outcomes(
                c,
                32.0,
                viewport[2],
                &chrome::Outcomes {
                    states: state_counts(frame),
                    population: frame.agents.len(),
                    completed: bundle.metrics.evacuated_agents,
                    people: bundle.metrics.total_agents,
                    duration_s: scenario.duration_s,
                    clearance_s: bundle.metrics.clearance_time_s,
                },
            );
            if let Some(inspection) = inspection {
                inspector::draw(c, options, inspection);
            }
            timeline(c, bundle, options);
        } else {
            c.text(
                (90.0, 340.0),
                "Waiting for a valid scenario",
                26,
                INK,
                1000.0,
            );
            c.text(
                (90.0, 373.0),
                "Save the watched JSON file to load its geometry and trace.",
                14,
                MUTED,
                1000.0,
            );
        }
        if let Some(status) = options.watch_status {
            let error = super::watch_status_is_error(status);
            c.rect(
                [0.0, 676.0, 1200.0, 20.0],
                if error { 0x00fc_eced } else { BACKGROUND },
            );
            let status = if error && bundle.is_some() {
                format!("Showing last valid revision · {status}")
            } else {
                status.to_owned()
            };
            c.text(
                (32.0, 690.0),
                &status,
                11,
                if error { ERROR } else { MUTED },
                1136.0,
            );
        }
        c.copy_to(buffer);
    }
}

fn timeline(c: &mut Canvas, bundle: &RunBundle, options: Options<'_>) {
    let start = bundle.trace[0].time_s;
    let end = bundle.trace.last().expect("non-empty trace").time_s;
    let now = bundle.trace[options.index].time_s;
    chrome::timeline(c, start, end, now, "Time");
    if let Some(inspection) = options.inspection.filter(|_| options.details) {
        if let Some(totals) = inspection
            .person
            .as_ref()
            .and_then(|p| p.whole_run.as_ref())
        {
            for span in &totals.low_progress {
                c.line(
                    (chrome::time_x(start, end, span.start_time_s), 757.0),
                    (chrome::time_x(start, end, span.end_time_s), 757.0),
                    WAITING,
                    4.0,
                );
            }
        }
        for event in &inspection.events {
            let x = chrome::time_x(start, end, event.event.time_s);
            c.line((x, 729.0), (x, 734.0), MUTED, 1.0);
        }
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
    fn watch_failure_preserves_the_last_valid_plan_and_source_verification_stays_explicit() {
        let bundle = fixture();
        let mut renderer = Renderer::new();
        let mut valid = vec![0; WIDTH * HEIGHT];
        let mut failed = valid.clone();
        let options = Options {
            source: Source::LivePreview,
            playback: Playback::Paused,
            ..Options::default()
        };
        renderer.render(&mut valid, Some(&bundle), options);
        renderer.render(
            &mut failed,
            Some(&bundle),
            Options {
                watch_status: Some("revision 2 failed: invalid scenario"),
                ..options
            },
        );
        for y in 82..552 {
            assert_eq!(
                &valid[y * WIDTH + 32..y * WIDTH + 1168],
                &failed[y * WIDTH + 32..y * WIDTH + 1168]
            );
        }
        assert_ne!(valid, failed);
        capture("watch-valid.png", &valid);
        capture("watch-error.png", &failed);
        let live_header = valid[..80 * WIDTH].to_vec();
        renderer.render(
            &mut valid,
            Some(&bundle),
            Options {
                source: Source::LegacyHashOnly,
                ..options
            },
        );
        capture("legacy.png", &valid);
        let legacy_header = valid[..80 * WIDTH].to_vec();
        renderer.render(&mut valid, Some(&bundle), Options::default());
        assert_ne!(live_header, legacy_header);
        assert_ne!(legacy_header, valid[..80 * WIDTH]);
        renderer.render(
            &mut failed,
            None,
            Options {
                watch_status: Some("cannot read draft.json"),
                ..options
            },
        );
        assert!(failed.contains(&ERROR));
        capture("watch-empty.png", &failed);
    }

    #[test]
    fn map_drawing_is_clipped_away_from_chrome_including_text_and_large_markers() {
        let mut canvas = Canvas::new();
        canvas.clear(BACKGROUND);
        canvas.map_clip(true);
        canvas.rect([0.0, 0.0, 1200.0, 800.0], PAPER);
        canvas.circle((916.0, 300.0), 100.0, Some(MOVING), None);
        canvas.line((0.0, 0.0), (1200.0, 800.0), INK, 20.0);
        canvas.text((900.0, 400.0), "Out of bounds", 32, ERROR, 500.0);
        let mut buffer = vec![0; WIDTH * HEIGHT];
        canvas.copy_to(&mut buffer);
        assert!(buffer.contains(&PAPER));
        for (index, color) in buffer.iter().enumerate() {
            let x = index % WIDTH;
            let y = index / WIDTH;
            if !(32..1168).contains(&x) || !(82..552).contains(&y) {
                assert_eq!(*color, BACKGROUND);
            }
        }
    }

    #[test]
    fn inspector_is_contextual_and_clearing_restores_the_full_plan() {
        use crate::inspection::{EvidenceIndex, Selection};
        let bundle = fixture();
        let evidence = EvidenceIndex::new(&bundle);
        let inspection = evidence
            .inspect(
                &bundle,
                0,
                &Selection::Agent {
                    id: bundle.trace[0].agents[0].id.clone(),
                },
                None,
            )
            .unwrap();
        let mut renderer = Renderer::new();
        let mut plain = vec![0; WIDTH * HEIGHT];
        let mut inspected = plain.clone();
        renderer.render(&mut plain, Some(&bundle), Options::default());
        renderer.render(
            &mut inspected,
            Some(&bundle),
            Options {
                inspection: Some(&inspection),
                ..Options::default()
            },
        );
        assert_eq!(plain[100 * WIDTH + 1185], BACKGROUND);
        assert_eq!(inspected[100 * WIDTH + 1185], PAPER);
        capture("person-inspector.png", &inspected);
        renderer.render(
            &mut inspected,
            Some(&bundle),
            Options {
                inspection: Some(&inspection),
                details: false,
                ..Options::default()
            },
        );
        assert_eq!(inspected[100 * WIDTH + 1185], BACKGROUND);
        renderer.render(&mut inspected, Some(&bundle), Options::default());
        assert_eq!(plain, inspected);
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
        assert_eq!(
            &single[704 * WIDTH..754 * WIDTH],
            &paired[704 * WIDTH..754 * WIDTH]
        );
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
