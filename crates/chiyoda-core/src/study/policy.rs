use super::CommunicationPolicy;
use crate::model::{Countermeasure, InformationSource, Message, Point3, Proposition, Scenario};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged, deny_unknown_fields)]
pub enum PolicyOrigin {
    Point(Point3),
    Anchor { anchor: OriginAnchor },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OriginAnchor {
    ArrivalHall,
}

impl PolicyOrigin {
    fn resolve(&self, surface: &str, origins: &BTreeMap<String, Point3>) -> Point3 {
        match self {
            Self::Point(point) => *point,
            // Missing surfaces remain invalid in the resolved scenario and
            // are reported by ordinary scenario validation for that case.
            Self::Anchor {
                anchor: OriginAnchor::ArrivalHall,
            } => origins
                .get(surface)
                .copied()
                .unwrap_or(Point3::new(0.0, 0.0, 0.0)),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PolicyMessage {
    pub id: String,
    pub source: InformationSource,
    pub surface: String,
    pub origin: PolicyOrigin,
    pub claim: Proposition,
    pub truthful: bool,
    pub at_s: f64,
    pub reach_m: f64,
    pub trust: f64,
    #[serde(default)]
    pub sampling_key: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PolicyCountermeasure {
    pub id: String,
    pub corrects: String,
    pub source: InformationSource,
    pub surface: String,
    pub origin: PolicyOrigin,
    pub at_s: f64,
    pub reach_m: f64,
    pub trust: f64,
    #[serde(default)]
    pub sampling_key: Option<String>,
}

impl CommunicationPolicy {
    pub(super) fn apply(&self, scenario: &mut Scenario, origins: &BTreeMap<String, Point3>) {
        scenario.messages = self
            .messages
            .iter()
            .map(|message| Message {
                id: message.id.clone(),
                source: message.source.clone(),
                surface: message.surface.clone(),
                origin: message.origin.resolve(&message.surface, origins),
                claim: message.claim.clone(),
                truthful: message.truthful,
                at_s: message.at_s,
                reach_m: message.reach_m,
                trust: message.trust,
                sampling_key: message.sampling_key.clone(),
            })
            .collect();
        scenario.countermeasures = self
            .countermeasures
            .iter()
            .map(|message| Countermeasure {
                id: message.id.clone(),
                corrects: message.corrects.clone(),
                source: message.source.clone(),
                surface: message.surface.clone(),
                origin: message.origin.resolve(&message.surface, origins),
                at_s: message.at_s,
                reach_m: message.reach_m,
                trust: message.trust,
                sampling_key: message.sampling_key.clone(),
            })
            .collect();
    }
}
