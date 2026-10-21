use anyhow::{Context, Result, bail};
use chiyoda_core::{
    BundleVerification, RunBundle, RunOptions, bundle::TraceFrame, decode_scenario, model::Surface,
    run, validate, verify_run_bundle,
};
use clap::{Parser, ValueEnum};
use flate2::{Compression, write::ZlibEncoder};
use gif::{Encoder as GifEncoder, Frame as GifFrame, Repeat};
use minifb::{Key, KeyRepeat, Window, WindowOptions};
use serde::Serialize;
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::mpsc::{self, Receiver, Sender},
    thread,
    time::{Duration, Instant},
};

const WIDTH: usize = 1_200;
const HEIGHT: usize = 800;
use render::BACKGROUND;
mod render;
const WATCH_POLL_INTERVAL: Duration = Duration::from_millis(100);
const WATCH_DEBOUNCE: Duration = Duration::from_millis(150);
#[derive(Debug, Parser)]
#[command(
    name = "chiyoda-replay",
    version,
    about = "Native deterministic Chiyoda trace replay"
)]
struct Cli {
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
    /// Simulation seconds shown per wall-clock second.
    #[arg(long, default_value_t = 1.0)]
    speed: f64,
    /// Trace cadence for in-memory runs made by `--watch`.
    #[arg(long, default_value_t = 1)]
    trace_every: u32,
    /// Directory in which pressing P writes a PNG snapshot of the current frame.
    #[arg(long, default_value = "out/chiyoda-replay")]
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

fn main() -> Result<()> {
    let cli = Cli::parse();
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
        (Some(bundle_path), None) => replay_bundle(&bundle_path, &cli),
        (None, Some(source_path)) => {
            if cli.allow_legacy_hash_only {
                bail!("--allow-legacy-hash-only applies only to a persisted run bundle");
            }
            replay_watch(source_path, &cli)
        }
        (None, None) => bail!("provide a run bundle or --watch SOURCE.json"),
        (Some(_), Some(_)) => unreachable!("clap rejects conflicting inputs"),
    }
}

