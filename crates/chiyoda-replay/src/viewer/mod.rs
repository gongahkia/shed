//! Native viewing and exports behind the unified `chiyoda view` command.
use anyhow::{Context, Result, bail};
use chiyoda_core::{
    BundleVerification, RunBundle, bundle::TraceFrame, model::Surface, verify_run_bundle,
};
use clap::Parser;
use std::{
    fs,
    path::{Path, PathBuf},
    sync::mpsc::{self, Receiver, Sender},
    thread,
    time::{Duration, Instant},
};

#[cfg(test)]
use crate::render::{self, BACKGROUND};
#[cfg(test)]
use crate::{
    HEIGHT, WIDTH,
    export::{export_png, frame_delay, write_png},
};
use crate::{
    ViewMode,
    export::{export_gif, gif_manifest_path, validate_playback_timing},
    load_watch_scenario,
};
mod native;
const WATCH_POLL_INTERVAL: Duration = Duration::from_millis(100);
const WATCH_DEBOUNCE: Duration = Duration::from_millis(150);
#[derive(Debug, Parser)]
#[command(
    name = "chiyoda view",
    version,
    about = "Native deterministic Chiyoda trace replay"
)]
pub struct ViewerArgs {
    /// A `run.json` emitted by `chiyoda run`.
    bundle: Option<PathBuf>,
    /// Read, validate, and rerun this JSON scenario after every saved edit.
    #[arg(long, value_name = "SOURCE", conflicts_with = "bundle")]
    watch: Option<PathBuf>,
    /// Start paused; space toggles playback and arrow keys advance frames.
    #[arg(long)]
    paused: bool,
    /// Start at an exact recorded integration step, for a diagnostic replay moment.
    #[arg(long, requires = "bundle", conflicts_with_all = ["watch", "export_gif"])]
    at_step: Option<u64>,
    /// Initial authored surface to display. Tab cycles declared surfaces.
    #[arg(long)]
    surface: Option<String>,
    /// Initial projection, also used by headless exports. V toggles it in the viewer.
    #[arg(long, value_enum, default_value_t = ViewMode::Surface)]
    view: ViewMode,
    /// Highlight an exact recorded person ID; PNG sidecars retain its detailed evidence.
    #[arg(long, requires="bundle", conflicts_with_all=["resource","export_gif"])]
    agent: Option<String>,
    /// Highlight exit:ID, gate:ID, connector:ID or waypoint:ID; retain its evidence in PNG sidecars.
    #[arg(long, requires = "bundle", conflicts_with = "export_gif")]
    resource: Option<String>,
    /// First recorded step in a selected person's trail.
    #[arg(long, requires = "agent", conflicts_with = "compare")]
    trail_start_step: Option<u64>,
    /// Compare another reconstructed run using the same camera and simulation clock.
    #[arg(long, requires="bundle", conflicts_with_all=["watch","export_gif","allow_legacy_hash_only"])]
    compare: Option<PathBuf>,
    /// Shared comparison time; panels use the latest recorded frame at or before it.
    #[arg(long, requires = "compare", conflicts_with = "at_step")]
    at_time: Option<f64>,
    #[arg(long, default_value_t = 1.0, conflicts_with = "export_gif")]
    zoom: f64,
    #[arg(
        long,
        default_value_t = 0.0,
        conflicts_with = "export_gif",
        allow_hyphen_values = true
    )]
    pan_x: f64,
    #[arg(
        long,
        default_value_t = 0.0,
        conflicts_with = "export_gif",
        allow_hyphen_values = true
    )]
    pan_y: f64,
    /// Simulation seconds shown per wall-clock second.
    #[arg(long, default_value_t = 1.0)]
    speed: f64,
    /// Trace cadence for in-memory runs made by `--watch`.
    #[arg(long, default_value_t = 1)]
    trace_every: u32,
    /// Directory in which pressing P writes a PNG snapshot of the current frame.
    #[arg(long, default_value = "out/chiyoda")]
    snapshot_dir: PathBuf,
    /// Write an animated GIF and a provenance sidecar from a verified run bundle, then exit.
    #[arg(long, value_name = "GIF", requires = "bundle", conflicts_with_all = ["watch", "export_png"])]
    export_gif: Option<PathBuf>,
    /// Write one exact recorded frame as PNG with provenance, then exit without a window.
    #[arg(
        long,
        value_name = "PNG",
        requires = "bundle",
        conflicts_with = "watch"
    )]
    export_png: Option<PathBuf>,
    /// Simulation seconds represented by one wall-clock second in `--export-gif`.
    #[arg(long, default_value_t = 1.0, requires = "export_gif")]
    gif_speed: f64,
    /// Permit a hash-only display of an incompatible legacy runtime artifact.
    #[arg(long)]
    allow_legacy_hash_only: bool,
}

