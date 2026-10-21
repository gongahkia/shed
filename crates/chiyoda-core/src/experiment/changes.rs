use super::{Change, ExperimentError, FieldChange};
use crate::{Scenario, bundle::canonical_hash, model::Connector};
use std::collections::BTreeSet;

pub(super) fn apply(scenario: &mut Scenario, change: &Change) -> Result<(), ExperimentError> {
    macro_rules! target {
        ($collection:ident, $id:expr) => {
            scenario
                .$collection
                .iter_mut()
                .find(|item| item.id == *$id)
                .ok_or_else(|| {
                    ExperimentError::new(
                        "unknown_id",
                        format!("{} has no object `{}`", stringify!($collection), $id),
                    )
                })?
        };
    }
    match change {
        Change::MoveObstacle { id, at } => target!(obstacles, id).at = *at,
        Change::ResizeObstacle {
            id,
            width_m,
            depth_m,
        } => {
            let obstacle = target!(obstacles, id);
            obstacle.width_m = *width_m;
            obstacle.depth_m = *depth_m;
        }
        Change::AddObstacle { obstacle } => scenario.obstacles.push(obstacle.clone()),
        Change::RemoveObstacle { id } => {
            target!(obstacles, id);
            scenario.obstacles.retain(|obstacle| obstacle.id != *id);
        }
        Change::SetExitWidth { id, width_m } => target!(exits, id).width_m = *width_m,
        Change::SetExitCapacity { id, capacity_per_s } => {
            target!(exits, id).capacity_per_s = *capacity_per_s;
        }
        Change::SetGateRate {
            id,
            service_rate_per_s,
        } => target!(gates, id).service_rate_per_s = *service_rate_per_s,
        Change::SetConnectorWidth { id, width_m } => {
            let connector = scenario
                .connectors
                .iter_mut()
                .find(|item| item.id() == id)
                .ok_or_else(|| {
                    ExperimentError::new("unknown_id", format!("connectors has no object `{id}`"))
                })?;
            match connector {
                Connector::Stair { width_m: width, .. }
                | Connector::Ramp { width_m: width, .. }
                | Connector::Escalator { width_m: width, .. } => *width = *width_m,
                Connector::Lift { .. } => {
                    return Err(ExperimentError::new(
                        "unsupported_change",
                        "lifts require cabin dimensions, not a passage width",
                    ));
                }
            }
        }
        Change::SetGroupCount { id, count } => target!(agents, id).count = *count,
        Change::SetGroupKnowledge { id, knowledge } => {
            target!(agents, id).knowledge.clone_from(knowledge);
        }
        Change::ReplaceInformation {
            messages,
            countermeasures,
        } => {
            scenario.messages.clone_from(messages);
            scenario.countermeasures.clone_from(countermeasures);
        }
    }
    Ok(())
}

pub(super) fn population_hash(scenario: &Scenario) -> String {
    let mut people = scenario.agents.clone();
    for group in &mut people {
        group.knowledge = None;
    }
    canonical_hash(&(scenario.seed, people))
}

pub(super) fn differences(before: &Scenario, after: &Scenario) -> Vec<FieldChange> {
    let before = serde_json::to_value(before).expect("scenario is serializable");
    let after = serde_json::to_value(after).expect("scenario is serializable");
    let fields: BTreeSet<_> = before
        .as_object()
        .expect("scenario object")
        .keys()
        .chain(after.as_object().expect("scenario object").keys())
        .collect();
    fields
        .into_iter()
        .filter(|field| before[*field] != after[*field])
        .map(|field| FieldChange {
            field: field.clone(),
            baseline_hash: canonical_hash(&before[field]),
            candidate_hash: canonical_hash(&after[field]),
        })
        .collect()
}
