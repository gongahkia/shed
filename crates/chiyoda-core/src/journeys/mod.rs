//! Evidence derived from complete integration traces. Analysis does not alter
//! ordinary movement, and comparison references do not assign causal effects.

mod observations;
mod summary;

use crate::{RunBundle, benchmark::diagnostics::LowProgressWindow, bundle::RunEvent};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const JOURNEY_VERSION: &str = "1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct JourneyOptions {
    pub low_progress_window_s: f64,
    pub max_low_progress_travel_m: f64,
    pub hotspot_cell_size_m: f64,
}

impl Default for JourneyOptions {
    fn default() -> Self {
        Self {
            low_progress_window_s: 5.0,
            max_low_progress_travel_m: 0.1,
            hotspot_cell_size_m: 2.0,
        }
    }
}

impl JourneyOptions {
    pub fn validate(&self) -> Result<(), String> {
        if !self.low_progress_window_s.is_finite()
            || self.low_progress_window_s <= 0.0
            || !self.max_low_progress_travel_m.is_finite()
            || self.max_low_progress_travel_m < 0.0
            || !self.hotspot_cell_size_m.is_finite()
            || self.hotspot_cell_size_m < 0.1
        {
            return Err("journey windows must be positive, travel limits nonnegative, and hotspot cells at least 0.1 m; all must be finite".into());
        }
        Ok(())
    }
}

/// Mutually exclusive preceding-boundary state exposure over the full run.
/// Moving exposure includes slow and stationary intervals. Low-progress spans
/// are a separate overlay, never another additive time-budget component.
#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TimeBudget {
    pub moving_s: f64,
    pub waiting_to_depart_s: f64,
    pub waypoint_pause_s: f64,
    pub route_wait_s: f64,
    pub lift_wait_s: f64,
    pub connector_wait_s: f64,
    pub gate_wait_s: f64,
    pub exit_wait_s: f64,
    pub transit_s: f64,
    pub evacuated_s: f64,
}

impl TimeBudget {
    #[must_use]
    pub fn service_wait_s(&self) -> f64 {
        self.lift_wait_s + self.connector_wait_s + self.gate_wait_s + self.exit_wait_s
    }

    #[must_use]
    pub fn total_s(&self) -> f64 {
        self.moving_s
            + self.waiting_to_depart_s
            + self.waypoint_pause_s
            + self.route_wait_s
            + self.service_wait_s()
            + self.transit_s
            + self.evacuated_s
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AgentJourney {
    pub agent: String,
    pub group: String,
    pub scheduled_release_s: f64,
    pub admitted_at_s: Option<f64>,
    pub evacuated_at_s: Option<f64>,
    pub elapsed_journey_s: Option<f64>,
    pub final_state: crate::AgentState,
    pub walking_distance_m: f64,
    pub connector_interior_distance_m: f64,
    /// Intervals entering/leaving transit cannot be cleanly assigned to
    /// walking versus connector motion from snapshots alone.
    pub transition_distance_m: f64,
    pub time: TimeBudget,
    pub avoidance_fallback_steps: u64,
    pub swept_guard_steps: u64,
    pub low_progress: Vec<LowProgressWindow>,
    /// Authored information, route, stage, and service events in recorded order.
    pub decisions: Vec<RunEvent>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JourneyHotspot {
    pub surface: String,
    pub x_m: f64,
    pub y_m: f64,
    pub low_progress_agent_s: f64,
    pub agents: Vec<String>,
    pub start_step: u64,
    pub end_step: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ResourceExposure {
    pub kind: String,
    pub resource: String,
    pub wait_agent_s: f64,
    pub peak_waiting_agents: u32,
    /// Proven queue-entry exposures, not a per-person resource wait estimate.
    pub agents: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JourneyReport {
    pub journey_version: String,
    pub runtime_version: String,
    pub source_bundle_hash: String,
    pub scenario_hash: String,
    pub options: JourneyOptions,
    pub observed_duration_s: f64,
    pub agents: Vec<AgentJourney>,
    pub hotspots: Vec<JourneyHotspot>,
    pub resources: Vec<ResourceExposure>,
    pub report_hash: String,
}

/// Compact case evidence. Full per-person records are stored in `journeys.json`
/// rather than retained for every member of a large study matrix.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JourneySummary {
    pub journey_version: String,
    pub report_hash: String,
    pub source_bundle_hash: String,
    pub people: u32,
    pub completed: u32,
    pub walking_distance_m: f64,
    pub moving_agent_s: f64,
    pub service_wait_agent_s: f64,
    pub waypoint_pause_agent_s: f64,
    pub route_wait_agent_s: f64,
    pub transit_agent_s: f64,
    pub low_progress_agents: u32,
    pub low_progress_span_agent_s: f64,
    pub decision_events: u64,
}

/// Analyze every integration interval; sparse traces are rejected instead of
/// reporting sampled chords as walked distance or sampled states as full time.
pub fn analyze(bundle: &RunBundle, options: &JourneyOptions) -> Result<JourneyReport, String> {
    observations::analyze(bundle, options)
}

impl JourneyReport {
    #[must_use]
    pub fn summary(&self) -> JourneySummary {
        summary::derive(self)
    }

    /// Re-derive observations and compare the complete analysis, including hash.
    /// This does not rerun the simulation; `verify_run_bundle` supplies that check.
    pub fn verify(&self, bundle: &RunBundle) -> Result<(), String> {
        if analyze(bundle, &self.options)? != *self {
            return Err("journey evidence does not match its source trace".into());
        }
        Ok(())
    }
}

impl JourneySummary {
    pub fn validate(&self) -> Result<(), String> {
        let nonnegative = [
            self.walking_distance_m,
            self.moving_agent_s,
            self.service_wait_agent_s,
            self.waypoint_pause_agent_s,
            self.route_wait_agent_s,
            self.transit_agent_s,
            self.low_progress_span_agent_s,
        ];
        if self.journey_version != JOURNEY_VERSION
            || self.people == 0
            || self.completed > self.people
            || self.low_progress_agents > self.people
            || [&self.report_hash, &self.source_bundle_hash]
                .iter()
                .any(|hash| hash.len() != 64 || !hash.bytes().all(|byte| byte.is_ascii_hexdigit()))
            || nonnegative
                .iter()
                .any(|value| !value.is_finite() || *value < 0.0)
        {
            return Err("invalid journey summary".into());
        }
        Ok(())
    }

    #[must_use]
    pub fn observations(&self) -> BTreeMap<String, f64> {
        let people = f64::from(self.people);
        BTreeMap::from([
            (
                "mean_walking_distance_m".into(),
                self.walking_distance_m / people,
            ),
            (
                "moving_time_per_person_s".into(),
                self.moving_agent_s / people,
            ),
            (
                "waypoint_pause_per_person_s".into(),
                self.waypoint_pause_agent_s / people,
            ),
            (
                "route_wait_per_person_s".into(),
                self.route_wait_agent_s / people,
            ),
            (
                "transit_time_per_person_s".into(),
                self.transit_agent_s / people,
            ),
            (
                "low_progress_span_per_person_s".into(),
                self.low_progress_span_agent_s / people,
            ),
            (
                "low_progress_people".into(),
                f64::from(self.low_progress_agents),
            ),
        ])
    }
}
