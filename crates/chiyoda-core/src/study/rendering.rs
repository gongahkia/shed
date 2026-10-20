//! Portable study summaries; full distributions and raw telemetry remain in JSON.
use super::{GroupKey, GroupSummary, StudyReport};
use std::{collections::BTreeSet, fmt::Write};

pub fn markdown(report: &StudyReport) -> Result<String, String> {
    report.verify()?;
    let name = report.config.name.replace(['\r', '\n'], " ");
    let mut output = format!(
        "# {name}\n\nPassed: {}; failed: {}; requested: {}. Runtime: {}.\n\nGenerated layouts: {}/{}; distinct geometries: {}; rejected attempts: {}.\n\nReport hash: {}.\n\n[Inputs](config.json) · [Full report](report.json) · [Per-case observations](observations.csv)\n\n",
        report.passed,
        report.failed,
        report.requested,
        report.runtime_version,
        report.generated_layouts,
        report.requested_layouts,
        report.unique_layouts,
        report.rejected_generation_attempts,
        report.report_hash
    );
    output.push_str("These are reference-runtime experiments. Longer journeys, changing density, service limits, and generator selection can affect results. The report does not infer a causal ranking or real-world safety.\n\n");
    output.push_str("## Outcomes across seeds\n\nClearance statistics include completed evacuations only. Each statistic shows its available sample count. Failed thresholds remain observations; invalid trajectories and failed setup/reconstruction do not enter distributions.\n\n");
    output.push_str("| Pattern | Scale | Load | Policy | Passed / requested | Measured | Complete | Mean people | Mean evacuation fraction | Mean queue wait/person (s) | P95 clearance (s; n) | Mean direct exit distance (m) |\n| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    for group in &report.groups {
        let clearance = group.metrics.get("clearance_time_s").map_or_else(
            || "unavailable".into(),
            |value| format!("{:.3}; {}", value.p95, value.samples),
        );
        writeln!(
            output,
            "| {} | {} | {} | {} | {}/{} | {} | {} | {} | {} | {} | {} | {} |",
            group.key.pattern,
            group.key.scale,
            group.key.load,
            group.key.policy,
            group.passed,
            group.requested,
            group.measured,
            group.fully_evacuated,
            metric(group, "people"),
            metric(group, "evacuated_fraction"),
            metric(group, "queue_wait_per_person_s"),
            clearance,
            metric(group, "mean_direct_exit_distance_m")
        )
        .expect("string writes");
    }
    output.push_str("\n## Comparisons\n\nDifferences are current minus the first declared value on that axis, matched by root seed. Policy comparisons reuse exact physical inputs and populations. Pattern and scale comparisons sample different geometry; matching a root seed does not make those layouts equivalent. Clearance differences require both members to finish.\n\n");
    output.push_str("| Axis | Reference | Current | Measured / requested pairs | Mean evacuation-fraction difference (n) | Mean queue-wait/person difference (s; n) | Mean clearance difference (s; n) |\n| --- | --- | --- | --- | --- | --- | --- |\n");
    for comparison in &report.comparisons {
        let delta = |key: &str| {
            comparison.deltas.get(key).map_or_else(
                || "unavailable".into(),
                |value| format!("{:.6}; {}", value.mean, value.samples),
            )
        };
        writeln!(
            output,
            "| {} | {} | {} | {}/{} | {} | {} | {} |",
            comparison.axis,
            label(&comparison.baseline),
            label(&comparison.current),
            comparison.measured_pairs,
            comparison.requested_pairs,
            delta("evacuated_fraction"),
            delta("queue_wait_per_person_s"),
            delta("clearance_time_s")
        )
        .expect("string writes");
    }
    journey_evidence(report, &mut output);
    cases(report, &mut output);
    Ok(output)
}

