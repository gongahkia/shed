use chiyoda_core::study::{StudyReport, StudyRunner};
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
fn local_knowledge_is_a_controlled_population_input_in_generated_studies() {
    let mut input = document();
    input["environment"]["duration_s"] = json!(0.4);
    input["loads"][0]["knowledge"] = json!({"observation_radius_m": 0.0, "known_exits": []});
    input["policies"] = json!([
        {"id": "quiet"},
        {"id": "introduce-exit", "messages": [{
            "id": "notice", "source": "Official", "surface": "floor_0", "origin": {"anchor": "arrival_hall"},
            "claim": {"ExitAvailability": {"exit": "primary", "open": true}},
            "truthful": true, "at_s": 0.2, "reach_m": 10000, "trust": 1
        }]}
    ]);
    let config: StudyConfig = serde_json::from_value(input.clone()).unwrap();
    let cases: Vec<_> = StudyRunner::new(config.clone()).unwrap().collect();
    assert!(cases.iter().all(|case| case.result.passed));
    assert_eq!(cases[0].result.context, cases[1].result.context);
    assert_eq!(
        cases[0].result.generation_hash,
        cases[1].result.generation_hash
    );
    let quiet = cases[0].bundle.as_ref().unwrap();
    let announced = cases[1].bundle.as_ref().unwrap();
    assert_eq!(
        quiet.trace.last().unwrap().agents[0].state,
        chiyoda_core::AgentState::WaitingForRoute
    );
    assert!(
        announced
            .trace
            .last()
            .unwrap()
            .agents
            .iter()
            .all(|agent| agent.knowledge.as_ref().unwrap().known_exits == ["primary"])
    );
    assert_eq!(
        announced.metrics.information_delivery["notice"].accepted_agents,
        2
    );
    let report =
        StudyReport::from_cases(config, cases.into_iter().map(|case| case.result).collect())
            .unwrap();
    report.verify().unwrap();
    for knowledge in [
        json!({"observation_radius_m": -1}),
        json!({"observation_radius_m": 1, "known_exits": ["missing"]}),
        json!({"observation_radius_m": 1, "known_exits": ["primary", "primary"]}),
    ] {
        input["loads"][0]["knowledge"] = knowledge;
        assert!(
            serde_json::from_value::<StudyConfig>(input.clone())
                .unwrap()
                .validate()
                .is_err()
        );
    }
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

#[test]
fn policy_cases_share_exact_inputs_and_identical_policies_have_identical_execution() {
    let mut config = config();
    let mut same = config.policies[0].clone();
    same.id = "same-policy-different-label".into();
    config.policies.push(same);
    config.seeds.push(74);
    let mut runner = StudyRunner::new(config).unwrap();
    assert_eq!(runner.len(), 4);
    let first = runner.next().unwrap();
    let second = runner.next().unwrap();
    assert_eq!(first.result.context, second.result.context);
    assert_eq!(first.result.layout_hash, second.result.layout_hash);
    assert_eq!(first.generation, second.generation);
    assert_eq!(first.bundle, second.bundle);
    assert!(
        first
            .result
            .observations()
            .contains_key("evacuated_fraction")
    );
    assert_eq!(runner.count(), 2);
}

#[test]
fn failed_generation_is_retained_for_every_requested_policy_and_load() {
    let mut config = config();
    config.environment.max_attempts = 2;
    config.patterns[0].pins.push(spaces::FloorConstraint {
        floor: 0,
        x: 0,
        y: 0,
        allowed_tiles: vec!["hall_nesw".into()],
    });
    let mut extra = config.policies[0].clone();
    extra.id = "second".into();
    config.policies.push(extra);
    let cases: Vec<_> = StudyRunner::new(config).unwrap().collect();
    assert_eq!(cases.len(), 2);
    assert_eq!(cases[0].result.setup_failure, cases[1].result.setup_failure);
    for case in cases {
        assert_eq!(case.result.generation_attempts.len(), 2);
        assert_eq!(
            case.result.setup_failure.as_ref().unwrap().stage,
            "generation"
        );
        assert!(!case.result.passed);
        assert!(case.bundle.is_none());
        assert!(case.result.observations().is_empty());
    }
}

#[test]
fn incomplete_runs_are_measured_and_invalid_policies_do_not_drop_later_cases() {
    let mut input = document();
    input["environment"]["duration_s"] = json!(0.2);
    input["evaluation"] =
        json!({"thresholds":{"min_evacuated_fraction":1.0, "max_clearance_time_s":0.2}});
    input["policies"] = json!([
        {"id":"invalid", "messages":[{"id":"notice", "source":"Official", "surface":"floor_0", "origin":{"x_m":0.0,"y_m":0.0,"z_m":0.0}, "claim":{"ExitAvailability":{"exit":"missing", "open":false}}, "truthful":true,"at_s":0.0,"reach_m":1000.0,"trust":1.0}]},
        {"id":"quiet"}
    ]);
    let cases: Vec<_> = StudyRunner::new(serde_json::from_value(input).unwrap())
        .unwrap()
        .collect();
    assert_eq!(cases.len(), 2);
    assert!(cases[0].bundle.is_none());
    assert!(cases[0].result.observations().is_empty());
    assert!(cases[1].bundle.is_some());
    assert!(!cases[1].result.passed);
    let observations = cases[1].result.observations();
    assert_eq!(observations.get("evacuated_fraction"), Some(&0.0));
    assert!(!observations.contains_key("clearance_time_s"));
}

#[test]
fn reports_verify_pairing_count_missing_outcomes_and_detect_tampering() {
    let mut input = document();
    input["environment"]["duration_s"] = json!(0.2);
    input["evaluation"] =
        json!({"thresholds":{"min_evacuated_fraction":1.0,"max_clearance_time_s":0.2}});
    input["policies"] = json!([{"id":"quiet"}, {"id":"same"}]);
    input["seeds"] = json!([73, 74, 75]);
    let config: StudyConfig = serde_json::from_value(input).unwrap();
    let cases = StudyRunner::new(config.clone())
        .unwrap()
        .map(|case| case.result)
        .collect::<Vec<_>>();
    let report = StudyReport::from_cases(config.clone(), cases.clone()).unwrap();
    report.verify().unwrap();
    let encoded = serde_json::to_string(&report).unwrap();
    serde_json::from_str::<StudyReport>(&encoded)
        .unwrap()
        .verify()
        .unwrap();
    assert_eq!(report.requested_layouts, 3);
    assert_eq!(report.generated_layouts, 3);
    assert_eq!(report.failed, 6);
    assert_eq!(report.groups[0].measured, 3);
    assert_eq!(report.groups[0].fully_evacuated, 0);
    assert!(!report.groups[0].metrics.contains_key("clearance_time_s"));
    assert_eq!(report.comparisons.len(), 1);
    let delta = &report.comparisons[0].deltas["evacuated_fraction"];
    assert_eq!(delta.samples, 3);
    assert!(delta.mean.abs() < f64::EPSILON);
    let mut tampered = report.clone();
    tampered.groups[0].measured += 1;
    assert!(tampered.verify().is_err());
    let mut tampered = cases.clone();
    tampered[1].context.as_mut().unwrap().population_hash = "different".into();
    assert!(StudyReport::from_cases(config.clone(), tampered).is_err());
    let mut reordered = cases.clone();
    reordered.swap(0, 1);
    assert!(StudyReport::from_cases(config.clone(), reordered).is_err());
    assert!(StudyReport::from_cases(config, cases[..5].to_vec()).is_err());
}

#[test]
fn summary_percentiles_and_comparisons_use_each_metrics_available_seed_pairs() {
    let mut input = document();
    input["environment"]["duration_s"] = json!(0.2);
    input["seeds"] = json!([73, 74, 75, 76]);
    input["policies"] = json!([{"id":"quiet"},{"id":"second"}]);
    let config: StudyConfig = serde_json::from_value(input).unwrap();
    let mut cases = StudyRunner::new(config.clone())
        .unwrap()
        .map(|case| case.result)
        .collect::<Vec<_>>();
    for (index, case) in cases.iter_mut().enumerate() {
        // The percentile fixture replaces observations with synthetic metrics.
        case.journeys = None;
        let metrics = case.evaluation.as_mut().unwrap().metrics.as_mut().unwrap();
        if index != 7 {
            metrics.evacuated_agents = metrics.total_agents;
            metrics.clearance_time_s = Some(
                f64::from(u32::try_from(index / 2 + 1).unwrap()) * 0.02
                    + if index % 2 == 1 { 0.01 } else { 0.0 },
            );
        }
    }
    let report = StudyReport::from_cases(config, cases).unwrap();
    let baseline = &report.groups[0].metrics["clearance_time_s"];
    assert!((baseline.mean - 0.05).abs() < f64::EPSILON);
    assert!((baseline.median - 0.05).abs() < f64::EPSILON);
    assert!((baseline.p95 - 0.08).abs() < f64::EPSILON);
    let differences = &report.comparisons[0].deltas["clearance_time_s"];
    assert_eq!(differences.samples, 3);
    assert_eq!(differences.missing, 1);
    assert!((differences.mean - 0.01).abs() < f64::EPSILON);
}

#[test]
fn anchored_communication_is_resolved_before_evaluation_and_preserves_common_inputs() {
    let mut value = document();
    value["environment"]["duration_s"] = json!(4.0);
    value["loads"][0]["population"]["agents"] = json!(12);
    let message = json!({"id":"rumor","source":"Peer","surface":"floor_0","origin":{"anchor":"arrival_hall"},
        "claim":{"ExitAvailability":{"exit":"primary","open":false}},"truthful":false,"at_s":0.2,"reach_m":10000.0,"trust":1.0,"sampling_key":"shared-rumor"});
    let correction = json!({"id":"correction","corrects":"rumor","source":"Staff","surface":"floor_0","origin":{"anchor":"arrival_hall"},
        "at_s":0.4,"reach_m":10000.0,"trust":1.0,"sampling_key":"shared-correction"});
    value["policies"] = json!([{"id":"quiet"},{"id":"rumor","messages":[message.clone()]},{"id":"corrected","messages":[message],"countermeasures":[correction]}]);
    let study: StudyConfig = serde_json::from_value(value).unwrap();
    let cases: Vec<_> = StudyRunner::new(study).unwrap().collect();
    assert_eq!(cases.len(), 3);
    assert_eq!(cases[0].result.context, cases[2].result.context);
    for case in &cases {
        assert!(case.result.passed, "{:?}", case.result.evaluation);
    }
    let misinformation = cases[1].bundle.as_ref().unwrap();
    let corrected = cases[2].bundle.as_ref().unwrap();
    assert_eq!(
        misinformation.metrics.information_delivery["rumor"].accepted_agents,
        12
    );
    assert_eq!(
        corrected.metrics.information_delivery["correction"].accepted_agents,
        12
    );
    assert_ne!(misinformation.trace, corrected.trace);
    chiyoda_core::validate(&corrected.scenario.scenario).unwrap();
    assert!(corrected.scenario.scenario.messages[0].origin.x_m > 0.0);
    let generated = spaces::generate(73, &config_for_anchor()).unwrap();
    let mut config = config();
    config.scales[0].height = 8;
    config.scales[0].width = 8;
    let prepared =
        PreparedInput::from_space(&config, &generated, &config.scales[0], &config.loads[0])
            .unwrap();
    let policy: chiyoda_core::study::CommunicationPolicy=serde_json::from_value(json!({"id":"large","messages":[{
        "id":"notice","source":"Peer","surface":"floor_0","origin":{"anchor":"arrival_hall"},"claim":{"ExitAvailability":{"exit":"primary","open":false}},
        "truthful":false,"at_s":0.0,"reach_m":10000.0,"trust":1.0}]})).unwrap();
    let resolved = prepared.with_policy(&policy);
    chiyoda_core::validate(&resolved).unwrap();
    assert!(resolved.messages[0].origin.y_m > 24.0);
}

fn config_for_anchor() -> spaces::SpaceConfig {
    let mut config = config();
    config.scales[0].width = 8;
    config.scales[0].height = 8;
    config.generation(&config.patterns[0], &config.scales[0])
}

#[test]
fn a_full_matrix_accounts_for_every_axis_and_uses_declared_references() {
    let mut study = config();
    study.environment.duration_s = 0.2;
    study.patterns[0].id = "z-halls".into();
    let mut other = study.patterns[0].clone();
    other.id = "a-corridors".into();
    other.preset = spaces::Preset::Corridors;
    study.patterns.push(other);
    let mut large = study.scales[0].clone();
    large.id = "large".into();
    large.width = 5;
    large.height = 5;
    study.scales.push(large);
    let mut load = study.loads[0].clone();
    load.id = "busy".into();
    load.population = Population::FixedPerFloor { agents: 3 };
    study.loads.push(load);
    let mut policy = study.policies[0].clone();
    policy.id = "same".into();
    study.policies.push(policy);
    study.seeds.push(74);
    let cases = StudyRunner::new(study.clone())
        .unwrap()
        .map(|case| case.result)
        .collect();
    let report = StudyReport::from_cases(study, cases).unwrap();
    report.verify().unwrap();
    assert_eq!(report.requested, 32);
    assert_eq!(report.requested_layouts, 8);
    assert_eq!(report.groups.len(), 16);
    assert_eq!(report.comparisons.len(), 32);
    for comparison in &report.comparisons {
        if comparison.axis == "pattern" {
            assert_eq!(comparison.baseline.pattern, "z-halls");
        }
        if comparison.axis == "policy" {
            assert_eq!(comparison.measured_pairs, 2);
            for delta in comparison.deltas.values() {
                assert!(delta.mean.abs() < f64::EPSILON);
            }
        }
    }
}

#[test]
fn service_events_and_lifts_scale_and_loads_share_a_deterministic_spatial_prefix() {
    let mut value = document();
    value["scales"][0]["floors"] = json!(2);
    value["scales"][0]["service_rate_multiplier"] = json!(2.0);
    value["loads"][0]["release_interval_s"] = json!(0.2);
    value["loads"].as_array_mut().unwrap().push(json!({"id":"more","population":{"kind":"fixed_per_floor","agents":8},"release_interval_s":0.2}));
    value["operations"] = json!({
        "exit_capacity_states":[{"id":"exit-rate","exit":"primary","capacity_per_s":0.5,"at_s":2.0}],
        "gate_capacity_states":[{"id":"gate-rate","gate":"primary_gate","capacity_per_s":0.25,"at_s":2.0}],
        "connector_capacity_states":[{"id":"stair-rate","connector":"link_1_0","capacity_per_s":0.25,"at_s":2.0}]
    });
    let study: StudyConfig = serde_json::from_value(value).unwrap();
    let generated =
        spaces::generate(73, &study.generation(&study.patterns[0], &study.scales[0])).unwrap();
    let light =
        PreparedInput::from_space(&study, &generated, &study.scales[0], &study.loads[0]).unwrap();
    let busy =
        PreparedInput::from_space(&study, &generated, &study.scales[0], &study.loads[1]).unwrap();
    for agent in &light.scenario.agents {
        assert!(busy.scenario.agents.contains(agent));
    }
    assert_eq!(
        light.scenario.exit_capacity_states[0]
            .capacity_per_s
            .to_bits(),
        1.0_f64.to_bits()
    );
    assert_eq!(
        light.scenario.gate_capacity_states[0]
            .capacity_per_s
            .to_bits(),
        0.5_f64.to_bits()
    );
    assert_eq!(
        light.scenario.connector_capacity_states[0]
            .capacity_per_s
            .to_bits(),
        0.5_f64.to_bits()
    );
    for connector in &light.scenario.connectors {
        if let chiyoda_core::model::Connector::Lift {
            cycle_s, capacity, ..
        } = connector
        {
            assert!((*cycle_s - 3.0).abs() < f64::EPSILON);
            assert_eq!(*capacity, 4);
        }
    }
    let mut too_large = study.clone();
    too_large.environment.duration_s = 1000.0;
    too_large.loads[0].population = Population::FixedPerFloor { agents: 1024 };
    let generated = spaces::generate(
        73,
        &too_large.generation(&too_large.patterns[0], &too_large.scales[0]),
    )
    .unwrap();
    assert_eq!(
        PreparedInput::from_space(
            &too_large,
            &generated,
            &too_large.scales[0],
            &too_large.loads[0]
        )
        .unwrap_err()
        .stage,
        "resource_limit"
    );
}
