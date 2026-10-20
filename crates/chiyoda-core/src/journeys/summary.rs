use super::{JOURNEY_VERSION, JourneyReport, JourneySummary};

#[allow(clippy::too_many_lines)] // one aggregate projection keeps each counter and its denominator together
pub(super) fn derive(report: &JourneyReport) -> JourneySummary {
    JourneySummary {
        journey_version: JOURNEY_VERSION.into(),
        report_hash: report.report_hash.clone(),
        source_bundle_hash: report.source_bundle_hash.clone(),
        people: u32::try_from(report.agents.len()).expect("validated population"),
        completed: count(
            report
                .agents
                .iter()
                .filter(|agent| agent.evacuated_at_s.is_some())
                .count(),
        ),
        walking_distance_m: report
            .agents
            .iter()
            .map(|agent| agent.walking_distance_m)
            .sum(),
        moving_agent_s: report.agents.iter().map(|agent| agent.time.moving_s).sum(),
        service_wait_agent_s: report
            .agents
            .iter()
            .map(|agent| agent.time.service_wait_s())
            .sum(),
        waypoint_pause_agent_s: report
            .agents
            .iter()
            .map(|agent| agent.time.waypoint_pause_s)
            .sum(),
        route_wait_agent_s: report
            .agents
            .iter()
            .map(|agent| agent.time.route_wait_s)
            .sum(),
        transit_agent_s: report.agents.iter().map(|agent| agent.time.transit_s).sum(),
        low_progress_agents: count(
            report
                .agents
                .iter()
                .filter(|agent| !agent.low_progress.is_empty())
                .count(),
        ),
        low_progress_span_agent_s: report
            .agents
            .iter()
            .flat_map(|agent| &agent.low_progress)
            .map(|window| window.end_time_s - window.start_time_s)
            .sum(),
        decision_events: report
            .agents
            .iter()
            .map(|agent| u64::try_from(agent.decisions.len()).expect("bounded event count"))
            .sum(),
        completed_elapsed_journey_s: report
            .agents
            .iter()
            .filter_map(|agent| agent.elapsed_journey_s)
            .sum(),
        nominal_estimates: count(
            report
                .agents
                .iter()
                .filter(|agent| agent.nominal_reference.is_some())
                .count(),
        ),
        nominal_journey_s: report
            .agents
            .iter()
            .filter_map(|agent| agent.nominal_reference.as_ref())
            .map(|reference| reference.centre_route_duration_s + reference.authored_dwell_s)
            .sum(),
        controls_requested: count(report.isolated_controls.len()),
        controls_run: count(
            report
                .isolated_controls
                .iter()
                .filter(|control| control.bundle_hash.is_some())
                .count(),
        ),
        controls_completed: count(
            report
                .isolated_controls
                .iter()
                .filter(|control| control.evacuated_at_s.is_some())
                .count(),
        ),
        control_pairs_completed: count(
            report
                .isolated_controls
                .iter()
                .filter(|control| control.crowded_minus_isolated_elapsed_s.is_some())
                .count(),
        ),
        paired_crowded_elapsed_s: report
            .isolated_controls
            .iter()
            .filter(|control| control.crowded_minus_isolated_elapsed_s.is_some())
            .filter_map(|control| control.crowded_elapsed_journey_s)
            .sum(),
        paired_isolated_elapsed_s: report
            .isolated_controls
            .iter()
            .filter(|control| control.crowded_minus_isolated_elapsed_s.is_some())
            .filter_map(|control| control.elapsed_journey_s)
            .sum(),
    }
}

fn count(value: usize) -> u32 {
    u32::try_from(value).expect("validated population")
}
