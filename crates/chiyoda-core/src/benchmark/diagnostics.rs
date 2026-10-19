//! Descriptive low-progress windows for agents recorded as `moving`.
//! Queued, dwelling, unreleased, and in-transit states reset the window. These
//! observations do not assert deadlock or predict progress toward a route goal.

use crate::{RunBundle, bundle::AgentState, model::Point3};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, VecDeque};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct DiagnosticOptions {
    pub window_s: f64,
    pub max_travel_m: f64,
}

impl Default for DiagnosticOptions {
    fn default() -> Self {
        Self {
            window_s: 5.0,
            max_travel_m: 0.1,
        }
    }
}

impl DiagnosticOptions {
    pub fn validate(&self) -> Result<(), String> {
        if !self.window_s.is_finite()
            || self.window_s <= 0.0
            || !self.max_travel_m.is_finite()
            || self.max_travel_m < 0.0
        {
            return Err("diagnostic window must be finite and positive; travel limit finite and nonnegative".into());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LowProgressWindow {
    pub agent: String,
    pub surface: String,
    pub start_step: u64,
    pub end_step: u64,
    pub start_time_s: f64,
    pub end_time_s: f64,
    pub minimum_window_travel_m: f64,
}

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Diagnosis {
    pub walking_low_progress: Vec<LowProgressWindow>,
}

impl Diagnosis {
    pub fn validate(&self) -> Result<(), String> {
        for window in &self.walking_low_progress {
            if window.agent.is_empty()
                || window.surface.is_empty()
                || window.start_step > window.end_step
                || ![
                    window.start_time_s,
                    window.end_time_s,
                    window.minimum_window_travel_m,
                ]
                .iter()
                .all(|value| value.is_finite() && *value >= 0.0)
                || window.start_time_s >= window.end_time_s
            {
                return Err("invalid walking low-progress observation".into());
            }
        }
        Ok(())
    }
}

struct Sample {
    time_s: f64,
    step: u64,
    point: Point3,
}

#[derive(Default)]
struct Track {
    surface: String,
    samples: VecDeque<Sample>,
    distance_m: f64,
    incident: Option<usize>,
}

/// Analyze a freshly evaluated full-step trace. Options are validated by the
/// enclosing suite; this function also handles invalid options without panic.
#[must_use]
pub fn analyze(bundle: &RunBundle, options: &DiagnosticOptions) -> Diagnosis {
    if options.validate().is_err() {
        return Diagnosis::default();
    }
    let mut tracks: BTreeMap<&str, Track> = BTreeMap::new();
    let mut diagnosis = Diagnosis::default();
    for frame in &bundle.trace {
        for agent in &frame.agents {
            let track = tracks.entry(&agent.id).or_default();
            if agent.state != AgentState::Moving || track.surface != agent.surface {
                track.samples.clear();
                track.distance_m = 0.0;
                track.incident = None;
                track.surface.clone_from(&agent.surface);
            }
            if agent.state != AgentState::Moving {
                continue;
            }
            let sample = Sample {
                time_s: frame.time_s,
                step: frame.step,
                point: Point3 {
                    x_m: agent.x_m,
                    y_m: agent.y_m,
                    z_m: agent.z_m,
                },
            };
            if let Some(previous) = track.samples.back() {
                track.distance_m += previous.point.distance(sample.point);
            }
            track.samples.push_back(sample);
            observe(track, &agent.id, options, &mut diagnosis);
        }
    }
    diagnosis
}

fn observe(track: &mut Track, agent: &str, options: &DiagnosticOptions, diagnosis: &mut Diagnosis) {
    let end = track.samples.back().expect("a sample was added");
    let cutoff = end.time_s - options.window_s;
    while track.samples.len() > 2 && track.samples[1].time_s <= cutoff {
        let removed = track.samples.pop_front().expect("nonempty window");
        track.distance_m -= removed.point.distance(track.samples[0].point);
    }
    let first = &track.samples[0];
    let Some(second) = track.samples.get(1) else {
        return;
    };
    if first.time_s > cutoff || second.time_s <= first.time_s {
        return;
    }
    let partial = first.point.distance(second.point)
        * ((cutoff - first.time_s) / (second.time_s - first.time_s)).clamp(0.0, 1.0);
    let travel = (track.distance_m - partial).max(0.0);
    if !travel.is_finite() || travel > options.max_travel_m {
        track.incident = None;
        return;
    }
    let end = track.samples.back().expect("nonempty window");
    if let Some(index) = track.incident {
        let incident = &mut diagnosis.walking_low_progress[index];
        incident.end_step = end.step;
        incident.end_time_s = rounded(end.time_s);
        incident.minimum_window_travel_m = incident.minimum_window_travel_m.min(rounded(travel));
    } else {
        track.incident = Some(diagnosis.walking_low_progress.len());
        diagnosis.walking_low_progress.push(LowProgressWindow {
            agent: agent.into(),
            surface: track.surface.clone(),
            start_step: first.step,
            end_step: end.step,
            start_time_s: rounded(cutoff),
            end_time_s: rounded(end.time_s),
            minimum_window_travel_m: rounded(travel),
        });
    }
}

fn rounded(value: f64) -> f64 {
    (value * 1_000_000_000.0).round() / 1_000_000_000.0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{RunOptions, parse, run};

    fn stationary_trace() -> RunBundle {
        let scenario = parse("scenario \"diagnostics\"\nseed 1\nduration 12s\ntimestep 1s\nsurface floor at (0m, 0m, 0m) size (100m, 10m)\nexit out on floor at (99m, 5m, 0m) width 1m\nagents person count 1 on floor at (1m, 5m, 0m) to out speed 1m/s radius 0.3m height 1.7m\n").unwrap();
        let mut bundle = run(
            &scenario,
            RunOptions {
                trace_every_steps: 1,
            },
        )
        .unwrap();
        for frame in &mut bundle.trace {
            frame.agents[0].x_m = 1.0;
        }
        bundle
    }

    #[test]
    fn merges_adjacent_windows_and_excludes_authored_wait_states() {
        let mut bundle = stationary_trace();
        let diagnosis = analyze(&bundle, &DiagnosticOptions::default());
        assert_eq!(diagnosis.walking_low_progress.len(), 1);
        assert!(
            diagnosis.walking_low_progress[0]
                .start_time_s
                .total_cmp(&0.0)
                .is_eq()
        );
        assert!(
            diagnosis.walking_low_progress[0]
                .end_time_s
                .total_cmp(&12.0)
                .is_eq()
        );
        bundle.trace[6].agents[0].state = AgentState::WaitingForGate;
        let diagnosis = analyze(&bundle, &DiagnosticOptions::default());
        assert_eq!(diagnosis.walking_low_progress.len(), 2);
        assert!(
            diagnosis.walking_low_progress[1]
                .start_time_s
                .total_cmp(&7.0)
                .is_eq()
        );
        for frame in &mut bundle.trace {
            frame.agents[0].state = AgentState::WaitingAtWaypoint;
        }
        assert!(
            analyze(&bundle, &DiagnosticOptions::default())
                .walking_low_progress
                .is_empty()
        );
    }

    #[test]
    fn measures_travel_instead_of_net_displacement_and_handles_fractional_windows() {
        let mut bundle = stationary_trace();
        for (index, frame) in bundle.trace.iter_mut().enumerate() {
            frame.agents[0].x_m = if index.is_multiple_of(2) { 1.0 } else { 2.0 };
        }
        assert!(
            analyze(&bundle, &DiagnosticOptions::default())
                .walking_low_progress
                .is_empty()
        );
        let diagnosis = analyze(
            &stationary_trace(),
            &DiagnosticOptions {
                window_s: 5.5,
                max_travel_m: 0.0,
            },
        );
        assert!(
            diagnosis.walking_low_progress[0]
                .start_time_s
                .total_cmp(&0.5)
                .is_eq()
        );
        diagnosis.validate().unwrap();
    }
}
