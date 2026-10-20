use super::STUDY_VERSION;
use crate::{
    evaluation::EvaluationOptions,
    generator::spaces::{FloorConstraint, Module, Preset, SpaceConfig},
    model::{
        ConnectorCapacityChange, ConnectorKind, ConnectorStateChange, Countermeasure,
        ExitCapacityChange, ExitStateChange, GateCapacityChange, GateStateChange, Message,
        Scenario,
    },
    wfc::ForbiddenNeighbor,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StudyConfig {
    pub study_version: String,
    pub name: String,
    #[serde(default)]
    pub environment: Environment,
    pub patterns: Vec<Pattern>,
    pub scales: Vec<Scale>,
    pub loads: Vec<Load>,
    pub policies: Vec<CommunicationPolicy>,
    pub seeds: Vec<u64>,
    #[serde(default)]
    pub operations: Operations,
    #[serde(default)]
    pub evaluation: EvaluationOptions,
}

/// Common physical and numerical assumptions. Study axes are separate fields,
/// so a generator default cannot silently override a load or a scale.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Environment {
    pub tile_size_m: f64,
    pub passage_width_m: f64,
    pub agent_radius_m: f64,
    pub speed_mps: f64,
    pub duration_s: f64,
    pub timestep_s: f64,
    pub connector_kinds: Vec<ConnectorKind>,
    pub max_attempts: u32,
    pub max_backtracks: u32,
    pub max_decisions: u32,
    pub services: Services,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Services {
    pub exit_capacity_per_s: f64,
    pub gate_service_rate_per_s: f64,
    pub connector_capacity_per_s: f64,
    pub lift_capacity: u32,
    pub lift_cycle_s: f64,
}

impl Default for Services {
    fn default() -> Self {
        Self {
            exit_capacity_per_s: 1.5,
            gate_service_rate_per_s: 1.0,
            connector_capacity_per_s: 1.0,
            lift_capacity: 4,
            lift_cycle_s: 6.0,
        }
    }
}

impl Default for Environment {
    fn default() -> Self {
        let config = SpaceConfig::default();
        Self {
            tile_size_m: config.tile_size_m,
            passage_width_m: config.passage_width_m,
            agent_radius_m: config.agent_radius_m,
            speed_mps: config.speed_mps,
            duration_s: config.duration_s,
            timestep_s: config.timestep_s,
            connector_kinds: config.connector_kinds,
            max_attempts: config.max_attempts,
            max_backtracks: config.max_backtracks,
            max_decisions: config.max_decisions,
            services: Services::default(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Pattern {
    pub id: String,
    #[serde(default)]
    pub preset: Preset,
    #[serde(default)]
    pub modules: Vec<Module>,
    #[serde(default)]
    pub forbidden_neighbors: Vec<ForbiddenNeighbor>,
    #[serde(default)]
    pub pins: Vec<FloorConstraint>,
    #[serde(default)]
    pub passage_width_m: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Scale {
    pub id: String,
    pub width: u32,
    pub height: u32,
    pub floors: u32,
    /// Multiplies discharge/service rates; divides lift cycle time. Resource
    /// counts, passage widths, and cabin passenger counts remain explicit.
    pub service_rate_multiplier: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Load {
    pub id: String,
    pub population: Population,
    #[serde(default)]
    pub placement: Placement,
    /// Cadence per floor, shared across policies. None releases everyone at 0.
    #[serde(default)]
    pub release_interval_s: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum Population {
    FixedPerFloor {
        agents: u32,
    },
    Density {
        agents_per_m2: f64,
        basis: AreaBasis,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AreaBasis {
    Gross,
    Walkable,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Placement {
    #[default]
    Distributed,
    ArrivalHall,
}

/// Uses the existing scenario message/correction model. Identical sampling keys
/// deliberately share trust draws across policies; policy IDs never alter RNG.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CommunicationPolicy {
    pub id: String,
    #[serde(default)]
    pub messages: Vec<Message>,
    #[serde(default)]
    pub countermeasures: Vec<Countermeasure>,
}

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Operations {
    pub exit_states: Vec<ExitStateChange>,
    pub gate_states: Vec<GateStateChange>,
    pub connector_states: Vec<ConnectorStateChange>,
    pub exit_capacity_states: Vec<ExitCapacityChange>,
    pub gate_capacity_states: Vec<GateCapacityChange>,
    pub connector_capacity_states: Vec<ConnectorCapacityChange>,
}

impl Operations {
    pub fn apply(&self, scenario: &mut Scenario, multiplier: f64) {
        scenario.exit_states.clone_from(&self.exit_states);
        scenario.gate_states.clone_from(&self.gate_states);
        scenario.connector_states.clone_from(&self.connector_states);
        scenario
            .exit_capacity_states
            .clone_from(&self.exit_capacity_states);
        scenario
            .gate_capacity_states
            .clone_from(&self.gate_capacity_states);
        scenario
            .connector_capacity_states
            .clone_from(&self.connector_capacity_states);
        for change in &mut scenario.exit_capacity_states {
            change.capacity_per_s *= multiplier;
        }
        for change in &mut scenario.gate_capacity_states {
            change.capacity_per_s *= multiplier;
        }
        for change in &mut scenario.connector_capacity_states {
            change.capacity_per_s *= multiplier;
        }
    }
}

impl StudyConfig {
    #[must_use]
    pub fn generation(&self, pattern: &Pattern, scale: &Scale) -> SpaceConfig {
        let env = &self.environment;
        SpaceConfig {
            preset: pattern.preset,
            width: scale.width,
            height: scale.height,
            floors: scale.floors,
            tile_size_m: env.tile_size_m,
            passage_width_m: pattern.passage_width_m.unwrap_or(env.passage_width_m),
            agents_per_floor: 1,
            agent_radius_m: env.agent_radius_m,
            speed_mps: env.speed_mps,
            duration_s: env.duration_s,
            timestep_s: env.timestep_s,
            connector_kinds: env.connector_kinds.clone(),
            perturbations: false,
            require_connected: true,
            max_attempts: env.max_attempts,
            max_backtracks: env.max_backtracks,
            max_decisions: env.max_decisions,
            modules: pattern.modules.clone(),
            forbidden_neighbors: pattern.forbidden_neighbors.clone(),
            pins: pattern.pins.clone(),
        }
    }

    #[must_use]
    pub fn case_count(&self) -> Option<usize> {
        [
            self.patterns.len(),
            self.scales.len(),
            self.seeds.len(),
            self.loads.len(),
            self.policies.len(),
        ]
        .into_iter()
        .try_fold(1usize, usize::checked_mul)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.study_version != STUDY_VERSION || self.name.trim().is_empty() {
            return Err("study_version must be 1 and name must be nonempty".into());
        }
        unique(
            "patterns",
            self.patterns.iter().map(|item| item.id.as_str()),
        )?;
        unique("scales", self.scales.iter().map(|item| item.id.as_str()))?;
        unique("loads", self.loads.iter().map(|item| item.id.as_str()))?;
        unique(
            "policies",
            self.policies.iter().map(|item| item.id.as_str()),
        )?;
        if self.seeds.is_empty()
            || self.seeds.iter().collect::<BTreeSet<_>>().len() != self.seeds.len()
            || self.case_count().is_none_or(|count| count > 4096)
        {
            return Err(
                "seeds must be nonempty and unique; at most 4096 cases are supported".into(),
            );
        }
        self.evaluation.thresholds.validate()?;
        let services = &self.environment.services;
        for value in [
            services.exit_capacity_per_s,
            services.gate_service_rate_per_s,
            services.connector_capacity_per_s,
            services.lift_cycle_s,
        ] {
            positive(value, "service rates and cycle times")?;
        }
        if !(1..=1024).contains(&services.lift_capacity) {
            return Err("lift_capacity must be 1..=1024".into());
        }
        for scale in &self.scales {
            positive(scale.service_rate_multiplier, "service_rate_multiplier")?;
            for value in [
                services.exit_capacity_per_s * scale.service_rate_multiplier,
                services.gate_service_rate_per_s * scale.service_rate_multiplier,
                services.connector_capacity_per_s * scale.service_rate_multiplier,
                services.lift_cycle_s / scale.service_rate_multiplier,
            ] {
                positive(value, "resolved service quantities")?;
            }
            for pattern in &self.patterns {
                self.generation(pattern, scale)
                    .validate()
                    .map_err(|error| format!("{}/{}: {error}", pattern.id, scale.id))?;
            }
        }
        for load in &self.loads {
            match load.population {
                Population::FixedPerFloor { agents } if !(1..=1024).contains(&agents) => {
                    return Err("fixed population must be 1..=1024 agents per floor".into());
                }
                Population::Density { agents_per_m2, .. } => {
                    positive(agents_per_m2, "agents_per_m2")?;
                }
                Population::FixedPerFloor { .. } => {}
            }
            if let Some(interval) = load.release_interval_s {
                positive(interval, "release_interval_s")?;
            }
        }
        // Reject NaN/Infinity in programmatically constructed documents as well
        // as ordinary malformed JSON. serde_json otherwise serializes them null.
        let encoded = serde_json::to_string(self).map_err(|error| error.to_string())?;
        if encoded.len() > 4 * 1024 * 1024 {
            return Err("study config exceeds 4 MiB".into());
        }
        serde_json::from_str::<Self>(&encoded).map_err(|error| error.to_string())?;
        Ok(())
    }
}

fn positive(value: f64, name: &str) -> Result<(), String> {
    if value.is_finite() && value > 0.0 {
        Ok(())
    } else {
        Err(format!("{name} must be finite and positive"))
    }
}

fn unique<'a>(axis: &str, ids: impl Iterator<Item = &'a str>) -> Result<(), String> {
    let mut seen = BTreeSet::new();
    for id in ids {
        if id.is_empty()
            || id.len() > 80
            || !id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || b"_-".contains(&byte))
            || !seen.insert(id)
        {
            return Err(format!(
                "{axis} IDs must be unique, 1..=80 ASCII letters, digits, underscores, or hyphens"
            ));
        }
    }
    if seen.is_empty() {
        Err(format!("{axis} must not be empty"))
    } else {
        Ok(())
    }
}