pub fn execute(cli: &ViewerArgs) -> Result<()> {
    if cli.export_gif.is_none() && (!cli.speed.is_finite() || cli.speed <= 0.0) {
        bail!("--speed must be a finite value greater than zero");
    }
    if cli.trace_every == 0 {
        bail!("--trace-every must be greater than zero");
    }
    if !cli.gif_speed.is_finite() || cli.gif_speed <= 0.0 {
        bail!("--gif-speed must be a finite value greater than zero");
    }
    match (cli.bundle.clone(), cli.watch.clone()) {
        (Some(bundle_path), None) => replay_bundle(&bundle_path, cli),
        (None, Some(source_path)) => {
            if cli.allow_legacy_hash_only {
                bail!("--allow-legacy-hash-only applies only to a persisted run bundle");
            }
            replay_watch(source_path, cli)
        }
        (None, None) => bail!("provide a run bundle or --watch SOURCE.json"),
        (Some(_), Some(_)) => unreachable!("clap rejects conflicting inputs"),
    }
}

fn replay_bundle(bundle_path: &Path, cli: &ViewerArgs) -> Result<()> {
    let (bundle, verification) = load_bundle(bundle_path, cli.allow_legacy_hash_only)?;
    let surface_index = surface_index(&bundle.scenario.scenario.surfaces, cli.surface.as_deref())?;
    if (cli.export_gif.is_some() || cli.export_png.is_some())
        && verification != BundleVerification::Reconstructed
    {
        bail!("headless export requires a bundle reconstructed by the installed runtime");
    }
    if let Some(output) = &cli.export_png {
        if let Some(path) = &cli.compare {
            let (candidate, _) = load_bundle(path, false)?;
            let request = native::comparison_request(&bundle, &candidate, cli)?;
            crate::comparison::export_comparison(&bundle, &candidate, &request, output)?;
        } else if let Some(selection) = native::selection(cli)? {
            let index = replay_start_index(&bundle.trace, cli.at_step)?;
            crate::evidence::export_evidence(
                &bundle,
                &crate::evidence::EvidenceRequest {
                    step: bundle.trace[index].step,
                    selection,
                    surface: cli.surface.clone(),
                    view: cli.view,
                    trail_start_step: cli.trail_start_step,
                    camera: native::camera(cli)?,
                },
                output,
            )?;
        } else {
            let index = replay_start_index(&bundle.trace, cli.at_step)?;
            let controls = native::camera(cli)?;
            crate::export::export_png_with_camera(
                &bundle,
                output,
                surface_index,
                index,
                cli.view,
                controls,
            )?;
        }
        println!("PNG replay export: {}", output.display());
        println!(
            "provenance sidecar: {}",
            gif_manifest_path(output).display()
        );
        return Ok(());
    }
    if let Some(output) = &cli.export_gif {
        if verification != BundleVerification::Reconstructed {
            bail!(
                "--export-gif requires a bundle reconstructed by the installed runtime; legacy hash-only display artifacts are not exportable"
            );
        }
        let manifest = export_gif(&bundle, output, surface_index, cli.gif_speed, cli.view)?;
        println!("GIF replay export: {}", output.display());
        println!(
            "provenance sidecar: {}",
            gif_manifest_path(output).display()
        );
        println!("bundle hash: {}", manifest.bundle_sha256);
        return Ok(());
    }
    validate_playback_timing(&bundle.trace, cli.speed)?;
    let initial_frame = replay_start_index(&bundle.trace, cli.at_step)?;
    native::open(Some(bundle), None, initial_frame, surface_index, cli)
}

fn replay_watch(source_path: PathBuf, cli: &ViewerArgs) -> Result<()> {
    native::open(
        None,
        Some(WatchController::new(source_path, cli.trace_every)),
        0,
        0,
        cli,
    )
}

fn replay_start_index(trace: &[TraceFrame], step: Option<u64>) -> Result<usize> {
    let Some(step) = step else {
        return Ok(0);
    };
    trace
        .iter()
        .position(|frame| frame.step == step)
        .with_context(|| format!("integration step {step} is not present in the recorded trace"))
}

