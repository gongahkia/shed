use chiyoda_core::{
    generator::spaces,
    study::{AreaBasis, Placement, Population, PreparedInput, StudyConfig},
};
use serde_json::{Value, json};

fn document() -> Value {
    json!({
        "study_version": "1", "name": "test-study",
        "environment": {"duration_s": 30.0, "timestep_s": 0.2},
        "patterns": [{"id": "halls", "preset": "concourse"}],
        "scales": [{"id": "small", "width": 4, "height": 4, "floors": 1, "service_rate_multiplier": 1.0}],
        "loads": [{"id": "light", "population": {"kind": "fixed_per_floor", "agents": 2}}],
        "policies": [{"id": "quiet"}], "seeds": [73]
    })
}

fn config() -> StudyConfig {
    serde_json::from_value(document()).unwrap()
}

#[test]
fn study_axes_are_explicit_strict_and_resolve_to_geometry_only_generation() {
    let config = config();
    config.validate().unwrap();
    assert_eq!(config.case_count(), Some(1));
    let generation = config.generation(&config.patterns[0], &config.scales[0]);
    assert_eq!(generation.agents_per_floor, 1);
    assert!(!generation.perturbations);
    assert!(generation.require_connected);
    let mut typo = document();
    typo["scales"][0]["service_multiplier"] = json!(2);
    assert!(serde_json::from_value::<StudyConfig>(typo).is_err());
    let mut missing = document();
    missing["scales"][0]
        .as_object_mut()
        .unwrap()
        .remove("service_rate_multiplier");
    assert!(serde_json::from_value::<StudyConfig>(missing).is_err());
}

#[test]
fn ambiguous_axes_invalid_searches_and_unbounded_studies_are_rejected() {
    for axis in ["patterns", "scales", "loads", "policies", "seeds"] {
        for duplicate in [false, true] {
            let mut input = document();
            input[axis] = if duplicate {
                json!([input[axis][0].clone(), input[axis][0].clone()])
            } else {
                json!([])
            };
            assert!(
                serde_json::from_value::<StudyConfig>(input)
                    .unwrap()
                    .validate()
                    .is_err(),
                "{axis}"
            );
        }
    }
    let mut invalid = config();
    invalid.patterns[0].id = "../escape".into();
    assert!(invalid.validate().is_err());
    invalid = config();
    invalid.seeds = (0..4097).collect();
    assert!(invalid.validate().is_err());
    invalid = config();
    invalid.scales[0].width = 17;
    assert!(invalid.validate().is_err());
    invalid = config();
    invalid.environment.services.lift_cycle_s = f64::NAN;
    assert!(invalid.validate().is_err());
    invalid = config();
    invalid.scales[0].service_rate_multiplier = 0.0;
    assert!(invalid.validate().is_err());
}

#[test]
fn density_population_is_distributed_reproducible_and_services_scale_explicitly() {
    let mut config = config();
    config.loads[0].population = Population::Density {
        agents_per_m2: 0.02,
        basis: AreaBasis::Gross,
    };
    let mut large = config.scales[0].clone();
    large.id = "large".into();
    large.width = 8;
    large.height = 8;
    large.service_rate_multiplier = 4.0;
    config.scales.push(large);
    let mut counts = Vec::new();
    for scale in &config.scales {
        let generated =
            spaces::generate(73, &config.generation(&config.patterns[0], scale)).unwrap();
        let first =
            PreparedInput::from_space(&config, &generated, scale, &config.loads[0]).unwrap();
        let repeated =
            PreparedInput::from_space(&config, &generated, scale, &config.loads[0]).unwrap();
        assert_eq!(first, repeated);
        chiyoda_core::validate(&first.scenario).unwrap();
        assert!(
            chiyoda_core::reachability::check(&first.scenario)
                .unwrap()
                .is_reachable()
        );
        counts.push(first.context.total_people);
        let positions: std::collections::BTreeSet<_> = first
            .scenario
            .agents
            .iter()
            .map(|agent| (agent.at.x_m.to_bits(), agent.at.y_m.to_bits()))
            .collect();
        assert_eq!(positions.len(), first.scenario.agents.len());
        assert!(
            first
                .scenario
                .agents
                .iter()
                .map(|agent| agent.at.x_m)
                .fold(0.0, f64::max)
                > 12.0
        );
        assert_eq!(
            first.scenario.exits[0].capacity_per_s,
            Some(1.5 * scale.service_rate_multiplier)
        );
        assert!(
            (first.scenario.gates[0].service_rate_per_s - scale.service_rate_multiplier).abs()
                < f64::EPSILON
        );
        assert!(first.context.floors[0].walkable_area_m2 < first.context.floors[0].gross_area_m2);
    }
    assert_eq!(counts, vec![12, 47]);
}

#[test]
fn impossible_population_and_cadence_are_reported_without_resampling_geometry() {
    let mut config = config();
    config.loads[0].population = Population::FixedPerFloor { agents: 200 };
    config.loads[0].placement = Placement::ArrivalHall;
    let generated = spaces::generate(
        73,
        &config.generation(&config.patterns[0], &config.scales[0]),
    )
    .unwrap();
    let failure =
        PreparedInput::from_space(&config, &generated, &config.scales[0], &config.loads[0])
            .unwrap_err();
    assert_eq!(failure.stage, "setup_validation");
    config.loads[0].placement = Placement::Distributed;
    config.loads[0].release_interval_s = Some(1.0);
    assert_eq!(
        PreparedInput::from_space(&config, &generated, &config.scales[0], &config.loads[0])
            .unwrap_err()
            .stage,
        "population"
    );
    config.loads[0].release_interval_s = None;
    config.loads[0].population = Population::Density {
        agents_per_m2: 100.0,
        basis: AreaBasis::Walkable,
    };
    assert_eq!(
        PreparedInput::from_space(&config, &generated, &config.scales[0], &config.loads[0])
            .unwrap_err()
            .stage,
        "population"
    );
}
