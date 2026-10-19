use super::{Module, ModuleKind, Preset, SpaceConfig};
use crate::wfc::{
    self, CellConstraint, Direction, Random, RuleSet, Solution, SolveError, SolveOptions,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, VecDeque};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FloorLayout {
    pub floor: u32,
    pub seed: u64,
    pub reserved: Vec<CellConstraint>,
    pub repair_targets: Vec<(u32, u32)>,
    pub solution: Solution,
}

pub(super) fn spawn(config: &SpaceConfig) -> (u32, u32) {
    (0, config.height / 2)
}
pub(super) fn portals(config: &SpaceConfig) -> [(u32, u32); 2] {
    [(1, 1), (1, config.height - 2)]
}
pub(super) fn exits(config: &SpaceConfig) -> [(u32, u32); 2] {
    [(config.width - 1, 0), (config.width - 1, config.height - 1)]
}

pub(super) fn generate_floor(
    config: &SpaceConfig,
    catalog: &[Module],
    rules: &RuleSet,
    floor: u32,
    seed: u64,
    repair_targets: &BTreeSet<(u32, u32)>,
) -> Result<FloorLayout, SolveError> {
    let mut random = Random::new(seed ^ 0x53ca_ff01_d000_0001);
    let root = spawn(config);
    let mut required: BTreeMap<(u32, u32), [bool; 4]> = BTreeMap::from([(root, [false; 4])]);
    let mut halls = BTreeSet::from([root]);
    let mut targets = portals(config).to_vec();
    if floor == 0 {
        targets.extend(exits(config));
    }
    halls.extend(targets.iter().copied());
    targets.extend(repair_targets.iter().copied());
    let choke = (config.width / 2, config.height / 2);
    for target in targets {
        if config.preset == Preset::Bottleneck && target.0 > choke.0 {
            walk(root, choke, &mut required, &mut random);
            // Force both halves to cross the one unblocked cell in the divider.
            walk(choke, (choke.0 + 1, choke.1), &mut required, &mut random);
            walk((choke.0 + 1, choke.1), target, &mut required, &mut random);
        } else {
            walk(root, target, &mut required, &mut random);
        }
    }
    let mut reserved = Vec::new();
    for (&(x, y), openings) in &required {
        let allowed_tiles: Vec<_> = catalog
            .iter()
            .filter(|module| {
                let kind_allowed = if halls.contains(&(x, y)) {
                    module.kind == ModuleKind::Hall
                } else {
                    module.kind != ModuleKind::Solid
                };
                kind_allowed
                    && openings
                        .iter()
                        .enumerate()
                        .all(|(i, needed)| !needed || module.openings[i])
            })
            .map(|module| module.id.clone())
            .collect();
        if allowed_tiles.is_empty() {
            return Err(SolveError::InvalidInput(format!(
                "catalog has no suitable reserved module for floor {floor}, cell ({x}, {y})"
            )));
        }
        reserved.push(CellConstraint {
            x,
            y,
            allowed_tiles,
        });
    }
    reserve_bottleneck(config, catalog, &mut reserved)?;
    for pin in config.pins.iter().filter(|pin| pin.floor == floor) {
        reserved.push(CellConstraint {
            x: pin.x,
            y: pin.y,
            allowed_tiles: pin.allowed_tiles.clone(),
        });
    }
    let solution = wfc::solve(
        rules,
        &SolveOptions {
            width: config.width,
            height: config.height,
            seed,
            boundary_socket: Some("wall".into()),
            max_backtracks: config.max_backtracks,
            max_decisions: config.max_decisions,
        },
        &reserved,
    )?;
    Ok(FloorLayout {
        floor,
        seed,
        reserved,
        repair_targets: repair_targets.iter().copied().collect(),
        solution,
    })
}

fn reserve_bottleneck(
    config: &SpaceConfig,
    catalog: &[Module],
    reserved: &mut Vec<CellConstraint>,
) -> Result<(), SolveError> {
    let choke = (config.width / 2, config.height / 2);
    if config.preset == Preset::Bottleneck {
        let solids: Vec<_> = catalog
            .iter()
            .filter(|module| module.kind == ModuleKind::Solid)
            .map(|module| module.id.clone())
            .collect();
        if solids.is_empty() {
            return Err(SolveError::InvalidInput(
                "bottleneck preset needs a solid module".into(),
            ));
        }
        for y in 0..config.height {
            if y != choke.1 {
                reserved.push(CellConstraint {
                    x: choke.0,
                    y,
                    allowed_tiles: solids.clone(),
                });
            }
        }
        let corridors: Vec<_> = catalog
            .iter()
            .filter(|module| {
                module.kind == ModuleKind::Corridor && module.openings == [false, true, false, true]
            })
            .map(|module| module.id.clone())
            .collect();
        if corridors.is_empty() {
            return Err(SolveError::InvalidInput(
                "bottleneck preset needs an east-west corridor module".into(),
            ));
        }
        reserved.push(CellConstraint {
            x: choke.0,
            y: choke.1,
            allowed_tiles: corridors,
        });
    }
    Ok(())
}

fn walk(
    mut current: (u32, u32),
    target: (u32, u32),
    required: &mut BTreeMap<(u32, u32), [bool; 4]>,
    random: &mut Random,
) {
    while current != target {
        let dx = current.0.abs_diff(target.0);
        let dy = current.1.abs_diff(target.1);
        let direction = if random.below(u64::from(dx + dy)) < u64::from(dx) {
            if current.0 < target.0 {
                Direction::East
            } else {
                Direction::West
            }
        } else if current.1 < target.1 {
            Direction::South
        } else {
            Direction::North
        };
        let next = match direction {
            Direction::North => (current.0, current.1 - 1),
            Direction::East => (current.0 + 1, current.1),
            Direction::South => (current.0, current.1 + 1),
            Direction::West => (current.0 - 1, current.1),
        };
        required.entry(current).or_default()[direction.index()] = true;
        required.entry(next).or_default()[direction.opposite().index()] = true;
        current = next;
    }
}

pub(super) fn disconnected_cells(
    layout: &FloorLayout,
    catalog: &[Module],
    root: (u32, u32),
) -> Vec<(u32, u32)> {
    let modules: Vec<_> = layout
        .solution
        .cells
        .iter()
        .map(|id| {
            catalog
                .iter()
                .find(|module| &module.id == id)
                .expect("solved tile exists")
        })
        .collect();
    let start = (root.1 * layout.solution.width + root.0) as usize;
    let width = layout.solution.width as usize;
    let height = layout.solution.height as usize;
    let mut visited = BTreeSet::from([start]);
    let mut queue = VecDeque::from([start]);
    while let Some(cell) = queue.pop_front() {
        for direction in Direction::ALL {
            if !modules[cell].openings[direction.index()] {
                continue;
            }
            let next = match direction {
                Direction::North => (cell >= width).then(|| cell - width),
                Direction::East => (cell % width + 1 < width).then_some(cell + 1),
                Direction::South => (cell / width + 1 < height).then_some(cell + width),
                Direction::West => (!cell.is_multiple_of(width)).then(|| cell - 1),
            };
            if let Some(next) = next
                && visited.insert(next)
            {
                queue.push_back(next);
            }
        }
    }
    modules
        .iter()
        .enumerate()
        .filter(|(index, module)| module.kind != ModuleKind::Solid && !visited.contains(index))
        .map(|(index, _)| {
            (
                u32::try_from(index % width).expect("bounded width"),
                u32::try_from(index / width).expect("bounded height"),
            )
        })
        .collect()
}
