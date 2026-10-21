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
