use chiyoda_core::{
    CanonicalScenario, decode_scenario,
    experiment::{self, Change, ExperimentOptions, ExperimentRequest, Variant},
    model::{AgentKnowledge, Obstacle, Point3},
};

fn request(changes: Vec<Change>) -> ExperimentRequest {
    ExperimentRequest {
        experiment_version: experiment::EXPERIMENT_VERSION.into(),
        name: "Controlled test".into(),
        baseline: CanonicalScenario::from(
            decode_scenario(include_str!("fixtures/json-free-walk.json")).unwrap(),
        ),
        variants: vec![Variant {
            id: "candidate".into(),
            changes,
        }],
        options: ExperimentOptions::default(),
    }
}

#[test]
fn variants_start_independently_and_record_actual_changed_fields() {
    let mut input = request(vec![Change::MoveObstacle {
        id: "kiosk".into(),
        at: Point3::new(5.0, 0.0, 0.0),
    }]);
    input.baseline.scenario.obstacles.push(Obstacle {
        id: "kiosk".into(),
        surface: "floor".into(),
        at: Point3::new(4.0, 0.0, 0.0),
        width_m: 1.0,
        depth_m: 1.0,
    });
    input.variants.push(Variant {
        id: "capacity".into(),
        changes: vec![Change::SetExitCapacity {
            id: "out".into(),
            capacity_per_s: Some(0.5),
        }],
    });
    let plan = experiment::plan(&input).unwrap();
    assert!(plan.runnable, "{plan:?}");
    assert_eq!(
        plan.arms[1]
            .changed_fields
            .iter()
            .map(|field| field.field.as_str())
            .collect::<Vec<_>>(),
        ["obstacles"]
    );
    assert_eq!(
        plan.arms[2]
            .changed_fields
            .iter()
            .map(|field| field.field.as_str())
            .collect::<Vec<_>>(),
        ["exits"]
    );
    assert!(plan.arms.iter().all(|arm| arm.same_population_inputs));
    assert_eq!(input.baseline.scenario.obstacles[0].at.x_m, 4.0);
    assert_eq!(plan.estimated_agent_steps, 360);
    assert_eq!(plan.retained_trace_samples, 183);
    assert_eq!(plan, experiment::plan(&input).unwrap());
}

#[test]
fn invalid_and_unreachable_candidates_remain_inspectable() {
    let input = request(vec![Change::SetExitWidth {
        id: "missing".into(),
        width_m: 2.0,
    }]);
    let plan = experiment::plan(&input).unwrap();
    assert!(!plan.runnable);
    assert!(plan.arms[0].issues.is_empty());
    assert_eq!(plan.arms[1].issues[0].code, "unknown_id");
    assert_eq!(plan.arms[1].issues[0].path, "changes[0]");

    let input = request(vec![Change::AddObstacle {
        obstacle: Obstacle {
            id: "wall".into(),
            surface: "floor".into(),
            at: Point3::new(5.0, 0.0, 0.0),
            width_m: 1.0,
            depth_m: 6.0,
        },
    }]);
    let plan = experiment::plan(&input).unwrap();
    assert!(!plan.runnable);
    assert!(!plan.arms[1].reachability.as_ref().unwrap().is_reachable());
}

#[test]
fn population_and_information_interventions_have_distinct_pairing_semantics() {
    let mut input = request(vec![Change::SetGroupKnowledge {
        id: "person".into(),
        knowledge: Some(AgentKnowledge {
            observation_radius_m: 2.0,
            known_exits: None,
        }),
    }]);
    input.variants.push(Variant {
        id: "busier".into(),
        changes: vec![Change::SetGroupCount {
            id: "person".into(),
            count: 2,
        }],
    });
    let plan = experiment::plan(&input).unwrap();
    assert!(plan.runnable);
    assert!(plan.arms[1].same_population_inputs);
    assert!(!plan.arms[2].same_population_inputs);
}

#[test]
fn resource_limits_are_checked_before_expanding_people_or_running_a_trace() {
    let mut input = request(vec![]);
    input.options.max_agent_steps = 239;
    assert!(!experiment::plan(&input).unwrap().runnable);
    input.options.max_agent_steps = 240;
    input.options.max_trace_samples = 121;
    assert!(!experiment::plan(&input).unwrap().runnable);
    input.options.max_trace_samples = 122;
    assert!(experiment::plan(&input).unwrap().runnable);
    input.baseline.scenario.agents[0].count = u32::MAX;
    let inspected = experiment::inspect(&input.baseline);
    assert_eq!(inspected.people, u64::from(u32::MAX));
    assert_eq!(inspected.issues[0].code, "resource_limit");
    assert!(inspected.reachability.is_none());
    input.baseline.scenario.agents[0].count = 1;
    input.baseline.scenario.timestep_s = 1e-10;
    assert!(!experiment::plan(&input).unwrap().runnable);
}

