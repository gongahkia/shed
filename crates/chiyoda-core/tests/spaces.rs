use chiyoda_core::{
    generator::spaces::{
        self, FloorConstraint, ModuleKind, Preset, RejectionKind, SpaceConfig, SpaceGenerationError,
    },
    model::{ConnectorKind, Obstacle, Point3},
    parse, reachability, validate,
};
use std::collections::BTreeSet;

fn small(preset: Preset) -> SpaceConfig {
    SpaceConfig {
        preset,
        width: 4,
        height: 4,
        floors: 1,
        agents_per_floor: 1,
        ..SpaceConfig::default()
    }
}

#[test]
fn generation_is_reproducible_and_round_trips_through_the_existing_language() {
    let generated = spaces::generate(73, &SpaceConfig::default()).unwrap();
    assert_eq!(
        generated,
        spaces::generate(73, &SpaceConfig::default()).unwrap()
    );
    assert_eq!(generated.scenario, parse(&generated.source).unwrap());
    assert_eq!(generated.scenario.surfaces.len(), 2);
    assert_eq!(generated.scenario.connectors.len(), 2);
    assert_eq!(generated.manifest.reachability.checked_spawns, 8);
    assert!(generated.manifest.reachability.is_reachable());
    let encoded = serde_json::to_string(&generated.manifest).unwrap();
    assert_eq!(generated.manifest, serde_json::from_str(&encoded).unwrap());
    assert!(
        generated
            .manifest
            .attempts
            .last()
            .unwrap()
            .rejection
            .is_none()
    );
}

#[test]
fn presets_generate_different_valid_topologies_across_a_seed_corpus() {
    for preset in [Preset::Concourse, Preset::Corridors, Preset::Bottleneck] {
        let mut layouts = BTreeSet::new();
        for seed in 0..32 {
            let generated = spaces::generate(seed, &small(preset))
                .unwrap_or_else(|error| panic!("{preset:?}, seed {seed}: {error}"));
            validate(&generated.scenario).unwrap();
            assert!(generated.manifest.reachability.is_reachable());
            let layout = &generated.manifest.floors[0].solution;
            layouts.insert(layout.cells.clone());
            // Check local adjacency and closed boundaries without invoking the solver.
            for y in 0..layout.height {
                for x in 0..layout.width {
                    let module = generated
                        .manifest
                        .catalog
                        .iter()
                        .find(|module| module.id == layout.cells[(y * layout.width + x) as usize])
                        .unwrap();
                    for (direction, next) in [
                        (0, y.checked_sub(1).map(|row| (x, row))),
                        (1, (x + 1 < layout.width).then_some((x + 1, y))),
                        (2, (y + 1 < layout.height).then_some((x, y + 1))),
                        (3, x.checked_sub(1).map(|column| (column, y))),
                    ] {
                        if let Some((nx, ny)) = next {
                            let other = generated
                                .manifest
                                .catalog
                                .iter()
                                .find(|module| {
                                    module.id == layout.cells[(ny * layout.width + nx) as usize]
                                })
                                .unwrap();
                            assert_eq!(
                                module.openings[direction],
                                other.openings[(direction + 2) % 4]
                            );
                        } else {
                            assert!(!module.openings[direction]);
                        }
                    }
                }
            }
        }
        assert!(
            layouts.len() > 16,
            "{preset:?} did not generate spatial diversity"
        );
    }
}

#[test]
fn a_bottleneck_has_exactly_one_crossing_and_blocking_it_disconnects_the_exit() {
    let config = small(Preset::Bottleneck);
    let mut generated = spaces::generate(19, &config).unwrap();
    let layout = &generated.manifest.floors[0].solution;
    let x = config.width / 2;
    for y in 0..config.height {
        let id = &layout.cells[(y * config.width + x) as usize];
        let module = generated
            .manifest
            .catalog
            .iter()
            .find(|module| &module.id == id)
            .unwrap();
        assert_eq!(
            module.kind,
            if y == config.height / 2 {
                ModuleKind::Corridor
            } else {
                ModuleKind::Solid
            }
        );
    }
    generated.scenario.obstacles.push(Obstacle {
        id: "seal".into(),
        surface: "floor_0".into(),
        at: Point3 {
            x_m: f64::from(x) * config.tile_size_m,
            y_m: f64::from(config.height / 2) * config.tile_size_m,
            z_m: 0.0,
        },
        width_m: config.tile_size_m,
        depth_m: config.tile_size_m,
    });
    assert!(
        !reachability::check(&generated.scenario)
            .unwrap()
            .is_reachable()
    );
}

