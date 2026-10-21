use super::*;
use crate::{Scenario, experiment::Issue};
pub(super) fn issue(code: &str, path: &str, message: impl Into<String>) -> Issue {
    Issue {
        code: code.into(),
        path: path.into(),
        message: message.into(),
    }
}
pub(super) fn kind_target(change: &Change) -> (EditKind, Option<&str>) {
    match change {
        Change::MoveObstacle { id, .. } => (EditKind::MoveObstacle, Some(id)),
        Change::ResizeObstacle { id, .. } => (EditKind::ResizeObstacle, Some(id)),
        Change::AddObstacle { obstacle } => (EditKind::AddObstacle, Some(&obstacle.id)),
        Change::RemoveObstacle { id } => (EditKind::RemoveObstacle, Some(id)),
        Change::SetExitWidth { id, .. } => (EditKind::SetExitWidth, Some(id)),
        Change::SetExitCapacity { id, .. } => (EditKind::SetExitCapacity, Some(id)),
        Change::SetGateRate { id, .. } => (EditKind::SetGateRate, Some(id)),
        Change::SetConnectorWidth { id, .. } => (EditKind::SetConnectorWidth, Some(id)),
        Change::SetGroupCount { id, .. } => (EditKind::SetGroupCount, Some(id)),
        Change::SetGroupKnowledge { id, .. } => (EditKind::SetGroupKnowledge, Some(id)),
        Change::ReplaceInformation { .. } => (EditKind::ReplaceInformation, None),
    }
}
pub(super) fn check(
    constraints: &Constraints,
    changes: &[Change],
    scenario: Option<&Scenario>,
    same_population: bool,
) -> Vec<Issue> {
    let mut issues = vec![];
    for (i, change) in changes.iter().enumerate() {
        let (kind, target) = kind_target(change);
        if !constraints.allowed_edits.iter().any(|p| {
            p.kind == kind
                && target.map_or(p.targets.is_empty(), |id| {
                    p.targets.iter().any(|t| t == id || t == "*")
                })
        }) {
            issues.push(issue(
                "permission",
                &format!("changes[{i}]"),
                format!("{kind:?} on {target:?} is not permitted"),
            ));
        }
    }
    if constraints.preserve_population && !same_population {
        issues.push(issue(
            "population",
            "changes",
            "physical traits, counts, goals, releases and seed must match the case baseline",
        ));
    }
    if let Some(scenario) = scenario {
        for (i, bound) in constraints.bounds.iter().enumerate() {
            if numeric(scenario, bound)
                .is_none_or(|v| !v.is_finite() || v < bound.min || v > bound.max)
            {
                issues.push(issue(
                    "numeric_bound",
                    &format!("constraints.bounds[{i}]"),
                    format!(
                        "{:?} on {} must exist in [{}, {}]",
                        bound.field, bound.target, bound.min, bound.max
                    ),
                ));
            }
        }
    }
    issues
}
fn numeric(scenario: &Scenario, bound: &NumericBound) -> Option<f64> {
    let (collection, path) = match bound.field {
        NumericField::ObstacleX => ("obstacles", "/at/x_m"),
        NumericField::ObstacleY => ("obstacles", "/at/y_m"),
        NumericField::ObstacleWidth => ("obstacles", "/width_m"),
        NumericField::ObstacleDepth => ("obstacles", "/depth_m"),
        NumericField::ExitWidth => ("exits", "/width_m"),
        NumericField::ExitCapacity => ("exits", "/capacity_per_s"),
        NumericField::GateRate => ("gates", "/service_rate_per_s"),
        NumericField::ConnectorWidth => ("connectors", "/width_m"),
        NumericField::GroupCount => ("agents", "/count"),
    };
    let value = serde_json::to_value(scenario).ok()?;
    value[collection]
        .as_array()?
        .iter()
        .find(|v| v["id"].as_str() == Some(&bound.target))?
        .pointer(path)?
        .as_f64()
}
