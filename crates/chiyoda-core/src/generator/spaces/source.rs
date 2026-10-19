use super::{FloorLayout, Module, SpaceConfig, layout};
use crate::model::ConnectorKind;
use std::fmt::Write;

pub(super) fn compile(
    seed: u64,
    config: &SpaceConfig,
    catalog: &[Module],
    floors: &[FloorLayout],
) -> String {
    let mut text = format!(
        "scenario \"wfc-{}-{seed}\"\nseed {seed}\nduration {}s\ntimestep {}s\n",
        config.preset.as_str(),
        config.duration_s,
        config.timestep_s
    );
    for floor in floors {
        let elevation = floor.floor * 3;
        writeln!(
            text,
            "surface floor_{} at (0m, 0m, {elevation}m) size ({}m, {}m)",
            floor.floor,
            f64::from(config.width) * config.tile_size_m,
            f64::from(config.height) * config.tile_size_m
        )
        .unwrap();
        compile_walls(&mut text, config, catalog, floor);
    }
    let [primary, secondary] = layout::exits(config).map(|cell| center(config, cell));
    writeln!(
        text,
        "exit primary on floor_0 at ({}m, {}m, 0m) width {}m capacity 1.5/s",
        primary.0, primary.1, config.passage_width_m
    )
    .unwrap();
    writeln!(
        text,
        "exit alternative on floor_0 at ({}m, {}m, 0m) width {}m capacity 1.5/s",
        secondary.0, secondary.1, config.passage_width_m
    )
    .unwrap();
    writeln!(
        text,
        "gate primary_gate on floor_0 at ({}m, {}m, 0m) width {}m capacity 1/s to primary",
        primary.0 - config.tile_size_m * 0.1,
        primary.1,
        config.passage_width_m
    )
    .unwrap();
    for floor in 1..config.floors {
        for (portal, position) in layout::portals(config)
            .map(|cell| center(config, cell))
            .iter()
            .enumerate()
        {
            let kind = config.connector_kinds
                [(2 * (floor as usize - 1) + portal) % config.connector_kinds.len()];
            compile_connector(&mut text, config, floor, portal, kind, *position);
        }
    }
    if config.perturbations {
        writeln!(
            text,
            "exit-state primary_closure exit primary closed time {}s",
            config.duration_s * 0.5
        )
        .unwrap();
        if config.floors > 1 {
            let notice = center(config, layout::spawn(config));
            let reach = config.tile_size_m * f64::from(config.width.max(config.height)) * 2.0;
            writeln!(text, "message false_notice source peer on floor_1 at ({}m, {}m, 3m) claim connector link_1_0 closed truth false time {}s reach {reach}m trust 0.7", notice.0, notice.1, config.duration_s * 0.2).unwrap();
            writeln!(text, "countermeasure correction corrects false_notice source staff on floor_1 at ({}m, {}m, 3m) time {}s reach {reach}m trust 0.9", notice.0, notice.1, config.duration_s * 0.35).unwrap();
        }
    }
    let spawn = layout::spawn(config);
    let inset = config.tile_size_m * 0.04 + config.agent_radius_m + 0.001;
    for floor in 0..config.floors {
        writeln!(text, "agents arrivals_{floor} count {} on floor_{floor} at ({}m, {}m, {}m) to primary speed {}m/s radius {}m height 1.7m alternative alternative release 0s", config.agents_per_floor, f64::from(spawn.0) * config.tile_size_m + inset, f64::from(spawn.1) * config.tile_size_m + inset, floor * 3, config.speed_mps, config.agent_radius_m).unwrap();
    }
    text
}

pub(super) fn center(config: &SpaceConfig, cell: (u32, u32)) -> (f64, f64) {
    (
        (f64::from(cell.0) + 0.5) * config.tile_size_m,
        (f64::from(cell.1) + 0.5) * config.tile_size_m,
    )
}

fn compile_connector(
    text: &mut String,
    config: &SpaceConfig,
    floor: u32,
    portal: usize,
    kind: ConnectorKind,
    position: (f64, f64),
) {
    let (x, y) = position;
    let prefix = format!(
        "{} link_{floor}_{portal} from floor_{floor} at ({x}m, {y}m, {}m) to floor_{} at ({x}m, {y}m, {}m)",
        kind.as_str(),
        floor * 3,
        floor - 1,
        (floor - 1) * 3
    );
    let width = config.passage_width_m;
    match kind {
        ConnectorKind::Stair | ConnectorKind::Ramp => {
            writeln!(text, "{prefix} width {width}m capacity 1/s clearance 2.1m").unwrap();
        }
        ConnectorKind::Escalator => writeln!(
            text,
            "{prefix} width {width}m belt 0.6m/s capacity 1/s clearance 2.1m"
        )
        .unwrap(),
        ConnectorKind::Lift => writeln!(
            text,
            "{prefix} cabin 2m 2m capacity 4 cycle 6s clearance 2.1m"
        )
        .unwrap(),
    }
}

/// Compile a common 5x5 module lattice, then merge blocked rectangles. All
/// rooms on a floor belong to one surface; tile boundaries are actual walls.
fn compile_walls(text: &mut String, config: &SpaceConfig, catalog: &[Module], floor: &FloorLayout) {
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
            writeln!(
                text,
                "obstacle wall_{}_{ordinal} on floor_{} at ({}m, {}m, {}m) size ({}m, {}m)",
                floor.floor,
                floor.floor,
                xs[x],
                ys[y],
                floor.floor * 3,
                xs[right] - xs[x],
                ys[bottom] - ys[y]
            )
            .unwrap();
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
