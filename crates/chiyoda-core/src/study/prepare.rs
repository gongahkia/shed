use super::{AreaBasis, CommunicationPolicy, Load, Placement, Population, Scale, StudyConfig};
use crate::{
    CanonicalScenario, Scenario,
    bundle::canonical_hash,
    generator::spaces::{GeneratedSpace, GenerationAttempt},
    integration_step_count,
    model::{AgentGroup, Connector, NAVIGATION_CLEARANCE_EPSILON_M, Point3, Surface},
    validate,
    wfc::Random,
};
use serde::{Deserialize, Serialize};

const MAX_CANDIDATES_PER_FLOOR: u32 = 200_000;
const MAX_AGENT_STEPS: u64 = 2_000_000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StudyFailure {
    pub stage: String,
    pub detail: String,
    pub generation_attempts: Vec<GenerationAttempt>,
}

impl StudyFailure {
    pub(super) fn new(stage: &str, detail: impl Into<String>) -> Self {
        Self {
            stage: stage.into(),
            detail: detail.into(),
            generation_attempts: vec![],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FloorPopulation {
    pub surface: String,
    pub gross_area_m2: f64,
    /// Rectangle area minus generated wall area, before radius expansion.
    pub walkable_area_m2: f64,
    pub people: u32,
    pub gross_density_per_m2: f64,
    pub walkable_density_per_m2: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StudyContext {
    /// Surfaces, obstacles, exits, gates, and connectors, including service limits.
    pub physical_input_hash: String,
    pub population_hash: String,
    pub operations_hash: String,
    /// Entire scenario before communication is applied. Policies share this hash.
    pub controlled_input_hash: String,
    pub floors: Vec<FloorPopulation>,
    pub total_people: u32,
    pub service_rate_multiplier: f64,
    /// Mean straight-line distance to the closest exit, including elevation.
    /// This is a geometric lower bound, not an obstacle-aware route or delay.
    pub mean_direct_exit_distance_m: f64,
    pub mean_release_time_s: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PreparedInput {
    pub scenario: Scenario,
    pub context: StudyContext,
    arrival_origins: std::collections::BTreeMap<String, Point3>,
}

impl PreparedInput {
    #[must_use]
    pub fn with_policy(&self, policy: &CommunicationPolicy) -> Scenario {
        let mut scenario = self.scenario.clone();
        policy.apply(&mut scenario, &self.arrival_origins);
        scenario
    }
    /// Replace the generator's placeholder population and services without
    /// resampling geometry. A setup failure never triggers a new layout search.
    pub fn from_space(
        config: &StudyConfig,
        space: &GeneratedSpace,
        scale: &Scale,
        load: &Load,
    ) -> Result<Self, StudyFailure> {
        config
            .validate()
            .map_err(|error| StudyFailure::new("configuration", error))?;
        if !config.scales.contains(scale)
            || !config.loads.contains(load)
            || !config
                .patterns
                .iter()
                .any(|pattern| config.generation(pattern, scale) == space.manifest.config)
            || canonical_hash(&CanonicalScenario::from(space.scenario.clone()))
                != space.manifest.scenario_hash
        {
            return Err(StudyFailure::new(
                "configuration",
                "space, scale, and load must belong to this study",
            ));
        }
        let mut scenario = space.scenario.clone();
        services(&mut scenario, config, scale);
        config
            .operations
            .apply(&mut scenario, scale.service_rate_multiplier);
        let floors = areas(&scenario, load)?;
        let total_people = floors.iter().map(|floor| floor.people).sum::<u32>();
        let steps = integration_step_count(scenario.duration_s, scenario.timestep_s);
        if steps
            .checked_mul(u64::from(total_people))
            .is_none_or(|steps| steps > MAX_AGENT_STEPS)
        {
            return Err(StudyFailure::new(
                "resource_limit",
                "case exceeds 2000000 agent integration steps; reduce population or duration, or increase timestep",
            ));
        }
        scenario.agents = population_groups(&scenario, &floors, load)?;
        validate(&scenario)
            .map_err(|errors| StudyFailure::new("setup_validation", format!("{errors:?}")))?;
        let mean_direct_exit_distance_m = scenario
            .agents
            .iter()
            .map(|group| {
                scenario
                    .exits
                    .iter()
                    .map(|exit| group.at.distance(exit.at))
                    .fold(f64::INFINITY, f64::min)
            })
            .sum::<f64>()
            / f64::from(total_people);
        let mean_release_time_s = scenario
            .agents
            .iter()
            .map(|group| group.release_at_s)
            .sum::<f64>()
            / f64::from(total_people);
        let context = StudyContext {
            physical_input_hash: canonical_hash(&(
                &scenario.surfaces,
                &scenario.obstacles,
                &scenario.exits,
                &scenario.gates,
                &scenario.connectors,
            )),
            population_hash: canonical_hash(&scenario.agents),
            operations_hash: canonical_hash(&(
                &scenario.exit_states,
                &scenario.gate_states,
                &scenario.connector_states,
                &scenario.exit_capacity_states,
                &scenario.gate_capacity_states,
                &scenario.connector_capacity_states,
            )),
            controlled_input_hash: canonical_hash(&CanonicalScenario::from(scenario.clone())),
            floors,
            total_people,
            service_rate_multiplier: scale.service_rate_multiplier,
            mean_direct_exit_distance_m,
            mean_release_time_s,
        };
        let arrival_origins = space
            .scenario
            .agents
            .iter()
            .map(|group| (group.surface.clone(), group.at))
            .collect();
        Ok(Self {
            scenario,
            context,
            arrival_origins,
        })
    }
}

fn services(scenario: &mut Scenario, config: &StudyConfig, scale: &Scale) {
    let services = &config.environment.services;
    let multiplier = scale.service_rate_multiplier;
    for exit in &mut scenario.exits {
        exit.capacity_per_s = Some(services.exit_capacity_per_s * multiplier);
    }
    for gate in &mut scenario.gates {
        gate.service_rate_per_s = services.gate_service_rate_per_s * multiplier;
    }
    for connector in &mut scenario.connectors {
        match connector {
            Connector::Stair { capacity_per_s, .. }
            | Connector::Ramp { capacity_per_s, .. }
            | Connector::Escalator { capacity_per_s, .. } => {
                *capacity_per_s = Some(services.connector_capacity_per_s * multiplier);
            }
            Connector::Lift {
                capacity, cycle_s, ..
            } => {
                *capacity = services.lift_capacity;
                *cycle_s = services.lift_cycle_s / multiplier;
            }
        }
    }
}

fn areas(scenario: &Scenario, load: &Load) -> Result<Vec<FloorPopulation>, StudyFailure> {
    scenario
        .surfaces
        .iter()
        .map(|surface| {
            let gross_area_m2 = surface.width_m * surface.depth_m;
            // Generated walls form a disjoint rectangular partition. This formula
            // must not be generalized to arbitrary authored, overlapping obstacles.
            let walkable_area_m2 = gross_area_m2
                - scenario
                    .obstacles
                    .iter()
                    .filter(|wall| wall.surface == surface.id)
                    .map(|wall| wall.width_m * wall.depth_m)
                    .sum::<f64>();
            let people = match load.population {
                Population::FixedPerFloor { agents } => agents,
                Population::Density {
                    agents_per_m2,
                    basis,
                } => {
                    let area = match basis {
                        AreaBasis::Gross => gross_area_m2,
                        AreaBasis::Walkable => walkable_area_m2,
                    };
                    bounded_integer(
                        (area * agents_per_m2).ceil(),
                        1024,
                        "density resolves outside 1..=1024 people per floor",
                    )?
                }
            };
            Ok(FloorPopulation {
                surface: surface.id.clone(),
                gross_area_m2,
                walkable_area_m2,
                people,
                gross_density_per_m2: f64::from(people) / gross_area_m2,
                walkable_density_per_m2: f64::from(people) / walkable_area_m2,
            })
        })
        .collect()
}

#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn bounded_integer(value: f64, maximum: u32, message: &str) -> Result<u32, StudyFailure> {
    if value.is_finite() && value >= 1.0 && value <= f64::from(maximum) {
        Ok(value as u32)
    } else {
        Err(StudyFailure::new("population", message))
    }
}

fn distributed(
    scenario: &Scenario,
    surface: &Surface,
    template: &AgentGroup,
    count: u32,
    floor: usize,
) -> Result<Vec<Point3>, StudyFailure> {
    let margin = template.radius_m + NAVIGATION_CLEARANCE_EPSILON_M * 2.0;
    let spacing = template.radius_m * 2.2;
    let dimension = |extent: f64| {
        bounded_integer(
            ((extent - 2.0 * margin) / spacing).floor() + 1.0,
            MAX_CANDIDATES_PER_FLOOR,
            "population placement lattice exceeds its resource limit",
        )
    };
    let columns = dimension(surface.width_m)?;
    let rows = dimension(surface.depth_m)?;
    let cells = columns
        .checked_mul(rows)
        .filter(|cells| *cells <= MAX_CANDIDATES_PER_FLOOR)
        .ok_or_else(|| {
            StudyFailure::new(
                "resource_limit",
                "population placement exceeds 200000 candidate points per floor",
            )
        })?;
    let mut order: Vec<_> = (0..cells).collect();
    let mut random = Random::new(
        scenario.seed ^ 0x7374_7564_795f_706f ^ u64::try_from(floor).expect("bounded floors"),
    );
    for index in (1..order.len()).rev() {
        let other = usize::try_from(
            random.below(u64::try_from(index + 1).expect("bounded candidate count")),
        )
        .expect("bounded random index");
        order.swap(index, other);
    }
    let walls: Vec<_> = scenario
        .obstacles
        .iter()
        .filter(|wall| wall.surface == surface.id)
        .collect();
    let mut positions = Vec::new();
    for cell in order {
        let point = Point3::new(
            surface.origin.x_m + margin + f64::from(cell % columns) * spacing,
            surface.origin.y_m + margin + f64::from(cell / columns) * spacing,
            surface.origin.z_m,
        );
        if walls
            .iter()
            .all(|wall| !wall.contains_with_clearance(point, margin))
        {
            positions.push(point);
            if positions.len() == count as usize {
                return Ok(positions);
            }
        }
    }
    Err(StudyFailure::new(
        "population",
        format!(
            "only {} nonoverlapping clear spawn points are available for {count} people on {}",
            positions.len(),
            surface.id
        ),
    ))
}

fn population_groups(
    scenario: &Scenario,
    floors: &[FloorPopulation],
    load: &Load,
) -> Result<Vec<AgentGroup>, StudyFailure> {
    let mut groups = Vec::new();
    for (floor, template) in scenario.agents.iter().enumerate() {
        let count = floors[floor].people;
        if load.release_interval_s.unwrap_or(0.0) * f64::from(count - 1) > scenario.duration_s {
            return Err(StudyFailure::new(
                "population",
                "release cadence extends past the scenario duration",
            ));
        }
        let positions = match load.placement {
            Placement::Distributed => {
                distributed(scenario, &scenario.surfaces[floor], template, count, floor)?
            }
            Placement::ArrivalHall => {
                let mut grid = template.clone();
                grid.count = count;
                grid.spawn_positions().collect()
            }
        };
        for (ordinal, at) in positions.into_iter().enumerate() {
            let mut group = template.clone();
            group.id = format!("study_{floor}_{ordinal}");
            group.count = 1;
            group.knowledge.clone_from(&load.knowledge);
            group.at = at;
            group.release_at_s = load.release_interval_s.unwrap_or(0.0)
                * f64::from(u32::try_from(ordinal).expect("bounded population"));
            groups.push(group);
        }
    }
    Ok(groups)
}
