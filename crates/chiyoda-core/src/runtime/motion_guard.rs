//! Bound simultaneous surface proposals before applying any discrete service
//! transition. Scaling a clear segment towards a stop preserves its static
//! half-spaces, speed limit, and authored surface.

use super::{Agent, Point3, SpatialIndex, SurfaceStep};

pub(super) const CLEARANCE_M: f64 = 1e-8;
const SCALING_PASSES: usize = 32;

#[derive(Debug, Clone)]
pub(super) struct Envelope {
    surface: String,
    start: Point3,
    end: Point3,
    radius_m: f64,
}

pub(super) fn envelopes(agents: &[Agent], steps: &[Option<SurfaceStep>]) -> Vec<Option<Envelope>> {
    agents
        .iter()
        .zip(steps)
        .map(|(agent, step)| {
            agent.occupies_surface().then(|| Envelope {
                surface: agent.surface.clone(),
                start: agent.position,
                end: step
                    .as_ref()
                    .map_or(agent.position, |step| step.proposal.end),
                radius_m: agent.radius_m,
            })
        })
        .collect()
}

pub(super) fn limit(agents: &[Agent], steps: &mut [Option<SurfaceStep>], spatial: &SpatialIndex) {
    let mut motions = envelopes(agents, steps);
    let max_travel = motions
        .iter()
        .flatten()
        .map(|motion| motion.start.distance(motion.end))
        .fold(0.0_f64, f64::max);
    let mut pairs = Vec::new();
    for (index, motion) in motions.iter().enumerate() {
        let Some(motion) = motion else {
            continue;
        };
        let reach = motion.radius_m
            + spatial.max_radius_m
            + motion.start.distance(motion.end)
            + max_travel
            + CLEARANCE_M;
        for other in spatial.nearby_indices_within(&motion.surface, motion.start, reach) {
            if other > index {
                pairs.push((index, other));
            }
        }
    }
    // Neither the spatial hash's bucket order nor mutable agent positions
    // determine which conflicting pair is processed first.
    pairs.sort_unstable();
    pairs.dedup();
    limit_envelopes(&mut motions, &pairs);
    for (step, motion) in steps.iter_mut().zip(motions) {
        if let (Some(step), Some(motion)) = (step, motion) {
            step.limited = step.proposal.end.distance(motion.end) > 1e-10;
            step.proposal.end = motion.end;
        }
    }
}

fn limit_envelopes(motions: &mut [Option<Envelope>], pairs: &[(usize, usize)]) {
    for _ in 0..SCALING_PASSES {
        let mut changed = false;
        for &(first, second) in pairs {
            let (Some(a), Some(b)) = (&motions[first], &motions[second]) else {
                continue;
            };
            let Some(fraction) = contact_fraction(a, b) else {
                continue;
            };
            let a_travel = a.start.distance(a.end);
            let b_travel = b.start.distance(b.end);
            let together = (a_travel + b_travel) * fraction;
            let mut a_stopped = a.clone();
            a_stopped.end = a.start;
            let mut b_stopped = b.clone();
            b_stopped.end = b.start;
            let a_alone = contact_fraction(a, &b_stopped).is_none();
            let b_alone = contact_fraction(&a_stopped, b).is_none();
            // Let a safe escape proceed when shortening both would suppress
            // more movement. Equal alternatives retain stable pair order.
            let (a_end, b_end) =
                if a_alone && a_travel > together && (!b_alone || a_travel >= b_travel) {
                    (a.end, b.start)
                } else if b_alone && b_travel > together {
                    (a.start, b.end)
                } else {
                    (a.start.lerp(a.end, fraction), b.start.lerp(b.end, fraction))
                };
            motions[first].as_mut().unwrap().end = a_end;
            motions[second].as_mut().unwrap().end = b_end;
            changed = true;
        }
        if !changed {
            return;
        }
    }
    // Dense dependency chains can need more scaling iterations. A bounded,
    // monotone fallback stops conflicting movers and propagates that stop to
    // any newly conflicting neighbour. Starting discs are independently clear,
    // so at most one stop per mover is needed before the component is safe.
    for _ in 0..=motions.len() {
        let mut changed = false;
        for &(first, second) in pairs {
            let (Some(a), Some(b)) = (&motions[first], &motions[second]) else {
                continue;
            };
            if contact_fraction(a, b).is_none() {
                continue;
            }
            let a_start = a.start;
            let b_start = b.start;
            motions[first].as_mut().unwrap().end = a_start;
            motions[second].as_mut().unwrap().end = b_start;
            changed = true;
        }
        if !changed {
            return;
        }
    }
}

