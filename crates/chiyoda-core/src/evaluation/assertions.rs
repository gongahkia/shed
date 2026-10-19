use super::{CaseReport, Thresholds};
use crate::{
    RunBundle,
    bundle::{AgentSnapshot, AgentState},
    model::{Obstacle, Point3},
};
use std::collections::BTreeMap;

const TOLERANCE_M: f64 = 1e-8;

pub(super) fn check(bundle: &RunBundle, thresholds: &Thresholds, report: &mut CaseReport) {
    check_positions(bundle, report);
    check_intervals(bundle, report);
    let metrics = &bundle.metrics;
    let fraction = if metrics.total_agents == 0 {
        1.0
    } else {
        f64::from(metrics.evacuated_agents) / f64::from(metrics.total_agents)
    };
    if let Some(minimum) = thresholds.min_evacuated_fraction
        && fraction < minimum
    {
        report.fail(
            "min_evacuated_fraction",
            format!("observed {fraction}, required >= {minimum}"),
        );
    }
    upper_bound(
        report,
        "max_clearance_time_s",
        metrics.clearance_time_s,
        thresholds.max_clearance_time_s,
    );
    let queue_wait = metrics.queue_metrics.as_ref().map(|queue| {
        queue.lift.cumulative_wait_agent_seconds
            + queue.connector.cumulative_wait_agent_seconds
            + queue.gate.cumulative_wait_agent_seconds
            + queue.exit.cumulative_wait_agent_seconds
    });
    upper_bound(
        report,
        "max_queue_wait_agent_seconds",
        queue_wait,
        thresholds.max_queue_wait_agent_seconds,
    );
    let movement = metrics.movement_metrics.as_ref();
    upper_bound(
        report,
        "max_disc_overlap_m",
        movement
            .and_then(|movement| movement.on_surface_clearance_audit.as_ref())
            .map(|audit| audit.maximum_disc_overlap_m),
        thresholds.max_disc_overlap_m,
    );
    upper_bound(
        report,
        "max_swept_disc_overlap_m",
        movement
            .and_then(|movement| movement.swept_on_surface_clearance_audit.as_ref())
            .map(|audit| audit.maximum_swept_disc_overlap_m),
        thresholds.max_swept_disc_overlap_m,
    );
    if let Some(limit) = thresholds.max_avoidance_fallback_steps {
        match movement.and_then(|movement| movement.local_avoidance_constraint_fallback_steps) {
            Some(observed) if observed <= limit => {}
            Some(observed) => report.fail(
                "max_avoidance_fallback_steps",
                format!("observed {observed}, allowed <= {limit}"),
            ),
            None => report.fail(
                "max_avoidance_fallback_steps",
                "required telemetry is absent",
            ),
        }
    }
}

fn upper_bound(report: &mut CaseReport, check: &str, observed: Option<f64>, limit: Option<f64>) {
    if let Some(limit) = limit {
        match observed {
            Some(observed) if observed <= limit => {}
            Some(observed) => {
                report.fail(check, format!("observed {observed}, allowed <= {limit}"));
            }
            None => report.fail(
                check,
                "required metric is absent; incomplete evacuation has no clearance time",
            ),
        }
    }
}

fn occupies_surface(agent: &AgentSnapshot) -> bool {
    !matches!(
        agent.state,
        AgentState::WaitingToDepart | AgentState::InTransit | AgentState::Evacuated
    )
}

fn point(agent: &AgentSnapshot) -> Point3 {
    Point3 {
        x_m: agent.x_m,
        y_m: agent.y_m,
        z_m: agent.z_m,
    }
}

fn fail_once(report: &mut CaseReport, check: &str, detail: String) {
    if !report.failures.iter().any(|failure| failure.check == check) {
        report.fail(check, detail);
    }
}

fn check_positions(bundle: &RunBundle, report: &mut CaseReport) {
    let scenario = &bundle.scenario.scenario;
    let groups: BTreeMap<_, _> = scenario
        .agents
        .iter()
        .map(|group| (group.id.as_str(), group))
        .collect();
    for frame in &bundle.trace {
        for agent in &frame.agents {
            if ![agent.x_m, agent.y_m, agent.z_m]
                .iter()
                .all(|value| value.is_finite())
            {
                fail_once(
                    report,
                    "finite_positions",
                    format!(
                        "{} at step {} has nonfinite coordinates",
                        agent.id, frame.step
                    ),
                );
            }
            if !occupies_surface(agent) {
                continue;
            }
            let position = point(agent);
            let radius = groups[agent.group.as_str()].radius_m;
            let surface = scenario
                .surfaces
                .iter()
                .find(|surface| surface.id == agent.surface)
                .expect("runtime surfaces are declared");
            if !surface.contains(position) {
                fail_once(
                    report,
                    "surface_bounds",
                    format!("{} leaves its surface at step {}", agent.id, frame.step),
                );
            }
            if scenario
                .obstacles
                .iter()
                .filter(|obstacle| obstacle.surface == agent.surface)
                .any(|obstacle| {
                    obstacle.contains_with_clearance(position, (radius - TOLERANCE_M).max(0.0))
                })
            {
                fail_once(
                    report,
                    "static_clearance",
                    format!(
                        "{} intersects an expanded obstacle at step {}",
                        agent.id, frame.step
                    ),
                );
            }
        }
    }
}

