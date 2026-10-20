use super::{FloorLayout, Module, SpaceConfig, layout};
use crate::{
    Scenario,
    model::{
        AgentGroup, Connector, ConnectorKind, Countermeasure, Exit, ExitStateChange, Gate,
        InformationSource, Message, Obstacle, Point3, Proposition, Surface,
    },
};

pub(super) fn build(
    seed: u64,
    config: &SpaceConfig,
    catalog: &[Module],
    floors: &[FloorLayout],
) -> Scenario {
    let mut scenario = Scenario::new(
        format!("wfc-{}-{seed}", config.preset.as_str()),
        seed,
        config.duration_s,
        config.timestep_s,
    );
    for floor in floors {
        scenario.surfaces.push(Surface {
            id: format!("floor_{}", floor.floor),
            origin: Point3::new(0.0, 0.0, f64::from(floor.floor * 3)),
            width_m: f64::from(config.width) * config.tile_size_m,
            depth_m: f64::from(config.height) * config.tile_size_m,
        });
        compile_walls(&mut scenario, config, catalog, floor);
    }
    let [primary, secondary] = layout::exits(config).map(|cell| center(config, cell));
    for (id, (x, y)) in [("primary", primary), ("alternative", secondary)] {
        scenario.exits.push(Exit {
            id: id.into(),
            surface: "floor_0".into(),
            at: Point3::new(x, y, 0.0),
            width_m: config.passage_width_m,
            capacity_per_s: Some(1.5),
        });
    }
    scenario.gates.push(Gate {
        id: "primary_gate".into(),
        surface: "floor_0".into(),
        at: Point3::new(primary.0 - config.tile_size_m * 0.1, primary.1, 0.0),
        width_m: config.passage_width_m,
        service_rate_per_s: 1.0,
        destination: "primary".into(),
    });
    for floor in 1..config.floors {
        for (portal, position) in layout::portals(config)
            .map(|cell| center(config, cell))
            .iter()
            .enumerate()
        {
            let kind = config.connector_kinds
                [(2 * (floor as usize - 1) + portal) % config.connector_kinds.len()];
            scenario
                .connectors
                .push(connector(config, floor, portal, kind, *position));
        }
    }
    if config.perturbations {
        perturbations(&mut scenario, config);
    }
    let spawn = layout::spawn(config);
    let inset = config.tile_size_m * 0.04 + config.agent_radius_m + 0.001;
    for floor in 0..config.floors {
        scenario.agents.push(AgentGroup {
            knowledge: None,
            id: format!("arrivals_{floor}"),
            count: config.agents_per_floor,
            surface: format!("floor_{floor}"),
            at: Point3::new(
                f64::from(spawn.0) * config.tile_size_m + inset,
                f64::from(spawn.1) * config.tile_size_m + inset,
                f64::from(floor * 3),
            ),
            destination: "primary".into(),
            alternative_destinations: vec!["alternative".into()],
            speed_mps: config.speed_mps,
            radius_m: config.agent_radius_m,
            height_m: 1.7,
            release_at_s: 0.0,
            release_interval_s: None,
            release_batch_size: None,
            via: vec![],
            excluded_connector_kinds: vec![],
        });
    }
    scenario
}

pub(super) fn center(config: &SpaceConfig, cell: (u32, u32)) -> (f64, f64) {
    (
        (f64::from(cell.0) + 0.5) * config.tile_size_m,
        (f64::from(cell.1) + 0.5) * config.tile_size_m,
    )
}

fn perturbations(scenario: &mut Scenario, config: &SpaceConfig) {
    scenario.exit_states.push(ExitStateChange {
        id: "primary_closure".into(),
        exit: "primary".into(),
        open: false,
        at_s: config.duration_s * 0.5,
    });
    if config.floors <= 1 {
        return;
    }
    let notice = center(config, layout::spawn(config));
    let origin = Point3::new(notice.0, notice.1, 3.0);
    let reach_m = config.tile_size_m * f64::from(config.width.max(config.height)) * 2.0;
    scenario.messages.push(Message {
        id: "false_notice".into(),
        source: InformationSource::Peer,
        surface: "floor_1".into(),
        origin,
        claim: Proposition::ConnectorAvailability {
            connector: "link_1_0".into(),
            open: false,
        },
        truthful: false,
        at_s: config.duration_s * 0.2,
        reach_m,
        trust: 0.7,
        sampling_key: None,
    });
    scenario.countermeasures.push(Countermeasure {
        id: "correction".into(),
        corrects: "false_notice".into(),
        source: InformationSource::Staff,
        surface: "floor_1".into(),
        origin,
        at_s: config.duration_s * 0.35,
        reach_m,
        trust: 0.9,
        sampling_key: None,
    });
}

