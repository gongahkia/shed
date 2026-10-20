//! Small authored regression contracts, embedded so packaging or a different
//! working directory cannot silently substitute benchmark inputs.

use super::{
    BENCHMARK_VERSION, BenchmarkCase, BenchmarkError, BenchmarkSuite, QueueMechanism, invalid,
};
use crate::evaluation::{EvaluationOptions, ExpectedReachability, Thresholds};

pub const NAMES: [&str; 6] = [
    "smoke",
    "navigation",
    "crowds",
    "operations",
    "all",
    "stress",
];

pub fn suite(name: &str) -> Result<BenchmarkSuite, BenchmarkError> {
    if !NAMES.contains(&name) {
        return Err(invalid(format!(
            "unknown profile `{name}`; choose {}",
            NAMES.join(", ")
        )));
    }
    let all = if name == "stress" {
        vec![
            case(
                "dense-bottleneck",
                "Dense simultaneous arrivals expose current disc-overlap limits.",
                include_str!("fixtures/dense-bottleneck.json"),
                40.0,
            ),
            case(
                "symmetric-crossing",
                "Simultaneous symmetric streams expose current low-progress limits.",
                include_str!("fixtures/symmetric-crossing.json"),
                20.0,
            ),
            case(
                "gate-queue",
                "Strict disc-clearance check of the physical gate queue.",
                include_str!("fixtures/gate-queue.json"),
                40.0,
            ),
            case(
                "exit-queue",
                "Strict disc-clearance check of the physical exit queue.",
                include_str!("fixtures/exit-queue.json"),
                40.0,
            ),
            case(
                "lift-queue",
                "Strict disc-clearance check of the physical lift queue.",
                include_str!("fixtures/lift-queue.json"),
                35.0,
            ),
        ]
    } else {
        cases()
    };
    let selected = match name {
        "smoke" => vec![
            "free-walk",
            "obstacle-detour",
            "gate-queue",
            "connector-closure",
        ],
        "navigation" => vec!["free-walk", "obstacle-detour", "bottleneck"],
        "crowds" => vec!["bottleneck", "counterflow", "crossing", "queue-grid"],
        "operations" => vec![
            "gate-queue",
            "exit-queue",
            "lift-queue",
            "connector-closure",
            "queue-grid",
        ],
        _ => all.iter().map(|case| case.id.as_str()).collect(),
    };
    let cases = all
        .iter()
        .filter(|case| selected.contains(&case.id.as_str()))
        .cloned()
        .collect();
    let suite = BenchmarkSuite {
        benchmark_version: BENCHMARK_VERSION.into(),
        name: name.into(),
        origin: None,
        diagnostics: super::diagnostics::DiagnosticOptions::default(),
        cases,
    };
    suite.validate()?;
    Ok(suite)
}

fn case(id: &str, description: &str, source: &str, clearance_s: f64) -> BenchmarkCase {
    BenchmarkCase {
        id: id.into(),
        description: description.into(),
        scenario: serde_json::from_str(source)
            .expect("embedded benchmark document matches the scenario schema"),
        expected: ExpectedReachability::Reachable,
        evaluation: EvaluationOptions {
            verify_replay: true,
            thresholds: Thresholds {
                min_evacuated_fraction: Some(1.0),
                max_clearance_time_s: Some(clearance_s),
                max_disc_overlap_m: Some(0.0),
                max_swept_disc_overlap_m: Some(0.0),
                ..Thresholds::default()
            },
        },
        checks: super::BehaviorChecks::default(),
    }
}

fn cases() -> Vec<BenchmarkCase> {
    let mut cases = vec![
        case(
            "free-walk",
            "One pedestrian crosses an unobstructed floor.",
            include_str!("fixtures/free-walk.json"),
            10.0,
        ),
        case(
            "obstacle-detour",
            "A pedestrian must walk around a rectangular island.",
            include_str!("fixtures/obstacle-detour.json"),
            18.0,
        ),
        case(
            "bottleneck",
            "Six paced arrivals pass a narrow physical opening.",
            include_str!("fixtures/bottleneck.json"),
            40.0,
        ),
        case(
            "counterflow",
            "Two opposing groups share a corridor.",
            include_str!("fixtures/counterflow.json"),
            30.0,
        ),
        case(
            "crossing",
            "Four offset streams cross with staggered releases.",
            include_str!("fixtures/crossing.json"),
            20.0,
        ),
        case(
            "gate-queue",
            "A declared half-person-per-second gate produces a queue.",
            include_str!("fixtures/gate-queue.json"),
            40.0,
        ),
        case(
            "exit-queue",
            "A declared half-person-per-second exit produces a queue.",
            include_str!("fixtures/exit-queue.json"),
            40.0,
        ),
        case(
            "lift-queue",
            "A one-person cabin serializes a four-person transfer.",
            include_str!("fixtures/lift-queue.json"),
            35.0,
        ),
        case(
            "connector-closure",
            "A physical closure forces an initially preferred route to change.",
            include_str!("fixtures/connector-closure.json"),
            35.0,
        ),
        case(
            "queue-grid",
            "Six pedestrians form and drain a serpentine FIFO gate queue.",
            include_str!("fixtures/queue-grid.json"),
            75.0,
        ),
    ];
    for case in &mut cases {
        let queue = match case.id.as_str() {
            "gate-queue" | "queue-grid" => Some(QueueMechanism::Gate),
            "exit-queue" => Some(QueueMechanism::Exit),
            "lift-queue" => Some(QueueMechanism::Lift),
            _ => None,
        };
        if matches!(case.id.as_str(), "gate-queue" | "exit-queue" | "lift-queue") {
            // Operational contracts assert completion, actual queue exposure,
            // and service waits. Strict zero-overlap contracts for these same
            // inputs remain visible in the separate stress profile.
            case.evaluation.thresholds.max_disc_overlap_m = None;
            case.evaluation.thresholds.max_swept_disc_overlap_m = None;
        }
        if let Some(queue) = queue {
            case.checks.min_queued_agents.insert(queue, 2);
            case.checks.min_queue_wait_agent_seconds.insert(queue, 1.0);
            case.evaluation.thresholds.max_queue_wait_agent_seconds =
                Some(if case.id == "queue-grid" { 240.0 } else { 30.0 });
        }
        if case.id == "connector-closure" {
            case.checks.min_events.insert("route_recomputed".into(), 2);
        }
        if case.id == "queue-grid" {
            case.checks
                .min_events
                .insert("queue_service_reserved".into(), 6);
        }
        if matches!(case.id.as_str(), "free-walk" | "obstacle-detour") {
            case.evaluation.thresholds.max_avoidance_fallback_steps = Some(0);
            case.evaluation.thresholds.max_queue_wait_agent_seconds = Some(0.0);
        }
    }
    cases
}
