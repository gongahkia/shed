use chiyoda_core::study::StudyConfig;
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
