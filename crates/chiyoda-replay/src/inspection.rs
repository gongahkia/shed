//! Recorded inspection evidence. Whole-run accounting is labelled separately
//! from the selected frame; sparse traces never acquire invented time budgets.
use anyhow::{Result, ensure};
use chiyoda_core::{
    AgentState, RunBundle,
    benchmark::diagnostics::LowProgressWindow,
    bundle::{AgentSnapshot, QueueResourceMetrics, RunEvent},
    journeys::{self, JourneyOptions, JourneyReport, TimeBudget},
    model::Point3,
};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum Selection {
    Agent {
        id: String,
    },
    Resource {
        resource_kind: ResourceKind,
        id: String,
    },
}
impl Selection {
    #[must_use]
    pub fn id(&self) -> &str {
        match self {
            Self::Agent { id } | Self::Resource { id, .. } => id,
        }
    }
}
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema, clap::ValueEnum,
)]
#[serde(rename_all = "snake_case")]
pub enum ResourceKind {
    Exit,
    Gate,
    Connector,
    Waypoint,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum Availability {
    Available { report_hash: String },
    Unavailable { reason: String },
}
#[derive(Debug, Clone, Serialize)]
pub struct EventEvidence {
    pub index: usize,
    pub event: RunEvent,
    pub replay_step: u64,
    pub replay_frame_time_s: f64,
    pub source_pointer: String,
}
#[derive(Debug, Clone, Serialize)]
pub struct TrailPoint {
    pub step: u64,
    pub time_s: f64,
    pub surface: String,
    pub position: Point3,
    pub state: AgentState,
}
#[derive(Debug, Clone, Serialize)]
pub struct PersonTotals {
    pub scope: &'static str,
    pub walking_distance_m: f64,
    pub time: TimeBudget,
    pub completed_elapsed_journey_s: Option<f64>,
    pub admitted_at_s: Option<f64>,
    pub evacuated_at_s: Option<f64>,
    pub low_progress_total: usize,
    pub low_progress: Vec<LowProgressWindow>,
    pub decision_events_total: usize,
}
#[derive(Debug, Clone, Serialize)]
pub struct PersonEvidence {
    pub snapshot: AgentSnapshot,
    pub snapshot_pointer: String,
    pub authored_group_pointer: String,
    pub authored_destination: String,
    pub alternative_destinations: Vec<String>,
    pub via: Vec<String>,
    pub latest_route_event: Option<RunEvent>,
    pub whole_run: Option<PersonTotals>,
}
#[derive(Debug, Clone, Serialize)]
pub struct ResourceEvidence {
    pub authored_pointer: String,
    pub authored: serde_json::Value,
    pub endpoints: Vec<(String, Point3)>,
    pub whole_run_queue_audit: Option<QueueResourceMetrics>,
    pub queue_audit_scope: &'static str,
    pub queue_audit_pointer: Option<String>,
    pub queue_entries_through_frame: usize,
    pub entrants_through_frame: Vec<String>,
    pub entrants_total: usize,
    pub attribution_note: &'static str,
}
#[derive(Debug, Clone, Serialize)]
pub struct Inspection {
    pub inspection_version: &'static str,
    pub source_bundle_hash: String,
    pub recorded_step: u64,
    pub recorded_time_s: f64,
    pub selection: Selection,
    pub diagnostics: Availability,
    pub person: Option<PersonEvidence>,
    pub resource: Option<ResourceEvidence>,
    pub events: Vec<EventEvidence>,
    pub events_through_frame_total: usize,
    pub trail: Vec<TrailPoint>,
    pub trail_requested_start_step: u64,
    pub trail_omitted_points: usize,
    pub claim_boundary: &'static str,
}

#[derive(Debug)]
pub struct EvidenceIndex {
    source_bundle_hash: String,
    report: Option<JourneyReport>,
    availability: Availability,
    agent_events: BTreeMap<String, Vec<usize>>,
}
impl EvidenceIndex {
    /// Build once per loaded run, outside the playback draw loop. Reconstruction
    /// is the caller's trust boundary; live previews retain their distinct label.
    #[must_use]
    pub fn new(bundle: &RunBundle) -> Self {
        let result = if bundle.runtime_version != chiyoda_core::RUNTIME_VERSION {
            Err("journey diagnostics require the current runtime contract".to_owned())
        } else if bundle.options.get("trace_every_steps").map(String::as_str) != Some("1") {
            Err("Detailed journey accounting is unavailable: record every integration step with --trace-every 1".to_owned())
        } else {
            journeys::analyze(bundle, &JourneyOptions::default())
        };
        let (report, availability) = match result {
            Ok(report) => {
                let status = Availability::Available {
                    report_hash: report.report_hash.clone(),
                };
                (Some(report), status)
            }
            Err(reason) => (None, Availability::Unavailable { reason }),
        };
        let mut agent_events: BTreeMap<String, Vec<usize>> = BTreeMap::new();
        for (index, event) in bundle.events.iter().enumerate() {
            agent_events
                .entry(event.subject.clone())
                .or_default()
                .push(index);
        }
        Self {
            source_bundle_hash: bundle.bundle_hash.clone(),
            report,
            availability,
            agent_events,
        }
    }
    #[allow(clippy::too_many_lines)] // selection branches retain explicit evidence scopes and source pointers
    pub fn inspect(
        &self,
        bundle: &RunBundle,
        index: usize,
        selection: &Selection,
        trail_start_step: Option<u64>,
    ) -> Result<Inspection> {
        ensure!(
            bundle.bundle_hash == self.source_bundle_hash,
            "inspection index belongs to a different bundle"
        );
        let frame = bundle
            .trace
            .get(index)
            .ok_or_else(|| anyhow::anyhow!("frame index is not recorded"))?;
        let start = trail_start_step.unwrap_or(0);
        ensure!(
            start <= frame.step,
            "trail start must not follow the selected frame"
        );
        ensure!(
            bundle.trace.iter().any(|f| f.step == start),
            "trail start is not recorded"
        );
        let mut result = Inspection {
            inspection_version: "1",
            source_bundle_hash: bundle.bundle_hash.clone(),
            recorded_step: frame.step,
            recorded_time_s: frame.time_s,
            selection: selection.clone(),
            diagnostics: self.availability.clone(),
            person: None,
            resource: None,
            events: vec![],
            events_through_frame_total: 0,
            trail: vec![],
            trail_requested_start_step: start,
            trail_omitted_points: 0,
            claim_boundary: "Frame state and event timestamps are recorded evidence. Whole-run totals and low-progress spans include the full run, including periods after this frame. Resource entrants are queue-entry history, not a reconstructed current queue or a per-resource individual wait. Trails use consecutive recorded positions, never interpolated movement or a measured path distance.",
        };
        let event_indices = match selection {
            Selection::Agent { id } => {
                let (agent_index, agent) = frame
                    .agents
                    .iter()
                    .enumerate()
                    .find(|(_, a)| a.id == *id)
                    .ok_or_else(|| anyhow::anyhow!("unknown person `{id}` in this frame"))?;
                let scenario = &bundle.scenario.scenario;
                let (group_index, group) = scenario
                    .agents
                    .iter()
                    .enumerate()
                    .find(|(_, g)| g.id == agent.group)
                    .ok_or_else(|| anyhow::anyhow!("recorded person has no authored group"))?;
                let journey = self
                    .report
                    .as_ref()
                    .and_then(|r| r.agents.iter().find(|a| a.agent == *id));
                let whole_run = journey.map(|j| PersonTotals {
                    scope: "whole_run",
                    walking_distance_m: j.walking_distance_m,
                    time: j.time.clone(),
                    completed_elapsed_journey_s: j.elapsed_journey_s,
                    admitted_at_s: j.admitted_at_s,
                    evacuated_at_s: j.evacuated_at_s,
                    low_progress_total: j.low_progress.len(),
                    low_progress: j.low_progress.iter().take(200).cloned().collect(),
                    decision_events_total: j.decisions.len(),
                });
                let indices: Vec<_> = self
                    .agent_events
                    .get(id)
                    .into_iter()
                    .flatten()
                    .copied()
                    .filter(|i| bundle.events[*i].time_s <= frame.time_s)
                    .collect();
                let latest_route_event = indices
                    .iter()
                    .rev()
                    .map(|i| &bundle.events[*i])
                    .find(|e| matches!(e.kind.as_str(), "route_recomputed" | "route_unavailable"))
                    .cloned();
                result.person = Some(PersonEvidence {
                    snapshot: agent.clone(),
                    snapshot_pointer: format!("/trace/{index}/agents/{agent_index}"),
                    authored_group_pointer: format!("/scenario/scenario/agents/{group_index}"),
                    authored_destination: group.destination.clone(),
                    alternative_destinations: group.alternative_destinations.clone(),
                    via: group.via.clone(),
                    latest_route_event,
                    whole_run,
                });
                let points: Vec<_> = bundle.trace[..=index]
                    .iter()
                    .filter(|f| f.step >= start)
                    .filter_map(|f| {
                        let a = f
                            .agents
                            .get(agent_index)
                            .filter(|a| a.id == *id)
                            .or_else(|| f.agents.iter().find(|a| a.id == *id))?;
                        Some(TrailPoint {
                            step: f.step,
                            time_s: f.time_s,
                            surface: a.surface.clone(),
                            position: Point3 {
                                x_m: a.x_m,
                                y_m: a.y_m,
                                z_m: a.z_m,
                            },
                            state: a.state.clone(),
                        })
                    })
                    .collect();
                result.trail_omitted_points = points.len().saturating_sub(2000);
                result.trail = points
                    .into_iter()
                    .skip(result.trail_omitted_points)
                    .collect();
                indices
            }
            Selection::Resource { resource_kind, id } => {
                let mut resource = resource_evidence(bundle, *resource_kind, id)?;
                let kind = resource_queue_kind(bundle, *resource_kind, id);
                let indices: Vec<_> = bundle
                    .events
                    .iter()
                    .enumerate()
                    .filter(|(_, e)| e.time_s <= frame.time_s && resource_event(e, kind, id))
                    .map(|(i, _)| i)
                    .collect();
                let entrants: BTreeSet<_> = indices
                    .iter()
                    .map(|i| &bundle.events[*i])
                    .filter(|e| e.kind.starts_with("queue_entered_"))
                    .map(|e| e.subject.clone())
                    .collect();
                resource.queue_entries_through_frame = indices
                    .iter()
                    .filter(|i| bundle.events[**i].kind.starts_with("queue_entered_"))
                    .count();
                resource.entrants_total = entrants.len();
                resource.entrants_through_frame = entrants.into_iter().take(200).collect();
                result.resource = Some(resource);
                indices
            }
        };
        result.events_through_frame_total = event_indices.len();
        result.events = event_indices
            .into_iter()
            .rev()
            .take(100)
            .map(|i| {
                let event = &bundle.events[i];
                let at = bundle
                    .trace
                    .partition_point(|f| f.time_s < event.time_s)
                    .min(bundle.trace.len() - 1);
                let replay = &bundle.trace[at];
                EventEvidence {
                    index: i,
                    event: event.clone(),
                    replay_step: replay.step,
                    replay_frame_time_s: replay.time_s,
                    source_pointer: format!("/events/{i}"),
                }
            })
            .collect();
        Ok(result)
    }
}
fn resource_queue_kind(bundle: &RunBundle, kind: ResourceKind, id: &str) -> &'static str {
    match kind {
        ResourceKind::Exit => "exit",
        ResourceKind::Gate => "gate",
        ResourceKind::Waypoint => "waypoint",
        ResourceKind::Connector => {
            if bundle
                .scenario
                .scenario
                .connectors
                .iter()
                .any(|c| c.id() == id && c.kind() == chiyoda_core::model::ConnectorKind::Lift)
            {
                "lift"
            } else {
                "connector"
            }
        }
    }
}
fn resource_event(event: &RunEvent, kind: &str, id: &str) -> bool {
    (event.kind == format!("queue_entered_{kind}") && event.detail == id)
        || (event.kind == "queue_service_reserved" && event.detail == format!("{kind}:{id}"))
        || (matches!(
            event.kind.as_str(),
            "connector_boarding" | "connector_arrival"
        ) && matches!(kind, "connector" | "lift")
            && event.detail == id)
        || (event.kind == "gate_processed" && kind == "gate" && event.detail == id)
        || (event.kind == "evacuated" && kind == "exit" && event.detail == id)
        || (event.kind == "waypoint_reached" && kind == "waypoint" && event.detail == id)
}
pub fn resource_evidence(
    bundle: &RunBundle,
    kind: ResourceKind,
    id: &str,
) -> Result<ResourceEvidence> {
    let s = &bundle.scenario.scenario;
    let (pointer, authored, endpoints) = match kind {
        ResourceKind::Exit => {
            let (i, r) = s
                .exits
                .iter()
                .enumerate()
                .find(|(_, r)| r.id == id)
                .ok_or_else(|| anyhow::anyhow!("unknown exit `{id}`"))?;
            (
                format!("/scenario/scenario/exits/{i}"),
                serde_json::to_value(r)?,
                vec![(r.surface.clone(), r.at)],
            )
        }
        ResourceKind::Gate => {
            let (i, r) = s
                .gates
                .iter()
                .enumerate()
                .find(|(_, r)| r.id == id)
                .ok_or_else(|| anyhow::anyhow!("unknown gate `{id}`"))?;
            (
                format!("/scenario/scenario/gates/{i}"),
                serde_json::to_value(r)?,
                vec![(r.surface.clone(), r.at)],
            )
        }
        ResourceKind::Waypoint => {
            let (i, r) = s
                .waypoints
                .iter()
                .enumerate()
                .find(|(_, r)| r.id == id)
                .ok_or_else(|| anyhow::anyhow!("unknown waypoint `{id}`"))?;
            (
                format!("/scenario/scenario/waypoints/{i}"),
                serde_json::to_value(r)?,
                vec![(r.surface.clone(), r.at)],
            )
        }
        ResourceKind::Connector => {
            let (i, r) = s
                .connectors
                .iter()
                .enumerate()
                .find(|(_, r)| r.id() == id)
                .ok_or_else(|| anyhow::anyhow!("unknown connector `{id}`"))?;
            (
                format!("/scenario/scenario/connectors/{i}"),
                serde_json::to_value(r)?,
                vec![
                    (r.from_surface().to_owned(), r.from()),
                    (r.to_surface().to_owned(), r.to()),
                ],
            )
        }
    };
    let queue_kind = resource_queue_kind(bundle, kind, id);
    let audit = bundle
        .metrics
        .queue_metrics
        .as_ref()
        .and_then(|q| q.by_resource.as_ref());
    let whole_run_queue_audit = audit
        .and_then(|a| match queue_kind {
            "exit" => a.exits.get(id),
            "gate" => a.gates.get(id),
            "connector" => a.connectors.get(id),
            "lift" => a.lifts.get(id),
            _ => None,
        })
        .cloned();
    let queue_audit_pointer = whole_run_queue_audit.as_ref().map(|_| {
        format!(
            "/metrics/queue_metrics/by_resource/{}/{}",
            match queue_kind {
                "exit" => "exits",
                "gate" => "gates",
                "lift" => "lifts",
                _ => "connectors",
            },
            id.replace('~', "~0").replace('/', "~1")
        )
    });
    Ok(ResourceEvidence {
        authored_pointer: pointer,
        authored,
        endpoints,
        whole_run_queue_audit,
        queue_audit_scope: "whole_run",
        queue_audit_pointer,
        queue_entries_through_frame: 0,
        entrants_through_frame: vec![],
        entrants_total: 0,
        attribution_note: "Queue entries prove historical exposure to this resource; current queue membership and per-resource individual wait are not inferred from nearby positions.",
    })
}

