//! Versioned JSON scenarios and deterministic pedestrian-flow simulation.
//!
//! The runtime records its authored inputs and every simulated state transition
//! so a compatible installation can reproduce a run bundle.

mod avoidance;
pub mod benchmark;
pub mod bundle;
pub mod evaluation;
pub mod experiment;
pub mod generator;
pub mod journeys;
pub mod model;
pub mod reachability;
pub mod runtime;
pub mod scenario_json;
pub mod study;
pub mod validate;
pub mod wfc;

pub use bundle::{
    AgentKnowledgeSnapshot, AgentState, InformationDeliveryMetrics, InformationInterventionKind,
    KnowledgeSource, KnownAvailability, MovementMetrics, OnSurfaceClearanceMetrics, QueueMetrics,
    QueueResourceBreakdown, QueueResourceMetrics, RunBundle, SweptOnSurfaceClearanceMetrics,
    bundle_hash,
};
pub use model::{CanonicalScenario, Scenario};
pub use runtime::{BundleVerification, RunOptions, integration_step_count, run, verify_run_bundle};
pub use scenario_json::{
    SCENARIO_SCHEMA_VERSION, ScenarioJsonError, decode_scenario, encode_scenario,
};
pub use validate::{ValidationError, validate};

pub const RUNTIME_VERSION: &str = env!("CARGO_PKG_VERSION");
