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

fn clone_trial(t: &TrialExecution) -> TrialExecution {
    TrialExecution {
        result: t.result.clone(),
        request: t.request.clone(),
        execution: t
            .execution
            .as_ref()
            .map(|e| chiyoda_core::experiment::ExperimentExecution {
                report: e.report.clone(),
                artifacts: e.artifacts.clone(),
            }),
    }
}
#[test]
fn screening_promotes_only_declared_candidates_and_stress_keeps_every_case_and_source() {
    let mut r = request();
    r.promotion.require_improvement = false;
    r.promotion.max_candidates = 1;
    r.stress_cases.push(CaseSpec {
        id: "more_people".into(),
        population_multiplier: 2,
        ..r.screening.clone()
    });
    r.candidates.push(Candidate {
        id: "forbidden".into(),
        changes: vec![Change::SetGroupCount {
            id: "person".into(),
            count: u32::MAX,
        }],
        case_changes: BTreeMap::new(),
    });
    let p = plan(&r).unwrap();
    assert!(p.runnable, "{:?}", p.issues);
    let execution = execute(&r, &p.plan_hash).unwrap();
    let report = &execution.report;
    assert_eq!(report.promoted, ["wider"]);
    assert_eq!(report.trials.len(), 3);
    assert_eq!(report.trials[1].status, TrialStatus::Rejected);
    assert!(report.candidates[0].final_rank.is_some());
    assert_eq!(report.candidates[0].expected_cases, 2);
    assert_eq!(report.candidates[0].eligible_cases, 2);
    assert!(
        report.charged_simulation_agent_steps
            + report
                .evidence_moments
                .iter()
                .map(|m| m.reconstruction_agent_steps)
                .sum::<u64>()
            <= p.reserved_agent_steps
    );
    report
        .verify_with(&r, true, |case, candidate| {
            Ok(clone_trial(
                execution
                    .trials
                    .iter()
                    .find(|t| t.result.case == case && t.result.candidate == candidate)
                    .unwrap(),
            ))
        })
        .unwrap();
    for t in &execution.trials {
        if let Some(e) = &t.execution {
            assert_eq!(e.report.arms.len(), 2);
            assert_eq!(
                e.report.comparisons[0].matched_people,
                e.artifacts[0].bundle.as_ref().unwrap().metrics.total_agents
            );
            for moment in &t.result.moments {
                let arm = e.artifacts.iter().find(|a| a.id == moment.arm).unwrap();
                assert!(
                    arm.bundle
                        .as_ref()
                        .unwrap()
                        .trace
                        .iter()
                        .any(|f| f.step == moment.step)
                );
                assert_eq!(
                    moment.source_bundle_hash,
                    arm.bundle.as_ref().unwrap().bundle_hash
                );
            }
        }
    }
}
#[test]
fn stale_plans_do_not_run_and_resealed_campaign_conclusions_do_not_verify() {
    let mut r = request();
    r.promotion.require_improvement = false;
    let p = plan(&r).unwrap();
    let execution = execute(&r, &p.plan_hash).unwrap();
    r.objectives[0].tolerance = 0.01;
    let mut observed = 0;
    assert_eq!(
        execute_with(&r, &p.plan_hash, |_| {
            observed += 1;
            Ok(())
        })
        .unwrap_err()
        .code,
        "stale_plan"
    );
    assert_eq!(observed, 0);
    r.objectives[0].tolerance = 0.;
    let mut false_report = execution.report.clone();
    false_report.promoted.clear();
    false_report.report_hash.clear();
    false_report.report_hash = chiyoda_core::bundle::canonical_hash(&false_report);
    assert!(false_report.check_hash().is_ok());
    assert_eq!(
        false_report
            .verify_with(&r, false, |case, candidate| Ok(clone_trial(
                execution
                    .trials
                    .iter()
                    .find(|t| t.result.case == case && t.result.candidate == candidate)
                    .unwrap()
            )))
            .unwrap_err()
            .code,
        "integrity"
    );
}
#[test]
fn unchanged_controls_are_not_promoted_as_improvements_and_missing_clearance_stays_missing() {
    let mut r = request();
    r.candidates[0].changes.clear();
    let p = plan(&r).unwrap();
    let e = execute(&r, &p.plan_hash).unwrap();
    assert!(e.report.promoted.is_empty());
    assert_eq!(e.report.candidates[0].status, "not_promoted");
    if let CaseSource::Authored { scenario } = &mut r.screening.source {
        scenario.scenario.duration_s = 0.5;
    }
    r.objectives.push(Objective {
        metric: Metric::ClearanceTimeS,
        direction: Direction::Minimize,
        tolerance: 0.,
    });
    let e = execute(&r, &plan(&r).unwrap().plan_hash).unwrap();
    assert!(!e.report.trials[0].eligible);
    assert!(e.report.trials[0].objective_values[1].benefit.is_none());
    assert!(
        e.report.trials[0]
            .reasons
            .iter()
            .any(|i| i.code == "missing_objective")
    );
}
