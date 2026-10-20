use super::{
    AgentJourney, JOURNEY_VERSION, JourneyHotspot, JourneyOptions, JourneyReport, ResourceExposure,
    TimeBudget,
};
use crate::{
    AgentState, RunBundle,
    benchmark::diagnostics::{self, DiagnosticOptions},
    bundle::{AgentSnapshot, canonical_hash},
    model::Point3,
};
use std::collections::{BTreeMap, BTreeSet};

pub(super) fn analyze(
    bundle: &RunBundle,
    options: &JourneyOptions,
) -> Result<JourneyReport, String> {
    options.validate()?;
    validate_trace(bundle)?;
    let first = &bundle.trace[0];
    let scenario = &bundle.scenario.scenario;
    let mut agents = Vec::with_capacity(first.agents.len());
    for initial in &first.agents {
        let group = scenario
            .agents
            .iter()
            .find(|group| group.id == initial.group)
            .ok_or("unknown agent group")?;
        let ordinal: u32 = initial
            .id
            .strip_prefix(&format!("{}:", group.id))
            .ok_or("agent identifier does not match its group")?
            .parse()
            .map_err(|_| "invalid agent ordinal")?;
        if ordinal >= group.count {
            return Err("agent ordinal exceeds authored group".into());
        }
        agents.push(AgentJourney {
            agent: initial.id.clone(),
            group: initial.group.clone(),
            scheduled_release_s: group.release_time_for(ordinal),
            admitted_at_s: (initial.state != AgentState::WaitingToDepart).then_some(0.0),
            evacuated_at_s: None,
            elapsed_journey_s: None,
            final_state: initial.state.clone(),
            walking_distance_m: 0.0,
            connector_interior_distance_m: 0.0,
            transition_distance_m: 0.0,
            time: TimeBudget::default(),
            nominal_reference: crate::runtime::nominal_journey_reference(
                scenario,
                group,
                group
                    .spawn_positions()
                    .nth(ordinal as usize)
                    .ok_or("missing original spawn")?,
            ),
            avoidance_fallback_steps: 0,
            swept_guard_steps: 0,
            low_progress: Vec::new(),
            decisions: Vec::new(),
        });
    }
    account_intervals(bundle, &mut agents);
    account_events(bundle, &mut agents)?;
    let diagnosis = diagnostics::analyze(
        bundle,
        &DiagnosticOptions {
            window_s: options.low_progress_window_s,
            max_travel_m: options.max_low_progress_travel_m,
        },
    );
    let indexed: BTreeMap<_, _> = agents
        .iter()
        .enumerate()
        .map(|(index, agent)| (agent.agent.clone(), index))
        .collect();
    for window in diagnosis.walking_low_progress {
        agents[indexed[&window.agent]].low_progress.push(window);
    }
    check_accounting(bundle, &agents)?;
    let mut report = JourneyReport {
        journey_version: JOURNEY_VERSION.into(),
        runtime_version: bundle.runtime_version.clone(),
        analyzer_runtime_version: crate::RUNTIME_VERSION.into(),
        source_bundle_hash: bundle.bundle_hash.clone(),
        scenario_hash: bundle.scenario_hash.clone(),
        options: options.clone(),
        observed_duration_s: scenario.duration_s,
        hotspots: hotspots(bundle, &agents, options)?,
        resources: resources(bundle)?,
        agents,
        isolated_controls: Vec::new(),
        report_hash: String::new(),
    };
    report.report_hash = canonical_hash(&report);
    Ok(report)
}

fn account_intervals(bundle: &RunBundle, agents: &mut [AgentJourney]) {
    for frames in bundle.trace.windows(2) {
        let elapsed = frames[1].time_s - frames[0].time_s;
        for ((start, end), journey) in frames[0]
            .agents
            .iter()
            .zip(&frames[1].agents)
            .zip(agents.iter_mut())
        {
            account_state(&mut journey.time, &start.state, elapsed);
            let distance = point(start).distance(point(end));
            if start.state == AgentState::InTransit && end.state == AgentState::InTransit {
                journey.connector_interior_distance_m += distance;
            } else if start.state == AgentState::InTransit
                || end.state == AgentState::InTransit
                || start.surface != end.surface
            {
                journey.transition_distance_m += distance;
            } else {
                journey.walking_distance_m += distance;
            }
            journey.final_state = end.state.clone();
        }
    }
}