fn contact_fraction(a: &Envelope, b: &Envelope) -> Option<f64> {
    let px = a.start.x_m - b.start.x_m;
    let py = a.start.y_m - b.start.y_m;
    let dx = (a.end.x_m - a.start.x_m) - (b.end.x_m - b.start.x_m);
    let dy = (a.end.y_m - a.start.y_m) - (b.end.y_m - b.start.y_m);
    let quadratic = dx.mul_add(dx, dy * dy);
    let half_linear = px.mul_add(dx, py * dy);
    if quadratic <= 1e-24 || half_linear >= 0.0 {
        return None;
    }
    let body_radius = a.radius_m + b.radius_m;
    let distance_squared = px.mul_add(px, py * py);
    let body_constant = distance_squared - body_radius * body_radius;
    let closest = (-half_linear / quadratic).min(1.0);
    let minimum = quadratic.mul_add(
        closest * closest,
        (2.0 * half_linear).mul_add(closest, body_constant),
    );
    if minimum >= -1e-14 {
        return None;
    }
    // Detect body intersection, not entry into the numerical retreat margin.
    // Otherwise a valid tangent at disc contact would be stopped forever.
    let margin = CLEARANCE_M.min((distance_squared.sqrt() - body_radius).max(0.0) * 0.5);
    let radius = body_radius + margin;
    let constant = distance_squared - radius * radius;
    if constant <= 0.0 {
        return Some(0.0);
    }
    let discriminant = half_linear
        .mul_add(half_linear, -quadratic * constant)
        .max(0.0);
    // Stable form of the earlier quadratic root; retreat slightly from exact
    // contact so the next step does not begin inside due to f64 rounding.
    Some((constant / (-half_linear + discriminant.sqrt())).clamp(0.0, 1.0) * (1.0 - 1e-10))
}

pub(super) fn landing_is_clear(
    motions: &[Option<Envelope>],
    surface: &str,
    point: Point3,
    radius_m: f64,
) -> bool {
    motions
        .iter()
        .flatten()
        .filter(|motion| motion.surface == surface)
        .all(|motion| {
            let dx = motion.end.x_m - motion.start.x_m;
            let dy = motion.end.y_m - motion.start.y_m;
            let length = dx.mul_add(dx, dy * dy);
            let fraction = if length > 0.0 {
                (((point.x_m - motion.start.x_m) * dx + (point.y_m - motion.start.y_m) * dy)
                    / length)
                    .clamp(0.0, 1.0)
            } else {
                0.0
            };
            point.distance(motion.start.lerp(motion.end, fraction))
                >= radius_m + motion.radius_m + CLEARANCE_M
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn motion(start: f64, end: f64) -> Envelope {
        Envelope {
            surface: "floor".into(),
            start: Point3::new(start, 0.0, 0.0),
            end: Point3::new(end, 0.0, 0.0),
            radius_m: 0.3,
        }
    }

    #[test]
    fn crossing_segments_stop_before_contact_even_when_the_endpoints_are_clear() {
        let mut motions = [motion(-1.0, 1.0), motion(1.0, -1.0)].map(Some);
        limit_envelopes(&mut motions, &[(0, 1)]);
        assert!(motions[0].as_ref().unwrap().end.x_m < -0.3);
        assert!(motions[1].as_ref().unwrap().end.x_m > 0.3);
        assert!(
            contact_fraction(motions[0].as_ref().unwrap(), motions[1].as_ref().unwrap()).is_none()
        );
    }

    #[test]
    fn equal_motion_and_a_stationary_person_keep_their_spacing() {
        let mut following = [motion(0.0, 1.0), motion(1.0, 2.0)].map(Some);
        limit_envelopes(&mut following, &[(0, 1)]);
        assert!((following[0].as_ref().unwrap().end.x_m - 1.0).abs() < 1e-12);
        let mut stationary = [motion(0.0, 1.0), motion(1.0, 1.0)].map(Some);
        limit_envelopes(&mut stationary, &[(0, 1)]);
        assert!(stationary[0].as_ref().unwrap().end.x_m < 0.4);
        assert!((stationary[1].as_ref().unwrap().end.x_m - 1.0).abs() < 1e-12);
    }

    #[test]
    fn a_landing_cannot_occupy_a_clear_endpoint_crossed_during_the_interval() {
        let motions = [motion(-1.0, 1.0)].map(Some);
        assert!(!landing_is_clear(
            &motions,
            "floor",
            Point3::new(0.0, 0.0, 0.0),
            0.3
        ));
        assert!(landing_is_clear(
            &motions,
            "other",
            Point3::new(0.0, 0.0, 0.0),
            0.3
        ));
    }

    #[test]
    fn tangent_motion_inside_the_numerical_margin_remains_available() {
        let moving = motion(-0.1, 0.1);
        let mut stationary = motion(0.0, 0.0);
        stationary.start.y_m = 0.6 + CLEARANCE_M * 0.5;
        stationary.end.y_m = stationary.start.y_m;
        let mut motions = [Some(moving), Some(stationary)];
        limit_envelopes(&mut motions, &[(0, 1)]);
        assert!((motions[0].as_ref().unwrap().end.x_m - 0.1).abs() < 1e-12);
    }

    #[test]
    fn interacting_truncations_keep_a_converging_group_clear() {
        let mut motions: Vec<_> = (0..9)
            .map(|index| {
                Some(Envelope {
                    surface: "floor".into(),
                    start: Point3::new(f64::from(index % 3), f64::from(index / 3), 0.0),
                    end: Point3::new(1.0, 1.0, 0.0),
                    radius_m: 0.3,
                })
            })
            .collect();
        let pairs: Vec<_> = (0..9)
            .flat_map(|a| (a + 1..9).map(move |b| (a, b)))
            .collect();
        limit_envelopes(&mut motions, &pairs);
        for &(a, b) in &pairs {
            let first = motions[a].as_ref().unwrap();
            let second = motions[b].as_ref().unwrap();
            for tick in 0..=100 {
                let fraction = f64::from(tick) / 100.0;
                assert!(
                    first
                        .start
                        .lerp(first.end, fraction)
                        .distance(second.start.lerp(second.end, fraction))
                        >= 0.6 - 1e-9
                );
            }
        }
    }
}