fn check_intervals(bundle: &RunBundle, report: &mut CaseReport) {
    let scenario = &bundle.scenario.scenario;
    let groups: BTreeMap<_, _> = scenario
        .agents
        .iter()
        .map(|group| (group.id.as_str(), group))
        .collect();
    for interval in bundle.trace.windows(2) {
        let elapsed = interval[1].time_s - interval[0].time_s;
        let previous: BTreeMap<_, _> = interval[0]
            .agents
            .iter()
            .map(|agent| (&agent.id, agent))
            .collect();
        for end in interval[1]
            .agents
            .iter()
            .filter(|agent| occupies_surface(agent))
        {
            let Some(start) = previous
                .get(&end.id)
                .filter(|start| occupies_surface(start) && start.surface == end.surface)
            else {
                continue;
            };
            let group = groups[end.group.as_str()];
            if point(start).distance(point(end)) > group.speed_mps * elapsed + TOLERANCE_M {
                fail_once(
                    report,
                    "walking_speed_bound",
                    format!(
                        "{} exceeds its authored speed in interval ending at step {}",
                        end.id, interval[1].step
                    ),
                );
            }
            if scenario
                .obstacles
                .iter()
                .filter(|obstacle| obstacle.surface == end.surface)
                .any(|obstacle| {
                    intersects(
                        point(start),
                        point(end),
                        obstacle,
                        (group.radius_m - TOLERANCE_M).max(0.0),
                    )
                })
            {
                fail_once(
                    report,
                    "static_segment_clearance",
                    format!(
                        "{} crosses an expanded obstacle in interval ending at step {}",
                        end.id, interval[1].step
                    ),
                );
            }
        }
    }
}

fn intersects(start: Point3, end: Point3, obstacle: &Obstacle, radius: f64) -> bool {
    let mut entering = 0.0_f64;
    let mut leaving = 1.0_f64;
    for (origin, destination, low, high) in [
        (
            start.x_m,
            end.x_m,
            obstacle.at.x_m - radius,
            obstacle.at.x_m + obstacle.width_m + radius,
        ),
        (
            start.y_m,
            end.y_m,
            obstacle.at.y_m - radius,
            obstacle.at.y_m + obstacle.depth_m + radius,
        ),
    ] {
        let delta = destination - origin;
        if delta == 0.0 {
            if origin < low || origin > high {
                return false;
            }
        } else {
            let a = (low - origin) / delta;
            let b = (high - origin) / delta;
            entering = entering.max(a.min(b));
            leaving = leaving.min(a.max(b));
            if entering > leaving {
                return false;
            }
        }
    }
    entering <= leaving
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_corrupted_trace_fails_speed_and_segment_assertions() {
        let scenario = crate::parse("scenario \"corrupt\"\nseed 1\nduration 1s\ntimestep 1s\nsurface floor at (0m, 0m, 0m) size (10m, 10m)\nobstacle wall on floor at (4m, 3m, 0m) size (2m, 4m)\nexit out on floor at (9m, 5m, 0m) width 1m\nagents person count 1 on floor at (1m, 5m, 0m) to out speed 1m/s radius 0.3m height 1.7m\n").unwrap();
        let mut bundle = crate::run(
            &scenario,
            crate::RunOptions {
                trace_every_steps: 1,
            },
        )
        .unwrap();
        // Both endpoints are clear; the impossible one-second jump crosses
        // the obstacle in between them.
        bundle.trace[1].agents[0].x_m = 9.0;
        bundle.trace[1].agents[0].y_m = 5.0;
        let mut report = CaseReport::new(
            "corrupted".into(),
            None,
            super::super::ExpectedReachability::Reachable,
        );
        check(&bundle, &Thresholds::default(), &mut report);
        assert!(
            report
                .failures
                .iter()
                .any(|failure| failure.check == "walking_speed_bound")
        );
        assert!(
            report
                .failures
                .iter()
                .any(|failure| failure.check == "static_segment_clearance")
        );
    }

    #[test]
    fn analytic_segment_check_catches_an_obstacle_between_clear_endpoints() {
        let obstacle = Obstacle {
            id: "wall".into(),
            surface: "floor".into(),
            at: Point3 {
                x_m: 4.0,
                y_m: 3.0,
                z_m: 0.0,
            },
            width_m: 2.0,
            depth_m: 4.0,
        };
        let from = Point3 {
            x_m: 1.0,
            y_m: 5.0,
            z_m: 0.0,
        };
        let to = Point3 {
            x_m: 9.0,
            y_m: 5.0,
            z_m: 0.0,
        };
        assert!(intersects(from, to, &obstacle, 0.3));
        assert!(!intersects(
            Point3 { y_m: 2.0, ..from },
            Point3 { y_m: 2.0, ..to },
            &obstacle,
            0.3
        ));
    }
}
