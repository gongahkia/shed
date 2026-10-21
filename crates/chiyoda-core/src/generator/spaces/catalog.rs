use super::Preset;
use crate::wfc::Tile;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ModuleKind {
    Solid,
    Corridor,
    Hall,
    Kiosk,
}

/// Openings are north/east/south/west. Every open edge has the same centered
/// passage width; module geometry is derived from the kind, never from art.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Module {
    pub id: String,
    pub weight: u32,
    pub openings: [bool; 4],
    pub kind: ModuleKind,
}

impl Module {
    pub(super) fn tile(&self) -> Tile {
        Tile {
            id: self.id.clone(),
            weight: self.weight,
            sockets: self.openings.map(|open| {
                if open {
                    "passage".into()
                } else {
                    "wall".into()
                }
            }),
        }
    }

    pub(super) fn blocked(&self, x: usize, y: usize) -> bool {
        match self.kind {
            ModuleKind::Solid => true,
            ModuleKind::Corridor => {
                !((x == 2 && y == 2)
                    || (x == 2 && y < 2 && self.openings[0])
                    || (y == 2 && x > 2 && self.openings[1])
                    || (x == 2 && y > 2 && self.openings[2])
                    || (y == 2 && x < 2 && self.openings[3]))
            }
            ModuleKind::Hall | ModuleKind::Kiosk => {
                (y == 0 && !(x == 2 && self.openings[0]))
                    || (x == 4 && !(y == 2 && self.openings[1]))
                    || (y == 4 && !(x == 2 && self.openings[2]))
                    || (x == 0 && !(y == 2 && self.openings[3]))
                    || (self.kind == ModuleKind::Kiosk && x == 2 && y == 2)
            }
        }
    }
}

#[must_use]
pub fn modules_for(preset: Preset) -> Vec<Module> {
    let mut modules = vec![Module {
        id: "solid".into(),
        weight: 2,
        openings: [false; 4],
        kind: ModuleKind::Solid,
    }];
    for mask in 1_u8..16 {
        let openings = std::array::from_fn(|direction| mask & (1 << direction) != 0);
        let suffix: String = ["n", "e", "s", "w"]
            .iter()
            .enumerate()
            .filter(|(i, _)| openings[*i])
            .map(|(_, letter)| *letter)
            .collect();
        for kind in [ModuleKind::Corridor, ModuleKind::Hall] {
            let weight = match (preset, kind) {
                (Preset::Concourse, ModuleKind::Hall)
                | (Preset::Corridors | Preset::Bottleneck, ModuleKind::Corridor) => 8,
                _ => 2,
            };
            modules.push(Module {
                id: format!(
                    "{}_{suffix}",
                    if kind == ModuleKind::Hall {
                        "hall"
                    } else {
                        "corridor"
                    }
                ),
                weight,
                openings,
                kind,
            });
        }
    }
    modules.push(Module {
        id: "kiosk_nesw".into(),
        weight: if preset == Preset::Concourse { 6 } else { 1 },
        openings: [true; 4],
        kind: ModuleKind::Kiosk,
    });
    modules
}
