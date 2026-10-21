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
pub const RENDERER_VERSION: &str = "chiyoda.replay-renderer.v3";

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
    #[allow(clippy::too_many_lines)] // one ordered presentation pass keeps map and chrome boundaries explicit
    pub fn render(&mut self, buffer: &mut [u32], bundle: Option<&RunBundle>, options: Options<'_>) {
        assert_eq!(
            buffer.len(),
            WIDTH * HEIGHT,
            "replay framebuffer dimensions"
        );
        let c = &mut self.canvas;
        c.clear(BACKGROUND);
        c.rect([0.0, 0.0, 1200.0, 80.0], PAPER);
        c.line((0.0, 79.5), (1200.0, 79.5), BORDER, 1.0);
        c.rect([916.0, 80.0, 284.0, 720.0], PAPER);
        c.line((915.5, 80.0), (915.5, 800.0), BORDER, 1.0);
        // A small plan glyph, drawn in the same stroke family as resource symbols.
        c.polygon(
            &[(32.0, 27.0), (52.0, 27.0), (52.0, 51.0), (32.0, 51.0)],
            None,
            Some((MOVING, 1.5)),
        );
        c.line((39.0, 27.0), (39.0, 43.0), MOVING, 1.5);
        c.line((39.0, 43.0), (52.0, 43.0), MOVING, 1.5);
        c.text((66.0, 38.0), "Chiyoda", 20, INK, 160.0);
        c.text(
            (66.0, 58.0),
            "PEDESTRIAN SIMULATION / REPLAY",
            10,
            MUTED,
            400.0,
        );
        c.text(
            (940.0, 36.0),
            options.source.label(),
            13,
            if options.source == Source::LegacyHashOnly {
                WAITING
            } else {
                MUTED
            },
            240.0,
        );
        c.text(
            (940.0, 57.0),
            "Recorded state · deterministic model",
            11,
            MUTED,
            240.0,
        );
        if let Some(bundle) = bundle {
            let scenario = &bundle.scenario.scenario;
            let surface = &scenario.surfaces[options.surface];
            let frame = &bundle.trace[options.index];
            c.text((320.0, 46.0), &scenario.name, 16, INK, 560.0);
            let title = if options.view == ViewMode::Surface {
                surface.id.as_str()
            } else {
                "All surfaces"
            };
            c.text((32.0, 117.0), title, 20, INK, 650.0);
            let subtitle = if options.view == ViewMode::Surface {
                format!(
                    "Floor plan   /   {:.1} × {:.1} m   /   elevation {:.1} m",
                    surface.width_m, surface.depth_m, surface.origin.z_m
                )
            } else {
                format!(
                    "Isometric overview   /   {} authored surfaces",
                    scenario.surfaces.len()
                )
            };
            c.text((32.0, 141.0), &subtitle, 12, MUTED, 790.0);
            scene::draw(
                c,
                scenario,
                frame,
                surface,
                options.view,
                options.map,
                options.inspection,
            );
            if options.details
                && let Some(inspection) = options.inspection
            {
                inspector(c, bundle, options, inspection);
            } else {
                sidebar(c, bundle, options);
            }
            timeline(c, bundle, options);
        } else {
            c.text(
                (90.0, 340.0),
                "Waiting for a valid scenario",
                26,
                INK,
                720.0,
            );
            c.text(
                (90.0, 373.0),
                "Save the watched JSON file to load its geometry and trace.",
                14,
                MUTED,
                720.0,
            );
            c.text((940.0, 130.0), "No trace loaded", 16, INK, 230.0);
            c.text(
                (940.0, 159.0),
                "Validation runs in the background.",
                12,
                MUTED,
                235.0,
            );
        }
        if let Some(status) = options.watch_status {
            let error = super::watch_status_is_error(status);
            c.rect(
                [0.0, 676.0, 915.0, 20.0],
                if error { 0x00fc_eced } else { BACKGROUND },
            );
            c.text(
                (32.0, 690.0),
                status,
                11,
                if error { ERROR } else { MUTED },
                850.0,
            );
            if error {
                c.rect([936.0, 606.0, 248.0, 80.0], 0x00fc_eced);
                c.text(
                    (948.0, 628.0),
                    if bundle.is_some() {
                        "Showing last valid revision"
                    } else {
                        "Source revision failed"
                    },
                    12,
                    ERROR,
                    224.0,
                );
                wrapped(c, (948.0, 649.0), status, 224.0, 2, ERROR);
            }
        }
        c.copy_to(buffer);
    }
}
#[allow(clippy::too_many_lines)] // fixed sidebar sections share one vertical layout
fn sidebar(c: &mut Canvas, bundle: &RunBundle, options: Options<'_>) {
    let frame = &bundle.trace[options.index];
    let scenario = &bundle.scenario.scenario;
    let state = match options.playback {
        Playback::Export => "RECORDED TIME",
        Playback::Paused => "PAUSED",
        Playback::Playing if options.index + 1 == bundle.trace.len() => "END OF TRACE",
        Playback::Playing => "PLAYING",
    };
    c.text((940.0, 112.0), state, 10, MUTED, 230.0);
    c.text(
        (940.0, 157.0),
        &format!("{:.2}", frame.time_s),
        36,
        INK,
        220.0,
    );
    c.text((1148.0, 156.0), "s", 16, MUTED, 30.0);
    c.text(
        (940.0, 185.0),
        &format!(
            "Frame {} / {} · step {}",
            options.index + 1,
            bundle.trace.len(),
            frame.step
        ),
        12,
        MUTED,
        236.0,
    );
    c.line((940.0, 209.5), (1176.0, 209.5), BORDER, 1.0);
    let states = state_counts(frame);
    c.text(
        (940.0, 240.0),
        &format!(
            "{} {} · all surfaces",
            frame.agents.len(),
            if frame.agents.len() == 1 {
                "person"
            } else {
                "people"
            }
        ),
        18,
        INK,
        236.0,
    );
    let on_floor = frame
        .agents
        .iter()
        .filter(|a| a.surface == scenario.surfaces[options.surface].id)
        .count();
    c.text(
        (940.0, 264.0),
        &format!(
            "{}: {on_floor} recorded",
            scenario.surfaces[options.surface].id
        ),
        12,
        MUTED,
        236.0,
    );
    for (i, (label, count, color, hollow)) in [
        ("Moving", states[0], MOVING, false),
        ("Waiting", states[1], WAITING, false),
        ("In transit", states[2], CONNECTOR, false),
        ("Not released", states[3], INACTIVE, true),
        ("Completed", states[4], EXIT, true),
    ]
    .into_iter()
    .enumerate()
    {
        #[allow(clippy::cast_precision_loss)]
        let y = 296.0 + i as f32 * 27.0;
        c.circle(
            (946.0, y - 4.0),
            4.0,
            if hollow { None } else { Some(color) },
            Some((color, 1.0)),
        );
        if label == "Waiting" {
            c.line((944.0, y - 4.0), (948.0, y - 4.0), PAPER, 1.0);
        }
        if label == "In transit" {
            c.circle((946.0, y - 4.0), 1.0, Some(PAPER), None);
        }
        c.text((960.0, y), label, 13, INK, 160.0);
        let count = count.to_string();
        c.text(
            (1176.0 - c.text_width(&count, 13), y),
            &count,
            13,
            INK,
            50.0,
        );
    }
    c.line((940.0, 426.5), (1176.0, 426.5), BORDER, 1.0);
    c.text((940.0, 452.0), "PLAN KEY", 10, MUTED, 230.0);
    for (i, (glyph, label, color)) in [
        ("→", "Exit", EXIT),
        ("=", "Gate", WAITING),
        ("S R", "Stair · ramp", CONNECTOR),
        ("E L", "Escalator · lift", CONNECTOR),
        ("◇", "Waypoint", WAYPOINT),
        ("○ · ○", "Authored queue slots", QUEUE),
    ]
    .into_iter()
    .enumerate()
    {
        #[allow(clippy::cast_precision_loss)]
        let y = 477.0 + i as f32 * 23.0;
        c.text((940.0, y), glyph, 11, color, 50.0);
        c.text((999.0, y), label, 12, MUTED, 179.0);
    }
    if options.details
        && !options
            .watch_status
            .is_some_and(super::watch_status_is_error)
    {
        c.text((940.0, 618.0), "SOURCE BUNDLE", 10, MUTED, 230.0);
        if options.source == Source::LivePreview {
            c.text((940.0, 641.0), "Unpersisted watch run", 12, MUTED, 230.0);
        } else {
            c.text((940.0, 641.0), &bundle.bundle_hash, 12, INK, 230.0);
        }
        c.text(
            (940.0, 663.0),
            &format!("Runtime {}", bundle.runtime_version),
            11,
            MUTED,
            230.0,
        );
    }
    c.line((940.0, 696.5), (1176.0, 696.5), BORDER, 1.0);
    wrapped(
        c,
        (940.0, 721.0),
        "Model output. Symbols and minimum-size people markers are schematic.",
        232.0,
        3,
        MUTED,
    );
    c.text(
        (940.0, 784.0),
        "Chiyoda / deterministic replay",
        10,
        MUTED,
        236.0,
    );
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
fn timeline(c: &mut Canvas, bundle: &RunBundle, options: Options<'_>) {
    c.line((0.0, 696.5), (915.0, 696.5), BORDER, 1.0);
    let start = bundle.trace[0].time_s;
    let end = bundle.trace.last().expect("non-empty trace").time_s;
    let now = bundle.trace[options.index].time_s;
    #[allow(clippy::cast_possible_truncation)]
    let progress = if end > start {
        ((now - start) / (end - start)).clamp(0.0, 1.0) as f32
    } else {
        0.0
    };
    c.text((32.0, 721.0), &format!("{start:.1} s"), 11, MUTED, 120.0);
    let end_label = format!("{end:.1} s");
    c.text(
        (884.0 - c.text_width(&end_label, 11), 721.0),
        &end_label,
        11,
        MUTED,
        160.0,
    );
    c.line((32.0, 737.0), (884.0, 737.0), BORDER, 3.0);
    c.line((32.0, 737.0), (32.0 + 852.0 * progress, 737.0), MOVING, 3.0);
    if let Some(inspection) = options.inspection.filter(|_| options.details) {
        #[allow(clippy::cast_possible_truncation)]
        let x_at = |time: f64| {
            32.0 + 852.0 * ((time - start) / (end - start).max(f64::EPSILON)).clamp(0.0, 1.0) as f32
        };
        if let Some(totals) = inspection
            .person
            .as_ref()
            .and_then(|p| p.whole_run.as_ref())
        {
            for span in &totals.low_progress {
                c.line(
                    (x_at(span.start_time_s), 748.0),
                    (x_at(span.end_time_s), 748.0),
                    WAITING,
                    4.0,
                );
            }
        }
        for event in &inspection.events {
            let x = x_at(event.event.time_s);
            c.line((x, 729.0), (x, 734.0), MUTED, 1.0);
        }
        c.text(
            (210.0, 721.0),
            if inspection
                .person
                .as_ref()
                .is_some_and(|p| p.whole_run.is_some())
            {
                "Selected person · amber: whole-run low progress · ticks: retained events"
            } else if inspection.person.is_some() {
                "Selected person · detailed accounting unavailable · ticks: retained events"
            } else {
                "Selected resource · ticks: retained events through frame"
            },
            10,
            MUTED,
            570.0,
        );
    }
    c.circle(
        (32.0 + 852.0 * progress, 737.0),
        4.5,
        Some(MOVING),
        Some((PAPER, 1.5)),
    );
    let footer = if options.playback == Playback::Export {
        format!(
            "Recorded trace  /  {} frames  /  {:.2}× playback  /  exact recorded frames",
            bundle.trace.len(),
            options.speed
        )
    } else {
        format!(
            "Click inspect · wheel zoom · right-drag pan · timeline seek · Space play · [ ] speed ({:.2}×) · R fit",
            options.speed
        )
    };
    c.text((32.0, 777.0), &footer, 12, MUTED, 852.0);
}
fn wrapped(c: &mut Canvas, at: (f32, f32), text: &str, width: f32, max_lines: usize, color: u32) {
    let mut lines = vec![String::new()];
    for word in text.split_whitespace() {
        let last = lines.last_mut().unwrap();
        let next = if last.is_empty() {
            word.to_owned()
        } else {
            format!("{last} {word}")
        };
        if c.text_width(&next, 12) > width && !last.is_empty() {
            lines.push(word.to_owned());
        } else {
            *last = next;
        }
    }
    for (i, line) in lines.iter().take(max_lines).enumerate() {
        let line = if i + 1 == max_lines && lines.len() > max_lines {
            format!("{line} …")
        } else {
            line.clone()
        };
        #[allow(clippy::cast_precision_loss)]
        let y = at.1 + i as f32 * 18.0;
        c.text((at.0, y), &line, 12, color, width);
    }
}

#[allow(clippy::too_many_lines)] // fixed inspector sections clearly separate frame state from whole-run evidence
fn inspector(
    c: &mut Canvas,
    bundle: &RunBundle,
    options: Options<'_>,
    inspection: &crate::inspection::Inspection,
) {
    sidebar(c, bundle, options);
    c.rect([936.0, 220.0, 248.0, 471.0], PAPER);
    let label = if inspection.person.is_some() {
        "PERSON"
    } else {
        "RESOURCE"
    };
    c.text((940.0, 240.0), label, 10, MUTED, 236.0);
    c.text((940.0, 265.0), inspection.selection.id(), 17, INK, 236.0);
    if let Some(person) = &inspection.person {
        c.text(
            (940.0, 289.0),
            &format!("{:?}", person.snapshot.state),
            13,
            MOVING,
            236.0,
        );
        c.text(
            (940.0, 311.0),
            &format!("On {}", person.snapshot.surface),
            12,
            MUTED,
            236.0,
        );
        c.text(
            (940.0, 333.0),
            &format!("Authored goal: {}", person.authored_destination),
            12,
            INK,
            236.0,
        );
        c.line((940.0, 350.0), (1176.0, 350.0), BORDER, 1.0);
        c.text((940.0, 373.0), "WHOLE-RUN EVIDENCE", 10, MUTED, 236.0);
        if let Some(totals) = &person.whole_run {
            for (i, text) in [
                format!("Walked {:.2} m", totals.walking_distance_m),
                format!("Moving {:.2} s", totals.time.moving_s),
                format!("Service waits {:.2} s", totals.time.service_wait_s()),
                format!("Authored pauses {:.2} s", totals.time.waypoint_pause_s),
                format!("Low progress: {} spans", totals.low_progress_total),
            ]
            .iter()
            .enumerate()
            {
                #[allow(clippy::cast_precision_loss)]
                let y = 397.0 + i as f32 * 21.0;
                c.text((940.0, y), text, 12, INK, 236.0);
            }
            if let Some(span) = totals.low_progress.last() {
                c.text(
                    (940.0, 500.0),
                    &format!(
                        "Retained span {:.2}–{:.2} s",
                        span.start_time_s, span.end_time_s
                    ),
                    11,
                    MUTED,
                    236.0,
                );
            }
        } else if let crate::inspection::Availability::Unavailable { reason } =
            &inspection.diagnostics
        {
            wrapped(c, (940.0, 397.0), reason, 236.0, 5, MUTED);
        }
    }
    if let Some(resource) = &inspection.resource {
        c.text(
            (940.0, 290.0),
            "Queue-entry history through frame",
            12,
            MUTED,
            236.0,
        );
        c.text(
            (940.0, 315.0),
            &format!(
                "{} {} · {} {}",
                resource.queue_entries_through_frame,
                if resource.queue_entries_through_frame == 1 {
                    "entry"
                } else {
                    "entries"
                },
                resource.entrants_total,
                if resource.entrants_total == 1 {
                    "person"
                } else {
                    "people"
                },
            ),
            14,
            INK,
            236.0,
        );
        c.line((940.0, 350.0), (1176.0, 350.0), BORDER, 1.0);
        c.text((940.0, 373.0), "WHOLE-RUN QUEUE AUDIT", 10, MUTED, 236.0);
        if let Some(audit) = &resource.whole_run_queue_audit {
            c.text(
                (940.0, 399.0),
                &format!(
                    "Peak waiting: {} {}",
                    audit.peak_waiting_agents,
                    if audit.peak_waiting_agents == 1 {
                        "person"
                    } else {
                        "people"
                    }
                ),
                13,
                INK,
                236.0,
            );
            c.text(
                (940.0, 423.0),
                &format!("Wait: {:.2} agent-s", audit.cumulative_wait_agent_seconds),
                13,
                INK,
                236.0,
            );
            c.text(
                (940.0, 447.0),
                &format!("Ever queued: {}", audit.ever_queued_agents),
                12,
                INK,
                236.0,
            );
        } else {
            wrapped(
                c,
                (940.0, 399.0),
                "No per-resource queue audit is recorded for this resource.",
                236.0,
                3,
                MUTED,
            );
        }
        c.text(
            (940.0, 478.0),
            &if resource.entrants_total > resource.entrants_through_frame.len() {
                format!(
                    "Rings: {} / {} historical entrants",
                    resource.entrants_through_frame.len(),
                    resource.entrants_total
                )
            } else {
                "Rings mark historical entrants.".to_owned()
            },
            11,
            WAITING,
            236.0,
        );
    }
    c.line((940.0, 514.0), (1176.0, 514.0), BORDER, 1.0);
    c.text(
        (940.0, 536.0),
        "EVENTS THROUGH THIS FRAME",
        10,
        MUTED,
        236.0,
    );
    let mut displayed: Vec<&chiyoda_core::bundle::RunEvent> = vec![];
    if let Some(route) = inspection
        .person
        .as_ref()
        .and_then(|p| p.latest_route_event.as_ref())
    {
        displayed.push(route);
    }
    if let Some(change) = inspection
        .resource
        .as_ref()
        .and_then(|r| r.latest_availability_event.as_ref())
    {
        displayed.push(change);
    }

    for event in &inspection.events {
        if !displayed.contains(&&event.event) {
            displayed.push(&event.event);
        }
        if displayed.len() >= 2 {
            break;
        }
    }
    let event_limit = if options
        .watch_status
        .is_some_and(crate::watch_status_is_error)
    {
        1
    } else {
        2
    };
    for (i, event) in displayed.into_iter().take(event_limit).enumerate() {
        #[allow(clippy::cast_precision_loss)]
        let y = 562.0 + i as f32 * 47.0;
        c.text(
            (940.0, y),
            &format!("{:.2} s · {}", event.time_s, event.kind),
            11,
            INK,
            236.0,
        );
        c.text((940.0, y + 18.0), &event.detail, 11, MUTED, 236.0);
    }
    if inspection.events.is_empty() {
        c.text(
            (940.0, 562.0),
            "No recorded events through frame.",
            12,
            MUTED,
            236.0,
        );
    }
    c.text(
        (940.0, 660.0),
        "Click empty space to clear.",
        11,
        MUTED,
        236.0,
    );
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
        for y in 162..634 {
            assert_eq!(
                &valid[y * WIDTH + 32..y * WIDTH + 884],
                &failed[y * WIDTH + 32..y * WIDTH + 884]
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
            if !(32..884).contains(&x) || !(162..634).contains(&y) {
                assert_eq!(*color, BACKGROUND);
            }
        }
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