fn validate_trace(bundle: &RunBundle) -> Result<(), String> {
    if !bundle.verifies_hash() || bundle.scenario_hash != canonical_hash(&bundle.scenario) {
        return Err("journey analysis requires a hash-verified source bundle".into());
    }
    bundle
        .scenario
        .check_schema()
        .map_err(|error| error.to_string())?;
    crate::validate(&bundle.scenario.scenario)
        .map_err(|errors| format!("invalid journey source: {errors:?}"))?;
    let scenario = &bundle.scenario.scenario;
    let steps = crate::integration_step_count(scenario.duration_s, scenario.timestep_s);
    if bundle.options.get("trace_every_steps").map(String::as_str) != Some("1")
        || u64::try_from(bundle.trace.len()).ok() != steps.checked_add(1)
    {
        return Err(
            "journey diagnostics require a complete trace; run with --trace-every 1".into(),
        );
    }
    let first = bundle.trace.first().ok_or("empty journey trace")?;
    let mut expected: BTreeMap<String, &str> = scenario
        .agents
        .iter()
        .flat_map(|group| {
            (0..group.count)
                .map(move |ordinal| (format!("{}:{ordinal}", group.id), group.id.as_str()))
        })
        .collect();
    if let Some(agent) = bundle.options.get("isolated_agent") {
        if !expected.contains_key(agent) {
            return Err("unknown isolated agent".into());
        }
        expected.retain(|id, _| id == agent);
    }
    if first.agents.len() != expected.len()
        || first.agents.len() != bundle.metrics.total_agents as usize
        || first
            .agents
            .iter()
            .map(|agent| &agent.id)
            .collect::<BTreeSet<_>>()
            .len()
            != expected.len()
    {
        return Err("journey trace population does not match its execution scope".into());
    }
    for (index, frame) in bundle.trace.iter().enumerate() {
        let step = u64::try_from(index).map_err(|_| "trace is too large")?;
        #[allow(clippy::cast_precision_loss)] // bounded integration schedule, as in runtime::run
        let expected_time = ((step as f64) * scenario.timestep_s).min(scenario.duration_s);
        if frame.step != step
            || !frame.time_s.is_finite()
            || (index > 0 && frame.time_s <= bundle.trace[index - 1].time_s)
            || (frame.time_s - expected_time).abs() > 1e-8 * (1.0 + expected_time)
            || frame.agents.len() != first.agents.len()
        {
            return Err(
                "journey trace has missing, duplicate, nonmonotonic, or mistimed integration frames".into(),
            );
        }
        for (initial, agent) in first.agents.iter().zip(&frame.agents) {
            if initial.id != agent.id
                || expected.get(&agent.id) != Some(&agent.group.as_str())
                || ![agent.x_m, agent.y_m, agent.z_m]
                    .iter()
                    .all(|value| value.is_finite())
                || !scenario
                    .surfaces
                    .iter()
                    .any(|surface| surface.id == agent.surface)
            {
                return Err("journey trace has inconsistent agents or coordinates".into());
            }
        }
    }
    if bundle.events.iter().any(|event| {
        !event.time_s.is_finite() || event.time_s < 0.0 || event.time_s > scenario.duration_s
    }) {
        return Err("journey event falls outside the observed run".into());
    }
    Ok(())
}

fn account_state(time: &mut TimeBudget, state: &AgentState, elapsed: f64) {
    let component = match state {
        AgentState::Moving => &mut time.moving_s,
        AgentState::WaitingToDepart => &mut time.waiting_to_depart_s,
        AgentState::WaitingAtWaypoint => &mut time.waypoint_pause_s,
        AgentState::WaitingForRoute => &mut time.route_wait_s,
        AgentState::WaitingForLift => &mut time.lift_wait_s,
        AgentState::WaitingForConnector => &mut time.connector_wait_s,
        AgentState::WaitingForGate => &mut time.gate_wait_s,
        AgentState::WaitingForExit => &mut time.exit_wait_s,
        AgentState::InTransit => &mut time.transit_s,
        AgentState::Evacuated => &mut time.evacuated_s,
    };
    *component += elapsed;
}

