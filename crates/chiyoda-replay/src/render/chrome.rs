//! Shared panel typography, outcome summaries and playhead presentation.
use super::{BORDER, INK, MOVING, MUTED, PAPER, canvas::Canvas};

pub(super) fn heading(c: &mut Canvas, left: f32, width: f32, title: &str, subtitle: &str) {
    c.text((left, 33.0), title, 16, INK, width);
    c.text((left, 60.0), subtitle, 12, MUTED, width);
}

pub(super) struct Outcomes {
    pub states: [usize; 5],
    pub population: usize,
    pub completed: u32,
    pub people: u32,
    pub duration_s: f64,
    pub clearance_s: Option<f64>,
}

pub(super) fn outcomes(c: &mut Canvas, left: f32, width: f32, values: &Outcomes) {
    c.text(
        (left, 609.0),
        &format!(
            "Recorded frame: {} / {} completed · {} moving · {} waiting",
            values.states[4], values.population, values.states[0], values.states[1]
        ),
        12,
        INK,
        width,
    );
    let clearance = values
        .clearance_s
        .map_or_else(|| "unavailable".to_owned(), |v| format!("{v:.2} s"));
    c.text(
        (left, 633.0),
        &format!(
            "Whole run ({:.1} s): {} / {} completed · clearance {clearance}",
            values.duration_s, values.completed, values.people
        ),
        11,
        MUTED,
        width,
    );
}

#[allow(clippy::cast_possible_truncation)] // fixed framebuffer coordinates
pub(super) fn time_x(start: f64, end: f64, now: f64) -> f32 {
    let progress = if end > start {
        ((now - start) / (end - start)).clamp(0.0, 1.0) as f32
    } else {
        0.0
    };
    32.0 + 1136.0 * progress
}

pub(super) fn timeline(c: &mut Canvas, start: f64, end: f64, now: f64, label: &str) {
    c.line((0.0, 704.0), (1200.0, 704.0), BORDER, 1.0);
    c.text((32.0, 725.0), &format!("{start:.1} s"), 11, MUTED, 150.0);
    let end_label = format!("{end:.1} s");
    c.text(
        (1168.0 - c.text_width(&end_label, 11), 725.0),
        &end_label,
        11,
        MUTED,
        150.0,
    );
    let playhead_x = time_x(start, end, now);
    c.line((32.0, 740.0), (1168.0, 740.0), BORDER, 3.0);
    c.line((32.0, 740.0), (playhead_x, 740.0), MOVING, 3.0);
    c.circle((playhead_x, 740.0), 4.5, Some(MOVING), Some((PAPER, 1.0)));
    let time_label = c.fit_text(&format!("{label} {now:.2} s"), 14, 250.0);
    let label_width = c.text_width(&time_label, 14);
    let label_x = (playhead_x - label_width / 2.0).clamp(32.0, 1168.0 - label_width);
    c.line((playhead_x, 747.0), (playhead_x, 753.0), MOVING, 1.0);
    c.text((label_x, 775.0), &time_label, 14, INK, 250.0);
}
