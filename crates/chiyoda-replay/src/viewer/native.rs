//! Native input orchestration. Recorded evidence, camera math and exports live in the library.
use super::{
    ViewerArgs, WatchController, load_bundle, next_snapshot_path, replay_start_index,
    select_reloaded_surface,
};
use crate::{
    HEIGHT, WIDTH, comparison, evidence,
    inspection::{EvidenceIndex, Selection},
    navigation::{CameraControl, PlaybackClock, index_at_time},
    render::{self, Camera, MapConfig, Options, Renderer},
};
use anyhow::{Result, ensure};
use chiyoda_core::RunBundle;
use minifb::{Key, KeyRepeat, MouseButton, MouseMode, Window, WindowOptions};
use std::time::Instant;

pub(super) fn selection(cli: &ViewerArgs) -> Result<Option<Selection>> {
    if let Some(id) = &cli.agent {
        return Ok(Some(Selection::Agent { id: id.clone() }));
    }
    cli.resource
        .as_ref()
        .map(|resource| {
            let (kind, id) = resource.split_once(':').ok_or_else(|| {
                anyhow::anyhow!("--resource requires exit:ID, gate:ID, connector:ID or waypoint:ID")
            })?;
            ensure!(!id.is_empty(), "resource ID must be nonempty");
            let resource_kind = match kind {
                "exit" => crate::inspection::ResourceKind::Exit,
                "gate" => crate::inspection::ResourceKind::Gate,
                "connector" => crate::inspection::ResourceKind::Connector,
                "waypoint" => crate::inspection::ResourceKind::Waypoint,
                _ => anyhow::bail!("unknown resource kind `{kind}`"),
            };
            Ok(Selection::Resource {
                resource_kind,
                id: id.to_owned(),
            })
        })
        .transpose()
}
pub(super) fn camera(cli: &ViewerArgs) -> Result<CameraControl> {
    let camera = CameraControl {
        zoom: cli.zoom,
        pan_x_pixels: cli.pan_x,
        pan_y_pixels: cli.pan_y,
    };
    camera.validate()?;
    Ok(camera)
}
pub(super) fn comparison_request(
    a: &RunBundle,
    b: &RunBundle,
    cli: &ViewerArgs,
) -> Result<comparison::ComparisonRequest> {
    let (start, _) = comparison::interval(a, b)?;
    let time_s = if let Some(time) = cli.at_time {
        time
    } else if let Some(step) = cli.at_step {
        a.trace[replay_start_index(&a.trace, Some(step))?].time_s
    } else {
        start
    };
    Ok(comparison::ComparisonRequest {
        time_s,
        surface: cli.surface.clone(),
        view: cli.view,
        selection: selection(cli)?,
        baseline_label: cli
            .bundle
            .as_ref()
            .and_then(|p| p.parent())
            .and_then(|p| p.file_name())
            .map(|s| s.to_string_lossy().into_owned()),
        candidate_label: cli
            .compare
            .as_ref()
            .and_then(|p| p.parent())
            .and_then(|p| p.file_name())
            .map(|s| s.to_string_lossy().into_owned()),
        camera: camera(cli)?,
    })
}

