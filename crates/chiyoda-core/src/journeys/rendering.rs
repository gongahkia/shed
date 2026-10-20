//! Portable explanations, per-person CSV, and exact recorded replay moments.
use super::{AgentJourney, JourneyReport};
use std::fmt::Write;

pub fn markdown(report: &JourneyReport) -> Result<String, String> {
    report.check_hash()?;
    let summary = report.summary();
    let mut out = format!(
        "# Journey evidence\n\nCompleted: {}/{}. [Source run](run.json) · [Per-person CSV](journeys.csv) · [Full evidence](journeys.json)\n\n",
        summary.completed, summary.people
    );
    out.push_str("Observed state time uses the preceding integration boundary. Its exclusive components cover the whole run, including departure wait and time after evacuation. Moving time can include slow or stationary movement. Low-progress spans overlap that budget and must not be added to it.\n\n");
    writeln!(out, "Walked distance: {:.3} m across all people; service wait: {:.3} agent s; low-progress spans: {:.3} agent s affecting {} people.\n", summary.walking_distance_m, summary.service_wait_agent_s, summary.low_progress_span_agent_s, summary.low_progress_agents).unwrap();
    out.push_str("The static reference uses obstacle-aware centre-to-centre routes, every resource open, all candidate exits familiar, authored waypoints/dwell and mobility restrictions, and nominal connector traversal. It ignores service queues, releases, closures, messages, and other people. It is a planning reference, not a predicted or guaranteed completion time.\n\n");
    findings(report, &mut out);
    people(report, &mut out);
    hotspots(report, &mut out);
    resources(report, &mut out);
    controls(report, &mut out);
    moments(report, &mut out);
    writeln!(
        out,
        "\nSource bundle: {}. Analysis checksum: {}.\n",
        report.source_bundle_hash, report.report_hash
    )
    .unwrap();
    Ok(out)
}

fn findings(report: &JourneyReport, out: &mut String) {
    out.push_str("## Observations to explain the outcome\n\n");
    if let Some(resource) = report
        .resources
        .iter()
        .filter(|resource| resource.wait_agent_s > 0.0)
        .max_by(|left, right| left.wait_agent_s.total_cmp(&right.wait_agent_s))
    {
        writeln!(out, "The largest recorded service wait is at {} {}: {:.3} agent s, a peak of {} waiting people, and {} people with queue-entry exposure.\n", escaped(&resource.kind), escaped(&resource.resource), resource.wait_agent_s, resource.peak_waiting_agents, resource.agents.len()).unwrap();
    } else {
        out.push_str("No modeled service-capacity wait was recorded. Physical crowd delay can still appear within moving exposure.\n\n");
    }
    if let Some(cell) = report.hotspots.first() {
        writeln!(out, "The largest low-progress concentration is on {} around cell centre ({:.3}, {:.3}) m: {:.3} agent s involving {}. Inspect recorded steps {}–{}. This locates observed low travel without assigning its cause.\n", escaped(&cell.surface), cell.x_m, cell.y_m, cell.low_progress_agent_s, cell.agents.iter().map(|id| escaped(id)).collect::<Vec<_>>().join(", "), cell.start_step, cell.end_step).unwrap();
        replay(out, cell.start_step, &cell.surface);
    }
    let information = report
        .agents
        .iter()
        .flat_map(|person| &person.decisions)
        .filter(|decision| {
            matches!(
                decision.event.kind.as_str(),
                "message_received" | "countermeasure_received"
            )
        })
        .count();
    let replanning = report
        .agents
        .iter()
        .flat_map(|person| &person.decisions)
        .filter(|decision| decision.event.kind == "route_recomputed")
        .count();
    writeln!(out, "Recorded information deliveries: {information}; route recomputations: {replanning}. The timelines retain acceptance and selected-route details for inspection; delivery and replanning counts alone do not establish a policy effect.\n").unwrap();
    for control in &report.isolated_controls {
        if let Some(difference) = control.crowded_minus_isolated_elapsed_s {
            writeln!(out, "For {}, crowded elapsed journey time minus its completed isolated control is {difference:+.3} s. This compares the complete recorded counterfactual, including any changed admission, information exposure, or routes.\n", escaped(&control.agent)).unwrap();
        }
    }
}