fn account_events(bundle: &RunBundle, agents: &mut [AgentJourney]) -> Result<(), String> {
    let indexed: BTreeMap<_, _> = agents
        .iter()
        .enumerate()
        .map(|(index, agent)| (agent.agent.clone(), index))
        .collect();
    for event in &bundle.events {
        let Some(index) = indexed.get(&event.subject) else {
            continue;
        };
        let agent = &mut agents[*index];
        match event.kind.as_str() {
            "agent_released" => {
                if agent.admitted_at_s.replace(event.time_s).is_some() {
                    return Err("duplicate release event".into());
                }
            }
            "evacuated" => {
                if agent.evacuated_at_s.replace(event.time_s).is_some() {
                    return Err("duplicate evacuation event".into());
                }
            }
            "local_avoidance_constraint_fallback" => agent.avoidance_fallback_steps += 1,
            "local_motion_limited_for_clearance" => agent.swept_guard_steps += 1,
            _ => {}
        }
        if matches!(
            event.kind.as_str(),
            "agent_released"
                | "evacuated"
                | "route_recomputed"
                | "route_unavailable"
                | "message_received"
                | "countermeasure_received"
                | "resource_observed"
                | "waypoint_reached"
                | "waypoint_wait_started"
                | "waypoint_wait_ended"
                | "connector_boarding"
                | "connector_arrival"
                | "gate_processed"
                | "queue_service_reserved"
        ) {
            let frame_index = bundle
                .trace
                .partition_point(|frame| frame.time_s < event.time_s);
            let frame = &bundle.trace[frame_index.min(bundle.trace.len() - 1)];
            agent.decisions.push(super::JourneyDecision {
                event: event.clone(),
                replay_step: frame.step,
                surface: frame.agents[*index].surface.clone(),
                recorded_position: point(&frame.agents[*index]),
            });
        }
    }
    for agent in agents {
        agent.elapsed_journey_s = agent
            .evacuated_at_s
            .zip(agent.admitted_at_s)
            .map(|(exit, entry)| exit - entry);
        if (agent.final_state == AgentState::Evacuated) != agent.evacuated_at_s.is_some()
            || agent.elapsed_journey_s.is_some_and(|time| time < 0.0)
        {
            return Err("evacuation events and trace states disagree".into());
        }
    }
    Ok(())
}

fn check_accounting(bundle: &RunBundle, agents: &[AgentJourney]) -> Result<(), String> {
    let close = |left: f64, right: f64| (left - right).abs() <= 1e-7 * (1.0 + right.abs());
    if agents.iter().any(|agent| {
        ![
            agent.walking_distance_m,
            agent.connector_interior_distance_m,
            agent.transition_distance_m,
        ]
        .iter()
        .all(|value| value.is_finite() && *value >= 0.0)
            || !close(agent.time.total_s(), bundle.scenario.scenario.duration_s)
    }) {
        return Err("journey state budgets do not cover the observed duration".into());
    }
    if let Some(queue) = &bundle.metrics.queue_metrics {
        for (observed, expected) in [
            (
                agents.iter().map(|agent| agent.time.lift_wait_s).sum(),
                queue.lift.cumulative_wait_agent_seconds,
            ),
            (
                agents.iter().map(|agent| agent.time.connector_wait_s).sum(),
                queue.connector.cumulative_wait_agent_seconds,
            ),
            (
                agents.iter().map(|agent| agent.time.gate_wait_s).sum(),
                queue.gate.cumulative_wait_agent_seconds,
            ),
            (
                agents.iter().map(|agent| agent.time.exit_wait_s).sum(),
                queue.exit.cumulative_wait_agent_seconds,
            ),
        ] {
            if !close(observed, expected) {
                return Err("journey service waits disagree with runtime telemetry".into());
            }
        }
    }
    if let Some(motion) = &bundle.metrics.movement_metrics {
        let fallbacks: u64 = agents
            .iter()
            .map(|agent| agent.avoidance_fallback_steps)
            .sum();
        let guards: u64 = agents.iter().map(|agent| agent.swept_guard_steps).sum();
        if motion
            .local_avoidance_constraint_fallback_steps
            .is_some_and(|count| count != fallbacks)
            || motion
                .swept_motion_guard_steps
                .is_some_and(|count| count != guards)
        {
            return Err("journey motion events disagree with runtime telemetry".into());
        }
    }
    if agents
        .iter()
        .filter(|agent| agent.evacuated_at_s.is_some())
        .count()
        != bundle.metrics.evacuated_agents as usize
    {
        return Err("journey completions disagree with runtime metrics".into());
    }
    Ok(())
}

