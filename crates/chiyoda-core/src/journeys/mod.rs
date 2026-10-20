//! Evidence derived from complete integration traces. Analysis does not alter
//! ordinary movement, and comparison references do not assign causal effects.

mod controls;
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub isolated_controls: Option<IsolationOptions>,
}

impl Default for JourneyOptions {
    fn default() -> Self {
        Self {
            low_progress_window_s: 5.0,
            max_low_progress_travel_m: 0.1,
            hotspot_cell_size_m: 2.0,
            isolated_controls: None,
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
        if let Some(controls) = &self.isolated_controls {
            controls.validate()?;
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
    pub nominal_reference: Option<NominalJourney>,
    pub avoidance_fallback_steps: u64,
    pub swept_guard_steps: u64,
    pub low_progress: Vec<LowProgressWindow>,
    /// Authored information, route, stage, and service events in recorded order.
    pub decisions: Vec<RunEvent>,
}

/// Centre-to-centre obstacle-aware nominal planning with every resource open,
/// all candidate exits familiar, and no service queues or other people. Keeps
/// mobility restrictions, ordered waypoints, authored dwell, and connector
/// traversal assumptions. Radius-contact exit admission can finish earlier.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NominalJourney {
    pub centre_route_duration_s: f64,
    pub authored_dwell_s: f64,
    pub exit: String,
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
    pub analyzer_runtime_version: String,
    pub source_bundle_hash: String,
    pub scenario_hash: String,
    pub options: JourneyOptions,
    pub observed_duration_s: f64,
    pub agents: Vec<AgentJourney>,
    pub hotspots: Vec<JourneyHotspot>,
    pub resources: Vec<ResourceExposure>,
    pub isolated_controls: Vec<IsolatedControl>,
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
    pub completed_elapsed_journey_s: f64,
    pub nominal_estimates: u32,
    pub nominal_journey_s: f64,
    pub controls_requested: u32,
    pub controls_run: u32,
    pub controls_completed: u32,
    pub control_pairs_completed: u32,
    pub paired_crowded_elapsed_s: f64,
    pub paired_isolated_elapsed_s: f64,
}

/// Analyze every integration interval; sparse traces are rejected instead of
/// reporting sampled chords as walked distance or sampled states as full time.
pub fn analyze(bundle: &RunBundle, options: &JourneyOptions) -> Result<JourneyReport, String> {
    Ok(analyze_with_controls(bundle, options)?.report)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct IsolationOptions {
    pub agents: Vec<String>,
    pub low_progress_agents: bool,
    pub all_agents: bool,
    #[serde(default = "default_control_count")]
    pub max_agents: u32,
    #[serde(default = "default_control_steps")]
    pub max_simulated_agent_steps: u64,
    #[serde(default = "default_replay")]
    pub verify_replay: bool,
}

impl Default for IsolationOptions {
    fn default() -> Self {
        Self {
            agents: Vec::new(),
            low_progress_agents: false,
            all_agents: false,
            max_agents: default_control_count(),
            max_simulated_agent_steps: default_control_steps(),
            verify_replay: true,
        }
    }
}

fn default_control_count() -> u32 {
    16
}
fn default_control_steps() -> u64 {
    20_000
}
fn default_replay() -> bool {
    true
}

impl IsolationOptions {
    pub fn validate(&self) -> Result<(), String> {
        let unique: std::collections::BTreeSet<_> = self.agents.iter().collect();
        if self.agents.len() > 4096
            || unique.len() != self.agents.len()
            || self.agents.iter().any(|id| id.trim().is_empty())
            || !(1..=64).contains(&self.max_agents)
            || !(1..=200_000).contains(&self.max_simulated_agent_steps)
        {
            return Err("isolated controls require unique nonempty IDs, max_agents 1..=64, and max_simulated_agent_steps 1..=200000".into());
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ControlStatus {
    Completed,
    Incomplete,
    BudgetLimit,
    AgentNotFound,
    RuntimeMismatch,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct IsolatedControl {
    pub agent: String,
    pub status: ControlStatus,
    pub bundle_hash: Option<String>,
    pub replay_verified: bool,
    pub admitted_at_s: Option<f64>,
    pub evacuated_at_s: Option<f64>,
    pub elapsed_journey_s: Option<f64>,
    pub walking_distance_m: Option<f64>,
    pub crowded_elapsed_journey_s: Option<f64>,
    /// Only paired completions have a difference. This is the effect of the
    /// recorded counterfactual, not a decomposition into independent causes.
    pub crowded_minus_isolated_elapsed_s: Option<f64>,
}

#[derive(Debug)]
pub struct JourneyAnalysis {
    pub report: JourneyReport,
    /// Bounded by both control count and total simulated agent steps.
    pub isolated_runs: Vec<RunBundle>,
}

pub fn analyze_with_controls(
    bundle: &RunBundle,
    options: &JourneyOptions,
) -> Result<JourneyAnalysis, String> {
    controls::analyze_with_controls(bundle, options)
}

impl JourneyReport {
    #[must_use]
    pub fn summary(&self) -> JourneySummary {
        summary::derive(self)
    }

    /// Re-derive observations and compare the complete analysis, including hash.
    /// Ordinary observations are re-derived without simulation. Explicit
    /// isolated controls are rerun when present; use `verify_evidence` to check
    /// archived control bundles without repeating their simulations.
    pub fn verify(&self, bundle: &RunBundle) -> Result<(), String> {
        if analyze(bundle, &self.options)? != *self {
            return Err("journey evidence does not match its source trace".into());
        }
        Ok(())
    }

    pub fn verify_evidence(
        &self,
        bundle: &RunBundle,
        isolated: &[RunBundle],
    ) -> Result<(), String> {
        let expected = controls::from_evidence(bundle, &self.options, isolated)?;
        if expected != *self {
            return Err("journey evidence does not match its archived bundles".into());
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
            self.completed_elapsed_journey_s,
            self.nominal_journey_s,
            self.paired_crowded_elapsed_s,
            self.paired_isolated_elapsed_s,
        ];
        if self.journey_version != JOURNEY_VERSION
            || self.people == 0
            || self.completed > self.people
            || self.low_progress_agents > self.people
            || self.nominal_estimates > self.people
            || self.controls_run > self.controls_requested
            || self.controls_completed > self.controls_run
            || self.control_pairs_completed > self.controls_completed
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
        let mut values = BTreeMap::from([
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
        ]);
        values.insert("journey_completed_people".into(), f64::from(self.completed));
        values.insert(
            "isolated_controls_requested".into(),
            f64::from(self.controls_requested),
        );
        values.insert("isolated_controls_run".into(), f64::from(self.controls_run));
        if self.completed > 0 {
            values.insert(
                "mean_elapsed_journey_s".into(),
                self.completed_elapsed_journey_s / f64::from(self.completed),
            );
        }
        if self.nominal_estimates > 0 {
            values.insert(
                "mean_static_nominal_journey_s".into(),
                self.nominal_journey_s / f64::from(self.nominal_estimates),
            );
        }
        if self.control_pairs_completed > 0 {
            values.insert(
                "mean_paired_crowded_journey_s".into(),
                self.paired_crowded_elapsed_s / f64::from(self.control_pairs_completed),
            );
            values.insert(
                "mean_paired_isolated_journey_s".into(),
                self.paired_isolated_elapsed_s / f64::from(self.control_pairs_completed),
            );
        }
        values
    }
}
