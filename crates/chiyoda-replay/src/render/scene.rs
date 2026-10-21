//! Spatial layers use authored coordinates and recorded states exclusively.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_precision_loss,
    clippy::cast_sign_loss
)]
use super::{
    BORDER, CONNECTOR, EXIT, INACTIVE, INK, MOVING, OBSTACLE, OBSTACLE_EDGE, PAPER, QUEUE,
    ViewMode, WAITING, WAYPOINT, canvas::Canvas,
};
use chiyoda_core::{
    AgentState,
    bundle::{AgentSnapshot, TraceFrame},
    model::{Point3, PortalResource, Scenario, Surface},
};

pub const VIEWPORT: [f32; 4] = [24.0, 24.0, 1152.0, 672.0];
#[derive(Clone, Copy, Debug, serde::Serialize)]
pub struct WorldBounds {
    pub min_x: f64,
    pub min_y: f64,
    pub max_x: f64,
    pub max_y: f64,
}
impl WorldBounds {
    #[must_use]
    pub fn union(self, other: Self) -> Self {
        Self {
            min_x: self.min_x.min(other.min_x),
            min_y: self.min_y.min(other.min_y),
            max_x: self.max_x.max(other.max_x),
            max_y: self.max_y.max(other.max_y),
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct MapConfig {
    pub viewport: [f32; 4],
    pub camera: crate::navigation::CameraControl,
    pub bounds: Option<WorldBounds>,
}
impl Default for MapConfig {
    fn default() -> Self {
        Self {
            viewport: VIEWPORT,
            camera: crate::navigation::CameraControl::default(),
            bounds: None,
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct Camera {
    pub scale: f64,
    min: (f64, f64),
    offset: (f64, f64),
    view: ViewMode,
}
impl Camera {
    #[must_use]
    pub fn new(scenario: &Scenario, surface: &Surface, view: ViewMode) -> Self {
        Self::controlled(scenario, surface, view, MapConfig::default())
    }
    #[must_use]
    pub fn bounds(scenario: &Scenario, surface: &Surface, view: ViewMode) -> WorldBounds {
        let surfaces: Vec<_> = if view == ViewMode::Surface {
            vec![surface]
        } else {
            scenario.surfaces.iter().collect()
        };
        let mut bounds = WorldBounds {
            min_x: f64::INFINITY,
            min_y: f64::INFINITY,
            max_x: f64::NEG_INFINITY,
            max_y: f64::NEG_INFINITY,
        };
        for surface in surfaces {
            for point in corners(surface.origin, surface.width_m, surface.depth_m) {
                let (x, y) = coordinates(point, view);
                bounds.min_x = bounds.min_x.min(x);
                bounds.min_y = bounds.min_y.min(y);
                bounds.max_x = bounds.max_x.max(x);
                bounds.max_y = bounds.max_y.max(y);
            }
        }
        bounds
    }
    #[must_use]
    pub fn controlled(
        scenario: &Scenario,
        surface: &Surface,
        view: ViewMode,
        map: MapConfig,
    ) -> Self {
        let bounds = map
            .bounds
            .unwrap_or_else(|| Self::bounds(scenario, surface, view));
        let width = (bounds.max_x - bounds.min_x).max(0.001);
        let height = (bounds.max_y - bounds.min_y).max(0.001);
        let [x, y, w, h] = map.viewport;
        let scale =
            ((f64::from(w) - 64.0) / width).min((f64::from(h) - 64.0) / height) * map.camera.zoom;
        Self {
            scale,
            min: (
                bounds.min_x.midpoint(bounds.max_x),
                bounds.min_y.midpoint(bounds.max_y),
            ),
            offset: (
                f64::from(x + w / 2.0) + map.camera.pan_x_pixels,
                f64::from(y + h / 2.0) + map.camera.pan_y_pixels,
            ),
            view,
        }
    }
    #[must_use]
    pub fn point(self, point: Point3) -> (f32, f32) {
        let (x, y) = coordinates(point, self.view);
        (
            (self.offset.0 + (x - self.min.0) * self.scale) as f32,
            (self.offset.1 + (y - self.min.1) * self.scale) as f32,
        )
    }
}
fn coordinates(p: Point3, view: ViewMode) -> (f64, f64) {
    match view {
        ViewMode::Surface => (p.x_m, p.y_m),
        ViewMode::Overview => (
            (p.x_m - p.y_m) * 0.866_025_403_784_438_6,
            p.x_m.midpoint(p.y_m) - p.z_m,
        ),
    }
}
fn corners(at: Point3, width: f64, depth: f64) -> [Point3; 4] {
    [
        at,
        Point3 {
            x_m: at.x_m + width,
            ..at
        },
        Point3 {
            x_m: at.x_m + width,
            y_m: at.y_m + depth,
            ..at
        },
        Point3 {
            y_m: at.y_m + depth,
            ..at
        },
    ]
}
#[allow(clippy::too_many_lines)] // authored layers are drawn in a deliberate back-to-front order
pub fn draw(
    canvas: &mut Canvas,
    scenario: &Scenario,
    frame: &TraceFrame,
    surface: &Surface,
    view: ViewMode,
    map: MapConfig,
    inspection: Option<&crate::inspection::Inspection>,
) {
    let camera = Camera::controlled(scenario, surface, view, map);
    canvas.set_map_viewport(map.viewport);
    canvas.map_clip(true);
    let mut floors: Vec<_> = scenario
        .surfaces
        .iter()
        .filter(|floor| view == ViewMode::Overview || floor.id == surface.id)
        .collect();
    floors.sort_by(|a, b| a.origin.z_m.total_cmp(&b.origin.z_m));
    for floor in floors {
        let outline = corners(floor.origin, floor.width_m, floor.depth_m).map(|p| camera.point(p));
        canvas.polygon(&outline, Some(PAPER), Some((BORDER, 1.5)));
        for obstacle in scenario.obstacles.iter().filter(|o| o.surface == floor.id) {
            let points =
                corners(obstacle.at, obstacle.width_m, obstacle.depth_m).map(|p| camera.point(p));
            canvas.polygon(&points, Some(OBSTACLE), Some((OBSTACLE_EDGE, 1.0)));
        }
        for footprint in scenario
            .queue_footprints
            .iter()
            .filter(|q| q.surface == floor.id)
        {
            for rank in 0..footprint.slots {
                let point = camera.point(footprint.position(rank));
                if rank > 0 {
                    canvas.dashed(camera.point(footprint.position(rank - 1)), point, QUEUE);
                }
                canvas.circle(point, 2.0, None, Some((QUEUE, 1.0)));
            }
        }
        for lanes in &scenario.portal_lanes {
            let endpoints = match &lanes.resource {
                PortalResource::Exit { id } => scenario
                    .exits
                    .iter()
                    .filter(|r| r.id == *id)
                    .map(|r| (r.surface.as_str(), r.at, r.width_m))
                    .collect::<Vec<_>>(),
                PortalResource::Gate { id } => scenario
                    .gates
                    .iter()
                    .filter(|r| r.id == *id)
                    .map(|r| (r.surface.as_str(), r.at, r.width_m))
                    .collect(),
                PortalResource::Connector { id } => scenario
                    .connectors
                    .iter()
                    .filter(|r| r.id() == id)
                    .flat_map(|r| {
                        [
                            (r.from_surface(), r.from(), r.width_m()),
                            (r.to_surface(), r.to(), r.width_m()),
                        ]
                    })
                    .collect(),
            };
            for (_, at, width) in endpoints.into_iter().filter(|(id, _, _)| *id == floor.id) {
                for rank in 0..lanes.count {
                    canvas.circle(
                        camera.point(lanes.position(at, width, rank)),
                        2.0,
                        Some(PAPER),
                        Some((INK, 1.0)),
                    );
                }
            }
        }
        for resource in scenario.waypoints.iter().filter(|r| r.surface == floor.id) {
            let p = camera.point(resource.at);
            canvas.polygon(
                &[
                    (p.0, p.1 - 4.0),
                    (p.0 + 4.0, p.1),
                    (p.0, p.1 + 4.0),
                    (p.0 - 4.0, p.1),
                ],
                None,
                Some((WAYPOINT, 1.5)),
            );
        }
        for exit in scenario.exits.iter().filter(|r| r.surface == floor.id) {
            let p = camera.point(exit.at);
            symbol(canvas, p, EXIT);
            canvas.line((p.0 - 3.0, p.1), (p.0 + 3.0, p.1), EXIT, 1.2);
            canvas.line((p.0, p.1 - 3.0), (p.0 + 3.0, p.1), EXIT, 1.2);
            canvas.line((p.0, p.1 + 3.0), (p.0 + 3.0, p.1), EXIT, 1.2);
        }
        for gate in scenario.gates.iter().filter(|r| r.surface == floor.id) {
            let p = camera.point(gate.at);
            symbol(canvas, p, WAITING);
            for dy in [-2.0, 2.0] {
                canvas.line((p.0 - 3.0, p.1 + dy), (p.0 + 3.0, p.1 + dy), WAITING, 1.2);
            }
        }
        for connector in &scenario.connectors {
            for (id, at) in [
                (connector.from_surface(), connector.from()),
                (connector.to_surface(), connector.to()),
            ] {
                if id == floor.id {
                    let p = camera.point(at);
                    symbol(canvas, p, CONNECTOR);
                    canvas.circle(p, 2.5, None, Some((CONNECTOR, 1.2)));
                }
            }
        }
        if let Some(inspection) = inspection {
            trail(canvas, inspection, floor, camera);
        }
        // Draw inactive records first; active movement stays legible at shared endpoints.
        for inactive in [true, false] {
            for agent in frame.agents.iter().filter(|a| {
                a.surface == floor.id
                    && matches!(a.state, AgentState::Evacuated | AgentState::WaitingToDepart)
                        == inactive
            }) {
                person(canvas, agent, scenario, camera);
            }
        }
    }
    if view == ViewMode::Overview {
        for connector in &scenario.connectors {
            canvas.dashed(
                camera.point(connector.from()),
                camera.point(connector.to()),
                CONNECTOR,
            );
        }
    }
    if let Some(inspection) = inspection {
        highlight(canvas, scenario, frame, surface, view, camera, inspection);
    }
    canvas.map_clip(false);
}
fn symbol(canvas: &mut Canvas, p: (f32, f32), color: u32) {
    canvas.rect([p.0 - 7.0, p.1 - 7.0, 14.0, 14.0], PAPER);
    canvas.polygon(
        &[
            (p.0 - 7.0, p.1 - 7.0),
            (p.0 + 7.0, p.1 - 7.0),
            (p.0 + 7.0, p.1 + 7.0),
            (p.0 - 7.0, p.1 + 7.0),
        ],
        None,
        Some((color, 1.2)),
    );
}
fn person(canvas: &mut Canvas, agent: &AgentSnapshot, scenario: &Scenario, camera: Camera) {
    let p = camera.point(Point3 {
        x_m: agent.x_m,
        y_m: agent.y_m,
        z_m: agent.z_m,
    });
    let radius = scenario
        .agents
        .iter()
        .find(|g| g.id == agent.group)
        .map_or(0.0, |g| g.radius_m);
    // A minimum screen radius preserves visibility; this is a marker, not a density estimate.
    let radius = (radius * camera.scale) as f32;
    let radius = radius.max(2.5);
    match agent.state {
        AgentState::WaitingToDepart => canvas.circle(p, radius, None, Some((INACTIVE, 1.2))),
        AgentState::Evacuated => canvas.circle(p, radius, None, Some((EXIT, 1.3))),
        AgentState::InTransit => {
            canvas.circle(p, radius, Some(CONNECTOR), Some((PAPER, 1.0)));
            canvas.circle(p, 1.0, Some(PAPER), None);
        }
        AgentState::Moving => canvas.circle(p, radius, Some(MOVING), Some((PAPER, 1.0))),
        _ => {
            canvas.circle(p, radius, Some(WAITING), Some((PAPER, 1.0)));
            canvas.line((p.0 - 1.5, p.1), (p.0 + 1.5, p.1), PAPER, 1.0);
        }
    }
}
fn trail(
    canvas: &mut Canvas,
    inspection: &crate::inspection::Inspection,
    floor: &Surface,
    camera: Camera,
) {
    for points in inspection.trail.windows(2) {
        let [a, b] = points else {
            continue;
        };
        if a.surface != floor.id
            || b.surface != floor.id
            || matches!(
                a.state,
                AgentState::InTransit | AgentState::WaitingToDepart | AgentState::Evacuated
            )
            || matches!(
                b.state,
                AgentState::InTransit | AgentState::WaitingToDepart | AgentState::Evacuated
            )
        {
            continue;
        }
        let low = inspection
            .person
            .as_ref()
            .and_then(|p| p.whole_run.as_ref())
            .is_some_and(|j| {
                j.low_progress
                    .iter()
                    .any(|w| a.step >= w.start_step && b.step <= w.end_step)
            });
        let from = camera.point(a.position);
        let to = camera.point(b.position);
        if (to.0 - from.0).hypot(to.1 - from.1) >= 0.2 {
            canvas.line(from, to, if low { WAITING } else { super::TRAIL }, 2.0);
        }
    }
}
fn highlight(
    canvas: &mut Canvas,
    scenario: &Scenario,
    frame: &TraceFrame,
    surface: &Surface,
    view: ViewMode,
    camera: Camera,
    inspection: &crate::inspection::Inspection,
) {
    if let Some(person) = &inspection.person {
        let agent = &person.snapshot;
        if view == ViewMode::Overview || agent.surface == surface.id {
            let at = camera.point(Point3 {
                x_m: agent.x_m,
                y_m: agent.y_m,
                z_m: agent.z_m,
            });
            let radius = scenario
                .agents
                .iter()
                .find(|g| g.id == agent.group)
                .map_or(2.5, |g| (g.radius_m * camera.scale) as f32)
                .max(2.5)
                + 4.0;
            canvas.circle(at, radius, None, Some((MOVING, 2.0)));
        }
    }
    if let Some(resource) = &inspection.resource {
        for (id, point) in &resource.endpoints {
            if view == ViewMode::Overview || *id == surface.id {
                canvas.circle(camera.point(*point), 12.0, None, Some((CONNECTOR, 2.0)));
            }
        }
        for agent in frame
            .agents
            .iter()
            .filter(|a| resource.entrants_through_frame.contains(&a.id))
        {
            if view == ViewMode::Overview || agent.surface == surface.id {
                canvas.circle(
                    camera.point(Point3 {
                        x_m: agent.x_m,
                        y_m: agent.y_m,
                        z_m: agent.z_m,
                    }),
                    7.0,
                    None,
                    Some((WAITING, 1.0)),
                );
            }
        }
    }
}
/// Hit testing uses the same world-to-screen transform as drawing. Shift-click
/// can prefer resource endpoints when people obscure a portal symbol.
#[must_use]
pub fn pick(
    bundle: &chiyoda_core::RunBundle,
    index: usize,
    surface: usize,
    view: ViewMode,
    map: MapConfig,
    at: (f32, f32),
    resources_only: bool,
) -> Option<crate::inspection::Selection> {
    use crate::inspection::{ResourceKind, Selection};
    let [left, top, width, height] = map.viewport;
    if at.0 < left || at.0 > left + width || at.1 < top || at.1 > top + height {
        return None;
    }
    let scenario = &bundle.scenario.scenario;
    let floor = &scenario.surfaces[surface];
    let camera = Camera::controlled(scenario, floor, view, map);
    if !resources_only {
        let person = bundle.trace[index]
            .agents
            .iter()
            .filter(|a| view == ViewMode::Overview || a.surface == floor.id)
            .filter_map(|a| {
                let p = camera.point(Point3 {
                    x_m: a.x_m,
                    y_m: a.y_m,
                    z_m: a.z_m,
                });
                let distance = (p.0 - at.0).hypot(p.1 - at.1);
                let radius = scenario
                    .agents
                    .iter()
                    .find(|g| g.id == a.group)
                    .map_or(2.5, |g| (g.radius_m * camera.scale) as f32)
                    .max(2.5);
                (distance <= radius + 5.0).then_some((distance, a))
            })
            .min_by(|a, b| a.0.total_cmp(&b.0));
        if let Some((_, a)) = person {
            return Some(Selection::Agent { id: a.id.clone() });
        }
    }
    let mut candidates = vec![];
    for r in &scenario.exits {
        candidates.push((ResourceKind::Exit, r.id.as_str(), r.surface.as_str(), r.at));
    }
    for r in &scenario.gates {
        candidates.push((ResourceKind::Gate, r.id.as_str(), r.surface.as_str(), r.at));
    }
    for r in &scenario.waypoints {
        candidates.push((
            ResourceKind::Waypoint,
            r.id.as_str(),
            r.surface.as_str(),
            r.at,
        ));
    }
    for r in &scenario.connectors {
        candidates.extend([
            (ResourceKind::Connector, r.id(), r.from_surface(), r.from()),
            (ResourceKind::Connector, r.id(), r.to_surface(), r.to()),
        ]);
    }
    candidates
        .into_iter()
        .filter(|(_, _, s, _)| view == ViewMode::Overview || *s == floor.id)
        .filter_map(|(kind, id, _, point)| {
            let p = camera.point(point);
            let distance = (p.0 - at.0).hypot(p.1 - at.1);
            (distance <= 14.0).then_some((distance, kind, id))
        })
        .min_by(|a, b| a.0.total_cmp(&b.0))
        .map(|(_, resource_kind, id)| Selection::Resource {
            resource_kind,
            id: id.to_owned(),
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decode_scenario;

    #[test]
    fn floor_projection_preserves_metric_aspect_ratio_and_stays_inside_its_viewport() {
        let canonical = decode_scenario(include_str!("../fixtures/main/project.json")).unwrap();
        let scenario = &canonical;
        let mut floor = scenario.surfaces[0].clone();
        for (width, depth) in [(100.0, 5.0), (5.0, 100.0), (20.0, 20.0)] {
            floor.width_m = width;
            floor.depth_m = depth;
            floor.origin.x_m = -30.0;
            floor.origin.y_m = 17.0;
            let camera = Camera::new(scenario, &floor, ViewMode::Surface);
            let origin = camera.point(floor.origin);
            let x = camera.point(Point3 {
                x_m: floor.origin.x_m + 1.0,
                ..floor.origin
            });
            let y = camera.point(Point3 {
                y_m: floor.origin.y_m + 1.0,
                ..floor.origin
            });
            assert!(((x.0 - origin.0) - (y.1 - origin.1)).abs() < 0.001);
            for point in corners(floor.origin, width, depth) {
                let p = camera.point(point);
                assert!((VIEWPORT[0]..=VIEWPORT[0] + VIEWPORT[2]).contains(&p.0));
                assert!((VIEWPORT[1]..=VIEWPORT[1] + VIEWPORT[3]).contains(&p.1));
            }
        }
    }

    #[test]
    fn overview_fits_authored_surface_corners_with_one_uniform_projection_scale() {
        let canonical = decode_scenario(include_str!("../fixtures/main/project.json")).unwrap();
        let scenario = &canonical;
        let camera = Camera::new(scenario, &scenario.surfaces[0], ViewMode::Overview);
        for surface in &scenario.surfaces {
            for point in corners(surface.origin, surface.width_m, surface.depth_m) {
                let p = camera.point(point);
                assert!(p.0 >= VIEWPORT[0] && p.0 <= VIEWPORT[0] + VIEWPORT[2]);
                assert!(p.1 >= VIEWPORT[1] && p.1 <= VIEWPORT[1] + VIEWPORT[3]);
            }
        }
    }
}
