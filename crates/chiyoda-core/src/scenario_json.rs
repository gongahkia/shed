//! Versioned JSON scenario input. Physical units are explicit in field names.

use crate::{CanonicalScenario, Scenario, ValidationError, validate};

pub const SCENARIO_SCHEMA_VERSION: &str = "1";

#[derive(Debug, thiserror::Error)]
pub enum ScenarioJsonError {
    #[error("invalid scenario JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error(
        "unsupported scenario schema `{found}`; expected schema_version `{SCENARIO_SCHEMA_VERSION}`"
    )]
    Version { found: String },
    #[error("invalid scenario: {0:?}")]
    Validation(Vec<ValidationError>),
}

/// Decode a current JSON document. Semantic validation is separate so callers
/// can distinguish malformed input from a well-formed, invalid experiment.
pub fn decode_scenario(json: &str) -> Result<Scenario, ScenarioJsonError> {
    let document: CanonicalScenario = serde_json::from_str(json)?;
    document.check_schema()?;
    Ok(document.scenario)
}

/// Encode a validated scenario in stable field order, with a terminal newline.
/// Arrays retain their authored order, including event tie-breakers.
pub fn encode_scenario(scenario: &Scenario) -> Result<String, ScenarioJsonError> {
    validate(scenario).map_err(ScenarioJsonError::Validation)?;
    let mut json = serde_json::to_string_pretty(&CanonicalScenario::from(scenario.clone()))?;
    json.push('\n');
    Ok(json)
}