fn load_bundle(
    bundle_path: &Path,
    allow_legacy_hash_only: bool,
) -> Result<(RunBundle, BundleVerification)> {
    let source = fs::read_to_string(bundle_path)
        .with_context(|| format!("reading {}", bundle_path.display()))?;
    let bundle: RunBundle = serde_json::from_str(&source)
        .with_context(|| format!("parsing {}", bundle_path.display()))?;
    let verification = verify_run_bundle(&bundle)?;
    match verification {
        BundleVerification::Reconstructed => {}
        BundleVerification::HashOnlyLegacy if allow_legacy_hash_only => {
            eprintln!(
                "warning: bundle uses an incompatible runtime contract; only its hash was verified"
            );
        }
        BundleVerification::HashOnlyLegacy => {
            bail!(
                "bundle uses an incompatible runtime contract and cannot be reconstructed; pass --allow-legacy-hash-only only to display its hash"
            );
        }
    }
    if bundle.trace.is_empty() {
        bail!("bundle contains no trace frames");
    }
    Ok((bundle, verification))
}

fn select_reloaded_surface(
    surfaces: &[Surface],
    previous_surface: Option<&str>,
    initial_surface: Option<&str>,
) -> Result<usize> {
    match previous_surface {
        Some(previous_surface) => Ok(surfaces
            .iter()
            .position(|surface| surface.id == previous_surface)
            .unwrap_or(0)),
        None => surface_index(surfaces, initial_surface),
    }
}

#[derive(Debug)]
struct PendingRevision {
    revision: u64,
    source: String,
    ready_at: Instant,
}

#[derive(Debug)]
struct WatchUpdate {
    revision: u64,
    result: std::result::Result<RunBundle, String>,
    evidence: Option<crate::inspection::EvidenceIndex>,
}

#[derive(Debug)]
struct WatchController {
    source_path: PathBuf,
    trace_every_steps: u32,
    observed_source: Option<String>,
    latest_revision: u64,
    pending: Option<PendingRevision>,
    running_revision: Option<u64>,
    sender: Sender<WatchUpdate>,
    receiver: Receiver<WatchUpdate>,
    last_poll: Instant,
    last_read_error: Option<String>,
    status: String,
}

impl WatchController {
    fn new(source_path: PathBuf, trace_every_steps: u32) -> Self {
        let (sender, receiver) = mpsc::channel();
        Self {
            source_path,
            trace_every_steps,
            observed_source: None,
            latest_revision: 0,
            pending: None,
            running_revision: None,
            sender,
            receiver,
            last_poll: Instant::now()
                .checked_sub(WATCH_POLL_INTERVAL)
                .unwrap_or_else(Instant::now),
            last_read_error: None,
            status: "waiting for the first saved source revision".to_owned(),
        }
    }

    fn tick(&mut self) -> Option<WatchUpdate> {
        let now = Instant::now();
        if now.duration_since(self.last_poll) >= WATCH_POLL_INTERVAL {
            self.last_poll = now;
            self.observe_source(now);
        }

        let mut latest_update = None;
        while let Ok(update) = self.receiver.try_recv() {
            if self.running_revision == Some(update.revision) {
                self.running_revision = None;
            }
            if update.revision == self.latest_revision {
                latest_update = Some(update);
            }
        }
        self.start_ready_revision(now);
        latest_update
    }

    fn observe_source(&mut self, now: Instant) {
        match fs::read_to_string(&self.source_path) {
            Ok(source) => {
                let source_became_readable = self.last_read_error.take().is_some();
                if self.observed_source.as_ref() == Some(&source) {
                    if source_became_readable {
                        self.status = format!(
                            "watch revision {} unchanged after source recovery",
                            self.latest_revision
                        );
                    }
                    return;
                }
                self.observed_source = Some(source.clone());
                self.latest_revision = self.latest_revision.saturating_add(1);
                self.pending = Some(PendingRevision {
                    revision: self.latest_revision,
                    source,
                    ready_at: now + WATCH_DEBOUNCE,
                });
                self.status = format!(
                    "watch revision {} waiting for save debounce",
                    self.latest_revision
                );
            }
            Err(error) => {
                let message = format!("cannot read {}: {error}", self.source_path.display());
                if self.last_read_error.as_deref() != Some(&message) {
                    eprintln!("watch source error: {message}");
                    self.last_read_error = Some(message.clone());
                }
                self.status = message;
            }
        }
    }

