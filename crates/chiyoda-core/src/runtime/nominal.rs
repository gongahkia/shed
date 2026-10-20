//! A named static planning reference, not a prediction of an operational run.
use super::{
    ExitPlanConstraints, RouteStart, route_to_target_avoiding, select_exit_plan, waypoint_target,
};
use crate::{
    Scenario,
    journeys::NominalJourney,
    model::{AgentGroup, Point3},
};
use std::collections::HashSet;

pub(crate) fn journey_reference(
    scenario: &Scenario,
    group: &AgentGroup,
    position: Point3,
) -> Option<NominalJourney> {
    let none = HashSet::new();
    let mut start = RouteStart {
        surface: &group.surface,
        position,
        walking_speed_mps: group.speed_mps,
        radius_m: group.radius_m,
        height_m: group.height_m,
        excluded_connector_kinds: &group.excluded_connector_kinds,
    };
    let mut travel = 0.0;
    let mut dwell = 0.0;
    for id in &group.via {
        let target = waypoint_target(scenario, id)?;
        travel += route_to_target_avoiding(scenario, start, &target, &none)?.nominal_duration_s;
        dwell += target.dwell_s;
        let waypoint = scenario
            .waypoints
            .iter()
            .find(|waypoint| waypoint.id == *id)?;
        start.surface = &waypoint.surface;
        start.position = waypoint.at;
    }
    let exit = select_exit_plan(
        scenario,
        start,
        group.exit_candidates(),
        &none,
        ExitPlanConstraints {
            blocked_exits: &none,
            blocked_gates: &none,
            passed_gates: None,
        },
    )?;
    let duration = travel + exit.plan.nominal_duration_s;
    if !duration.is_finite() || !dwell.is_finite() {
        return None;
    }
    Some(NominalJourney {
        centre_route_duration_s: duration,
        authored_dwell_s: dwell,
        exit: exit.target.id,
    })
}
