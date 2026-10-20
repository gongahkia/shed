use super::{
    Agent, InflatedObstacle, RunEvent, RuntimeResources, reroute, segment_intersects_obstacle,
};
use crate::{
    bundle::{AgentKnowledgeSnapshot, KnowledgeSource, KnownAvailability},
    model::{
        AgentGroup, AgentKnowledge, Point3, PortalLanes, PortalResource, Proposition, Scenario,
    },
};
use std::collections::BTreeMap;

#[derive(Debug, Clone)]
pub(super) struct LocalKnowledge {
    observation_radius_m: f64,
    pub snapshot: AgentKnowledgeSnapshot,
}

impl LocalKnowledge {
    pub fn new(config: &AgentKnowledge, group: &AgentGroup) -> Self {
        Self {
            observation_radius_m: config.observation_radius_m,
            snapshot: AgentKnowledgeSnapshot {
                known_exits: group
                    .exit_candidates()
                    .filter(|exit| {
                        config
                            .known_exits
                            .as_ref()
                            .is_none_or(|known| known.iter().any(|id| id == exit))
                    })
                    .map(str::to_owned)
                    .collect(),
                availability: BTreeMap::new(),
            },
        }
    }
}

pub(super) fn knows_exit(agent: &Agent, exit: &str) -> bool {
    agent
        .knowledge
        .as_ref()
        .is_none_or(|knowledge| knowledge.snapshot.known_exits.iter().any(|id| id == exit))
}

pub(super) fn resource_for_claim(claim: &Proposition) -> PortalResource {
    match claim {
        Proposition::ConnectorAvailability { connector, .. } => PortalResource::Connector {
            id: connector.clone(),
        },
        Proposition::ExitAvailability { exit, .. } => PortalResource::Exit { id: exit.clone() },
        Proposition::GateAvailability { gate, .. } => PortalResource::Gate { id: gate.clone() },
    }
}

/// Returns whether evidence or exit familiarity changed. Unknown availability
/// is optimistically open until evidence arrives; known geometry stays static.
pub(super) fn learn(
    agent: &mut Agent,
    resource: &PortalResource,
    open: bool,
    time_s: f64,
    source: KnowledgeSource,
) -> bool {
    let Some(knowledge) = &mut agent.knowledge else {
        return false;
    };
    let mut changed = false;
    if let PortalResource::Exit { id } = resource
        && agent.exit_candidates.contains(id)
        && !knowledge.snapshot.known_exits.contains(id)
    {
        knowledge.snapshot.known_exits.push(id.clone());
        // Familiarity never changes the declared equal-cost route tie break.
        knowledge.snapshot.known_exits.sort_by_key(|exit| {
            agent
                .exit_candidates
                .iter()
                .position(|candidate| candidate == exit)
        });
        changed = true;
    }
    let key = format!("{}:{}", resource.kind(), resource.id());
    if knowledge
        .snapshot
        .availability
        .get(&key)
        .is_none_or(|prior| prior.open != open || prior.source != source)
    {
        knowledge.snapshot.availability.insert(
            key,
            KnownAvailability {
                open,
                learned_at_s: time_s,
                source,
            },
        );
        changed = true;
    }
    match resource {
        PortalResource::Connector { id } => super::apply_connector_belief(agent, id, open),
        PortalResource::Exit { id } => super::apply_exit_belief(agent, id, open),
        PortalResource::Gate { id } => super::apply_gate_belief(agent, id, open),
    }
    changed
}

pub(super) fn encounter(
    agent: &mut Agent,
    resource: &PortalResource,
    open: bool,
    time_s: f64,
    events: &mut Vec<RunEvent>,
) {
    if learn(agent, resource, open, time_s, KnowledgeSource::Observation) {
        events.push(observation_event(agent, resource, open, time_s));
    }
}

fn observation_event(
    agent: &Agent,
    resource: &PortalResource,
    open: bool,
    time_s: f64,
) -> RunEvent {
    RunEvent {
        time_s,
        kind: "resource_observed".into(),
        subject: agent.id.clone(),
        detail: format!(
            "{}:{}={}",
            resource.kind(),
            resource.id(),
            if open { "open" } else { "closed" }
        ),
    }
}

fn visible(
    scenario: &Scenario,
    agent: &Agent,
    surface: &str,
    portal: Point3,
    lanes: Option<&PortalLanes>,
    width_m: f64,
) -> bool {
    let Some(knowledge) = &agent.knowledge else {
        return false;
    };
    let contact = super::portal_position(lanes, portal, width_m, &agent.id);
    let at_contact =
        agent.position.distance(contact) <= agent.radius_m + super::NAVIGATION_EPSILON_M;
    let seen_point = if at_contact { contact } else { portal };
    agent.surface == surface
        && (at_contact || agent.position.distance(portal) <= knowledge.observation_radius_m)
        && scenario
            .obstacles
            .iter()
            .filter(|wall| wall.surface == surface)
            .all(|wall| {
                !segment_intersects_obstacle(
                    agent.position,
                    seen_point,
                    InflatedObstacle {
                        min_x: wall.at.x_m,
                        max_x: wall.at.x_m + wall.width_m,
                        min_y: wall.at.y_m,
                        max_y: wall.at.y_m + wall.depth_m,
                    },
                )
            })
}

pub(super) fn observe_agents(
    scenario: &Scenario,
    agents: &mut [Agent],
    time_s: f64,
    resources: &RuntimeResources,
    events: &mut Vec<RunEvent>,
) {
    for agent in agents
        .iter_mut()
        .filter(|agent| agent.knowledge.is_some() && agent.occupies_surface())
    {
        let mut changed = false;
        for (resource, seen) in scenario
            .exits
            .iter()
            .map(|exit| {
                (
                    PortalResource::Exit {
                        id: exit.id.clone(),
                    },
                    visible(
                        scenario,
                        agent,
                        &exit.surface,
                        exit.at,
                        scenario.portal_lanes_for_exit(&exit.id),
                        exit.width_m,
                    ),
                )
            })
            .chain(scenario.gates.iter().map(|gate| {
                (
                    PortalResource::Gate {
                        id: gate.id.clone(),
                    },
                    visible(
                        scenario,
                        agent,
                        &gate.surface,
                        gate.at,
                        scenario.portal_lanes_for_gate(&gate.id),
                        gate.width_m,
                    ),
                )
            }))
            .chain(scenario.connectors.iter().map(|connector| {
                (
                    PortalResource::Connector {
                        id: connector.id().into(),
                    },
                    visible(
                        scenario,
                        agent,
                        connector.from_surface(),
                        connector.from(),
                        scenario.portal_lanes_for_connector(connector.id()),
                        connector.width_m(),
                    ) || visible(
                        scenario,
                        agent,
                        connector.to_surface(),
                        connector.to(),
                        scenario.portal_lanes_for_connector(connector.id()),
                        connector.width_m(),
                    ),
                )
            }))
            .collect::<Vec<_>>()
        {
            if seen {
                let open = super::resource_is_open(resources, &resource);
                if learn(agent, &resource, open, time_s, KnowledgeSource::Observation) {
                    changed = true;
                    events.push(observation_event(agent, &resource, open, time_s));
                }
            }
        }
        if changed {
            reroute(
                scenario,
                agent,
                time_s,
                events,
                &resources.closed_connectors,
                &resources.closed_exits,
                &resources.closed_gates,
            );
        }
    }
}