#[test]
fn versions_ids_and_changes_are_strict_and_machine_discoverable() {
    let mut input = request(vec![]);
    input.experiment_version = "999".into();
    assert_eq!(experiment::plan(&input).unwrap_err().code, "version");
    input.experiment_version = "1".into();
    input.variants[0].id = "../escape".into();
    assert_eq!(experiment::plan(&input).unwrap_err().code, "configuration");
    assert!(
        serde_json::from_value::<Change>(
            serde_json::json!({"kind":"set_exit_width","id":"out","width_m":2,"typo":true})
        )
        .is_err()
    );
    let schema = experiment::json_schema();
    assert_eq!(schema["properties"]["experiment_version"]["const"], "1");
    assert_eq!(
        schema["$defs"]["CanonicalScenario"]["properties"]["schema_version"]["const"],
        "1"
    );
    assert_eq!(
        schema["$defs"]["Change"]["oneOf"].as_array().unwrap().len(),
        11
    );
}

#[test]
fn identical_control_reproduces_normal_runtime_and_rederives_saved_evidence() {
    let input = request(vec![]);
    let plan = experiment::plan(&input).unwrap();
    let executed = experiment::execute(&input, &plan.input_hash).unwrap();
    let baseline = executed.artifacts[0].bundle.as_ref().unwrap();
    let ordinary = chiyoda_core::run(
        &input.baseline.scenario,
        chiyoda_core::RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    assert_eq!(*baseline, ordinary);
    assert_eq!(baseline, executed.artifacts[1].bundle.as_ref().unwrap());
    assert!(
        executed
            .report
            .arms
            .iter()
            .all(|arm| arm.evaluation.passed && arm.measured)
    );
    assert_eq!(executed.report.comparisons[0].matched_people, 1);
    assert_eq!(
        executed.report.comparisons[0].agents[0].elapsed_difference_s,
        Some(0.0)
    );
    assert!(
        executed.report.comparisons[0]
            .metrics
            .values()
            .all(|metric| metric.candidate_minus_baseline == Some(0.0))
    );
    executed
        .report
        .verify(&input, &executed.artifacts, false)
        .unwrap();
    executed
        .report
        .verify(&input, &executed.artifacts, true)
        .unwrap();
    let roundtrip: experiment::ExperimentReport =
        serde_json::from_str(&serde_json::to_string(&executed.report).unwrap()).unwrap();
    roundtrip
        .verify(&input, &executed.artifacts, false)
        .unwrap();
}

#[test]
fn incomplete_and_threshold_failed_runs_are_visible_without_fabricated_clearance() {
    let mut input = request(vec![Change::SetExitCapacity {
        id: "out".into(),
        capacity_per_s: Some(0.01),
    }]);
    input.options.evaluation.thresholds.min_evacuated_fraction = Some(1.0);
    input.options.evaluation.thresholds.max_clearance_time_s = Some(10.0);
    let executed =
        experiment::execute(&input, &experiment::plan(&input).unwrap().input_hash).unwrap();
    assert!(executed.report.arms[0].evaluation.passed);
    assert!(!executed.report.arms[1].evaluation.passed);
    assert!(executed.report.arms[1].measured);
    let comparison = &executed.report.comparisons[0];
    assert_eq!(
        comparison.metrics["completed_fraction"].candidate,
        Some(0.0)
    );
    assert!(comparison.metrics["clearance_time_s"].baseline.is_some());
    assert_eq!(comparison.metrics["clearance_time_s"].candidate, None);
    assert_eq!(
        comparison.metrics["clearance_time_s"].candidate_minus_baseline,
        None
    );
    assert_eq!(comparison.both_completed, 0);
    assert_eq!(comparison.agents[0].elapsed_difference_s, None);
    assert!(
        executed.report.arms[1]
            .findings
            .iter()
            .any(|finding| finding.code == "observed_service_wait")
    );
    executed
        .report
        .verify(&input, &executed.artifacts, true)
        .unwrap();
}

#[test]
fn changed_demand_disables_person_pairs_and_stale_inputs_cannot_execute() {
    let mut input = request(vec![Change::SetGroupCount {
        id: "person".into(),
        count: 2,
    }]);
    let hash = experiment::plan(&input).unwrap().input_hash;
    input.options.max_agent_steps -= 1;
    assert_eq!(
        experiment::execute(&input, &hash).unwrap_err().code,
        "stale_plan"
    );
    let executed =
        experiment::execute(&input, &experiment::plan(&input).unwrap().input_hash).unwrap();
    assert_eq!(executed.report.comparisons[0].matched_people, 0);
    assert!(executed.report.comparisons[0].agents.is_empty());
    assert_eq!(executed.report.arms[1].observations["people"], 2.0);
}

#[test]
fn resealed_conclusions_and_cross_arm_evidence_do_not_verify() {
    let mut input = request(vec![]);
    input.options.evaluation.verify_replay = false;
    let mut executed =
        experiment::execute(&input, &experiment::plan(&input).unwrap().input_hash).unwrap();
    executed
        .report
        .verify(&input, &executed.artifacts, true)
        .unwrap();
    assert!(!executed.report.arms[0].evaluation.replay_verified);
    executed.report.comparisons[0].agents[0].elapsed_difference_s = Some(-100.0);
    executed.report.report_hash.clear();
    executed.report.report_hash = chiyoda_core::bundle::canonical_hash(&executed.report);
    assert_eq!(
        executed
            .report
            .verify(&input, &executed.artifacts, false)
            .unwrap_err()
            .code,
        "integrity"
    );
}