    fn start_ready_revision(&mut self, now: Instant) {
        if self.running_revision.is_some() {
            return;
        }
        let Some(pending) = self.pending.take_if(|pending| pending.ready_at <= now) else {
            return;
        };
        let sender = self.sender.clone();
        let trace_every_steps = self.trace_every_steps;
        let revision = pending.revision;
        self.running_revision = Some(revision);
        self.status = format!("watch revision {revision} loading and running");
        thread::spawn(move || {
            let result = load_watch_scenario(&pending.source, trace_every_steps);
            let evidence = result
                .as_ref()
                .ok()
                .map(crate::inspection::EvidenceIndex::new);
            let _ = sender.send(WatchUpdate {
                revision,
                result,
                evidence,
            });
        });
    }

    fn mark_loaded(&mut self, revision: u64) {
        self.status = format!("watch revision {revision} loaded");
    }

    fn mark_failed(&mut self, revision: u64, message: &str) {
        self.status = format!(
            "watch revision {revision} failed: {}",
            concise_message(message)
        );
    }

    fn status(&self) -> &str {
        &self.status
    }
}

fn concise_message(message: &str) -> String {
    const MAXIMUM_CHARS: usize = 120;
    let one_line = message.replace('\n', " ");
    if one_line.chars().count() <= MAXIMUM_CHARS {
        one_line
    } else {
        format!(
            "{}…",
            one_line.chars().take(MAXIMUM_CHARS - 1).collect::<String>()
        )
    }
}

fn surface_index(surfaces: &[Surface], requested: Option<&str>) -> Result<usize> {
    if surfaces.is_empty() {
        bail!("bundle scenario has no authored surfaces");
    }
    match requested {
        Some(id) => surfaces
            .iter()
            .position(|surface| surface.id == id)
            .with_context(|| format!("surface `{id}` is not present in the bundle")),
        None => Ok(0),
    }
}

fn snapshot_path(directory: &Path, snapshot_number: u64, step: u64) -> PathBuf {
    directory.join(format!("snapshot-{snapshot_number:04}-step-{step}.png"))
}

