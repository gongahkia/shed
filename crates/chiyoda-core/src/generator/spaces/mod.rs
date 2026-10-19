//! Rule-constrained space generation with explicit provenance and rejection logs.

mod catalog;
mod layout;
mod source;

use crate::{
    CanonicalScenario, Scenario,
    bundle::canonical_hash,
    format_scenario,
    model::ConnectorKind,
    parse,
    reachability::{self, ReachabilityReport},
    validate,
    wfc::{self, ForbiddenNeighbor, SolveError, SolveStats},
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

pub use catalog::{Module, ModuleKind, modules_for};
pub use layout::FloorLayout;

pub const GENERATOR_VERSION: &str = "2";

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Preset {
    #[default]
    Concourse,
    Corridors,
    Bottleneck,
}

impl Preset {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Concourse => "concourse",
            Self::Corridors => "corridors",
            Self::Bottleneck => "bottleneck",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FloorConstraint {
    pub floor: u32,
    pub x: u32,
    pub y: u32,
    pub allowed_tiles: Vec<String>,
}

/// A complete experiment input. Omitted JSON fields take these versioned
/// defaults; unknown fields are rejected so misspelled rules cannot be ignored.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct SpaceConfig {
    pub preset: Preset,
    pub width: u32,
    pub height: u32,
    pub floors: u32,
    pub tile_size_m: f64,
    pub passage_width_m: f64,
    pub agents_per_floor: u32,
    pub agent_radius_m: f64,
    pub speed_mps: f64,
    pub duration_s: f64,
    pub timestep_s: f64,
    pub connector_kinds: Vec<ConnectorKind>,
    pub perturbations: bool,
    pub require_connected: bool,
    pub max_attempts: u32,
    pub max_backtracks: u32,
    pub max_decisions: u32,
    /// Empty selects the preset catalog. Otherwise this replaces that catalog.
    pub modules: Vec<Module>,
    pub forbidden_neighbors: Vec<ForbiddenNeighbor>,
    pub pins: Vec<FloorConstraint>,
}

impl Default for SpaceConfig {
    fn default() -> Self {
        Self {
            preset: Preset::Concourse,
            width: 5,
            height: 4,
            floors: 2,
            tile_size_m: 6.0,
            passage_width_m: 2.0,
            agents_per_floor: 4,
            agent_radius_m: 0.3,
            speed_mps: 1.2,
            duration_s: 90.0,
            timestep_s: 0.2,
            connector_kinds: vec![ConnectorKind::Stair, ConnectorKind::Lift],
            perturbations: true,
            require_connected: true,
            max_attempts: 64,
            max_backtracks: 10_000,
            max_decisions: 100_000,
            modules: vec![],
            forbidden_neighbors: vec![],
            pins: vec![],
        }
    }
}

impl SpaceConfig {
    pub fn validate(&self) -> Result<(), SpaceGenerationError> {
        if !(4..=16).contains(&self.width)
            || !(4..=16).contains(&self.height)
            || !(1..=4).contains(&self.floors)
        {
            return Err(config_error("width/height must be 4..=16 and floors 1..=4"));
        }
        if !(1..=64).contains(&self.agents_per_floor)
            || !(1..=128).contains(&self.max_attempts)
            || self.max_decisions == 0
        {
            return Err(config_error(
                "agents_per_floor must be 1..=64, max_attempts 1..=128, and max_decisions positive",
            ));
        }
        if ![
            self.tile_size_m,
            self.passage_width_m,
            self.agent_radius_m,
            self.speed_mps,
            self.duration_s,
            self.timestep_s,
        ]
        .iter()
        .all(|value| value.is_finite() && *value > 0.0)
        {
            return Err(config_error(
                "physical quantities must be finite and positive",
            ));
        }
        if self.tile_size_m > 100.0
            || self.duration_s / self.timestep_s > 100_000.0
            || self.timestep_s > self.duration_s
        {
            return Err(config_error(
                "tile size must be <=100m and timing must specify 1..=100000 integration steps",
            ));
        }
        let margin = (self.tile_size_m - self.passage_width_m) / 2.0;
        let wall = self.tile_size_m * 0.04;
        if self.passage_width_m <= 2.0 * self.agent_radius_m + 0.001
            || margin <= wall + 2.0 * self.agent_radius_m + 0.001
        {
            return Err(config_error(
                "passages and kiosk bypasses must clear the authored agent diameter",
            ));
        }
        let columns = (1..=self.agents_per_floor)
            .find(|columns| columns * columns >= self.agents_per_floor)
            .expect("positive bounded count");
        let spawn_span = f64::from(columns - 1) * self.agent_radius_m * 2.1;
        if spawn_span + 2.0 * (wall + self.agent_radius_m + 0.001) >= self.tile_size_m {
            return Err(config_error(
                "the full deterministic spawn grid must fit inside its reserved hall",
            ));
        }
        if self.connector_kinds.is_empty()
            || self
                .connector_kinds
                .iter()
                .enumerate()
                .any(|(i, kind)| self.connector_kinds[..i].contains(kind))
        {
            return Err(config_error(
                "connector_kinds must be nonempty and contain no duplicates",
            ));
        }
        let catalog = self.catalog();
        for module in &catalog {
            if module.kind == ModuleKind::Solid && module.openings.iter().any(|open| *open) {
                return Err(config_error("solid modules cannot have passage openings"));
            }
        }
        self.rules(&catalog)
            .validate()
            .map_err(|error| config_error(error.to_string()))?;
        for pin in &self.pins {
            if pin.floor >= self.floors
                || pin.x >= self.width
                || pin.y >= self.height
                || pin.allowed_tiles.is_empty()
                || pin
                    .allowed_tiles
                    .iter()
                    .any(|id| !catalog.iter().any(|module| &module.id == id))
            {
                return Err(config_error(
                    "pins must reference in-bounds cells and known nonempty tile choices",
                ));
            }
        }
        Ok(())
    }

    #[must_use]
    pub fn catalog(&self) -> Vec<Module> {
        if self.modules.is_empty() {
            modules_for(self.preset)
        } else {
            self.modules.clone()
        }
    }

    fn rules(&self, catalog: &[Module]) -> wfc::RuleSet {
        wfc::RuleSet {
            tiles: catalog.iter().map(Module::tile).collect(),
            forbidden_neighbors: self.forbidden_neighbors.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FloorSearch {
    pub floor: u32,
    pub seed: u64,
    pub stats: SolveStats,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RejectionKind {
    Contradiction,
    SearchLimit,
    DisconnectedLayout,
    InvalidGeometry,
    UnreachableJourney,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Rejection {
    pub kind: RejectionKind,
    pub detail: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GenerationAttempt {
    pub attempt: u32,
    pub floor_searches: Vec<FloorSearch>,
    pub rejection: Option<Rejection>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GenerationManifest {
    pub generator_version: String,
    pub solver_version: String,
    pub runtime_version: String,
    pub seed: u64,
    pub config: SpaceConfig,
    pub config_hash: String,
    pub catalog: Vec<Module>,
    pub rules_hash: String,
    pub source_sha256: String,
    pub scenario_hash: String,
    pub attempts: Vec<GenerationAttempt>,
    pub floors: Vec<FloorLayout>,
    pub tile_counts: BTreeMap<String, u32>,
    pub reachability: ReachabilityReport,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GeneratedSpace {
    pub source: String,
    pub scenario: Scenario,
    pub manifest: GenerationManifest,
}

#[derive(Debug, thiserror::Error)]
pub enum SpaceGenerationError {
    #[error("invalid space-generation config: {0}")]
    InvalidConfig(String),
    #[error("space generation exhausted {} attempts", .attempts.len())]
    Exhausted { attempts: Vec<GenerationAttempt> },
}

/// Generate an ordinary scenario only after local rules, optional whole-layout
/// connectivity, source validation, and independent geometric preflight pass.
pub fn generate(seed: u64, config: &SpaceConfig) -> Result<GeneratedSpace, SpaceGenerationError> {
    config.validate()?;
    let catalog = config.catalog();
    let rules = config.rules(&catalog);
    let mut random = wfc::Random::new(seed);
    let mut attempts = Vec::new();
    let mut repair_targets = vec![BTreeSet::new(); config.floors as usize];
    for attempt in 1..=config.max_attempts {
        let mut record = GenerationAttempt {
            attempt,
            floor_searches: vec![],
            rejection: None,
        };
        let mut floors = Vec::new();
        for floor in 0..config.floors {
            let floor_seed = random.next();
            match layout::generate_floor(
                config,
                &catalog,
                &rules,
                floor,
                floor_seed,
                &repair_targets[floor as usize],
            ) {
                Ok(layout) => {
                    record.floor_searches.push(FloorSearch {
                        floor,
                        seed: floor_seed,
                        stats: layout.solution.stats.clone(),
                    });
                    let disconnected =
                        layout::disconnected_cells(&layout, &catalog, layout::spawn(config));
                    if config.require_connected && !disconnected.is_empty() {
                        let isolated_count = disconnected.len();
                        repair_targets[floor as usize].extend(disconnected);
                        record.rejection = Some(Rejection {
                            kind: RejectionKind::DisconnectedLayout,
                            detail: format!(
                                "floor {floor} contains {isolated_count} isolated walkable modules; reserve connecting paths next attempt"
                            ),
                        });
                        break;
                    }
                    floors.push(layout);
                }
                Err(SolveError::InvalidInput(detail)) => return Err(config_error(detail)),
                Err(error) => {
                    let (kind, stats) = match error {
                        SolveError::Contradiction(stats) => (RejectionKind::Contradiction, stats),
                        SolveError::SearchLimit(stats) => (RejectionKind::SearchLimit, stats),
                        SolveError::InvalidInput(_) => unreachable!(),
                    };
                    record.floor_searches.push(FloorSearch {
                        floor,
                        seed: floor_seed,
                        stats,
                    });
                    record.rejection = Some(Rejection {
                        kind,
                        detail: format!("floor {floor} could not be solved"),
                    });
                    break;
                }
            }
        }
        if record.rejection.is_some() {
            attempts.push(record);
            continue;
        }
        match finish(seed, config, &catalog, &floors) {
            Ok(finished) => {
                attempts.push(record);
                return Ok(assemble(seed, config, &catalog, floors, attempts, finished));
            }
            Err(rejection) => {
                record.rejection = Some(rejection);
                attempts.push(record);
            }
        }
    }
    Err(SpaceGenerationError::Exhausted { attempts })
}

fn assemble(
    seed: u64,
    config: &SpaceConfig,
    catalog: &[Module],
    floors: Vec<FloorLayout>,
    attempts: Vec<GenerationAttempt>,
    finished: (String, Scenario, ReachabilityReport),
) -> GeneratedSpace {
    let (source, scenario, reachability) = finished;
    let mut tile_counts = catalog
        .iter()
        .map(|module| (module.id.clone(), 0))
        .collect::<BTreeMap<_, _>>();
    for floor in &floors {
        for id in &floor.solution.cells {
            *tile_counts.get_mut(id).expect("solved tile exists") += 1;
        }
    }
    let manifest = GenerationManifest {
        generator_version: GENERATOR_VERSION.into(),
        solver_version: wfc::SOLVER_VERSION.into(),
        runtime_version: crate::RUNTIME_VERSION.into(),
        seed,
        config: config.clone(),
        config_hash: canonical_hash(config),
        catalog: catalog.to_vec(),
        rules_hash: canonical_hash(&config.rules(catalog)),
        source_sha256: format!("{:x}", Sha256::digest(source.as_bytes())),
        scenario_hash: canonical_hash(&CanonicalScenario::from(scenario.clone())),
        attempts,
        floors,
        tile_counts,
        reachability,
    };
    GeneratedSpace {
        source,
        scenario,
        manifest,
    }
}

fn finish(
    seed: u64,
    config: &SpaceConfig,
    catalog: &[Module],
    floors: &[FloorLayout],
) -> Result<(String, Scenario, ReachabilityReport), Rejection> {
    let raw = source::compile(seed, config, catalog, floors);
    let scenario = parse(&raw).map_err(|error| Rejection {
        kind: RejectionKind::InvalidGeometry,
        detail: error.to_string(),
    })?;
    validate(&scenario).map_err(|errors| Rejection {
        kind: RejectionKind::InvalidGeometry,
        detail: format!("{errors:?}"),
    })?;
    let report = reachability::check(&scenario).map_err(|error| Rejection {
        kind: RejectionKind::InvalidGeometry,
        detail: error.to_string(),
    })?;
    if !report.is_reachable() {
        return Err(Rejection {
            kind: RejectionKind::UnreachableJourney,
            detail: format!("{:?}", report.unreachable),
        });
    }
    Ok((format_scenario(&scenario), scenario, report))
}

fn config_error(message: impl Into<String>) -> SpaceGenerationError {
    SpaceGenerationError::InvalidConfig(message.into())
}
