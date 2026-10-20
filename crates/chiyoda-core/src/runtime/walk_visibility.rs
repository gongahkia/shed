//! Bounded memoization of directed visibility between static obstacle corners.
//! Start/target edges and Dijkstra traversal retain their original calculations.
use super::InflatedObstacle;
use crate::model::Surface;
use std::cell::RefCell;

const MAX_ENTRIES: usize = 4;
const MAX_CORNERS: usize = 2048;

struct Entry {
    key: Vec<u64>,
    visibility: Vec<u8>,
}

thread_local! {
    static CACHE: RefCell<Vec<Entry>> = const { RefCell::new(Vec::new()) };
}

pub(super) fn with_cache<R>(
    surface: &Surface,
    obstacles: &[InflatedObstacle],
    corners: usize,
    compute: impl FnOnce(Option<&mut [u8]>) -> R,
) -> R {
    if corners == 0 || corners > MAX_CORNERS {
        return compute(None);
    }
    let mut key = vec![
        surface.origin.x_m.to_bits(),
        surface.origin.y_m.to_bits(),
        surface.origin.z_m.to_bits(),
        surface.width_m.to_bits(),
        surface.depth_m.to_bits(),
    ];
    key.push(u64::try_from(corners).expect("bounded corner count"));
    for obstacle in obstacles {
        key.extend([
            obstacle.min_x.to_bits(),
            obstacle.max_x.to_bits(),
            obstacle.min_y.to_bits(),
            obstacle.max_y.to_bits(),
        ]);
    }
    CACHE.with(|cache| {
        let mut cache = cache.borrow_mut();
        if let Some(index) = cache.iter().position(|entry| entry.key == key) {
            let entry = cache.remove(index);
            cache.push(entry);
        } else {
            if cache.len() == MAX_ENTRIES {
                cache.remove(0);
            }
            cache.push(Entry {
                key,
                visibility: vec![0; corners * corners],
            });
        }
        compute(Some(
            &mut cache
                .last_mut()
                .expect("populated visibility cache")
                .visibility,
        ))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Point3;

    #[test]
    fn exact_geometry_bits_select_entries_and_capacity_is_bounded() {
        CACHE.with(|cache| cache.borrow_mut().clear());
        let mut surface = Surface {
            id: "test".into(),
            origin: Point3::new(0.0, 0.0, 0.0),
            width_m: 10.0,
            depth_m: 10.0,
        };
        let obstacle = InflatedObstacle {
            min_x: 2.0,
            max_x: 3.0,
            min_y: 2.0,
            max_y: 3.0,
        };
        with_cache(&surface, &[obstacle], 4, |entry| entry.unwrap()[1] = 2);
        with_cache(&surface, &[obstacle], 4, |entry| {
            assert_eq!(entry.unwrap()[1], 2);
        });
        for index in 1..8 {
            surface.origin.z_m = f64::from(index);
            with_cache(&surface, &[obstacle], 4, |entry| {
                assert_eq!(entry.unwrap()[1], 0);
            });
        }
        CACHE.with(|cache| assert_eq!(cache.borrow().len(), MAX_ENTRIES));
        with_cache(&surface, &[obstacle], MAX_CORNERS + 1, |entry| {
            assert!(entry.is_none());
        });
        CACHE.with(|cache| assert_eq!(cache.borrow().len(), MAX_ENTRIES));
    }

    #[test]
    fn cached_and_uncached_searches_match_across_endpoints_radii_and_geometry() {
        use crate::runtime::shortest_walk_path_in_graph;
        for variant in 0..6 {
            let surface = Surface {
                id: "floor".into(),
                origin: Point3::new(0.0, 0.0, f64::from(variant)),
                width_m: 10.0 + f64::from(variant),
                depth_m: 10.0,
            };
            for radius in [0.0, 0.2, 0.6] {
                let obstacles = [
                    InflatedObstacle {
                        min_x: 2.0 - radius,
                        max_x: 4.0 + radius,
                        min_y: 2.0 - radius,
                        max_y: 7.0 + radius,
                    },
                    InflatedObstacle {
                        min_x: 6.0 - radius,
                        max_x: 7.0 + radius,
                        min_y: 1.0 - radius,
                        max_y: 5.0 + radius,
                    },
                ];
                let endpoints = [(0.8, 0.8), (8.0, 8.0), (0.8, 8.0), (8.0, 0.8)];
                for (sx, sy) in endpoints {
                    for (tx, ty) in endpoints {
                        let mut nodes = vec![
                            Point3::new(sx, sy, surface.origin.z_m),
                            Point3::new(tx, ty, surface.origin.z_m),
                        ];
                        for obstacle in &obstacles {
                            nodes.extend(
                                obstacle
                                    .corners(surface.origin.z_m)
                                    .into_iter()
                                    .filter(|point| surface.contains(*point)),
                            );
                        }
                        let expected = shortest_walk_path_in_graph(&nodes, &obstacles, None);
                        let cached = with_cache(&surface, &obstacles, nodes.len() - 2, |cache| {
                            shortest_walk_path_in_graph(&nodes, &obstacles, cache)
                        });
                        assert_eq!(
                            cached, expected,
                            "variant {variant}, radius {radius}, {sx},{sy} to {tx},{ty}"
                        );
                    }
                }
            }
        }
    }
}
