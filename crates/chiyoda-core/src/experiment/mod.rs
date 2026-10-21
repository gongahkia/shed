//! Versioned, bounded interventions on an authored or WFC-generated scenario.
//! Agents and interactive applications share this experiment contract.

mod changes;
mod planning;
mod runner;

pub(crate) use planning::prepare;
pub use planning::{inspect, plan};
pub use runner::{
    AgentDifference, ArmArtifacts, ArmResult, Comparison, EvidenceReference, ExperimentExecution,
    ExperimentReport, Finding, MetricDifference, execute,
};

use crate::{
    CanonicalScenario,
    evaluation::EvaluationOptions,
    model::{AgentKnowledge, Countermeasure, Message, Obstacle, Point3},
    reachability::ReachabilityReport,
};
use serde::{Deserialize, Serialize};

pub const EXPERIMENT_VERSION: &str = "1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ExperimentRequest {
    pub experiment_version: String,
    pub name: String,
    pub baseline: CanonicalScenario,
    /// Every variant starts independently from the same baseline.
    pub variants: Vec<Variant>,
    #[serde(default)]
    pub options: ExperimentOptions,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Variant {
    /// Portable identifier: 1..=64 ASCII letters, digits, underscores or hyphens.
    pub id: String,
    /// Ordered edits. An empty list is an explicit identical-input control.
    pub changes: Vec<Change>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum Change {
    MoveObstacle {
        id: String,
        at: Point3,
    },
    ResizeObstacle {
        id: String,
        width_m: f64,
        depth_m: f64,
    },
    AddObstacle {
        obstacle: Obstacle,
    },
    RemoveObstacle {
        id: String,
    },
    SetExitWidth {
        id: String,
        width_m: f64,
    },
    /// Null removes the authored discharge limit; width has independent semantics.
    SetExitCapacity {
        id: String,
        capacity_per_s: Option<f64>,
    },
    SetGateRate {
        id: String,
        service_rate_per_s: f64,
    },
    /// Applicable to stairs, ramps and escalators; lift cabin dimensions differ.
    SetConnectorWidth {
        id: String,
        width_m: f64,
    },
    SetGroupCount {
        id: String,
        count: u32,
    },
    SetGroupKnowledge {
        id: String,
        knowledge: Option<AgentKnowledge>,
    },
    /// Replaces information interventions together, preserving their authored order.
    ReplaceInformation {
        messages: Vec<Message>,
        countermeasures: Vec<Countermeasure>,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(default, deny_unknown_fields)]
pub struct ExperimentOptions {
    pub evaluation: EvaluationOptions,
    /// Counts both initial simulations and requested reconstruction executions.
    pub max_agent_steps: u64,
    /// Counts retained full-step agent snapshots, including initial frames.
    pub max_trace_samples: u64,
}

impl Default for ExperimentOptions {
    fn default() -> Self {
        Self {
            evaluation: EvaluationOptions::default(),
            max_agent_steps: 1_000_000,
            max_trace_samples: 1_000_000,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Issue {
    pub code: String,
    pub path: String,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, thiserror::Error)]
#[error("{code}: {message}")]
pub struct ExperimentError {
    pub code: String,
    pub message: String,
}

impl ExperimentError {
    pub(crate) fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FieldChange {
    pub field: String,
    pub baseline_hash: String,
    pub candidate_hash: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ScenarioInspection {
    pub experiment_version: String,
    pub scenario_hash: String,
    pub scenario: CanonicalScenario,
    pub people: u64,
    pub integration_steps: u64,
    pub issues: Vec<Issue>,
    pub reachability: Option<ReachabilityReport>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ArmPlan {
    pub id: String,
    pub scenario_hash: String,
    pub people: u64,
    pub integration_steps: u64,
    /// Seed, physical population, goals and releases. Knowledge is an intervention.
    pub population_hash: String,
    pub same_population_inputs: bool,
    pub changed_fields: Vec<FieldChange>,
    pub issues: Vec<Issue>,
    pub reachability: Option<ReachabilityReport>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ExperimentPlan {
    pub experiment_version: String,
    pub runtime_version: String,
    pub input_hash: String,
    pub runnable: bool,
    pub estimated_agent_steps: u64,
    pub retained_trace_samples: u64,
    pub arms: Vec<ArmPlan>,
    pub issues: Vec<Issue>,
}

#[must_use]
pub fn json_schema() -> serde_json::Value {
    let mut schema = serde_json::to_value(schemars::schema_for!(ExperimentRequest))
        .expect("generated experiment schema is serializable");
    schema["properties"]["experiment_version"]["const"] = EXPERIMENT_VERSION.into();
    schema["$defs"]["CanonicalScenario"]["properties"]["schema_version"]["const"] =
        crate::SCENARIO_SCHEMA_VERSION.into();
    schema
}
