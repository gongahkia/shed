//! Choose obstacle-free half-spaces before local velocity selection. Clipping
//! a reciprocal solution afterwards can remove one agent's promised avoidance.

use super::InflatedObstacle;
use crate::{
    avoidance::{Vec2, VelocityConstraint},
    model::{Obstacle, Point3, Surface},
};

pub(super) fn constraints(
    position: Point3,
    preferred: Vec2,
    speed_mps: f64,
    timestep_s: f64,
    surface: &Surface,
    obstacles: &[Obstacle],
    radius_m: f64,
) -> Vec<VelocityConstraint> {
    let mut bounds = vec![
        bound(
            Vec2 { x: 1.0, y: 0.0 },
            position.x_m - surface.origin.x_m,
            timestep_s,
        ),
        bound(
            Vec2 { x: -1.0, y: 0.0 },
            surface.origin.x_m + surface.width_m - position.x_m,
            timestep_s,
        ),
        bound(
            Vec2 { x: 0.0, y: 1.0 },
            position.y_m - surface.origin.y_m,
            timestep_s,
        ),
        bound(
            Vec2 { x: 0.0, y: -1.0 },
            surface.origin.y_m + surface.depth_m - position.y_m,
            timestep_s,
        ),
    ];
    let reach = speed_mps * timestep_s;
    for obstacle in obstacles.iter().filter(|wall| wall.surface == surface.id) {
        let wall = InflatedObstacle::from_obstacle(obstacle, radius_m);
        if wall.max_x < position.x_m - reach
            || wall.min_x > position.x_m + reach
            || wall.max_y < position.y_m - reach
            || wall.min_y > position.y_m + reach
        {
            continue;
        }
        let faces = [
            (Vec2 { x: -1.0, y: 0.0 }, wall.min_x - position.x_m),
            (Vec2 { x: 1.0, y: 0.0 }, position.x_m - wall.max_x),
            (Vec2 { x: 0.0, y: -1.0 }, wall.min_y - position.y_m),
            (Vec2 { x: 0.0, y: 1.0 }, position.y_m - wall.max_y),
        ];
        // A clear starting point has at least one outside face. Prefer the
        // face with most clearance along the navigator's intended step; ties
        // retain axis order. The entire selected segment then stays outside.
        let mut selected: Option<(Vec2, f64, f64)> = None;
        for (normal, gap) in faces.into_iter().filter(|(_, gap)| *gap >= 0.0) {
            let score = gap + normal.dot(preferred) * timestep_s;
            if selected.is_none_or(|(_, _, prior)| score > prior) {
                selected = Some((normal, gap, score));
            }
        }
        if let Some((normal, gap, _)) = selected {
            bounds.push(bound(normal, gap, timestep_s));
        }
    }
    bounds
}

fn bound(normal: Vec2, gap: f64, timestep_s: f64) -> VelocityConstraint {
    VelocityConstraint {
        normal,
        // Keep a strict interior margin. An endpoint rounded onto an expanded
        // wall otherwise makes the final segment clip discard tangential motion.
        // If already closer, a stop remains feasible and no inward step is allowed.
        minimum: -(gap - 1e-8).max(0.0) / timestep_s,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::avoidance::{AvoidanceAgent, choose_velocity_in_domain};

    #[test]
    fn chosen_steps_clear_walls_and_boundaries_even_when_preference_points_into_them() {
        let surface = Surface {
            id: "floor".into(),
            origin: Point3::new(0.0, 0.0, 0.0),
            width_m: 10.0,
            depth_m: 10.0,
        };
        let walls = [Obstacle {
            id: "wall".into(),
            surface: "floor".into(),
            at: Point3::new(4.0, 4.0, 0.0),
            width_m: 2.0,
            depth_m: 2.0,
        }];
        for position in [
            Point3::new(3.69, 5.0, 0.0),
            Point3::new(3.69, 3.69, 0.0),
            Point3::new(0.01, 0.01, 0.0),
        ] {
            for preferred in [
                Vec2 { x: 1.0, y: 0.0 },
                Vec2 { x: -1.0, y: -1.0 },
                Vec2 { x: 0.8, y: 0.8 },
            ] {
                let agents = [AvoidanceAgent {
                    id: "own".into(),
                    position: Vec2 {
                        x: position.x_m,
                        y: position.y_m,
                    },
                    velocity: Vec2::ZERO,
                    radius_m: 0.3,
                    max_speed_mps: 1.0,
                    queue_priority: None,
                }];
                let domain = constraints(position, preferred, 1.0, 0.2, &surface, &walls, 0.3);
                let selected =
                    choose_velocity_in_domain(&agents, 0, preferred, 1.0, 2.5, 0.2, &domain);
                let end = Point3::new(
                    position.x_m + selected.velocity.x * 0.2,
                    position.y_m + selected.velocity.y * 0.2,
                    0.0,
                );
                assert!(surface.contains(end));
                let clipped =
                    crate::runtime::static_safe_position(position, end, &surface, &walls, 0.3);
                assert!(end.distance(clipped) < 1e-12);
            }
        }
    }
}
