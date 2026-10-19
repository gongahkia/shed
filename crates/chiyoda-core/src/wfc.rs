//! Seeded tiled wave function collapse, independent of pedestrian simulation.
//!
//! Socket matching and explicit neighbour exclusions are local constraints.
//! A completed wave is not a proof of geometric connectivity or evacuation.

use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, VecDeque};

pub const SOLVER_VERSION: &str = "1";
const MAX_CELLS: usize = 4096;
const MAX_TILES: usize = 64;

/// Socket order throughout the generator is north, east, south, west.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    North,
    East,
    South,
    West,
}

impl Direction {
    pub const ALL: [Self; 4] = [Self::North, Self::East, Self::South, Self::West];

    #[must_use]
    pub const fn index(self) -> usize {
        match self {
            Self::North => 0,
            Self::East => 1,
            Self::South => 2,
            Self::West => 3,
        }
    }

    #[must_use]
    pub const fn opposite(self) -> Self {
        match self {
            Self::North => Self::South,
            Self::East => Self::West,
            Self::South => Self::North,
            Self::West => Self::East,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Tile {
    pub id: String,
    pub weight: u32,
    pub sockets: [String; 4],
}

/// An exclusion also forbids the reverse pair in the opposite direction.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ForbiddenNeighbor {
    pub tile: String,
    pub direction: Direction,
    pub neighbor: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RuleSet {
    pub tiles: Vec<Tile>,
    #[serde(default)]
    pub forbidden_neighbors: Vec<ForbiddenNeighbor>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CellConstraint {
    pub x: u32,
    pub y: u32,
    pub allowed_tiles: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SolveOptions {
    pub width: u32,
    pub height: u32,
    pub seed: u64,
    /// When present, every outward-facing boundary socket must match this.
    pub boundary_socket: Option<String>,
    pub max_backtracks: u32,
    pub max_decisions: u32,
}

impl Default for SolveOptions {
    fn default() -> Self {
        Self {
            width: 5,
            height: 5,
            seed: 73,
            boundary_socket: None,
            max_backtracks: 10_000,
            max_decisions: 100_000,
        }
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SolveStats {
    /// Includes alternative choices made after a contradiction.
    pub decisions: u32,
    pub backtracks: u32,
    /// Domain reductions, including reductions made in abandoned branches.
    pub propagations: u64,
    pub contradictions: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Solution {
    pub width: u32,
    pub height: u32,
    /// Row-major tile identifiers; north is decreasing row index.
    pub cells: Vec<String>,
    pub stats: SolveStats,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SolveError {
    #[error("invalid WFC input: {0}")]
    InvalidInput(String),
    #[error("WFC constraints are contradictory after {0:?}")]
    Contradiction(SolveStats),
    #[error("WFC search budget exhausted after {0:?}")]
    SearchLimit(SolveStats),
}

struct CompiledRules {
    allowed: Vec<[u64; 4]>,
    indices: BTreeMap<String, usize>,
    weights: Vec<u32>,
}

impl RuleSet {
    pub fn validate(&self) -> Result<(), SolveError> {
        self.compile().map(|_| ())
    }

    fn compile(&self) -> Result<CompiledRules, SolveError> {
        if self.tiles.is_empty() || self.tiles.len() > MAX_TILES {
            return Err(invalid("a rule set must contain between 1 and 64 tiles"));
        }
        let mut indices = BTreeMap::new();
        for (index, tile) in self.tiles.iter().enumerate() {
            if tile.id.trim().is_empty() || indices.insert(tile.id.clone(), index).is_some() {
                return Err(invalid("tile identifiers must be nonempty and unique"));
            }
            if tile.weight == 0 || tile.sockets.iter().any(|socket| socket.trim().is_empty()) {
                return Err(invalid(
                    "tile weights and socket names must be nonzero/nonempty",
                ));
            }
        }
        let mut forbidden = BTreeSet::new();
        for rule in &self.forbidden_neighbors {
            let left = *indices
                .get(&rule.tile)
                .ok_or_else(|| invalid(format!("unknown excluded tile `{}`", rule.tile)))?;
            let right = *indices
                .get(&rule.neighbor)
                .ok_or_else(|| invalid(format!("unknown excluded neighbor `{}`", rule.neighbor)))?;
            forbidden.insert((left, rule.direction.index(), right));
            forbidden.insert((right, rule.direction.opposite().index(), left));
        }
        let mut allowed = vec![[0; 4]; self.tiles.len()];
        for (left, tile) in self.tiles.iter().enumerate() {
            for direction in Direction::ALL {
                for (right, neighbor) in self.tiles.iter().enumerate() {
                    if tile.sockets[direction.index()]
                        == neighbor.sockets[direction.opposite().index()]
                        && !forbidden.contains(&(left, direction.index(), right))
                    {
                        allowed[left][direction.index()] |= 1_u64 << right;
                    }
                }
            }
        }
        Ok(CompiledRules {
            allowed,
            indices,
            weights: self.tiles.iter().map(|tile| tile.weight).collect(),
        })
    }
}

struct Decision {
    domains: Vec<u64>,
    cell: usize,
    remaining: u64,
}

/// Solve a finite wave with weighted minimum-entropy choices and bounded
/// chronological backtracking. All iteration and tie orders are stable.
pub fn solve(
    rules: &RuleSet,
    options: &SolveOptions,
    constraints: &[CellConstraint],
) -> Result<Solution, SolveError> {
    let compiled = rules.compile()?;
    let width = usize::try_from(options.width).map_err(|_| invalid("width overflow"))?;
    let height = usize::try_from(options.height).map_err(|_| invalid("height overflow"))?;
    let count = width
        .checked_mul(height)
        .ok_or_else(|| invalid("grid size overflow"))?;
    if count == 0 || count > MAX_CELLS || options.max_decisions == 0 {
        return Err(invalid(
            "grid must have 1..=4096 cells and a positive decision budget",
        ));
    }
    let mut domains = initial_domains(rules, &compiled, options, constraints, width, height)?;
    let mut stats = SolveStats::default();
    let initial: Vec<_> = (0..count).collect();
    if !propagate(&mut domains, &compiled, width, height, &initial, &mut stats) {
        stats.contradictions += 1;
        return Err(SolveError::Contradiction(stats));
    }
    let mut random = Random::new(options.seed);
    let mut decisions: Vec<Decision> = Vec::new();
    loop {
        let Some(cell) = minimum_entropy_cell(&domains, &compiled.weights) else {
            return Ok(Solution {
                width: options.width,
                height: options.height,
                cells: domains
                    .iter()
                    .map(|domain| rules.tiles[domain.trailing_zeros() as usize].id.clone())
                    .collect(),
                stats,
            });
        };
        if stats.decisions >= options.max_decisions {
            return Err(SolveError::SearchLimit(stats));
        }
        let selected = weighted_choice(domains[cell], &compiled.weights, &mut random);
        decisions.push(Decision {
            domains: domains.clone(),
            cell,
            remaining: domains[cell] & !selected,
        });
        domains[cell] = selected;
        stats.decisions += 1;
        while !propagate(
            &mut domains,
            &compiled,
            width,
            height,
            &[cell_from_last(&decisions)],
            &mut stats,
        ) {
            stats.contradictions += 1;
            loop {
                let Some(mut decision) = decisions.pop() else {
                    return Err(SolveError::Contradiction(stats));
                };
                if stats.backtracks >= options.max_backtracks {
                    return Err(SolveError::SearchLimit(stats));
                }
                stats.backtracks += 1;
                if decision.remaining == 0 {
                    continue;
                }
                if stats.decisions >= options.max_decisions {
                    return Err(SolveError::SearchLimit(stats));
                }
                domains.clone_from(&decision.domains);
                let selected = weighted_choice(decision.remaining, &compiled.weights, &mut random);
                decision.remaining &= !selected;
                domains[decision.cell] = selected;
                decisions.push(decision);
                stats.decisions += 1;
                break;
            }
        }
    }
}

fn initial_domains(
    rules: &RuleSet,
    compiled: &CompiledRules,
    options: &SolveOptions,
    constraints: &[CellConstraint],
    width: usize,
    height: usize,
) -> Result<Vec<u64>, SolveError> {
    let all = u64::MAX >> (MAX_TILES - rules.tiles.len());
    let mut domains = vec![all; width * height];
    if let Some(boundary) = &options.boundary_socket {
        if boundary.trim().is_empty() {
            return Err(invalid("boundary socket must be nonempty"));
        }
        for (cell, domain) in domains.iter_mut().enumerate() {
            for direction in Direction::ALL {
                if neighbor(cell, direction, width, height).is_none() {
                    *domain &= rules
                        .tiles
                        .iter()
                        .enumerate()
                        .fold(0, |mask, (index, tile)| {
                            mask | (u64::from(tile.sockets[direction.index()] == *boundary)
                                << index)
                        });
                }
            }
        }
    }
    for constraint in constraints {
        if constraint.x >= options.width || constraint.y >= options.height {
            return Err(invalid("cell constraint lies outside the wave"));
        }
        if constraint.allowed_tiles.is_empty() {
            return Err(invalid(
                "cell constraints must name at least one allowed tile",
            ));
        }
        let mut mask = 0;
        for id in &constraint.allowed_tiles {
            let index = compiled
                .indices
                .get(id)
                .ok_or_else(|| invalid(format!("unknown pinned tile `{id}`")))?;
            mask |= 1_u64 << index;
        }
        domains[constraint.y as usize * width + constraint.x as usize] &= mask;
    }
    Ok(domains)
}

fn cell_from_last(decisions: &[Decision]) -> usize {
    decisions
        .last()
        .expect("a search branch has a decision")
        .cell
}

fn propagate(
    domains: &mut [u64],
    rules: &CompiledRules,
    width: usize,
    height: usize,
    initial: &[usize],
    stats: &mut SolveStats,
) -> bool {
    if domains.contains(&0) {
        return false;
    }
    let mut queue: VecDeque<_> = initial.iter().copied().collect();
    while let Some(cell) = queue.pop_front() {
        for direction in Direction::ALL {
            let Some(next) = neighbor(cell, direction, width, height) else {
                continue;
            };
            let mut supported = 0;
            let mut remaining = domains[cell];
            while remaining != 0 {
                let tile = remaining.trailing_zeros() as usize;
                remaining &= remaining - 1;
                supported |= rules.allowed[tile][direction.index()];
            }
            let reduced = domains[next] & supported;
            if reduced != domains[next] {
                domains[next] = reduced;
                stats.propagations += 1;
                if reduced == 0 {
                    return false;
                }
                queue.push_back(next);
            }
        }
    }
    true
}

fn minimum_entropy_cell(domains: &[u64], weights: &[u32]) -> Option<usize> {
    domains
        .iter()
        .enumerate()
        .filter(|(_, mask)| mask.count_ones() > 1)
        .map(|(cell, mask)| {
            let mut sum = 0.0;
            let mut weighted_log = 0.0;
            for (tile, weight) in weights.iter().enumerate() {
                if mask & (1_u64 << tile) != 0 {
                    let weight = f64::from(*weight);
                    sum += weight;
                    weighted_log += weight * weight.ln();
                }
            }
            (cell, sum.ln() - weighted_log / sum)
        })
        .min_by(|(left, a), (right, b)| a.total_cmp(b).then(left.cmp(right)))
        .map(|(cell, _)| cell)
}

fn neighbor(cell: usize, direction: Direction, width: usize, height: usize) -> Option<usize> {
    match direction {
        Direction::North => (cell >= width).then(|| cell - width),
        Direction::East => (cell % width + 1 < width).then_some(cell + 1),
        Direction::South => (cell / width + 1 < height).then_some(cell + width),
        Direction::West => (!cell.is_multiple_of(width)).then(|| cell - 1),
    }
}

fn weighted_choice(mask: u64, weights: &[u32], random: &mut Random) -> u64 {
    let sum: u64 = weights
        .iter()
        .enumerate()
        .filter(|(index, _)| mask & (1_u64 << index) != 0)
        .map(|(_, weight)| u64::from(*weight))
        .sum();
    let mut draw = random.below(sum);
    for (index, weight) in weights.iter().enumerate() {
        if mask & (1_u64 << index) == 0 {
            continue;
        }
        if draw < u64::from(*weight) {
            return 1_u64 << index;
        }
        draw -= u64::from(*weight);
    }
    unreachable!("a positive weighted domain always has a choice")
}

/// Versioned `SplitMix64` stream with unbiased bounded integer sampling.
pub(crate) struct Random(u64);

impl Random {
    pub(crate) const fn new(seed: u64) -> Self {
        Self(seed)
    }

    pub(crate) fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut value = self.0;
        value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        value ^ (value >> 31)
    }

    pub(crate) fn below(&mut self, bound: u64) -> u64 {
        debug_assert!(bound > 0);
        let threshold = bound.wrapping_neg() % bound;
        loop {
            let value = self.next();
            if value >= threshold {
                return value % bound;
            }
        }
    }
}

fn invalid(message: impl Into<String>) -> SolveError {
    SolveError::InvalidInput(message.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tiny_waves_agree_with_exhaustive_independent_constraint_enumeration() {
        let mut random = Random::new(512);
        for seed in 0..128 {
            let tiles: Vec<_> = (0..4)
                .map(|id| Tile {
                    id: id.to_string(),
                    weight: u32::try_from(random.below(20) + 1).unwrap(),
                    sockets: std::array::from_fn(|_| random.below(2).to_string()),
                })
                .collect();
            let forbidden_neighbors = (0..4)
                .map(|_| ForbiddenNeighbor {
                    tile: random.below(4).to_string(),
                    direction: Direction::ALL[usize::try_from(random.below(4)).unwrap()],
                    neighbor: random.below(4).to_string(),
                })
                .collect();
            let rules = RuleSet {
                tiles,
                forbidden_neighbors,
            };
            let mut valid = Vec::new();
            for encoded in 0_usize..256 {
                let cells = [encoded % 4, encoded / 4 % 4, encoded / 16 % 4, encoded / 64];
                let compatible = [
                    (0, 1, Direction::East),
                    (0, 2, Direction::South),
                    (1, 3, Direction::South),
                    (2, 3, Direction::East),
                ]
                .iter()
                .all(|&(a, b, direction)| {
                    let left = &rules.tiles[cells[a]];
                    let right = &rules.tiles[cells[b]];
                    left.sockets[direction.index()] == right.sockets[direction.opposite().index()]
                        && !rules.forbidden_neighbors.iter().any(|rule| {
                            (rule.tile == left.id
                                && rule.neighbor == right.id
                                && rule.direction == direction)
                                || (rule.tile == right.id
                                    && rule.neighbor == left.id
                                    && rule.direction == direction.opposite())
                        })
                });
                if compatible {
                    valid.push(cells.map(|id| id.to_string()).to_vec());
                }
            }
            match solve(
                &rules,
                &SolveOptions {
                    width: 2,
                    height: 2,
                    seed,
                    ..SolveOptions::default()
                },
                &[],
            ) {
                Ok(solution) => assert!(valid.contains(&solution.cells), "seed {seed}"),
                Err(SolveError::Contradiction(_)) => assert!(valid.is_empty(), "seed {seed}"),
                Err(error) => panic!("tiny search unexpectedly failed: {error}"),
            }
        }
    }

    fn cycle_rules(size: usize) -> RuleSet {
        let tiles: Vec<_> = (0..size)
            .map(|i| Tile {
                id: i.to_string(),
                weight: if i == 0 { 1_000_000 } else { 1 },
                sockets: std::array::from_fn(|_| "same".to_owned()),
            })
            .collect();
        let mut forbidden_neighbors = Vec::new();
        for tile in 0..size {
            for other in 0..size {
                for (direction, expected) in [
                    (Direction::East, (tile + 1) % size),
                    (
                        Direction::South,
                        match tile {
                            0 => 1,
                            1 => 0,
                            _ => tile,
                        },
                    ),
                ] {
                    if other != expected {
                        forbidden_neighbors.push(ForbiddenNeighbor {
                            tile: tile.to_string(),
                            direction,
                            neighbor: other.to_string(),
                        });
                    }
                }
            }
        }
        RuleSet {
            tiles,
            forbidden_neighbors,
        }
    }

    #[test]
    fn backtracking_finds_the_only_globally_consistent_square() {
        // E is a four-cycle and S swaps 0/1. Only starting at 2 makes ES=SE.
        let options = SolveOptions {
            width: 2,
            height: 2,
            seed: 0,
            ..SolveOptions::default()
        };
        let solution = solve(&cycle_rules(4), &options, &[]).unwrap();
        assert_eq!(solution.cells, ["2", "3", "2", "3"]);
        assert!(solution.stats.backtracks > 0);
        assert_eq!(solution, solve(&cycle_rules(4), &options, &[]).unwrap());
    }

    #[test]
    fn detects_unsatisfiable_cycles_and_reports_budget_exhaustion_separately() {
        let options = SolveOptions {
            width: 2,
            height: 2,
            ..SolveOptions::default()
        };
        assert!(matches!(
            solve(&cycle_rules(3), &options, &[]),
            Err(SolveError::Contradiction(_))
        ));
        assert!(matches!(
            solve(
                &cycle_rules(4),
                &SolveOptions {
                    seed: 0,
                    max_backtracks: 0,
                    ..options.clone()
                },
                &[]
            ),
            Err(SolveError::SearchLimit(_))
        ));
        assert!(matches!(
            solve(
                &cycle_rules(4),
                &SolveOptions {
                    seed: 0,
                    max_decisions: 1,
                    ..options
                },
                &[]
            ),
            Err(SolveError::SearchLimit(_))
        ));
    }

    #[test]
    fn boundaries_pins_and_socket_matching_are_hard_constraints() {
        let rules = RuleSet {
            tiles: vec![
                Tile {
                    id: "left".into(),
                    weight: 1,
                    sockets: ["wall", "open", "wall", "wall"].map(str::to_owned),
                },
                Tile {
                    id: "right".into(),
                    weight: 1,
                    sockets: ["wall", "wall", "wall", "open"].map(str::to_owned),
                },
            ],
            forbidden_neighbors: vec![],
        };
        let options = SolveOptions {
            width: 2,
            height: 1,
            boundary_socket: Some("wall".into()),
            ..SolveOptions::default()
        };
        assert_eq!(
            solve(&rules, &options, &[]).unwrap().cells,
            ["left", "right"]
        );
        let pin = CellConstraint {
            x: 0,
            y: 0,
            allowed_tiles: vec!["right".into()],
        };
        assert!(matches!(
            solve(&rules, &options, &[pin]),
            Err(SolveError::Contradiction(_))
        ));
    }

    #[test]
    fn rejects_malformed_rules_and_inputs_without_panicking() {
        let mut rules = cycle_rules(3);
        rules.tiles[0].weight = 0;
        assert!(rules.validate().is_err());
        rules.tiles[0].weight = 1;
        rules.tiles[0].id = rules.tiles[1].id.clone();
        assert!(rules.validate().is_err());
        assert!(
            solve(
                &cycle_rules(3),
                &SolveOptions {
                    width: u32::MAX,
                    height: u32::MAX,
                    ..SolveOptions::default()
                },
                &[]
            )
            .is_err()
        );
        assert!(
            solve(
                &cycle_rules(3),
                &SolveOptions::default(),
                &[CellConstraint {
                    x: 0,
                    y: 0,
                    allowed_tiles: vec!["missing".into()]
                }]
            )
            .is_err()
        );
        let rules = RuleSet {
            tiles: (0..64)
                .map(|i| Tile {
                    id: i.to_string(),
                    weight: 1,
                    sockets: std::array::from_fn(|_| "x".into()),
                })
                .collect(),
            forbidden_neighbors: vec![],
        };
        assert_eq!(
            solve(
                &rules,
                &SolveOptions {
                    width: 1,
                    height: 1,
                    ..SolveOptions::default()
                },
                &[]
            )
            .unwrap()
            .cells
            .len(),
            1
        );
    }
}