pub fn frame_index(bundle: &RunBundle, step: u64) -> Result<usize> {
    bundle
        .trace
        .iter()
        .position(|f| f.step == step)
        .ok_or_else(|| anyhow::anyhow!("step {step} is not recorded"))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn run(cadence: u32) -> RunBundle {
        let mut scenario =
            chiyoda_core::decode_scenario(include_str!("fixtures/main/project.json")).unwrap();
        scenario.duration_s = 10.0;
        scenario.timestep_s = 0.2;
        scenario.agents[0].count = 4;
        scenario.agents[0].speed_mps = 2.0;
        scenario.exits[0].capacity_per_s = Some(0.5);
        chiyoda_core::run(
            &scenario,
            chiyoda_core::RunOptions {
                trace_every_steps: cadence,
            },
        )
        .unwrap()
    }
    #[test]
    fn full_trace_evidence_retains_exact_sources_and_never_leaks_future_events_or_trails() {
        let bundle = run(1);
        let original = serde_json::to_vec(&bundle).unwrap();
        let evidence = EvidenceIndex::new(&bundle);
        let selection = Selection::Agent {
            id: "passengers:0".into(),
        };
        let at = evidence.inspect(&bundle, 0, &selection, None).unwrap();
        assert!(matches!(at.diagnostics, Availability::Available { .. }));
        assert!(
            at.events
                .iter()
                .all(|e| e.event.time_s <= at.recorded_time_s)
        );
        assert!(at.trail.iter().all(|p| p.step <= at.recorded_step));
        let json = serde_json::to_value(&bundle).unwrap();
        let person = at.person.unwrap();
        assert_eq!(
            json.pointer(&person.snapshot_pointer).unwrap(),
            &serde_json::to_value(&person.snapshot).unwrap()
        );
        let totals = person.whole_run.unwrap();
        assert_eq!(totals.scope, "whole_run");
        assert!((totals.time.total_s() - 10.0).abs() < 1e-7);
        assert!(
            totals.time.moving_s > 0.0,
            "whole-run evidence is explicitly allowed to include the future"
        );
        let end = evidence
            .inspect(&bundle, bundle.trace.len() - 1, &selection, Some(10))
            .unwrap();
        assert_eq!(end.trail[0].step, 10);
        assert!(end.trail.iter().all(|p| p.step >= 10));
        for event in end.events {
            assert_eq!(
                json.pointer(&event.source_pointer).unwrap(),
                &serde_json::to_value(&event.event).unwrap()
            );
        }
        assert_eq!(serde_json::to_vec(&bundle).unwrap(), original);
    }
    #[test]
    fn sparse_traces_keep_positions_and_resource_audits_without_inventing_journey_accounting() {
        let bundle = run(5);
        let index = EvidenceIndex::new(&bundle);
        let person = index
            .inspect(
                &bundle,
                0,
                &Selection::Agent {
                    id: "passengers:0".into(),
                },
                None,
            )
            .unwrap();
        assert!(matches!(
            person.diagnostics,
            Availability::Unavailable { .. }
        ));
        assert!(person.person.unwrap().whole_run.is_none());
        let exit = index
            .inspect(
                &bundle,
                bundle.trace.len() - 1,
                &Selection::Resource {
                    resource_kind: ResourceKind::Exit,
                    id: "street".into(),
                },
                None,
            )
            .unwrap()
            .resource
            .unwrap();
        assert_eq!(exit.queue_audit_scope, "whole_run");
        assert!(exit.whole_run_queue_audit.is_some());
        assert!(exit.queue_entries_through_frame > 0);
        let json = serde_json::to_value(&bundle).unwrap();
        assert!(
            json.pointer(exit.queue_audit_pointer.as_ref().unwrap())
                .is_some()
        );
    }
    #[test]
    fn selectors_and_windows_reject_unknown_or_unrecorded_evidence() {
        let bundle = run(5);
        let index = EvidenceIndex::new(&bundle);
        assert!(
            index
                .inspect(
                    &bundle,
                    0,
                    &Selection::Agent {
                        id: "missing".into()
                    },
                    None
                )
                .is_err()
        );
        assert!(
            index
                .inspect(
                    &bundle,
                    0,
                    &Selection::Resource {
                        resource_kind: ResourceKind::Gate,
                        id: "street".into()
                    },
                    None
                )
                .is_err()
        );
        let selection = Selection::Agent {
            id: "passengers:0".into(),
        };
        assert!(index.inspect(&bundle, 0, &selection, Some(5)).is_err());
        assert!(index.inspect(&bundle, 2, &selection, Some(1)).is_err());
        assert!(frame_index(&bundle, 1).is_err());
    }
}