fn people(report: &JourneyReport, out: &mut String) {
    out.push_str("## People\n\nElapsed journey time requires both admission and evacuation; unfinished people retain an unavailable value. Walked distance includes the final evacuation segment. Intervals entering/leaving transit are recorded separately in CSV/JSON.\n\n| Person | Final state | Admission (s) | Elapsed journey (s) | Walked (m) | Static route + dwell (s) | Service wait (s) |\n| --- | --- | --- | --- | --- | --- | --- |\n");
    for (index, agent) in report.agents.iter().enumerate() {
        writeln!(out, "| [{}](#person-{index}) | {:?} | {} | {} | {:.3} | {} | {:.3} |", escaped(&agent.agent), agent.final_state, number(agent.admitted_at_s), number(agent.elapsed_journey_s), agent.walking_distance_m,
            number(agent.nominal_reference.as_ref().map(|reference| reference.centre_route_duration_s + reference.authored_dwell_s)), agent.time.service_wait_s()).unwrap();
    }
}

fn hotspots(report: &JourneyReport, out: &mut String) {
    out.push_str("\n## Low-progress locations\n\nWindows identify low travel while recorded as moving. They do not establish deadlock or a particular cause. Window duration is allocated over spatial cells using the recorded linear intervals; cells are ordered by accumulated agent-seconds.\n\n");
    if report.hotspots.is_empty() {
        out.push_str("No qualifying low-progress windows.\n");
        return;
    }
    out.push_str("| Surface | Cell centre (m) | Window span (agent s) | People | Recorded steps |\n| --- | --- | --- | --- | --- |\n");
    for hotspot in &report.hotspots {
        writeln!(
            out,
            "| {} | {:.3}, {:.3} | {:.3} | {} | {}–{} |",
            escaped(&hotspot.surface),
            hotspot.x_m,
            hotspot.y_m,
            hotspot.low_progress_agent_s,
            hotspot
                .agents
                .iter()
                .map(|id| escaped(id))
                .collect::<Vec<_>>()
                .join(", "),
            hotspot.start_step,
            hotspot.end_step
        )
        .unwrap();
    }
}

fn resources(report: &JourneyReport, out: &mut String) {
    out.push_str("\n## Service resources\n\nPer-resource wait and peak counts come from the runtime audit. Listed people have recorded queue-entry exposure; their individual resource wait durations are not inferred from their positions.\n\n| Kind | Resource | Wait (agent s) | Peak waiting | People exposed |\n| --- | --- | --- | --- | --- |\n");
    for resource in &report.resources {
        writeln!(
            out,
            "| {} | {} | {:.3} | {} | {} |",
            escaped(&resource.kind),
            escaped(&resource.resource),
            resource.wait_agent_s,
            resource.peak_waiting_agents,
            resource
                .agents
                .iter()
                .map(|id| escaped(id))
                .collect::<Vec<_>>()
                .join(", ")
        )
        .unwrap();
    }
}

fn controls(report: &JourneyReport, out: &mut String) {
    out.push_str("\n## Isolated controls\n\n");
    if report.isolated_controls.is_empty() {
        out.push_str("No isolated controls were requested or selected.\n");
        return;
    }
    out.push_str("Each observed control retains the original identity, spawn, eligibility time, geometry, operations, information, and seed, with other people absent. Changing interactions can also change observation times, information exposure, route choices, and admission times. Differences describe this complete counterfactual and are not an additive causal decomposition. A difference requires both runs to finish. Unavailable and budget-limited controls remain visible.\n\n| Person | Status | Isolated elapsed (s) | Crowded elapsed (s) | Crowded − isolated (s) | Evidence |\n| --- | --- | --- | --- | --- | --- |\n");
    for (index, control) in report.isolated_controls.iter().enumerate() {
        let link = if control.bundle_hash.is_some() {
            format!("[run](isolated-{index:04}/run.json)")
        } else {
            "unavailable".into()
        };
        writeln!(
            out,
            "| {} | {:?} | {} | {} | {} | {} |",
            escaped(&control.agent),
            control.status,
            number(control.elapsed_journey_s),
            number(control.crowded_elapsed_journey_s),
            number(control.crowded_minus_isolated_elapsed_s),
            link
        )
        .unwrap();
    }
}

