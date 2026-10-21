use chiyoda_core::{CanonicalScenario, campaign::*, decode_scenario, experiment::Change};
use std::collections::BTreeMap;
fn request() -> CampaignRequest {
    CampaignRequest {
        campaign_version: "1".into(),
        name: "Test campaign".into(),
        screening: CaseSpec {
            id: "screen".into(),
            source: CaseSource::Authored {
                scenario: CanonicalScenario::from(
                    decode_scenario(include_str!("fixtures/json-free-walk.json")).unwrap(),
                ),
            },
            population_multiplier: 1,
        },
        stress_cases: vec![],
        candidates: vec![Candidate {
            id: "wider".into(),
            changes: vec![Change::SetExitWidth {
                id: "out".into(),
                width_m: 3.,
            }],
            case_changes: BTreeMap::new(),
        }],
        constraints: Constraints {
            allowed_edits: vec![Permission {
                kind: EditKind::SetExitWidth,
                targets: vec!["out".into()],
            }],
            bounds: vec![NumericBound {
                field: NumericField::ExitWidth,
                target: "out".into(),
                min: 1.,
                max: 4.,
            }],
            preserve_population: true,
            outcomes: vec![],
        },
        objectives: vec![Objective {
            metric: Metric::CompletedFraction,
            direction: Direction::Maximize,
            tolerance: 0.,
        }],
        promotion: Promotion::default(),
        options: CampaignOptions::default(),
    }
}
#[test]
fn campaign_plans_are_deterministic_permissioned_and_budget_the_worst_case() {
    let mut r = request();
    r.stress_cases.push(CaseSpec {
        id: "busier".into(),
        population_multiplier: 2,
        ..r.screening.clone()
    });
    let p = plan(&r).unwrap();
    assert!(p.runnable);
    assert_eq!(p, plan(&r).unwrap());
    assert_eq!(p.reserved_trials, 2);
    let simulation_cost: u64 = p
        .trials
        .iter()
        .map(|t| t.experiment.as_ref().unwrap().estimated_agent_steps)
        .sum();
    assert_eq!(
        p.reserved_agent_steps,
        simulation_cost + p.reserved_evidence_agent_steps
    );
    r.options.budget.max_agent_steps = p.reserved_agent_steps - 1;
    assert!(!plan(&r).unwrap().runnable);
    r.options.budget.max_agent_steps = p.reserved_agent_steps;
    r.candidates[0].changes.push(Change::SetGroupCount {
        id: "person".into(),
        count: 2,
    });
    let p = plan(&r).unwrap();
    assert!(p.runnable);
    assert!(
        p.trials
            .iter()
            .all(|t| t.issues.iter().any(|i| i.code == "permission"))
    );
    assert!(p.trials[0].issues.iter().any(|i| i.code == "population"));
    assert!(p.trials[1].experiment.as_ref().unwrap().arms[1].same_population_inputs);
}
#[test]
fn bounds_and_invalid_candidates_are_retained_without_blocking_other_trials() {
    let mut r = request();
    r.candidates.push(Candidate {
        id: "too_wide".into(),
        changes: vec![Change::SetExitWidth {
            id: "out".into(),
            width_m: 5.,
        }],
        case_changes: BTreeMap::new(),
    });
    let p = plan(&r).unwrap();
    assert!(p.runnable);
    assert!(p.trials[0].issues.is_empty());
    assert!(p.trials[1].issues.iter().any(|i| i.code == "numeric_bound"));
    assert!(p.cases[0].scenario.is_some());
    assert_eq!(
        r,
        serde_json::from_value(serde_json::to_value(&r).unwrap()).unwrap()
    );
    assert_eq!(
        json_schema()["properties"]["campaign_version"]["const"],
        "1"
    );
}
#[test]
fn generation_budgets_are_checked_before_the_solver_and_recipe_overrides_are_strict() {
    let mut r = request();
    r.stress_cases.push(CaseSpec {
        id: "wfc".into(),
        source: CaseSource::Generated {
            seed: 1,
            config: Box::default(),
        },
        population_multiplier: 1,
    });
    r.options.budget.max_generation_attempts = 0;
    let p = plan(&r).unwrap();
    assert!(!p.runnable);
    assert!(p.cases.is_empty());
    assert!(p.issues.iter().any(|i| i.path == "max_generation_attempts"));
    r.candidates[0].case_changes.insert("typo".into(), vec![]);
    assert_eq!(plan(&r).unwrap_err().code, "configuration");
}