fn journey_evidence(report: &StudyReport, output: &mut String) {
    if !report.cases.iter().any(|case| case.journeys.is_some()) {
        return;
    }
    output.push_str("\n## Journey observations\n\nDistance and state exposure cover every person, including unfinished people. Elapsed journey statistics include completed people only. The static centre-route reference ignores closures, knowledge, messages, queues, releases, and other people while retaining obstacles, mobility restrictions, required stages, dwell, and nominal connector travel. It is not a predicted completion time. Low-progress spans overlap moving time and do not identify a cause.\n\n| Pattern / scale / load / policy | Mean walked/person (m; cases) | Mean static nominal journey (s; cases) | Mean moving/person (s; cases) | Mean route wait/person (s; cases) | Mean low-progress span/person (s; cases) |\n| --- | --- | --- | --- | --- | --- |\n");
    for group in &report.groups {
        writeln!(
            output,
            "| {} | {} | {} | {} | {} | {} |",
            label(&group.key),
            metric(group, "mean_walking_distance_m"),
            metric(group, "mean_static_nominal_journey_s"),
            metric(group, "moving_time_per_person_s"),
            metric(group, "route_wait_per_person_s"),
            metric(group, "low_progress_span_per_person_s")
        )
        .unwrap();
    }
    output.push_str("\n### Observed changes between arms\n\nDifferences retain the same available-seed pairing as the outcome comparisons. Policy arms share geometry and people. Walking-distance and wait differences describe observed changes; inspect accepted-information and route-event timelines before assigning an explanation. Isolated controls, when requested, change interactions and can also change later information exposure and routes.\n\n| Axis | Reference | Current | Walked/person change (m; n) | Moving/person change (s; n) | Service wait/person change (s; n) | Low-progress span/person change (s; n) |\n| --- | --- | --- | --- | --- | --- | --- |\n");
    for comparison in &report.comparisons {
        let delta = |key: &str| {
            comparison.deltas.get(key).map_or_else(
                || "unavailable".into(),
                |value| format!("{:+.3}; {}", value.mean, value.samples),
            )
        };
        writeln!(
            output,
            "| {} | {} | {} | {} | {} | {} | {} |",
            comparison.axis,
            label(&comparison.baseline),
            label(&comparison.current),
            delta("mean_walking_distance_m"),
            delta("moving_time_per_person_s"),
            delta("queue_wait_per_person_s"),
            delta("low_progress_span_per_person_s")
        )
        .unwrap();
    }
    output.push_str("\n### Cases to inspect\n\n| Case | People with low progress | Low-progress span (agent s) | Service wait (agent s) | Isolated controls run / requested | Evidence |\n| --- | --- | --- | --- | --- | --- |\n");
    for (index, case) in report.cases.iter().enumerate() {
        if let Some(summary) = &case.journeys {
            writeln!(output, "| {index:04} | {} | {:.3} | {:.3} | {}/{} | [locations, people, routes and replay](case-{index:04}/journeys.md) |", summary.low_progress_agents, summary.low_progress_span_agent_s, summary.service_wait_agent_s, summary.controls_run, summary.controls_requested).unwrap();
        }
    }
}

fn metric(group: &GroupSummary, key: &str) -> String {
    group.metrics.get(key).map_or_else(
        || "unavailable".into(),
        |value| format!("{:.6} ({})", value.mean, value.samples),
    )
}

fn label(key: &GroupKey) -> String {
    format!("{}/{}/{}/{}", key.pattern, key.scale, key.load, key.policy)
}

pub fn csv(report: &StudyReport) -> Result<String, String> {
    report.verify()?;
    let observations: Vec<_> = report
        .cases
        .iter()
        .map(super::StudyCaseResult::observations)
        .collect();
    let metrics: BTreeSet<_> = observations
        .iter()
        .flat_map(|values| values.keys().cloned())
        .collect();
    let mut output =
        "case,pattern,scale,load,policy,seed,passed,measured,controlled_input_hash".to_owned();
    for metric in &metrics {
        write!(output, ",{metric}").expect("string writes");
    }
    output.push('\n');
    for (index, (case, values)) in report.cases.iter().zip(observations).enumerate() {
        write!(
            output,
            "{index},{},{},{},{},{},{},{},{}",
            case.key.pattern,
            case.key.scale,
            case.key.load,
            case.key.policy,
            case.key.seed,
            case.passed,
            !values.is_empty(),
            case.context
                .as_ref()
                .map_or("", |context| context.controlled_input_hash.as_str())
        )
        .expect("string writes");
        for metric in &metrics {
            output.push(',');
            if let Some(value) = values.get(metric) {
                write!(output, "{value}").expect("string writes");
            }
        }
        output.push('\n');
    }
    Ok(output)
}

fn cases(report: &StudyReport, output: &mut String) {
    output.push_str("\n## Cases and replay\n\nPaths are relative to this report. Open an available run with `chiyoda-replay case-0000/run.json`; the case index below selects the artifact.\n\n| Case | Pattern / scale / load / policy | Seed | Outcome | Artifacts |\n| --- | --- | --- | --- | --- |\n");
    for (index, case) in report.cases.iter().enumerate() {
        let root = format!("case-{index:04}");
        let outcome = if case.passed {
            "passed".into()
        } else if let Some(failure) = &case.setup_failure {
            format!("{} failed", failure.stage)
        } else {
            case.evaluation
                .as_ref()
                .expect("verified report")
                .failures
                .iter()
                .map(|failure| failure.check.clone())
                .collect::<Vec<_>>()
                .join(", ")
        };
        let mut links = vec![format!("[report]({root}/report.json)")];
        if case.generation_hash.is_some() {
            links.push(format!("[geometry]({root}/generation.json)"));
        }
        if case.input_sha256.is_some() {
            links.push(format!("[scenario]({root}/scenario.json)"));
        }
        if case
            .evaluation
            .as_ref()
            .is_some_and(|evaluation| evaluation.bundle_hash.is_some())
        {
            links.push(format!("[run]({root}/run.json)"));
        }
        if case.journeys.is_some() {
            links.push(format!("[journeys]({root}/journeys.md)"));
            links.push(format!("[people CSV]({root}/journeys.csv)"));
        }
        writeln!(
            output,
            "| {index:04} | {} | {} | {} | {} |",
            label(&GroupKey::from(case)),
            case.key.seed,
            outcome,
            links.join(" · ")
        )
        .expect("string writes");
    }
}
