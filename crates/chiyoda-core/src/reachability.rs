//! Geometric preflight independent of the runtime's visibility-graph planner.
//!
//! A rectangular free-space decomposition establishes connected components on
//! each floor. Eligible directed connectors join those components. This checks
//! authored journeys and gate approaches, ignoring scheduled availability,
//! beliefs, queues, lane assignment, and other agents. It does not predict
//! whether a dynamically simulated journey will finish.

use crate::{
    Scenario, ValidationError,
    model::{AgentGroup, NAVIGATION_CLEARANCE_EPSILON_M, Obstacle, Point3, Surface},
    validate,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, VecDeque};

const MAX_REGION_CELLS: usize = 1_000_000;
const BLOCKED: usize = usize::MAX;
const UNVISITED: usize = usize::MAX - 1;
type Node = (usize, usize);

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UnreachableJourney {
    pub group: String,
    pub ordinal: u32,
    pub stage: String,
    pub target: String,
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReachabilityReport {
    pub checked_spawns: u32,
    pub checked_journeys: u64,
    pub unreachable: Vec<UnreachableJourney>,
}

impl ReachabilityReport {
    #[must_use]
    pub fn is_reachable(&self) -> bool {
        self.unreachable.is_empty()
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ReachabilityError {
    #[error("geometric preflight requires a valid scenario: {0:?}")]
    InvalidScenario(Vec<ValidationError>),
    #[error("surface `{0}` exceeds the geometric preflight decomposition budget")]
    ResourceLimit(String),
    #[error("surface `{0}` has unrepresentable coordinate extents")]
    InvalidExtent(String),
}

/// Check every deterministic spawn, every ordered waypoint leg, and every
/// declared exit candidate. A controlled exit needs a reachable gate with a
/// clear same-surface walk from that gate to the exit.
pub fn check(scenario: &Scenario) -> Result<ReachabilityReport, ReachabilityError> {
    validate(scenario).map_err(ReachabilityError::InvalidScenario)?;
    let mut report = ReachabilityReport::default();
    // Geometry is shared between groups with the same authored radius.
    let mut cache: BTreeMap<u64, Vec<Regions>> = BTreeMap::new();
    for group in &scenario.agents {
        if let std::collections::btree_map::Entry::Vacant(entry) =
            cache.entry(group.radius_m.to_bits())
        {
            let regions = scenario
                .surfaces
                .iter()
                .map(|surface| Regions::new(surface, &scenario.obstacles, group.radius_m))
                .collect::<Result<Vec<_>, _>>()?;
            entry.insert(regions);
        }
        let regions = &cache[&group.radius_m.to_bits()];
        let topology = Topology::new(scenario, group, regions);
        for (ordinal, position) in group.spawn_positions().enumerate() {
            let ordinal = u32::try_from(ordinal).expect("validated group count fits u32");
            report.checked_spawns += 1;
            let mut current = topology.node(&group.surface, position);
            for waypoint_id in &group.via {
                let waypoint = scenario
                    .waypoints
                    .iter()
                    .find(|waypoint| &waypoint.id == waypoint_id)
                    .expect("validated waypoint exists");
                let target = topology.node(&waypoint.surface, waypoint.at);
                record(
                    &mut report,
                    group,
                    ordinal,
                    "waypoint",
                    waypoint_id,
                    topology.reachable(current).contains(&target),
                );
                current = target;
            }
            let reached = topology.reachable(current);
            for exit_id in group.exit_candidates() {
                let exit = scenario
                    .exits
                    .iter()
                    .find(|exit| exit.id == exit_id)
                    .expect("validated exit exists");
                let target = topology.node(&exit.surface, exit.at);
                let gates: Vec<_> = scenario
                    .gates
                    .iter()
                    .filter(|gate| gate.destination == exit_id)
                    .collect();
                let accessible = target.is_some()
                    && if gates.is_empty() {
                        reached.contains(&target)
                    } else {
                        gates.iter().any(|gate| {
                            let gate_node = topology.node(&gate.surface, gate.at);
                            gate_node == target && reached.contains(&gate_node)
                        })
                    };
                record(&mut report, group, ordinal, "exit", exit_id, accessible);
            }
        }
    }
    Ok(report)
}

fn record(
    report: &mut ReachabilityReport,
    group: &AgentGroup,
    ordinal: u32,
    stage: &str,
    target: &str,
    reachable: bool,
) {
    report.checked_journeys += 1;
    if !reachable {
        report.unreachable.push(UnreachableJourney {
            group: group.id.clone(),
            ordinal,
            stage: stage.to_owned(),
            target: target.to_owned(),
        });
    }
}

struct Topology<'a> {
    regions: &'a [Regions],
    edges: BTreeMap<Node, Vec<Node>>,
}

impl<'a> Topology<'a> {
    fn new(scenario: &Scenario, group: &AgentGroup, regions: &'a [Regions]) -> Self {
        let mut topology = Self {
            regions,
            edges: BTreeMap::new(),
        };
        for connector in scenario.connectors.iter().filter(|connector| {
            group.allows_connector(connector) && connector.supports_height(group.height_m)
        }) {
            if let (Some(from), Some(to)) = (
                topology.node(connector.from_surface(), connector.from()),
                topology.node(connector.to_surface(), connector.to()),
            ) {
                topology.edges.entry(from).or_default().push(to);
            }
        }
        topology
    }

    fn node(&self, surface: &str, point: Point3) -> Option<Node> {
        self.regions
            .iter()
            .enumerate()
            .find(|(_, region)| region.surface.id == surface)
            .and_then(|(index, region)| {
                region
                    .component_at(point)
                    .map(|component| (index, component))
            })
    }

    fn reachable(&self, start: Option<Node>) -> BTreeSet<Option<Node>> {
        let Some(start) = start else {
            return BTreeSet::new();
        };
        let mut visited = BTreeSet::from([Some(start)]);
        let mut queue = VecDeque::from([start]);
        while let Some(current) = queue.pop_front() {
            for next in self.edges.get(&current).into_iter().flatten() {
                if visited.insert(Some(*next)) {
                    queue.push_back(*next);
                }
            }
        }
        visited
    }
}

#[derive(Clone, Copy)]
struct Rectangle {
    left: f64,
    right: f64,
    top: f64,
    bottom: f64,
}

impl Rectangle {
    fn contains(self, x: f64, y: f64) -> bool {
        x >= self.left && x <= self.right && y >= self.top && y <= self.bottom
    }
}

struct Regions {
    surface: Surface,
    xs: Vec<f64>,
    ys: Vec<f64>,
    rectangles: Vec<Rectangle>,
    labels: Vec<usize>,
}

impl Regions {
    fn new(
        surface: &Surface,
        obstacles: &[Obstacle],
        radius: f64,
    ) -> Result<Self, ReachabilityError> {
        let left = surface.origin.x_m;
        let right = left + surface.width_m;
        let top = surface.origin.y_m;
        let bottom = top + surface.depth_m;
        if ![left, right, top, bottom]
            .iter()
            .all(|value| value.is_finite())
            || left >= right
            || top >= bottom
        {
            return Err(ReachabilityError::InvalidExtent(surface.id.clone()));
        }
        let expansion = radius + NAVIGATION_CLEARANCE_EPSILON_M;
        let rectangles: Vec<_> = obstacles
            .iter()
            .filter(|obstacle| obstacle.surface == surface.id)
            .map(|obstacle| Rectangle {
                left: obstacle.at.x_m - expansion,
                right: obstacle.at.x_m + obstacle.width_m + expansion,
                top: obstacle.at.y_m - expansion,
                bottom: obstacle.at.y_m + obstacle.depth_m + expansion,
            })
            .collect();
        let mut xs = vec![left, right];
        let mut ys = vec![top, bottom];
        for rectangle in &rectangles {
            xs.extend([
                rectangle.left.clamp(left, right),
                rectangle.right.clamp(left, right),
            ]);
            ys.extend([
                rectangle.top.clamp(top, bottom),
                rectangle.bottom.clamp(top, bottom),
            ]);
        }
        xs.sort_by(f64::total_cmp);
        xs.dedup();
        ys.sort_by(f64::total_cmp);
        ys.dedup();
        let width = xs.len() - 1;
        let height = ys.len() - 1;
        if width
            .checked_mul(height)
            .is_none_or(|count| count > MAX_REGION_CELLS)
        {
            return Err(ReachabilityError::ResourceLimit(surface.id.clone()));
        }
        let mut labels = vec![UNVISITED; width * height];
        for (index, label) in labels.iter_mut().enumerate() {
            let x = f64::midpoint(xs[index % width], xs[index % width + 1]);
            let y = f64::midpoint(ys[index / width], ys[index / width + 1]);
            if rectangles.iter().any(|rectangle| rectangle.contains(x, y)) {
                *label = BLOCKED;
            }
        }
        let mut component = 0;
        for cell in 0..labels.len() {
            if labels[cell] != UNVISITED {
                continue;
            }
            labels[cell] = component;
            let mut queue = VecDeque::from([cell]);
            while let Some(current) = queue.pop_front() {
                let x = current % width;
                let y = current / width;
                for next in [
                    x.checked_sub(1).map(|_| current - 1),
                    (x + 1 < width).then_some(current + 1),
                    y.checked_sub(1).map(|_| current - width),
                    (y + 1 < height).then_some(current + width),
                ]
                .into_iter()
                .flatten()
                {
                    if labels[next] == UNVISITED {
                        labels[next] = component;
                        queue.push_back(next);
                    }
                }
            }
            component += 1;
        }
        Ok(Self {
            surface: surface.clone(),
            xs,
            ys,
            rectangles,
            labels,
        })
    }

    fn component_at(&self, point: Point3) -> Option<usize> {
        if !self.surface.contains(point)
            || self
                .rectangles
                .iter()
                .any(|rectangle| rectangle.contains(point.x_m, point.y_m))
        {
            return None;
        }
        let x = self
            .xs
            .partition_point(|coordinate| *coordinate <= point.x_m)
            .saturating_sub(1)
            .min(self.xs.len() - 2);
        let y = self
            .ys
            .partition_point(|coordinate| *coordinate <= point.y_m)
            .saturating_sub(1)
            .min(self.ys.len() - 2);
        let label = self.labels[y * (self.xs.len() - 1) + x];
        (label != BLOCKED).then_some(label)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decode_scenario;

    fn floor(obstacles: Vec<Obstacle>) -> Scenario {
        let mut scenario =
            decode_scenario(include_str!("../tests/fixtures/json-free-walk.json")).unwrap();
        scenario.name = "preflight".into();
        scenario.duration_s = 30.0;
        scenario.timestep_s = 0.1;
        scenario.surfaces[0].width_m = 10.0;
        scenario.surfaces[0].depth_m = 10.0;
        scenario.exits[0].at = Point3::new(9.0, 5.0, 0.0);
        scenario.agents[0].at = Point3::new(1.0, 5.0, 0.0);
        scenario.agents[0].speed_mps = 1.0;
        scenario.obstacles = obstacles;
        scenario
    }

    fn obstacle(id: &str, x: f64, y: f64, width_m: f64, depth_m: f64) -> Obstacle {
        Obstacle {
            id: id.into(),
            surface: "floor".into(),
            at: Point3::new(x, y, 0.0),
            width_m,
            depth_m,
        }
    }

    #[test]
    fn catches_a_wall_that_surface_topology_validation_accepts() {
        let scenario = floor(vec![obstacle("wall", 4.0, 0.0, 2.0, 10.0)]);
        validate(&scenario).unwrap();
        let report = check(&scenario).unwrap();
        assert_eq!(report.checked_spawns, 1);
        assert_eq!(report.unreachable[0].target, "out");
        assert!(!report.is_reachable());
    }

    #[test]
    fn distinguishes_radius_clear_passages_and_obstacle_detours() {
        assert!(
            check(&floor(vec![obstacle("island", 4.0, 3.0, 2.0, 4.0)]))
                .unwrap()
                .is_reachable()
        );
        let mut scenario = floor(vec![
            obstacle("upper", 4.0, 0.0, 2.0, 4.75),
            obstacle("lower", 4.0, 5.25, 2.0, 4.75),
        ]);
        assert!(!check(&scenario).unwrap().is_reachable());
        scenario.agents[0].radius_m = 0.2;
        assert!(check(&scenario).unwrap().is_reachable());
    }

    #[test]
    fn checks_waypoints_and_gate_approaches_instead_of_just_the_exit() {
        let mut scenario = floor(vec![obstacle("wall", 4.0, 0.0, 2.0, 10.0)]);
        scenario.waypoints.push(crate::model::Waypoint {
            id: "required".into(),
            surface: "floor".into(),
            at: Point3::new(8.0, 2.0, 0.0),
            dwell_s: 0.0,
        });
        scenario.gates.push(crate::model::Gate {
            id: "fare".into(),
            surface: "floor".into(),
            at: Point3::new(8.0, 5.0, 0.0),
            width_m: 1.0,
            service_rate_per_s: 1.0,
            destination: "out".into(),
        });
        scenario.exits[0].at.x_m = 2.0;
        scenario.agents[0].via.push("required".into());
        let report = check(&scenario).unwrap();
        assert_eq!(report.unreachable.len(), 2);
        assert_eq!(report.unreachable[0].stage, "waypoint");
        assert_eq!(report.unreachable[1].stage, "exit");
    }

    #[test]
    fn follows_only_eligible_directed_connectors_and_ignores_schedules() {
        let source = include_str!(
            "fixtures/reachability/follows_only_eligible_directed_connectors_and_ignores_schedules.json"
        );
        let mut scenario = decode_scenario(source).unwrap();
        assert_eq!(check(&scenario).unwrap().checked_journeys, 2);
        assert!(check(&scenario).unwrap().is_reachable());
        scenario.obstacles.push(Obstacle {
            id: "wall".into(),
            surface: "lower".into(),
            at: Point3 {
                x_m: 6.0,
                y_m: 0.0,
                z_m: 0.0,
            },
            width_m: 1.0,
            depth_m: 10.0,
        });
        assert_eq!(check(&scenario).unwrap().unreachable.len(), 2);
    }

    #[test]
    fn handles_boundary_targets_and_rejects_invalid_sources() {
        let mut scenario = floor(vec![]);
        scenario.exits[0].at.x_m = 10.0;
        assert!(check(&scenario).unwrap().is_reachable());
        scenario.agents[0].radius_m = -1.0;
        assert!(matches!(
            check(&scenario),
            Err(ReachabilityError::InvalidScenario(_))
        ));
    }
}
