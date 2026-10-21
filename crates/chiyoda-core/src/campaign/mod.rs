//! Budgeted, permissioned screening and held-out stress experiments.
//! Callers propose candidates; this layer executes and explains an explicit objective order.
mod planning;
mod policy;
use crate::{
    CanonicalScenario,
    evaluation::EvaluationOptions,
    experiment::{Change, ExperimentError, ExperimentPlan, Issue},
    generator::spaces::SpaceConfig,
};
pub use planning::plan;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
pub const CAMPAIGN_VERSION: &str = "1";
pub type CampaignError = ExperimentError;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CampaignRequest {
    pub campaign_version: String,
    pub name: String,
    pub screening: CaseSpec,
    #[serde(default)]
    pub stress_cases: Vec<CaseSpec>,
    pub candidates: Vec<Candidate>,
    pub constraints: Constraints,
    pub objectives: Vec<Objective>,
    #[serde(default)]
    pub promotion: Promotion,
    #[serde(default)]
    pub options: CampaignOptions,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CaseSpec {
    pub id: String,
    pub source: CaseSource,
    /// Applied to both arms before any intervention, with checked integer counts.
    #[serde(default = "one")]
    pub population_multiplier: u32,
}
const fn one() -> u32 {
    1
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum CaseSource {
    Authored { scenario: CanonicalScenario },
    Generated { seed: u64, config: Box<SpaceConfig> },
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Candidate {
    pub id: String,
    pub changes: Vec<Change>,
    /// Explicit recipes for different authored IDs/geometries, declared before screening.
    /// Omission reuses `changes` exactly; missing IDs are retained preflight rejections.
    #[serde(default)]
    pub case_changes: BTreeMap<String, Vec<Change>>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum EditKind {
    MoveObstacle,
    ResizeObstacle,
    AddObstacle,
    RemoveObstacle,
    SetExitWidth,
    SetExitCapacity,
    SetGateRate,
    SetConnectorWidth,
    SetGroupCount,
    SetGroupKnowledge,
    ReplaceInformation,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Permission {
    pub kind: EditKind,
    /// Exact object IDs. `*` explicitly permits every ID of this kind.
    /// ReplaceInformation replaces the whole policy and requires an empty list.
    pub targets: Vec<String>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum NumericField {
    ObstacleX,
    ObstacleY,
    ObstacleWidth,
    ObstacleDepth,
    ExitWidth,
    ExitCapacity,
    GateRate,
    ConnectorWidth,
    GroupCount,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct NumericBound {
    pub field: NumericField,
    pub target: String,
    pub min: f64,
    pub max: f64,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Constraints {
    pub allowed_edits: Vec<Permission>,
    #[serde(default)]
    pub bounds: Vec<NumericBound>,
    #[serde(default = "yes")]
    pub preserve_population: bool,
    /// Candidate outcomes must satisfy these absolute bounds to be eligible.
    #[serde(default)]
    pub outcomes: Vec<OutcomeBound>,
}
const fn yes() -> bool {
    true
}
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum Metric {
    CompletedFraction,
    UnfinishedPeople,
    ServiceWaitPerPersonS,
    ClearanceTimeS,
    LowProgressPerPersonS,
    MeanCompletedJourneyS,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Minimize,
    Maximize,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Objective {
    pub metric: Metric,
    pub direction: Direction,
    /// Used for win/tie/loss classification only. Ranking uses exact values.
    #[serde(default)]
    pub tolerance: f64,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct OutcomeBound {
    pub metric: Metric,
    pub min: Option<f64>,
    pub max: Option<f64>,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(default, deny_unknown_fields)]
pub struct Promotion {
    pub max_candidates: u32,
    pub require_improvement: bool,
}
impl Default for Promotion {
    fn default() -> Self {
        Self {
            max_candidates: 2,
            require_improvement: true,
        }
    }
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(default, deny_unknown_fields)]
pub struct CampaignOptions {
    pub budget: Budget,
    pub evaluation: EvaluationOptions,
}
impl Default for CampaignOptions {
    fn default() -> Self {
        Self {
            budget: Budget::default(),
            evaluation: EvaluationOptions::default(),
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(default, deny_unknown_fields)]
pub struct Budget {
    pub max_candidates: u32,
    pub max_trials: u32,
    pub max_agent_steps: u64,
    pub max_trace_samples: u64,
    pub max_generation_attempts: u64,
    pub max_generation_decisions: u64,
    /// Image rendering reconstructs its source run; that work is reserved too.
    pub max_evidence_images: u32,
}
impl Default for Budget {
    fn default() -> Self {
        Self {
            max_candidates: 20,
            max_trials: 128,
            max_agent_steps: 2_000_000,
            max_trace_samples: 2_000_000,
            max_generation_attempts: 128,
            max_generation_decisions: 16_000_000,
            max_evidence_images: 4,
        }
    }
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PreparedCase {
    pub id: String,
    pub source_hash: String,
    pub scenario: Option<CanonicalScenario>,
    pub generation: Option<serde_json::Value>,
    pub issues: Vec<Issue>,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TrialPlan {
    pub case: String,
    pub candidate: String,
    pub experiment: Option<ExperimentPlan>,
    pub issues: Vec<Issue>,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CampaignPlan {
    pub campaign_version: String,
    pub runtime_version: String,
    pub input_hash: String,
    pub plan_hash: String,
    pub runnable: bool,
    pub reserved_trials: u64,
    pub reserved_agent_steps: u64,
    pub reserved_trace_samples: u64,
    pub reserved_evidence_agent_steps: u64,
    pub reserved_generation_attempts: u64,
    pub reserved_generation_decisions: u64,
    pub cases: Vec<PreparedCase>,
    pub trials: Vec<TrialPlan>,
    pub issues: Vec<Issue>,
}
#[must_use]
pub fn json_schema() -> serde_json::Value {
    let mut schema =
        serde_json::to_value(schemars::schema_for!(CampaignRequest)).expect("campaign schema");
    schema["properties"]["campaign_version"]["const"] = CAMPAIGN_VERSION.into();
    schema["$defs"]["CanonicalScenario"]["properties"]["schema_version"]["const"] =
        crate::SCENARIO_SCHEMA_VERSION.into();
    schema
}