fn resources(bundle: &RunBundle) -> Result<Vec<ResourceExposure>, String> {
    let Some(resources) = bundle
        .metrics
        .queue_metrics
        .as_ref()
        .and_then(|queue| queue.by_resource.as_ref())
    else {
        return Ok(Vec::new());
    };
    let mut result = Vec::new();
    for (kind, map) in [
        ("lift", &resources.lifts),
        ("connector", &resources.connectors),
        ("gate", &resources.gates),
        ("exit", &resources.exits),
    ] {
        for (id, metrics) in map {
            let agents: BTreeSet<_> = bundle
                .events
                .iter()
                .filter(|event| {
                    event.kind == format!("queue_entered_{kind}") && event.detail == *id
                })
                .map(|event| event.subject.clone())
                .collect();
            if agents.len() != metrics.ever_queued_agents as usize {
                return Err("queue-entry events and resource exposure disagree".into());
            }
            result.push(ResourceExposure {
                kind: kind.into(),
                resource: id.clone(),
                wait_agent_s: metrics.cumulative_wait_agent_seconds,
                peak_waiting_agents: metrics.peak_waiting_agents,
                agents: agents.into_iter().collect(),
            });
        }
    }
    Ok(result)
}

#[derive(Default)]
struct Cell {
    seconds: f64,
    agents: BTreeSet<String>,
    first: u64,
    last: u64,
}

fn hotspots(
    bundle: &RunBundle,
    agents: &[AgentJourney],
    options: &JourneyOptions,
) -> Result<Vec<JourneyHotspot>, String> {
    let mut cells = BTreeMap::<(String, u64, u64), Cell>::new();
    for (index, agent) in agents.iter().enumerate() {
        for window in &agent.low_progress {
            let first = bundle
                .trace
                .partition_point(|frame| frame.time_s <= window.start_time_s)
                .saturating_sub(1);
            let last = bundle
                .trace
                .partition_point(|frame| frame.time_s < window.end_time_s)
                .min(bundle.trace.len() - 1);
            for frames in bundle.trace[first..=last].windows(2) {
                let start = frames[0].time_s.max(window.start_time_s);
                let end = frames[1].time_s.min(window.end_time_s);
                let position = point(&frames[0].agents[index]).lerp(
                    point(&frames[1].agents[index]),
                    (f64::midpoint(start, end) - frames[0].time_s)
                        / (frames[1].time_s - frames[0].time_s),
                );
                let x = (position.x_m / options.hotspot_cell_size_m).floor()
                    * options.hotspot_cell_size_m;
                let y = (position.y_m / options.hotspot_cell_size_m).floor()
                    * options.hotspot_cell_size_m;
                if !x.is_finite() || !y.is_finite() {
                    return Err("hotspot coordinates exceed analysis range".into());
                }
                let cell = cells
                    .entry((window.surface.clone(), x.to_bits(), y.to_bits()))
                    .or_default();
                if cell.agents.is_empty() {
                    cell.first = frames[0].step;
                }
                cell.first = cell.first.min(frames[0].step);
                cell.last = cell.last.max(frames[1].step);
                cell.seconds += end - start;
                cell.agents.insert(agent.agent.clone());
            }
        }
    }
    let mut result: Vec<_> = cells
        .into_iter()
        .map(|((surface, x, y), cell)| JourneyHotspot {
            surface,
            x_m: f64::from_bits(x) + options.hotspot_cell_size_m * 0.5,
            y_m: f64::from_bits(y) + options.hotspot_cell_size_m * 0.5,
            low_progress_agent_s: cell.seconds,
            agents: cell.agents.into_iter().collect(),
            start_step: cell.first,
            end_step: cell.last,
        })
        .collect();
    result.sort_by(|left, right| {
        right
            .low_progress_agent_s
            .total_cmp(&left.low_progress_agent_s)
            .then(left.surface.cmp(&right.surface))
            .then(left.x_m.total_cmp(&right.x_m))
            .then(left.y_m.total_cmp(&right.y_m))
    });
    Ok(result)
}

fn point(agent: &AgentSnapshot) -> Point3 {
    Point3::new(agent.x_m, agent.y_m, agent.z_m)
}