fn replay_bundle(bundle_path: &Path, cli: &Cli) -> Result<()> {
    let (bundle, verification) = load_bundle(bundle_path, cli.allow_legacy_hash_only)?;
    let surface_index = surface_index(&bundle.scenario.scenario.surfaces, cli.surface.as_deref())?;
    if (cli.export_gif.is_some() || cli.export_png.is_some())
        && verification != BundleVerification::Reconstructed
    {
        bail!("headless export requires a bundle reconstructed by the installed runtime");
    }
    if let Some(output) = &cli.export_png {
        let index = replay_start_index(&bundle.trace, cli.at_step)?;
        export_png(&bundle, output, surface_index, index, cli.view)?;
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
    replay(
        Some(bundle),
        cli.paused,
        surface_index,
        cli.surface.as_deref(),
        cli.speed,
        &cli.snapshot_dir,
        None,
        initial_frame,
        cli.view,
        if verification == BundleVerification::Reconstructed {
            render::Source::Reconstructed
        } else {
            render::Source::LegacyHashOnly
        },
    )
}

fn replay_watch(source_path: PathBuf, cli: &Cli) -> Result<()> {
    replay(
        None,
        cli.paused,
        0,
        cli.surface.as_deref(),
        cli.speed,
        &cli.snapshot_dir,
        Some(WatchController::new(source_path, cli.trace_every)),
        0,
        cli.view,
        render::Source::LivePreview,
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

#[allow(clippy::too_many_lines, clippy::too_many_arguments)] // UI input, hot-reload state, and framebuffer presentation share one event loop
fn replay(
    mut bundle: Option<RunBundle>,
    mut paused: bool,
    mut surface_index: usize,
    initial_surface: Option<&str>,
    speed: f64,
    snapshot_dir: &Path,
    mut watch: Option<WatchController>,
    initial_frame: usize,
    initial_view: ViewMode,
    source: render::Source,
) -> Result<()> {
    let mut window = Window::new(
        "Chiyoda replay — loading",
        WIDTH,
        HEIGHT,
        WindowOptions::default(),
    )?;
    window.set_target_fps(30);
    let mut buffer = vec![BACKGROUND; WIDTH * HEIGHT];
    let mut index = initial_frame;
    let mut last_advance = Instant::now();
    let mut view_mode = initial_view;
    let mut snapshot_number = 0_u64;
    let mut details_open = true;
    let mut renderer = render::Renderer::new();

    while window.is_open() && !window.is_key_down(Key::Escape) {
        if let Some(controller) = &mut watch
            && let Some(update) = controller.tick()
        {
            match update.result {
                Ok(next_bundle) => {
                    if let Err(error) = validate_playback_timing(&next_bundle.trace, speed) {
                        let message = error.to_string();
                        controller.mark_failed(update.revision, &message);
                        eprintln!("watch revision {} failed: {message}", update.revision);
                    } else {
                        let previously_selected_surface = bundle.as_ref().and_then(|current| {
                            current
                                .scenario
                                .scenario
                                .surfaces
                                .get(surface_index)
                                .map(|surface| surface.id.as_str())
                        });
                        match select_reloaded_surface(
                            &next_bundle.scenario.scenario.surfaces,
                            previously_selected_surface,
                            initial_surface,
                        ) {
                            Ok(next_surface_index) => {
                                surface_index = next_surface_index;
                                bundle = Some(next_bundle);
                                index = 0;
                                last_advance = Instant::now();
                                controller.mark_loaded(update.revision);
                                eprintln!("watch revision {} loaded", update.revision);
                            }
                            Err(error) => {
                                let message = format!("view configuration error: {error}");
                                controller.mark_failed(update.revision, &message);
                                eprintln!("watch revision {} failed: {message}", update.revision);
                            }
                        }
                    }
                }
                Err(message) => {
                    controller.mark_failed(update.revision, &message);
                    eprintln!("watch revision {} failed: {message}", update.revision);
                }
            }
        }

        if window.is_key_pressed(Key::Space, KeyRepeat::No) {
            paused = !paused;
            last_advance = Instant::now();
        }
        let mut manually_advanced = false;
        if let Some(current_bundle) = &bundle {
            if window.is_key_pressed(Key::Right, KeyRepeat::Yes) {
                index = (index + 1).min(current_bundle.trace.len() - 1);
                manually_advanced = true;
            }
            if window.is_key_pressed(Key::Left, KeyRepeat::Yes) {
                index = index.saturating_sub(1);
                manually_advanced = true;
            }
            if window.is_key_pressed(Key::Tab, KeyRepeat::No) {
                surface_index =
                    (surface_index + 1) % current_bundle.scenario.scenario.surfaces.len();
            }
        }
        if window.is_key_pressed(Key::V, KeyRepeat::No) {
            view_mode = view_mode.toggle();
        }
        if window.is_key_pressed(Key::D, KeyRepeat::No) {
            details_open = !details_open;
        }
        if manually_advanced {
            last_advance = Instant::now();
        }
        if let Some(current_bundle) = &bundle
            && !paused
            && index < current_bundle.trace.len() - 1
            && last_advance.elapsed() >= frame_delay(&current_bundle.trace, index, speed)
        {
            index += 1;
            last_advance = Instant::now();
        }
        let save_snapshot = window.is_key_pressed(Key::P, KeyRepeat::No);
        renderer.render(
            &mut buffer,
            bundle.as_ref(),
            render::Options {
                index,
                surface: surface_index,
                view: view_mode,
                source,
                playback: if paused {
                    render::Playback::Paused
                } else {
                    render::Playback::Playing
                },
                speed,
                details: details_open,
                watch_status: watch.as_ref().map(WatchController::status),
            },
        );
        window.set_title(&window_title(
            bundle.as_ref(),
            surface_index,
            index,
            speed,
            view_mode,
            watch.as_ref().map(WatchController::status),
        ));
        if save_snapshot {
            let step = bundle
                .as_ref()
                .and_then(|current| current.trace.get(index))
                .map_or(0, |frame| frame.step);
            match next_snapshot_path(snapshot_dir, &mut snapshot_number, step) {
                Ok(path) => match write_png(&path, &buffer, WIDTH, HEIGHT) {
                    Ok(()) => eprintln!("wrote PNG snapshot: {}", path.display()),
                    Err(error) => {
                        eprintln!("could not write PNG snapshot {}: {error}", path.display());
                    }
                },
                Err(error) => eprintln!("could not allocate a PNG snapshot path: {error}"),
            }
        }
        window.update_with_buffer(&buffer, WIDTH, HEIGHT)?;
        if paused {
            thread::sleep(Duration::from_millis(10));
        }
    }
    Ok(())
}

fn render_bundle_frame(
    buffer: &mut [u32],
    bundle: &RunBundle,
    index: usize,
    surface_index: usize,
    view_mode: ViewMode,
) {
    render::Renderer::new().render(
        buffer,
        Some(bundle),
        render::Options {
            index,
            surface: surface_index,
            view: view_mode,
            ..render::Options::default()
        },
    );
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum, Serialize)]
#[serde(rename_all = "snake_case")]
enum ViewMode {
    Surface,
    Overview,
}

impl ViewMode {
    fn toggle(self) -> Self {
        match self {
            Self::Surface => Self::Overview,
            Self::Overview => Self::Surface,
        }
    }

    const fn label(self) -> &'static str {
        match self {
            Self::Surface => "surface",
            Self::Overview => "overview",
        }
    }
}

fn window_title(
    bundle: Option<&RunBundle>,
    surface_index: usize,
    index: usize,
    speed: f64,
    view_mode: ViewMode,
    watch_status: Option<&str>,
) -> String {
    let controls =
        "space: pause, arrows: step, tab: surface, V: view, D: details, P: PNG, escape: quit";
    let status = watch_status.unwrap_or("recorded bundle replay");
    match bundle {
        Some(bundle) => {
            let surface = &bundle.scenario.scenario.surfaces[surface_index];
            format!(
                "Chiyoda {} — {} — {:.3}s — {speed:.2}× — {status} — {controls}",
                view_mode.label(),
                surface.id,
                bundle.trace[index].time_s,
            )
        }
        None => format!("Chiyoda watch — no valid scene — {status} — {controls}"),
    }
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
            let _ = sender.send(WatchUpdate { revision, result });
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

fn load_watch_scenario(
    source: &str,
    trace_every_steps: u32,
) -> std::result::Result<RunBundle, String> {
    let scenario = decode_scenario(source).map_err(|error| format!("JSON input error: {error}"))?;
    validate(&scenario).map_err(|errors| {
        let details = errors
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("; ");
        format!("validation error: {details}")
    })?;
    run(&scenario, RunOptions { trace_every_steps })
        .map_err(|error| format!("runtime error: {error}"))
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

fn watch_status_is_error(status: &str) -> bool {
    status.starts_with("cannot read") || status.contains(" failed:")
}

fn validate_playback_timing(trace: &[TraceFrame], speed: f64) -> Result<()> {
    if !speed.is_finite() || speed <= 0.0 {
        bail!("--speed must be a finite value greater than zero");
    }
    for (index, frame) in trace.iter().enumerate() {
        if !frame.time_s.is_finite() {
            bail!("trace frame {index} has a non-finite timestamp");
        }
        if index > 0 && frame.time_s < trace[index - 1].time_s {
            bail!("trace frame {index} precedes the prior frame in simulation time");
        }
        if index > 0 {
            let delay_s = (frame.time_s - trace[index - 1].time_s) / speed;
            if !delay_s.is_finite() || delay_s > Duration::MAX.as_secs_f64() {
                bail!("trace timestamps and --speed produce an unrepresentable playback delay");
            }
        }
    }
    Ok(())
}

fn frame_delay(trace: &[TraceFrame], index: usize, speed: f64) -> Duration {
    Duration::from_secs_f64((trace[index + 1].time_s - trace[index].time_s) / speed)
}

#[derive(Debug, Serialize)]
struct GifExportManifest {
    schema_version: String,
    format: String,
    bundle_sha256: String,
    scenario_sha256: String,
    runtime_version: String,
    bundle_version: String,
    trace_every_steps: u32,
    trace_frame_count: usize,
    rendered_surface: String,
    view: ViewMode,
    render_width_pixels: usize,
    render_height_pixels: usize,
    playback_speed: f64,
    frame_delays_centiseconds: Vec<u16>,
    looping: bool,
    timing_policy: String,
    renderer: render::Provenance,
    claim_boundary: String,
}

/// Export a review-only animation from the exact trace stored in a verified
/// bundle. The export contains no re-simulation or source recompilation.
fn export_gif(
    bundle: &RunBundle,
    output: &Path,
    surface_index: usize,
    playback_speed: f64,
    view: ViewMode,
) -> Result<GifExportManifest> {
    validate_playback_timing(&bundle.trace, playback_speed)?;
    let trace_every_steps = bundle
        .options
        .get("trace_every_steps")
        .context("verified bundle omits trace_every_steps")?
        .parse::<u32>()
        .context("verified bundle has an invalid trace_every_steps")?;
    if trace_every_steps == 0 {
        bail!("verified bundle has a zero trace_every_steps");
    }
    let frame_delays_centiseconds = gif_frame_delays_centiseconds(&bundle.trace, playback_speed)?;
    let manifest = GifExportManifest {
        schema_version: "chiyoda.replay-gif.v2".to_owned(),
        format: "GIF89a".to_owned(),
        bundle_sha256: bundle.bundle_hash.clone(),
        scenario_sha256: bundle.scenario_hash.clone(),
        runtime_version: bundle.runtime_version.clone(),
        bundle_version: bundle.bundle_version.clone(),
        trace_every_steps,
        trace_frame_count: bundle.trace.len(),
        rendered_surface: bundle.scenario.scenario.surfaces[surface_index].id.clone(),
        view,
        render_width_pixels: WIDTH,
        render_height_pixels: HEIGHT,
        playback_speed,
        frame_delays_centiseconds,
        looping: true,
        renderer: render::provenance(),
        timing_policy: "Each non-terminal GIF delay is the next recorded trace timestamp gap divided by playback_speed, rounded to the nearest centisecond. A zero-length rounded gap and the terminal frame use one centisecond because GIF cannot encode a terminal hold without a delay. Exact trace timestamps remain in the verified canonical JSON bundle.".to_owned(),
        claim_boundary: "This derived visualization renders recorded state from one reconstructed deterministic trace. The selected surface applies to the floor plan; overview projects all authored surfaces. The canonical JSON bundle remains the source of timing and metrics.".to_owned(),
    };
    let sidecar = gif_manifest_path(output);
    ensure_new_export_targets(output, &sidecar)?;

    if let Err(error) = write_gif(
        bundle,
        surface_index,
        &manifest.frame_delays_centiseconds,
        output,
        view,
        playback_speed,
    ) {
        let _ = fs::remove_file(output);
        return Err(error);
    }
    if let Err(error) = write_gif_manifest(&sidecar, &manifest) {
        let _ = fs::remove_file(output);
        return Err(error);
    }
    Ok(manifest)
}

fn gif_manifest_path(output: &Path) -> PathBuf {
    let mut sidecar = output.as_os_str().to_os_string();
    sidecar.push(".json");
    PathBuf::from(sidecar)
}

#[derive(Debug, Serialize)]
struct PngExportManifest<'a> {
    schema_version: &'static str,
    renderer: render::Provenance,
    bundle_sha256: &'a str,
    scenario_sha256: &'a str,
    runtime_version: &'a str,
    bundle_version: &'a str,
    rendered_surface: &'a str,
    view: ViewMode,
    recorded_step: u64,
    recorded_time_s: f64,
    render_width_pixels: usize,
    render_height_pixels: usize,
    claim_boundary: &'static str,
}

fn export_png(
    bundle: &RunBundle,
    output: &Path,
    surface_index: usize,
    index: usize,
    view: ViewMode,
) -> Result<()> {
    let sidecar = gif_manifest_path(output);
    ensure_new_export_targets(output, &sidecar)?;
    let frame = &bundle.trace[index];
    let manifest = PngExportManifest {
        schema_version: "chiyoda.replay-png.v1",
        renderer: render::provenance(),
        bundle_sha256: &bundle.bundle_hash,
        scenario_sha256: &bundle.scenario_hash,
        runtime_version: &bundle.runtime_version,
        bundle_version: &bundle.bundle_version,
        rendered_surface: &bundle.scenario.scenario.surfaces[surface_index].id,
        view,
        recorded_step: frame.step,
        recorded_time_s: frame.time_s,
        render_width_pixels: WIDTH,
        render_height_pixels: HEIGHT,
        claim_boundary: "One exact recorded frame from a reconstructed deterministic trace. This derived image does not add measurements or interpolate movement. The canonical JSON bundle remains the source of timing and metrics.",
    };
    let mut buffer = vec![BACKGROUND; WIDTH * HEIGHT];
    render_bundle_frame(&mut buffer, bundle, index, surface_index, view);
    write_png(output, &buffer, WIDTH, HEIGHT)?;
    let result = (|| -> Result<()> {
        let file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&sidecar)?;
        serde_json::to_writer_pretty(file, &manifest)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(output);
    }
    result
}

fn ensure_new_export_targets(output: &Path, sidecar: &Path) -> Result<()> {
    if output == sidecar {
        bail!("image output and provenance sidecar must be distinct paths");
    }
    if output.exists() {
        bail!("refusing to overwrite image export {}", output.display());
    }
    if sidecar.exists() {
        bail!(
            "refusing to overwrite image provenance sidecar {}",
            sidecar.display()
        );
    }
    if let Some(parent) = output
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        fs::create_dir_all(parent)
            .with_context(|| format!("creating image export directory {}", parent.display()))?;
    }
    Ok(())
}

fn write_gif(
    bundle: &RunBundle,
    surface_index: usize,
    frame_delays_centiseconds: &[u16],
    output: &Path,
    view: ViewMode,
    playback_speed: f64,
) -> Result<()> {
    if bundle.trace.len() != frame_delays_centiseconds.len() {
        bail!(
            "GIF export has {} trace frames but {} timing entries",
            bundle.trace.len(),
            frame_delays_centiseconds.len()
        );
    }
    let width = u16::try_from(WIDTH).context("GIF width does not fit in u16")?;
    let height = u16::try_from(HEIGHT).context("GIF height does not fit in u16")?;
    let file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(output)
        .with_context(|| format!("creating GIF export {}", output.display()))?;
    let mut encoder =
        GifEncoder::new(file, width, height, &[]).context("creating GIF89a encoder")?;
    encoder
        .set_repeat(Repeat::Infinite)
        .context("setting GIF loop policy")?;
    let mut buffer = vec![BACKGROUND; WIDTH * HEIGHT];
    let mut renderer = render::Renderer::new();
    for (index, delay) in frame_delays_centiseconds.iter().copied().enumerate() {
        renderer.render(
            &mut buffer,
            Some(bundle),
            render::Options {
                index,
                surface: surface_index,
                view,
                speed: playback_speed,
                ..render::Options::default()
            },
        );
        let rgb: Vec<u8> = buffer
            .iter()
            .flat_map(|color| {
                let [_, r, g, b] = color.to_be_bytes();
                [r, g, b]
            })
            .collect();
        let mut frame = GifFrame::from_rgb_speed(width, height, &rgb, 10);
        frame.delay = delay;
        encoder
            .write_frame(&frame)
            .with_context(|| format!("encoding GIF frame {index}"))?;
    }

    Ok(())
}

fn gif_frame_delays_centiseconds(trace: &[TraceFrame], playback_speed: f64) -> Result<Vec<u16>> {
    validate_playback_timing(trace, playback_speed)?;
    trace
        .iter()
        .enumerate()
        .map(|(index, frame)| {
            let delay_s = trace
                .get(index + 1)
                .map_or(0.01, |next| (next.time_s - frame.time_s) / playback_speed);
            let rounded_centiseconds = (delay_s * 100.0).round();
            if !rounded_centiseconds.is_finite()
                || rounded_centiseconds > f64::from(u16::MAX)
            {
                bail!(
                    "trace frame {index} produces an unrepresentable GIF delay at --gif-speed {playback_speed}"
                );
            }
            #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
            let rounded_centiseconds = rounded_centiseconds.max(1.0) as u16;
            Ok(rounded_centiseconds)
        })
        .collect()
}

fn write_gif_manifest(path: &Path, manifest: &GifExportManifest) -> Result<()> {
    let file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .with_context(|| format!("creating GIF provenance sidecar {}", path.display()))?;
    serde_json::to_writer_pretty(&file, manifest)
        .with_context(|| format!("writing GIF provenance sidecar {}", path.display()))?;
    writeln!(&file)
        .with_context(|| format!("finalizing GIF provenance sidecar {}", path.display()))?;
    Ok(())
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

fn write_png(path: &Path, buffer: &[u32], width: usize, height: usize) -> Result<()> {
    if buffer.len() != width.saturating_mul(height) {
        bail!(
            "framebuffer contains {} pixels but {width}×{height} pixels were expected",
            buffer.len()
        );
    }
    let scanline_bytes = width
        .checked_mul(3)
        .context("PNG row byte count overflowed")?;
    let raw_capacity = scanline_bytes
        .checked_add(1)
        .and_then(|row| row.checked_mul(height))
        .context("PNG image byte count overflowed")?;
    let mut raw = Vec::with_capacity(raw_capacity);
    for row in buffer.chunks_exact(width) {
        raw.push(0);
        for pixel in row {
            let bytes = pixel.to_be_bytes();
            raw.extend_from_slice(&bytes[1..]);
        }
    }
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(&raw)?;
    let compressed = encoder.finish()?;
    let width = u32::try_from(width).context("PNG width does not fit in u32")?;
    let height = u32::try_from(height).context("PNG height does not fit in u32")?;
    let mut header = Vec::with_capacity(13);
    header.extend(width.to_be_bytes());
    header.extend(height.to_be_bytes());
    header.extend([8, 2, 0, 0, 0]);
    if let Some(directory) = path.parent() {
        fs::create_dir_all(directory)
            .with_context(|| format!("creating snapshot directory {}", directory.display()))?;
    }
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .with_context(|| format!("creating PNG snapshot {}", path.display()))?;
    file.write_all(b"\x89PNG\r\n\x1a\n")?;
    write_png_chunk(&mut file, *b"IHDR", &header)?;
    write_png_chunk(&mut file, *b"IDAT", &compressed)?;
    write_png_chunk(&mut file, *b"IEND", &[])?;
    Ok(())
}

fn write_png_chunk(file: &mut fs::File, kind: [u8; 4], data: &[u8]) -> Result<()> {
    let length = u32::try_from(data.len()).context("PNG chunk exceeds u32 length")?;
    file.write_all(&length.to_be_bytes())?;
    file.write_all(&kind)?;
    file.write_all(data)?;
    let mut crc_data = Vec::with_capacity(kind.len() + data.len());
    crc_data.extend(kind);
    crc_data.extend(data);
    file.write_all(&png_crc32(&crc_data).to_be_bytes())?;
    Ok(())
}

fn png_crc32(bytes: &[u8]) -> u32 {
    let mut crc = u32::MAX;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            crc = if crc & 1 == 1 {
                (crc >> 1) ^ 0xedb8_8320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

#[cfg(test)]
mod tests {
    use super::*;
    use chiyoda_core::model::Point3;
    use flate2::read::ZlibDecoder;
    use std::io::Read;
    const WATCH_SOURCE: &str = include_str!("fixtures/main/project.json");

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
            Cli::try_parse_from(["chiyoda-replay", "run.json", "--paused", "--at-step", "3"])
                .is_ok()
        );
        assert!(
            Cli::try_parse_from([
                "chiyoda-replay",
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
        let mut decoder = gif::DecodeOptions::new();
        decoder.set_color_output(gif::ColorOutput::RGBA);
        let mut reader = decoder.read_info(gif.as_slice()).unwrap();
        assert_eq!(usize::from(reader.width()), WIDTH);
        assert_eq!(usize::from(reader.height()), HEIGHT);
        let mut delays = Vec::new();
        while let Some(frame) = reader.read_next_frame().unwrap() {
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
        let cli = Cli::try_parse_from([
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
            assert!(Cli::try_parse_from(args).is_err());
        }
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
