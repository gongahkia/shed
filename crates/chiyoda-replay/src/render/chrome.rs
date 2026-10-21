//! The progress bar is the only presentation outside the simulation.
use super::{BORDER, INK, MOVING, MUTED, PAPER, TIMELINE_BOUNDS, canvas::Canvas};

#[allow(clippy::cast_possible_truncation)] // fixed framebuffer coordinates
pub(super) fn time_x(start: f64, end: f64, now: f64) -> f32 {
    let progress = if end > start {
        ((now - start) / (end - start)).clamp(0.0, 1.0) as f32
    } else {
        0.0
    };
    TIMELINE_BOUNDS[0] + TIMELINE_BOUNDS[2] * progress
}

pub(super) fn timeline(c: &mut Canvas, interval: Option<(f64, f64, f64)>) {
    let left = TIMELINE_BOUNDS[0];
    let right = left + TIMELINE_BOUNDS[2];
    c.line((left, 740.0), (right, 740.0), BORDER, 3.0);
    let Some((start, end, now)) = interval else {
        return;
    };
    c.text((left, 724.0), &format!("{start:.1} s"), 11, MUTED, 150.0);
    let end_label = format!("{end:.1} s");
    c.text(
        (right - c.text_width(&end_label, 11), 724.0),
        &end_label,
        11,
        MUTED,
        150.0,
    );
    let playhead_x = time_x(start, end, now);
    c.line((left, 740.0), (playhead_x, 740.0), MOVING, 3.0);
    c.circle((playhead_x, 740.0), 4.5, Some(MOVING), Some((PAPER, 1.0)));
    let time_label = c.fit_text(&format!("{now:.2} s"), 14, 250.0);
    let label_width = c.text_width(&time_label, 14);
    let label_x = (playhead_x - label_width / 2.0).clamp(left, right - label_width);
    c.line((playhead_x, 747.0), (playhead_x, 753.0), MOVING, 1.0);
    c.text((label_x, 775.0), &time_label, 14, INK, 250.0);
}
