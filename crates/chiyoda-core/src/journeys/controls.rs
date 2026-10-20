use super::{
    AgentJourney, ControlStatus, IsolatedControl, JourneyAnalysis, JourneyOptions, JourneyReport,
    observations,
};
use crate::{
    BundleVerification, RunBundle, RunOptions, bundle::canonical_hash, runtime::run_isolated,
    verify_run_bundle,
};
use std::collections::{BTreeMap, BTreeSet};

pub(super) fn analyze_with_controls(
    bundle: &RunBundle,
    options: &JourneyOptions,
) -> Result<JourneyAnalysis, String> {
    let mut isolated_runs = Vec::new();
    let report = build(bundle, options, |id, replay| {
        let isolated = run_isolated(
            &bundle.scenario.scenario,
            RunOptions {
                trace_every_steps: 1,
            },
            id,
        )
        .map_err(|error| error.to_string())?;
        if replay
            && verify_run_bundle(&isolated).map_err(|error| error.to_string())?
                != BundleVerification::Reconstructed
        {
            return Err("isolated control cannot be reconstructed".into());
        }
        let person = control_journey(bundle, &isolated, id)?;
        let hash = isolated.bundle_hash.clone();
        isolated_runs.push(isolated);
        Ok((hash, person))
    })?;
    Ok(JourneyAnalysis {
        report,
        isolated_runs,
    })
}

pub(super) fn from_evidence(
    bundle: &RunBundle,
    options: &JourneyOptions,
    evidence: &[RunBundle],
) -> Result<JourneyReport, String> {
    let mut indexed = BTreeMap::new();
    for isolated in evidence {
        let id = isolated
            .options
            .get("isolated_agent")
            .ok_or("control has no isolated identity")?;
        if indexed.insert(id.as_str(), isolated).is_some() {
            return Err("duplicate isolated evidence".into());
        }
    }
    let report = build(bundle, options, |id, _| {
        let isolated = indexed
            .remove(id)
            .ok_or("missing isolated control bundle")?;
        Ok((
            isolated.bundle_hash.clone(),
            control_journey(bundle, isolated, id)?,
        ))
    })?;
    if !indexed.is_empty() {
        return Err("unexpected isolated control evidence".into());
    }
    Ok(report)
}

fn control_journey(
    source: &RunBundle,
    isolated: &RunBundle,
    id: &str,
) -> Result<AgentJourney, String> {
    if isolated.scenario != source.scenario
        || isolated.runtime_version != source.runtime_version
        || isolated.options.get("isolated_agent").map(String::as_str) != Some(id)
        || isolated.options.get("execution_scope").map(String::as_str)
            != Some("one-original-agent-no-other-people-1")
    {
        return Err(
            "isolated control does not retain its source scenario, runtime, and identity".into(),
        );
    }
    let observed = observations::analyze(isolated, &JourneyOptions::default())?;
    if observed.agents.len() != 1 || observed.agents[0].agent != id {
        return Err("isolated control has the wrong population".into());
    }
    Ok(observed.agents.into_iter().next().expect("one person"))
}

fn build(
    bundle: &RunBundle,
    options: &JourneyOptions,
    mut resolve: impl FnMut(&str, bool) -> Result<(String, AgentJourney), String>,
) -> Result<JourneyReport, String> {
    let mut report = observations::analyze(bundle, options)?;
    let Some(controls) = &options.isolated_controls else {
        return Ok(report);
    };
    let people: BTreeMap<_, _> = report
        .agents
        .iter()
        .map(|person| (person.agent.as_str(), person))
        .collect();
    let mut requested: BTreeSet<_> = controls.agents.iter().map(String::as_str).collect();
    for person in &report.agents {
        if controls.all_agents || controls.low_progress_agents && !person.low_progress.is_empty() {
            requested.insert(&person.agent);
        }
    }
    let per_run = crate::integration_step_count(
        bundle.scenario.scenario.duration_s,
        bundle.scenario.scenario.timestep_s,
    )
    .checked_mul(if controls.verify_replay { 2 } else { 1 })
    .ok_or("control step budget overflows")?;
    let mut used = 0u64;
    let mut count = 0u32;
    for id in requested {
        let person = people.get(id).copied();
        let status = if person.is_none() {
            Some(ControlStatus::AgentNotFound)
        } else if bundle.runtime_version != crate::RUNTIME_VERSION
            || bundle.options.contains_key("isolated_agent")
        {
            Some(ControlStatus::RuntimeMismatch)
        } else if count >= controls.max_agents
            || per_run > controls.max_simulated_agent_steps.saturating_sub(used)
        {
            Some(ControlStatus::BudgetLimit)
        } else {
            None
        };
        let mut result = IsolatedControl {
            agent: id.into(),
            status: status.clone().unwrap_or(ControlStatus::Incomplete),
            bundle_hash: None,
            replay_verified: false,
            admitted_at_s: None,
            evacuated_at_s: None,
            elapsed_journey_s: None,
            walking_distance_m: None,
            crowded_elapsed_journey_s: person.and_then(|person| person.elapsed_journey_s),
            crowded_minus_isolated_elapsed_s: None,
        };
        if status.is_none() {
            let (hash, isolated) = resolve(id, controls.verify_replay)?;
            result.status = if isolated.evacuated_at_s.is_some() {
                ControlStatus::Completed
            } else {
                ControlStatus::Incomplete
            };
            result.bundle_hash = Some(hash);
            result.replay_verified = controls.verify_replay;
            result.admitted_at_s = isolated.admitted_at_s;
            result.evacuated_at_s = isolated.evacuated_at_s;
            result.elapsed_journey_s = isolated.elapsed_journey_s;
            result.walking_distance_m = Some(isolated.walking_distance_m);
            result.crowded_minus_isolated_elapsed_s = result
                .crowded_elapsed_journey_s
                .zip(isolated.elapsed_journey_s)
                .map(|(crowded, alone)| crowded - alone);
            used += per_run;
            count += 1;
        }
        report.isolated_controls.push(result);
    }
    report.report_hash.clear();
    report.report_hash = canonical_hash(&report);
    Ok(report)
}
