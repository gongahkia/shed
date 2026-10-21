//! UI-independent recorded-time navigation and bounded camera controls.
use anyhow::{Result, ensure};
use chiyoda_core::bundle::TraceFrame;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(default, deny_unknown_fields)]
pub struct CameraControl {
    pub zoom: f64,
    pub pan_x_pixels: f64,
    pub pan_y_pixels: f64,
}
impl Default for CameraControl {
    fn default() -> Self {
        Self {
            zoom: 1.0,
            pan_x_pixels: 0.0,
            pan_y_pixels: 0.0,
        }
    }
}
impl CameraControl {
    pub fn validate(self) -> Result<()> {
        ensure!(
            self.zoom.is_finite()
                && (0.25..=32.0).contains(&self.zoom)
                && self.pan_x_pixels.is_finite()
                && self.pan_y_pixels.is_finite()
                && self.pan_x_pixels.abs() <= 1_000_000.0
                && self.pan_y_pixels.abs() <= 1_000_000.0,
            "camera requires zoom 0.25..32 and finite bounded pixel offsets"
        );
        Ok(())
    }
    /// Anchor is relative to the centre of the applicable plan viewport.
    pub fn zoom_at(&mut self, factor: f64, anchor: (f64, f64)) {
        if !factor.is_finite() || factor <= 0.0 {
            return;
        }
        let next = (self.zoom * factor).clamp(0.25, 32.0);
        let ratio = next / self.zoom;
        self.pan_x_pixels =
            (anchor.0 - (anchor.0 - self.pan_x_pixels) * ratio).clamp(-1_000_000.0, 1_000_000.0);
        self.pan_y_pixels =
            (anchor.1 - (anchor.1 - self.pan_y_pixels) * ratio).clamp(-1_000_000.0, 1_000_000.0);
        self.zoom = next;
    }
    pub fn pan(&mut self, dx: f64, dy: f64) {
        if dx.is_finite() && dy.is_finite() {
            self.pan_x_pixels = (self.pan_x_pixels + dx).clamp(-1_000_000.0, 1_000_000.0);
            self.pan_y_pixels = (self.pan_y_pixels + dy).clamp(-1_000_000.0, 1_000_000.0);
        }
    }
}

/// Last recorded state at or before a shared time; never borrows a future frame.
pub fn index_at_time(trace: &[TraceFrame], time: f64) -> Result<usize> {
    ensure!(
        !trace.is_empty() && time.is_finite(),
        "a finite time and nonempty trace are required"
    );
    ensure!(
        time >= trace[0].time_s && time <= trace.last().unwrap().time_s,
        "requested time is outside the recorded interval"
    );
    Ok(trace
        .partition_point(|f| f.time_s <= time)
        .saturating_sub(1))
}
#[derive(Debug, Clone)]
pub struct PlaybackClock {
    pub time_s: f64,
    pub paused: bool,
    pub speed: f64,
}
impl PlaybackClock {
    pub fn new(time_s: f64, paused: bool, speed: f64) -> Result<Self> {
        ensure!(
            time_s.is_finite() && speed.is_finite() && speed > 0.0,
            "playback requires a finite time and positive speed"
        );
        Ok(Self {
            time_s,
            paused,
            speed,
        })
    }
    pub fn advance(&mut self, elapsed_s: f64, start: f64, end: f64) {
        if !self.paused && elapsed_s.is_finite() && elapsed_s >= 0.0 {
            self.time_s = (self.time_s + elapsed_s * self.speed).clamp(start, end);
        }
    }
    pub fn seek_fraction(&mut self, fraction: f64, start: f64, end: f64) {
        if fraction.is_finite() {
            self.time_s = (start + (end - start) * fraction.clamp(0.0, 1.0)).clamp(start, end);
            self.paused = true;
        }
    }
    pub fn change_speed(&mut self, factor: f64) {
        if factor.is_finite() && factor > 0.0 {
            self.speed = (self.speed * factor).clamp(0.03125, 256.0);
        }
    }
}
#[cfg(test)]
#[allow(clippy::float_cmp)] // exact binary fractions and clamped limits are intentional
mod tests {
    use super::*;
    fn trace() -> Vec<TraceFrame> {
        [0.0, 0.2, 1.0, 2.0]
            .into_iter()
            .enumerate()
            .map(|(i, t)| TraceFrame {
                step: u64::try_from(i).unwrap(),
                time_s: t,
                agents: vec![],
            })
            .collect()
    }
    #[test]
    fn seeking_uses_timestamps_and_never_interpolates_or_selects_future_evidence() {
        let t = trace();
        assert_eq!(index_at_time(&t, 0.9).unwrap(), 1);
        assert_eq!(index_at_time(&t, 1.0).unwrap(), 2);
        assert_eq!(index_at_time(&t, 2.0).unwrap(), 3);
        assert!(index_at_time(&t, -0.1).is_err());
        assert!(index_at_time(&t, f64::NAN).is_err());
    }
    #[test]
    fn playback_catches_up_by_elapsed_time_and_seeking_pauses() {
        let mut c = PlaybackClock::new(0.0, false, 4.0).unwrap();
        c.advance(0.3, 0.0, 2.0);
        assert!((c.time_s - 1.2).abs() < 1e-9);
        assert_eq!(index_at_time(&trace(), c.time_s).unwrap(), 2);
        c.seek_fraction(0.25, 0.0, 2.0);
        assert!(c.paused);
        assert_eq!(c.time_s, 0.5);
        c.advance(10.0, 0.0, 2.0);
        assert_eq!(c.time_s, 0.5);
        c.change_speed(100.0);
        assert_eq!(c.speed, 256.0);
    }
    #[test]
    fn zoom_preserves_anchor_and_controls_remain_bounded() {
        let mut c = CameraControl::default();
        c.zoom_at(2.0, (100.0, 20.0));
        assert_eq!(c.zoom, 2.0);
        assert_eq!(c.pan_x_pixels, -100.0);
        assert_eq!(c.pan_y_pixels, -20.0);
        c.zoom_at(0.5, (100.0, 20.0));
        assert_eq!(c, CameraControl::default());
        c.pan(2e9, -2e9);
        c.validate().unwrap();
        c.zoom = f64::NAN;
        assert!(c.validate().is_err());
    }
}