#[allow(clippy::too_many_lines)] // one event loop owns native input; analysis/rendering are shared library calls
pub(super) fn open(
    mut bundle: Option<RunBundle>,
    mut watch: Option<WatchController>,
    initial_frame: usize,
    mut surface: usize,
    cli: &ViewerArgs,
) -> Result<()> {
    let candidate = cli
        .compare
        .as_ref()
        .map(|path| load_bundle(path, false).map(|(bundle, _)| bundle))
        .transpose()?;
    let mut selected = selection(cli)?;
    let mut controls = camera(cli)?;
    let mut evidence = bundle.as_ref().map(EvidenceIndex::new);
    let candidate_evidence = candidate.as_ref().map(EvidenceIndex::new);
    let mut time = bundle
        .as_ref()
        .map_or(0.0, |b| b.trace[initial_frame].time_s);
    if let (Some(a), Some(b)) = (&bundle, &candidate) {
        let request = comparison_request(a, b, cli)?;
        comparison::prepare(
            a,
            b,
            &request,
            evidence.as_ref().unwrap(),
            candidate_evidence.as_ref().unwrap(),
        )?;
        time = request.time_s;
        surface = comparison::surfaces(a, b, request.surface.as_deref())?.0;
    } else if let (Some(b), Some(selection)) = (&bundle, &selected) {
        let inspected = evidence.as_ref().unwrap().inspect(
            b,
            initial_frame,
            selection,
            cli.trail_start_step,
        )?;
        surface = evidence::selected_surface(b, cli.surface.as_deref(), Some(&inspected))?;
    }
    let mut clock = PlaybackClock::new(time, cli.paused, cli.speed)?;
    let mut view = cli.view;
    let mut renderer = Renderer::new();
    let mut buffer = vec![0; WIDTH * HEIGHT];
    let mut window = Window::new("Chiyoda", WIDTH, HEIGHT, WindowOptions::default())?;
    window.set_target_fps(30);
    let mut profile = std::env::var_os("CHIYODA_REPLAY_PROFILE").map(|_| Profile::default());
    let mut last_render_key = String::new();
    let mut last_tick = Instant::now();
    let mut previous_left = false;
    let mut drag = None;
    let mut snapshots = 0;
    while window.is_open() && !window.is_key_down(Key::Escape) {
        let now = Instant::now();
        let elapsed = now.duration_since(last_tick).as_secs_f64();
        last_tick = now;
        if let Some(controller) = &mut watch
            && let Some(update) = controller.tick()
        {
            match update.result {
                Ok(next) => {
                    let previous = bundle
                        .as_ref()
                        .map(|b| b.scenario.scenario.surfaces[surface].id.as_str());
                    match select_reloaded_surface(
                        &next.scenario.scenario.surfaces,
                        previous,
                        cli.surface.as_deref(),
                    ) {
                        Ok(next_surface) => {
                            surface = next_surface;
                            evidence = update.evidence.or_else(|| Some(EvidenceIndex::new(&next)));
                            clock.time_s = next.trace[0].time_s;
                            bundle = Some(next);
                            selected = None;
                            controls = CameraControl::default();
                            controller.mark_loaded(update.revision);
                        }
                        Err(error) => {
                            eprintln!("watch revision {} failed: {error:#}", update.revision);
                            controller.mark_failed(update.revision, &error.to_string());
                        }
                    }
                }
                Err(message) => {
                    eprintln!("watch revision {} failed: {message}", update.revision);
                    controller.mark_failed(update.revision, &message);
                }
            }
        }
        let range = bundle
            .as_ref()
            .map(|a| {
                candidate.as_ref().map_or_else(
                    || Ok((a.trace[0].time_s, a.trace.last().unwrap().time_s)),
                    |b| comparison::interval(a, b),
                )
            })
            .transpose()?;
        if let Some((start, end)) = range {
            clock.advance(elapsed, start, end);
        }
        if window.is_key_pressed(Key::Space, KeyRepeat::No) {
            if let Some((start, end)) = range
                && clock.time_s >= end
            {
                clock.time_s = start;
                clock.paused = false;
            } else {
                clock.paused = !clock.paused;
            }
        }
        if window.is_key_pressed(Key::LeftBracket, KeyRepeat::No) {
            clock.change_speed(0.5);
        }
        if window.is_key_pressed(Key::RightBracket, KeyRepeat::No) {
            clock.change_speed(2.0);
        }
        if window.is_key_pressed(Key::V, KeyRepeat::No) {
            view = view.toggle();
            controls = CameraControl::default();
        }
        if window.is_key_pressed(Key::R, KeyRepeat::No) {
            controls = CameraControl::default();
        }
        for (key, factor) in [(Key::Equal, 1.25), (Key::Minus, 0.8)] {
            if window.is_key_pressed(key, KeyRepeat::Yes) {
                controls.zoom_at(factor, (0.0, 0.0));
            }
        }
        let mut index = 0;
        if let Some(a) = &bundle {
            index = index_at_time(&a.trace, clock.time_s)?;
            if window.is_key_pressed(Key::Right, KeyRepeat::Yes) {
                index = (index + 1).min(a.trace.len() - 1);
                clock.time_s = a.trace[index].time_s;
                clock.paused = true;
            }
            if window.is_key_pressed(Key::Left, KeyRepeat::Yes) {
                index = index.saturating_sub(1);
                clock.time_s = a.trace[index].time_s;
                clock.paused = true;
            }
            if let Some((start, end)) = range {
                clock.time_s = clock.time_s.clamp(start, end);
                index = index_at_time(&a.trace, clock.time_s)?;
            }
            if window.is_key_pressed(Key::Tab, KeyRepeat::No) {
                let next = (1..=a.scenario.scenario.surfaces.len())
                    .map(|offset| (surface + offset) % a.scenario.scenario.surfaces.len())
                    .find(|i| {
                        candidate.as_ref().is_none_or(|b| {
                            b.scenario
                                .scenario
                                .surfaces
                                .iter()
                                .any(|s| s.id == a.scenario.scenario.surfaces[*i].id)
                        })
                    });
                if let Some(next) = next {
                    surface = next;
                    controls = CameraControl::default();
                }
            }
            let compare = candidate
                .as_ref()
                .map(|b| {
                    comparison::prepare(
                        a,
                        b,
                        &comparison::ComparisonRequest {
                            time_s: clock.time_s,
                            surface: Some(a.scenario.scenario.surfaces[surface].id.clone()),
                            view,
                            selection: None,
                            baseline_label: None,
                            candidate_label: None,
                            camera: controls,
                        },
                        evidence.as_ref().unwrap(),
                        candidate_evidence.as_ref().unwrap(),
                    )
                })
                .transpose()?;
            let bounds = compare.as_ref().map(|c| c.common_camera_bounds);
            let map = |camera| MapConfig {
                camera,
                bounds,
                ..MapConfig::default()
            };
            let mouse = window.get_mouse_pos(MouseMode::Pass);
            let viewport = if candidate.is_some() {
                render::COMPARISON_VIEWPORTS[0]
            } else {
                render::VIEWPORT
            };
            let mut under_mouse = map(controls);
            under_mouse.viewport = viewport;
            let mut pick_bundle = a;
            let mut pick_index = index;
            let mut pick_surface = surface;
            if let (Some(b), Some(c), Some((x, _))) = (&candidate, &compare, mouse)
                && x >= 600.0
            {
                under_mouse.viewport = render::COMPARISON_VIEWPORTS[1];
                pick_bundle = b;
                pick_index = index_at_time(&b.trace, clock.time_s)?;
                pick_surface = b
                    .scenario
                    .scenario
                    .surfaces
                    .iter()
                    .position(|s| s.id == c.candidate.surface)
                    .unwrap();
            }
            let left = window.get_mouse_down(MouseButton::Left);
            if let Some(at) = mouse {
                let [timeline_left, _, timeline_width, _] = render::TIMELINE_BOUNDS;
                if left && inside(at, render::TIMELINE_BOUNDS) {
                    if let Some((start, end)) = range {
                        clock.seek_fraction(
                            f64::from((at.0 - timeline_left) / timeline_width),
                            start,
                            end,
                        );
                        index = index_at_time(&a.trace, clock.time_s)?;
                    }
                } else if left && !previous_left && inside(at, under_mouse.viewport) {
                    selected = render::pick(
                        pick_bundle,
                        pick_index,
                        pick_surface,
                        view,
                        under_mouse,
                        at,
                        window.is_key_down(Key::LeftShift) || window.is_key_down(Key::RightShift),
                    );
                }
                if let Some((_, scroll)) = window.get_scroll_wheel()
                    && inside(at, under_mouse.viewport)
                {
                    let [x, y, w, h] = under_mouse.viewport;
                    controls.zoom_at(
                        2.0f64.powf(f64::from(scroll) * 0.2),
                        (f64::from(at.0 - x - w / 2.0), f64::from(at.1 - y - h / 2.0)),
                    );
                }
                if window.get_mouse_down(MouseButton::Right)
                    || window.get_mouse_down(MouseButton::Middle)
                {
                    if let Some((x, y)) = drag {
                        controls.pan(f64::from(at.0 - x), f64::from(at.1 - y));
                    }
                    if drag.is_some() || inside(at, under_mouse.viewport) {
                        drag = Some(at);
                    }
                } else {
                    drag = None;
                }
            }
            previous_left = left;
            if window.is_key_pressed(Key::F, KeyRepeat::No)
                && let Some(selection) = &selected
                && let Ok(inspected) = evidence
                    .as_ref()
                    .unwrap()
                    .inspect(a, index, selection, None)
            {
                let target = inspected
                    .person
                    .as_ref()
                    .map(|p| {
                        (
                            p.snapshot.surface.clone(),
                            chiyoda_core::model::Point3 {
                                x_m: p.snapshot.x_m,
                                y_m: p.snapshot.y_m,
                                z_m: p.snapshot.z_m,
                            },
                        )
                    })
                    .or_else(|| {
                        inspected
                            .resource
                            .as_ref()
                            .and_then(|r| r.endpoints.first().cloned())
                    });
                if let Some((id, point)) = target
                    && let Some(floor) =
                        a.scenario.scenario.surfaces.iter().position(|s| s.id == id)
                    && candidate
                        .as_ref()
                        .is_none_or(|b| b.scenario.scenario.surfaces.iter().any(|s| s.id == id))
                {
                    surface = floor;
                    controls = CameraControl {
                        zoom: 2.0,
                        ..CameraControl::default()
                    };
                    let viewport = if candidate.is_some() {
                        render::COMPARISON_VIEWPORTS[0]
                    } else {
                        render::VIEWPORT
                    };
                    let map = MapConfig {
                        viewport,
                        camera: controls,
                        bounds: candidate.as_ref().map(|b| {
                            let other = b
                                .scenario
                                .scenario
                                .surfaces
                                .iter()
                                .find(|s| s.id == id)
                                .unwrap();
                            Camera::bounds(
                                &a.scenario.scenario,
                                &a.scenario.scenario.surfaces[surface],
                                view,
                            )
                            .union(Camera::bounds(
                                &b.scenario.scenario,
                                other,
                                view,
                            ))
                        }),
                    };
                    let camera = Camera::controlled(
                        &a.scenario.scenario,
                        &a.scenario.scenario.surfaces[surface],
                        view,
                        map,
                    );
                    let p = camera.point(point);
                    controls.pan(
                        f64::from(viewport[0] + viewport[2] / 2.0 - p.0),
                        f64::from(viewport[1] + viewport[3] / 2.0 - p.1),
                    );
                }
            }
        }
        let render_key = format!(
            "{:?}",
            (
                bundle.as_ref().map(|b| &b.bundle_hash),
                index,
                surface,
                view,
                controls,
                selected.as_ref(),
                clock.paused,
                clock.speed,
                if candidate.is_some() {
                    Some(clock.time_s)
                } else {
                    None
                },
                watch.as_ref().map(WatchController::status)
            )
        );
        if render_key != last_render_key {
            let render_started = Instant::now();
            if let Some(a) = &bundle {
                if let Some(b) = &candidate {
                    let request = comparison::ComparisonRequest {
                        time_s: clock.time_s,
                        surface: Some(a.scenario.scenario.surfaces[surface].id.clone()),
                        view,
                        selection: selected.clone(),
                        baseline_label: cli
                            .bundle
                            .as_ref()
                            .and_then(|p| p.parent())
                            .and_then(|p| p.file_name())
                            .map(|s| s.to_string_lossy().into_owned()),
                        candidate_label: cli
                            .compare
                            .as_ref()
                            .and_then(|p| p.parent())
                            .and_then(|p| p.file_name())
                            .map(|s| s.to_string_lossy().into_owned()),
                        camera: controls,
                    };
                    let comparison = comparison::prepare(
                        a,
                        b,
                        &request,
                        evidence.as_ref().unwrap(),
                        candidate_evidence.as_ref().unwrap(),
                    )?;
                    renderer.render_comparison(&mut buffer, a, b, &request, &comparison);
                } else {
                    let inspection = selected.as_ref().and_then(|s| {
                        evidence
                            .as_ref()
                            .unwrap()
                            .inspect(
                                a,
                                index,
                                s,
                                cli.trail_start_step
                                    .filter(|start| *start <= a.trace[index].step),
                            )
                            .ok()
                    });
                    renderer.render(
                        &mut buffer,
                        Some(a),
                        Options {
                            index,
                            surface,
                            view,
                            map: MapConfig {
                                camera: controls,
                                ..MapConfig::default()
                            },
                            inspection: inspection.as_ref(),
                        },
                    );
                }
            } else {
                renderer.render(&mut buffer, None, Options::default());
            }
            if let Some(profile) = &mut profile {
                profile.record(render_started.elapsed().as_secs_f64() * 1000.0);
            }
            last_render_key = render_key;
        }
        if window.is_key_pressed(Key::P, KeyRepeat::No) {
            let step = bundle.as_ref().map_or(0, |b| b.trace[index].step);
            let path = next_snapshot_path(&cli.snapshot_dir, &mut snapshots, step)?;
            crate::export::write_png(&path, &buffer, WIDTH, HEIGHT)?;
            eprintln!("wrote PNG snapshot: {}", path.display());
        }
        window.update_with_buffer(&buffer, WIDTH, HEIGHT)?;
        if let Some(profile) = &mut profile {
            profile.presented += 1;
        }
    }
    if let Some(profile) = profile {
        profile.finish();
    }
    Ok(())
}
fn inside(at: (f32, f32), viewport: [f32; 4]) -> bool {
    let [x, y, w, h] = viewport;
    at.0 >= x && at.0 <= x + w && at.1 >= y && at.1 <= y + h
}