fn connector(
    config: &SpaceConfig,
    floor: u32,
    portal: usize,
    kind: ConnectorKind,
    position: (f64, f64),
) -> Connector {
    let id = format!("link_{floor}_{portal}");
    let from_surface = format!("floor_{floor}");
    let to_surface = format!("floor_{}", floor - 1);
    let from = Point3::new(position.0, position.1, f64::from(floor * 3));
    let to = Point3::new(position.0, position.1, f64::from((floor - 1) * 3));
    let width_m = config.passage_width_m;
    let capacity_per_s = Some(1.0);
    let clearance_height_m = Some(2.1);
    match kind {
        ConnectorKind::Stair => Connector::Stair {
            id,
            from_surface,
            from,
            to_surface,
            to,
            width_m,
            capacity_per_s,
            clearance_height_m,
        },
        ConnectorKind::Ramp => Connector::Ramp {
            id,
            from_surface,
            from,
            to_surface,
            to,
            width_m,
            capacity_per_s,
            clearance_height_m,
        },
        ConnectorKind::Escalator => Connector::Escalator {
            id,
            from_surface,
            from,
            to_surface,
            to,
            width_m,
            belt_speed_mps: 0.6,
            capacity_per_s,
            clearance_height_m,
        },
        ConnectorKind::Lift => Connector::Lift {
            id,
            from_surface,
            from,
            to_surface,
            to,
            cabin_width_m: 2.0,
            cabin_depth_m: 2.0,
            capacity: 4,
            cycle_s: 6.0,
            clearance_height_m,
        },
    }
}

/// Compile a common 5x5 module lattice, then merge blocked rectangles. All
/// rooms on a floor belong to one surface; tile boundaries are actual walls.
fn compile_walls(
    scenario: &mut Scenario,
    config: &SpaceConfig,
    catalog: &[Module],
    floor: &FloorLayout,
) {
    let width = config.width as usize * 5;
    let height = config.height as usize * 5;
    let mut blocked = vec![false; width * height];
    for (cell, id) in floor.solution.cells.iter().enumerate() {
        let module = catalog
            .iter()
            .find(|module| &module.id == id)
            .expect("solved tile exists");
        let base_x = cell % config.width as usize * 5;
        let base_y = cell / config.width as usize * 5;
        for y in 0..5 {
            for x in 0..5 {
                blocked[(base_y + y) * width + base_x + x] = module.blocked(x, y);
            }
        }
    }
    let xs = coordinates(config, config.width);
    let ys = coordinates(config, config.height);
    let mut ordinal = 0;
    for y in 0..height {
        for x in 0..width {
            if !blocked[y * width + x] {
                continue;
            }
            let mut right = x + 1;
            while right < width && blocked[y * width + right] {
                right += 1;
            }
            let mut bottom = y + 1;
            while bottom < height && (x..right).all(|column| blocked[bottom * width + column]) {
                bottom += 1;
            }
            for row in y..bottom {
                blocked[row * width + x..row * width + right].fill(false);
            }
            scenario.obstacles.push(Obstacle {
                id: format!("wall_{}_{ordinal}", floor.floor),
                surface: format!("floor_{}", floor.floor),
                at: Point3::new(xs[x], ys[y], f64::from(floor.floor * 3)),
                width_m: xs[right] - xs[x],
                depth_m: ys[bottom] - ys[y],
            });
            ordinal += 1;
        }
    }
}

fn coordinates(config: &SpaceConfig, tiles: u32) -> Vec<f64> {
    let size = config.tile_size_m;
    let margin = (size - config.passage_width_m) / 2.0;
    let offsets = [
        0.0,
        size * 0.04,
        margin,
        margin + config.passage_width_m,
        size * 0.96,
    ];
    let mut coordinates: Vec<_> = (0..tiles)
        .flat_map(|tile| offsets.map(|offset| f64::from(tile) * size + offset))
        .collect();
    coordinates.push(f64::from(tiles) * size);
    coordinates
}