#[test]
fn custom_catalog_pins_and_rejection_logs_are_explicit() {
    let mut config = small(Preset::Concourse);
    config.modules = config.catalog();
    config.pins.push(FloorConstraint {
        floor: 0,
        x: 0,
        y: 2,
        allowed_tiles: vec!["hall_nes".into()],
    });
    let generated = spaces::generate(11, &config).unwrap();
    assert_eq!(generated.manifest.floors[0].solution.cells[8], "hall_nes");
    config.pins[0].allowed_tiles = vec!["solid".into()];
    config.max_attempts = 2;
    match spaces::generate(11, &config).unwrap_err() {
        SpaceGenerationError::Exhausted { attempts } => {
            assert_eq!(attempts.len(), 2);
            assert!(
                attempts
                    .iter()
                    .all(|attempt| attempt.rejection.as_ref().unwrap().kind
                        == RejectionKind::Contradiction)
            );
        }
        error @ SpaceGenerationError::InvalidConfig(_) => panic!("unexpected error: {error}"),
    }
    config.pins[0].allowed_tiles = vec!["misspelled".into()];
    assert!(matches!(
        spaces::generate(11, &config),
        Err(SpaceGenerationError::InvalidConfig(_))
    ));
}

#[test]
fn multi_floor_geometry_supports_all_connector_classes_and_large_spawn_grids() {
    let config = SpaceConfig {
        width: 4,
        height: 4,
        floors: 3,
        agents_per_floor: 64,
        connector_kinds: vec![
            ConnectorKind::Stair,
            ConnectorKind::Ramp,
            ConnectorKind::Escalator,
            ConnectorKind::Lift,
        ],
        ..SpaceConfig::default()
    };
    let generated = spaces::generate(9, &config).unwrap();
    assert_eq!(generated.scenario.connectors.len(), 4);
    assert_eq!(generated.manifest.reachability.checked_spawns, 192);
    assert!(generated.manifest.reachability.is_reachable());
}

#[test]
fn connectivity_repairs_scale_to_larger_multi_floor_layouts() {
    // These previously exhausted every attempt by repeatedly rediscovering
    // isolated rooms. Repairs must retain their targets across attempts.
    for (seed, width, height, floors) in
        [(69, 5, 4, 2), (76, 5, 4, 2), (0, 8, 6, 3), (0, 16, 16, 4)]
    {
        let config = SpaceConfig {
            width,
            height,
            floors,
            agents_per_floor: 1,
            ..SpaceConfig::default()
        };
        let generated = spaces::generate(seed, &config)
            .unwrap_or_else(|error| panic!("{width}x{height}x{floors}, seed {seed}: {error}"));
        assert!(generated.manifest.reachability.is_reachable());
        assert!(
            generated
                .manifest
                .floors
                .iter()
                .any(|floor| !floor.repair_targets.is_empty())
        );
        assert!(generated.manifest.attempts.len() <= config.max_attempts as usize);
    }
}

#[test]
fn configuration_rejects_unsafe_dimensions_unknown_fields_and_invalid_weights() {
    assert!(serde_json::from_str::<SpaceConfig>(r#"{"widht": 8}"#).is_err());
    for config in [
        SpaceConfig {
            width: u32::MAX,
            ..SpaceConfig::default()
        },
        SpaceConfig {
            passage_width_m: 0.5,
            ..SpaceConfig::default()
        },
        SpaceConfig {
            tile_size_m: f64::NAN,
            ..SpaceConfig::default()
        },
        SpaceConfig {
            timestep_s: 0.0,
            ..SpaceConfig::default()
        },
    ] {
        assert!(config.validate().is_err());
    }
    let mut config = SpaceConfig::default();
    config.modules = config.catalog();
    config.modules[0].weight = 0;
    assert!(config.validate().is_err());
}