struct Profile {
    started: Instant,
    frames: u64,
    presented: u64,
    render_ms: Vec<f64>,
}
impl Default for Profile {
    fn default() -> Self {
        Self {
            started: Instant::now(),
            frames: 0,
            presented: 0,
            render_ms: vec![],
        }
    }
}
impl Profile {
    fn record(&mut self, ms: f64) {
        self.frames += 1;
        if self.render_ms.len() < 10000 {
            self.render_ms.push(ms);
        }
    }
    fn finish(mut self) {
        self.render_ms.sort_by(f64::total_cmp);
        let elapsed = self.started.elapsed().as_secs_f64();
        let samples = self.render_ms.len();
        #[allow(clippy::cast_precision_loss)]
        let fps = self.presented as f64 / elapsed.max(1e-9);
        eprintln!(
            "replay_profile={}",
            serde_json::json!({"scene_redraws":self.frames,"frames":self.presented,"elapsed_s":elapsed,"window_frames_per_second":fps,"render_samples":samples,"render_median_ms":self.render_ms.get(samples/2),"render_p95_ms":self.render_ms.get((samples*95/100).min(samples.saturating_sub(1))),"scope":"native event loop including presentation pacing; render and inspection samples exclude input, snapshot encoding and presentation, capped at the first 10000 frames"})
        );
    }
}
