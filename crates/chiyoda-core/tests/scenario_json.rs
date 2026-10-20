use chiyoda_core::{
    BundleVerification, CanonicalScenario, RunBundle, RunOptions, ScenarioJsonError,
    decode_scenario, encode_scenario, run, validate, verify_run_bundle,
};
use serde_json::{Value, json};

const INPUT: &str = include_str!("fixtures/json-free-walk.json");

#[test]
fn authentic_local_knowledge_bundle_from_the_previous_motion_contract_retains_its_hash() {
    let bundle: RunBundle =
        serde_json::from_str(include_str!("fixtures/legacy-0.44-run.json")).unwrap();
    assert_eq!(
        bundle.bundle_hash,
        "0947c7ef7f077ef013e673a465258f3c483d6d86a3882c678feec18322161d03"
    );
    assert!(bundle.verifies_hash());
    assert!(bundle.trace.last().unwrap().agents[0].knowledge.is_some());
    assert_eq!(
        verify_run_bundle(&bundle).unwrap(),
        BundleVerification::HashOnlyLegacy
    );
}

#[test]
fn authentic_pre_json_bundle_retains_its_hash_and_is_explicitly_legacy() {
    let bundle: RunBundle =
        serde_json::from_str(include_str!("fixtures/legacy-0.42-run.json")).unwrap();
    assert!(bundle.verifies_hash());
    assert_eq!(
        verify_run_bundle(&bundle).unwrap(),
        BundleVerification::HashOnlyLegacy
    );
    assert!(bundle.scenario.check_schema().is_err());
    let scenario = bundle.scenario.scenario;
    let current = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1000,
        },
    )
    .unwrap();
    assert_eq!(current.trace.len(), 2);
    assert_eq!(current.metrics.evacuated_agents, 1);
}

#[test]
fn versioned_json_round_trips_and_reconstructs_without_a_language_frontend() {
    let scenario = decode_scenario(INPUT).unwrap();
    validate(&scenario).unwrap();
    let encoded = encode_scenario(&scenario).unwrap();
    assert_eq!(scenario, decode_scenario(&encoded).unwrap());
    let bundle = run(&scenario, RunOptions::default()).unwrap();
    let bundle: RunBundle = serde_json::from_str(&serde_json::to_string(&bundle).unwrap()).unwrap();
    assert_eq!(
        verify_run_bundle(&bundle).unwrap(),
        BundleVerification::Reconstructed
    );
    assert_eq!(bundle.scenario.schema_version, "1");
}

#[test]
fn input_rejects_missing_future_legacy_duplicate_and_unknown_schema_fields() {
    let original: Value = serde_json::from_str(INPUT).unwrap();
    for version in [json!("999"), json!(0), Value::Null] {
        let mut input = original.clone();
        input["schema_version"] = version;
        assert!(decode_scenario(&input.to_string()).is_err());
    }
    let mut missing = original.clone();
    missing.as_object_mut().unwrap().remove("schema_version");
    assert!(decode_scenario(&missing.to_string()).is_err());
    let duplicate = INPUT.replacen(
        "\"schema_version\": \"1\"",
        "\"schema_version\": \"1\", \"schema_version\": \"1\"",
        1,
    );
    assert!(
        decode_scenario(&duplicate)
            .unwrap_err()
            .to_string()
            .contains("duplicate")
    );
    let mut unknown = original.clone();
    unknown["scenario"]["surfaces"][0]["widht_m"] = json!(10);
    assert!(
        decode_scenario(&unknown.to_string())
            .unwrap_err()
            .to_string()
            .contains("widht_m")
    );
    for extra_version in [Value::Null, json!("0.29")] {
        let mut ambiguous = original.clone();
        ambiguous["language_version"] = extra_version;
        assert!(decode_scenario(&ambiguous.to_string()).is_err());
    }
    let mut legacy = original;
    legacy.as_object_mut().unwrap().remove("schema_version");
    legacy["language_version"] = json!("0.29");
    let text = legacy.to_string();
    assert!(matches!(
        decode_scenario(&text),
        Err(ScenarioJsonError::Version { .. })
    ));
    let archived: CanonicalScenario = serde_json::from_str(&text).unwrap();
    let serialized = serde_json::to_value(&archived).unwrap();
    assert_eq!(
        serialized, legacy,
        "archival hashing retains the legacy envelope"
    );
}

#[test]
fn json_has_explicit_units_strict_types_and_stable_high_precision_numbers() {
    let original: Value = serde_json::from_str(INPUT).unwrap();
    for bad in [json!("10s"), Value::Null, json!(-1)] {
        let mut value = original.clone();
        value["scenario"]["duration_s"] = bad;
        if let Ok(scenario) = decode_scenario(&value.to_string()) {
            assert!(validate(&scenario).is_err());
        }
    }
    let mut scenario = decode_scenario(INPUT).unwrap();
    scenario.agents[0].speed_mps = 1.234_567_890_123_456_7;
    scenario.seed = u64::MAX;
    assert_eq!(
        decode_scenario(&encode_scenario(&scenario).unwrap()).unwrap(),
        scenario
    );
    let mut unknown = original;
    unknown["scenario"]["agents"][0]["count"] = json!(1.5);
    assert!(decode_scenario(&unknown.to_string()).is_err());
}

#[test]
fn semantic_checks_apply_to_json_and_programmatic_inputs() {
    let mut scenario = decode_scenario(INPUT).unwrap();
    scenario.surfaces[0].origin.x_m = f64::NAN;
    assert!(validate(&scenario).is_err());
    assert!(encode_scenario(&scenario).is_err());
    let mut scenario = chiyoda_core::generator::scenario(73).unwrap();
    scenario.countermeasures[0].source = chiyoda_core::model::InformationSource::Peer;
    assert!(
        validate(&scenario)
            .unwrap_err()
            .iter()
            .any(|error| error.path == "countermeasures[0].source")
    );
}