fn moments(report: &JourneyReport, out: &mut String) {
    out.push_str("\n## People and replay moments\n\nCommands assume this file's directory. Event positions are from the first integration frame at/after delivery; movement can follow the event in that step. Recorded event order is preserved. Information and route events are evidence for inspection, not an automatic attribution of cause.\n");
    for (index, agent) in report.agents.iter().enumerate() {
        writeln!(
            out,
            "\n<a id=\"person-{index}\"></a>\n\n### {}\n",
            escaped(&agent.agent)
        )
        .unwrap();
        let t = &agent.time;
        writeln!(out, "Moving {:.3} s; departure wait {:.3} s; waypoint pause {:.3} s; no-route wait {:.3} s; service wait {:.3} s; transit {:.3} s; evacuated {:.3} s. Avoidance fallbacks: {}; swept guards: {}.\n", t.moving_s, t.waiting_to_depart_s, t.waypoint_pause_s, t.route_wait_s, t.service_wait_s(), t.transit_s, t.evacuated_s, agent.avoidance_fallback_steps, agent.swept_guard_steps).unwrap();
        for window in &agent.low_progress {
            writeln!(
                out,
                "Low progress {:.3}–{:.3} s on {} (recorded steps {}–{}).\n",
                window.start_time_s,
                window.end_time_s,
                escaped(&window.surface),
                window.start_step,
                window.end_step
            )
            .unwrap();
            replay(out, window.start_step, &window.surface);
        }
        if !agent.decisions.is_empty() {
            out.push_str(
                "| Event time (s) | Event | Recorded detail | Frame |\n| --- | --- | --- | --- |\n",
            );
            for (event_index, decision) in agent.decisions.iter().enumerate() {
                writeln!(
                    out,
                    "| {:.3} | {} | {} | [step {}](#event-{index}-{event_index}) |",
                    decision.event.time_s,
                    escaped(&decision.event.kind),
                    escaped(&decision.event.detail),
                    decision.replay_step
                )
                .unwrap();
            }
            for (event_index, decision) in agent.decisions.iter().enumerate() {
                writeln!(out, "\n<a id=\"event-{index}-{event_index}\"></a>\n\n{} at {:.3} s; recorded position {:.3}, {:.3} on {}.\n", escaped(&decision.event.kind), decision.event.time_s, decision.recorded_position.x_m, decision.recorded_position.y_m, escaped(&decision.surface)).unwrap();
                replay(out, decision.replay_step, &decision.surface);
            }
        }
    }
}

fn replay(out: &mut String, step: u64, surface: &str) {
    let command = format!(
        "chiyoda-replay run.json --paused --at-step {step} --surface {}",
        shell_quote(surface)
    );
    writeln!(out, "    {}\n", command.replace('\n', "\n    ")).unwrap();
}

pub fn csv(report: &JourneyReport) -> Result<String, String> {
    report.check_hash()?;
    let mut out = "agent,group,final_state,scheduled_release_s,admitted_at_s,evacuated_at_s,elapsed_journey_s,walking_distance_m,connector_interior_distance_m,transition_distance_m,moving_s,waiting_to_depart_s,waypoint_pause_s,route_wait_s,lift_wait_s,connector_wait_s,gate_wait_s,exit_wait_s,transit_s,evacuated_s,static_centre_route_s,static_authored_dwell_s,low_progress_span_s,avoidance_fallback_steps,swept_guard_steps\n".into();
    for agent in &report.agents {
        csv_person(agent, &mut out);
    }
    Ok(out)
}

fn csv_person(agent: &AgentJourney, out: &mut String) {
    let t = &agent.time;
    let mut fields = vec![
        csv_text(&agent.agent),
        csv_text(&agent.group),
        format!("{:?}", agent.final_state),
        agent.scheduled_release_s.to_string(),
    ];
    fields.extend(
        [
            agent.admitted_at_s,
            agent.evacuated_at_s,
            agent.elapsed_journey_s,
        ]
        .map(csv_number),
    );
    fields.extend(
        [
            agent.walking_distance_m,
            agent.connector_interior_distance_m,
            agent.transition_distance_m,
            t.moving_s,
            t.waiting_to_depart_s,
            t.waypoint_pause_s,
            t.route_wait_s,
            t.lift_wait_s,
            t.connector_wait_s,
            t.gate_wait_s,
            t.exit_wait_s,
            t.transit_s,
            t.evacuated_s,
        ]
        .map(|value| value.to_string()),
    );
    fields.push(csv_number(
        agent
            .nominal_reference
            .as_ref()
            .map(|reference| reference.centre_route_duration_s),
    ));
    fields.push(csv_number(
        agent
            .nominal_reference
            .as_ref()
            .map(|reference| reference.authored_dwell_s),
    ));
    fields.push(
        agent
            .low_progress
            .iter()
            .map(|window| window.end_time_s - window.start_time_s)
            .sum::<f64>()
            .to_string(),
    );
    fields.push(agent.avoidance_fallback_steps.to_string());
    fields.push(agent.swept_guard_steps.to_string());
    writeln!(out, "{}", fields.join(",")).unwrap();
}

fn csv_number(value: Option<f64>) -> String {
    value.map_or_else(String::new, |value| value.to_string())
}
fn csv_text(value: &str) -> String {
    format!("\"{}\"", value.replace('"', "\"\""))
}
fn number(value: Option<f64>) -> String {
    value.map_or_else(|| "unavailable".into(), |value| format!("{value:.3}"))
}
fn escaped(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('|', "&#124;")
        .replace('`', "&#96;")
        .replace(['\r', '\n'], " ")
}
fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}