fn next_snapshot_path(directory: &Path, snapshot_number: &mut u64, step: u64) -> Result<PathBuf> {
    loop {
        *snapshot_number = snapshot_number
            .checked_add(1)
            .context("PNG snapshot number overflowed")?;
        let path = snapshot_path(directory, *snapshot_number, step);
        if !path.exists() {
            return Ok(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::export::{gif_frame_delays_centiseconds, palette_indices};
    use chiyoda_core::model::Point3;
    use chiyoda_core::{RunOptions, decode_scenario, run};
    use flate2::read::ZlibDecoder;
    use std::collections::HashMap;
    use std::io::Read;
    const WATCH_SOURCE: &str = include_str!("../fixtures/main/project.json");

    fn surface(id: &str, x_m: f64, y_m: f64, width_m: f64, depth_m: f64) -> Surface {
        Surface {
            id: id.to_owned(),
            origin: Point3 { x_m, y_m, z_m: 0.0 },
            width_m,
            depth_m,
        }
    }

    #[test]
    fn named_surface_selection_follows_declaration_order_and_rejects_unknown_ids() {
        let surfaces = [
            surface("concourse", 0.0, 0.0, 10.0, 10.0),
            surface("platform", 0.0, 0.0, 10.0, 10.0),
        ];

        assert_eq!(surface_index(&surfaces, None).unwrap(), 0);
        assert_eq!(surface_index(&surfaces, Some("platform")).unwrap(), 1);
        assert!(surface_index(&surfaces, Some("roof")).is_err());
    }

    #[test]
    fn diagnostic_replay_seeks_an_exact_recorded_step_and_rejects_missing_steps() {
        let trace: Vec<_> = [0, 3, 6]
            .into_iter()
            .map(|step| TraceFrame {
                step,
                time_s: 0.0,
                agents: Vec::new(),
            })
            .collect();
        assert_eq!(replay_start_index(&trace, None).unwrap(), 0);
        assert_eq!(replay_start_index(&trace, Some(3)).unwrap(), 1);
        assert!(replay_start_index(&trace, Some(4)).is_err());
        assert!(replay_start_index(&trace, Some(9)).is_err());
        assert!(
            ViewerArgs::try_parse_from(["chiyoda view", "run.json", "--paused", "--at-step", "3"])
                .is_ok()
        );
        assert!(
            ViewerArgs::try_parse_from([
                "chiyoda view",
                "--watch",
                "scenario.json",
                "--at-step",
                "3"
            ])
            .is_err()
        );
    }

    #[test]
    fn replay_timing_uses_simulation_timestamps_and_rejects_regressions() {
        let trace = [
            TraceFrame {
                step: 0,
                time_s: 0.0,
                agents: Vec::new(),
            },
            TraceFrame {
                step: 1,
                time_s: 0.5,
                agents: Vec::new(),
            },
        ];
        assert_eq!(frame_delay(&trace, 0, 2.0), Duration::from_millis(250));
        assert!(validate_playback_timing(&trace, 1.0).is_ok());
        let invalid = [trace[1].clone(), trace[0].clone()];
        assert!(validate_playback_timing(&invalid, 1.0).is_err());
        assert!(validate_playback_timing(&trace, 0.0).is_err());
        let unrepresentable = [
            trace[0].clone(),
            TraceFrame {
                step: 2,
                time_s: f64::MAX,
                agents: Vec::new(),
            },
        ];
        assert!(validate_playback_timing(&unrepresentable, 1.0).is_err());
    }

    #[test]
    fn watch_loading_matches_an_explicit_in_memory_run() {
        let watched = load_watch_scenario(WATCH_SOURCE, 1).expect("watch fixture succeeds");
        let scenario = decode_scenario(WATCH_SOURCE).expect("watch fixture parses");
        let expected = run(
            &scenario,
            RunOptions {
                trace_every_steps: 1,
            },
        )
        .expect("explicit run succeeds");

        assert_eq!(watched.bundle_hash, expected.bundle_hash);
        assert_eq!(watched.trace, expected.trace);
        assert_eq!(watched.metrics, expected.metrics);
    }

    #[test]
    fn watch_loading_keeps_json_and_validation_failures_explicit() {
        let missing_scenario = load_watch_scenario("seed 1\n", 1).unwrap_err();
        assert!(missing_scenario.starts_with("JSON input error:"));

        let invalid = WATCH_SOURCE.replace("\"duration_s\": 2.0", "\"duration_s\": 0.0");
        let invalid_result = load_watch_scenario(&invalid, 1).unwrap_err();
        assert!(invalid_result.starts_with("validation error:"));
    }

    #[test]
    fn stale_watch_worker_result_is_discarded() {
        let path = std::env::temp_dir().join(format!(
            "chiyoda-replay-watch-stale-{}-{}.json",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let mut controller = WatchController::new(path, 1);
        controller.latest_revision = 2;
        controller.running_revision = Some(1);
        controller
            .sender
            .send(WatchUpdate {
                revision: 1,
                result: Err("stale failure".to_owned()),
                evidence: None,
            })
            .expect("test receiver is live");

        assert!(controller.tick().is_none());
        assert_eq!(controller.running_revision, None);
        assert_eq!(controller.latest_revision, 2);
    }

    #[test]
    fn reload_surface_selection_prefers_the_existing_surface_then_the_requested_one() {
        let surfaces = [
            surface("concourse", 0.0, 0.0, 10.0, 10.0),
            surface("platform", 0.0, 0.0, 10.0, 10.0),
        ];
        assert_eq!(
            select_reloaded_surface(&surfaces, Some("platform"), Some("concourse"))
                .expect("existing surface remains selected"),
            1,
        );
        assert_eq!(
            select_reloaded_surface(&surfaces, None, Some("concourse"))
                .expect("requested surface is selected"),
            0,
        );
        assert_eq!(
            select_reloaded_surface(&surfaces, Some("missing"), None)
                .expect("a deleted selected surface falls back to the first one"),
            0,
        );
        assert!(select_reloaded_surface(&surfaces, None, Some("missing")).is_err());
    }

    #[test]
    fn verified_bundle_gif_export_records_trace_provenance_without_rerunning() {
        let directory = std::env::temp_dir().join(format!(
            "chiyoda-replay-gif-{}-{}",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let output = directory.join("watch.gif");
        let bundle = load_watch_scenario(WATCH_SOURCE, 1).expect("watch fixture succeeds");
        let manifest =
            export_gif(&bundle, &output, 0, 2.0, ViewMode::Surface).expect("GIF export succeeds");
        let gif = fs::read(&output).expect("GIF output reads");
        let sidecar: serde_json::Value = serde_json::from_str(
            &fs::read_to_string(gif_manifest_path(&output)).expect("sidecar reads"),
        )
        .expect("sidecar parses");

        assert_eq!(&gif[..6], b"GIF89a");
        assert_eq!(manifest.bundle_sha256, bundle.bundle_hash);
        assert_eq!(manifest.scenario_sha256, bundle.scenario_hash);
        assert_eq!(manifest.trace_every_steps, 1);
        assert_eq!(manifest.frame_delays_centiseconds.len(), bundle.trace.len());
        assert_eq!(sidecar["bundle_sha256"], bundle.bundle_hash);
        assert_eq!(sidecar["playback_speed"], 2.0);
        assert!(sidecar.get("sprite_atlas").is_none());
        assert_eq!(sidecar["renderer"]["version"], render::RENDERER_VERSION);
        assert_eq!(
            sidecar["selected_surface"],
            bundle.scenario.scenario.surfaces[0].id
        );
        assert_eq!(
            sidecar["rendered_surfaces"],
            serde_json::json!([bundle.scenario.scenario.surfaces[0].id])
        );
        let mut decoder = gif::DecodeOptions::new();
        decoder.set_color_output(gif::ColorOutput::RGBA);
        let mut reader = decoder.read_info(gif.as_slice()).unwrap();
        assert!(reader.global_palette().is_some());
        assert_eq!(usize::from(reader.width()), WIDTH);
        assert_eq!(usize::from(reader.height()), HEIGHT);
        let mut delays = Vec::new();
        while let Some(frame) = reader.read_next_frame().unwrap() {
            assert!(
                frame.palette.is_none(),
                "every frame uses the same global palette"
            );
            assert_eq!(frame.buffer.len(), WIDTH * HEIGHT * 4);
            delays.push(frame.delay);
        }
        assert_eq!(delays, manifest.frame_delays_centiseconds);
        fs::remove_dir_all(directory).expect("test GIF directory is removable");
    }

    #[test]
    fn headless_png_uses_an_exact_frame_preserves_the_bundle_and_refuses_overwrites() {
        let directory =
            std::env::temp_dir().join(format!("chiyoda-replay-png-{}", std::process::id()));
        let output = directory.join("frame.png");
        let bundle = load_watch_scenario(WATCH_SOURCE, 1).expect("fixture runs");
        let original = serde_json::to_vec(&bundle).unwrap();
        export_png(&bundle, &output, 0, 1, ViewMode::Overview).unwrap();
        let sidecar: serde_json::Value =
            serde_json::from_slice(&fs::read(gif_manifest_path(&output)).unwrap()).unwrap();
        assert_eq!(sidecar["recorded_step"], bundle.trace[1].step);
        assert_eq!(sidecar["recorded_time_s"], bundle.trace[1].time_s);
        assert_eq!(sidecar["view"], "overview");
        assert_eq!(
            sidecar["rendered_surfaces"],
            serde_json::json!(
                bundle
                    .scenario
                    .scenario
                    .surfaces
                    .iter()
                    .map(|surface| &surface.id)
                    .collect::<Vec<_>>()
            )
        );
        assert_eq!(sidecar["bundle_sha256"], bundle.bundle_hash);
        assert_eq!(serde_json::to_vec(&bundle).unwrap(), original);
        let image = fs::read(&output).unwrap();
        assert!(export_png(&bundle, &output, 0, 0, ViewMode::Surface).is_err());
        assert_eq!(fs::read(&output).unwrap(), image);
        fs::remove_file(&output).unwrap();
        assert!(export_png(&bundle, &output, 0, 0, ViewMode::Surface).is_err());
        assert!(
            !output.exists(),
            "a pre-existing sidecar blocks the image too"
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn headless_cli_requires_a_bundle_and_rejects_conflicting_exports_and_sprites() {
        let cli = ViewerArgs::try_parse_from([
            "replay",
            "run.json",
            "--export-png",
            "frame.png",
            "--at-step",
            "20",
            "--view",
            "overview",
        ])
        .unwrap();
        assert_eq!(cli.at_step, Some(20));
        assert_eq!(cli.view, ViewMode::Overview);
        for args in [
            vec!["replay", "--export-png", "frame.png"],
            vec![
                "replay",
                "run.json",
                "--export-png",
                "frame.png",
                "--export-gif",
                "run.gif",
            ],
            vec![
                "replay",
                "--watch",
                "source.json",
                "--export-png",
                "frame.png",
            ],
            vec!["replay", "run.json", "--sprite-atlas", "atlas.json"],
        ] {
            assert!(ViewerArgs::try_parse_from(args).is_err());
        }
    }

    #[test]
    fn gif_palette_preserves_all_semantic_colors_exactly_and_reuses_edge_mappings() {
        let palette = render::gif_palette();
        let mut cache = HashMap::new();
        let indices = palette_indices(&palette, &palette, &mut cache);
        for (color, index) in palette.iter().zip(indices) {
            assert_eq!(*color, palette[usize::from(index)]);
        }
        let edges = [0x0039_6e91, 0x00df_e1e7];
        let first = palette_indices(&edges, &palette, &mut cache);
        let count = cache.len();
        assert_eq!(first, palette_indices(&edges, &palette, &mut cache));
        assert_eq!(count, cache.len());
    }

    #[test]
    fn gif_timing_uses_recorded_trace_gaps_and_a_terminal_minimum_delay() {
        let trace = [
            TraceFrame {
                step: 0,
                time_s: 0.0,
                agents: Vec::new(),
            },
            TraceFrame {
                step: 1,
                time_s: 0.5,
                agents: Vec::new(),
            },
        ];
        assert_eq!(gif_frame_delays_centiseconds(&trace, 2.0).unwrap(), [25, 1]);
    }

    #[test]
    fn png_snapshot_encodes_rgb_frame_data() {
        let directory = std::env::temp_dir().join(format!(
            "chiyoda-replay-png-{}-{}",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let path = snapshot_path(&directory, 1, 4);
        write_png(&path, &[0x0011_2233, 0x0044_5566], 2, 1).expect("PNG writes");
        let encoded = fs::read(&path).expect("PNG reads");

        assert_eq!(&encoded[..8], b"\x89PNG\r\n\x1a\n");
        assert_eq!(&encoded[12..16], b"IHDR");
        assert_eq!(&encoded[16..20], &2_u32.to_be_bytes());
        assert_eq!(&encoded[20..24], &1_u32.to_be_bytes());
        let idat_offset = 8 + 4 + 4 + 13 + 4;
        let idat_length =
            u32::from_be_bytes(encoded[idat_offset..idat_offset + 4].try_into().unwrap());
        assert_eq!(&encoded[idat_offset + 4..idat_offset + 8], b"IDAT");
        let idat_start = idat_offset + 8;
        let idat_end = idat_start + usize::try_from(idat_length).expect("u32 fits in usize");
        let mut decoded = Vec::new();
        ZlibDecoder::new(&encoded[idat_start..idat_end])
            .read_to_end(&mut decoded)
            .expect("PNG image data decompresses");
        assert_eq!(decoded, [0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66]);
        fs::remove_dir_all(directory).expect("test snapshot directory is removable");
    }

    #[test]
    fn snapshot_paths_do_not_reuse_existing_exports() {
        let directory = std::env::temp_dir().join(format!(
            "chiyoda-replay-snapshot-path-{}-{}",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        fs::create_dir_all(&directory).expect("test snapshot directory is created");
        let existing = snapshot_path(&directory, 1, 4);
        fs::write(&existing, "existing snapshot").expect("existing snapshot is written");
        let mut snapshot_number = 0;

        let next = next_snapshot_path(&directory, &mut snapshot_number, 4)
            .expect("a fresh snapshot path is selected");
        assert_eq!(next, snapshot_path(&directory, 2, 4));
        assert_eq!(snapshot_number, 2);
        fs::remove_dir_all(directory).expect("test snapshot directory is removable");
    }

    #[test]
    fn png_writer_does_not_overwrite_an_existing_snapshot() {
        let directory = std::env::temp_dir().join(format!(
            "chiyoda-replay-no-overwrite-{}-{}",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let path = snapshot_path(&directory, 1, 4);
        write_png(&path, &[BACKGROUND], 1, 1).expect("initial PNG writes");
        assert!(write_png(&path, &[BACKGROUND], 1, 1).is_err());
        fs::remove_dir_all(directory).expect("test snapshot directory is removable");
    }

    #[test]
    fn png_snapshot_rejects_wrong_framebuffer_dimensions() {
        let error = write_png(Path::new("unused.png"), &[BACKGROUND], 2, 1).unwrap_err();
        assert!(error.to_string().contains("framebuffer contains"));
    }
}
