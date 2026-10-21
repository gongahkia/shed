//! Display-independent image exports and exact recorded-frame timing.
use crate::render::BACKGROUND;
use crate::{HEIGHT, ViewMode, WIDTH, render, render_bundle_frame};
use anyhow::{Context, Result, bail};
use chiyoda_core::{RunBundle, bundle::TraceFrame};
use flate2::{Compression, write::ZlibEncoder};
use gif::{Encoder as GifEncoder, Frame as GifFrame, Repeat};
use serde::Serialize;
use std::{
    collections::HashMap,
    fs,
    io::Write,
    path::{Path, PathBuf},
    time::Duration,
};

pub fn validate_playback_timing(trace: &[TraceFrame], speed: f64) -> Result<()> {
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

#[must_use]
pub fn frame_delay(trace: &[TraceFrame], index: usize, speed: f64) -> Duration {
    Duration::from_secs_f64((trace[index + 1].time_s - trace[index].time_s) / speed)
}

#[derive(Debug, Serialize)]
pub struct GifExportManifest {
    pub schema_version: String,
    pub format: String,
    pub bundle_sha256: String,
    pub scenario_sha256: String,
    pub runtime_version: String,
    pub bundle_version: String,
    pub trace_every_steps: u32,
    pub trace_frame_count: usize,
    pub selected_surface: String,
    pub rendered_surfaces: Vec<String>,
    pub view: ViewMode,
    pub render_width_pixels: usize,
    pub render_height_pixels: usize,
    pub playback_speed: f64,
    pub frame_delays_centiseconds: Vec<u16>,
    pub looping: bool,
    pub timing_policy: String,
    pub renderer: render::Provenance,
    pub claim_boundary: String,
}

/// Export a review-only animation from the exact trace stored in a verified
/// bundle. The export contains no re-simulation or source recompilation.
pub fn export_gif(
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
        selected_surface: bundle.scenario.scenario.surfaces[surface_index].id.clone(),
        rendered_surfaces: rendered_surface_ids(bundle, surface_index, view),
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

#[must_use]
pub fn gif_manifest_path(output: &Path) -> PathBuf {
    let mut sidecar = output.as_os_str().to_os_string();
    sidecar.push(".json");
    PathBuf::from(sidecar)
}

#[derive(Debug, Serialize)]
pub struct PngExportManifest<'a> {
    pub schema_version: &'static str,
    pub renderer: render::Provenance,
    pub bundle_sha256: &'a str,
    pub scenario_sha256: &'a str,
    pub runtime_version: &'a str,
    pub bundle_version: &'a str,
    pub selected_surface: &'a str,
    pub rendered_surfaces: Vec<String>,
    pub view: ViewMode,
    pub recorded_step: u64,
    pub recorded_time_s: f64,
    pub render_width_pixels: usize,
    pub render_height_pixels: usize,
    pub claim_boundary: &'static str,
}

pub fn export_png(
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
        selected_surface: &bundle.scenario.scenario.surfaces[surface_index].id,
        rendered_surfaces: rendered_surface_ids(bundle, surface_index, view),
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

pub fn ensure_new_export_targets(output: &Path, sidecar: &Path) -> Result<()> {
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

#[must_use]
pub fn rendered_surface_ids(bundle: &RunBundle, selected: usize, view: ViewMode) -> Vec<String> {
    bundle
        .scenario
        .scenario
        .surfaces
        .iter()
        .enumerate()
        .filter(|(index, _)| view == ViewMode::Overview || *index == selected)
        .map(|(_, surface)| surface.id.clone())
        .collect()
}

pub fn write_gif(
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
    let palette = render::gif_palette();
    let rgb_palette: Vec<u8> = palette
        .iter()
        .flat_map(|color| {
            let [_, r, g, b] = color.to_be_bytes();
            [r, g, b]
        })
        .collect();
    let mut encoder =
        GifEncoder::new(file, width, height, &rgb_palette).context("creating GIF89a encoder")?;
    encoder
        .set_repeat(Repeat::Infinite)
        .context("setting GIF loop policy")?;
    let mut buffer = vec![BACKGROUND; WIDTH * HEIGHT];
    let mut renderer = render::Renderer::new();
    let mut color_indices = HashMap::new();
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
        let pixels = palette_indices(&buffer, &palette, &mut color_indices);
        let mut frame = GifFrame::from_indexed_pixels(width, height, pixels, None);
        frame.delay = delay;
        encoder
            .write_frame(&frame)
            .with_context(|| format!("encoding GIF frame {index}"))?;
    }

    Ok(())
}

pub fn palette_indices<S: std::hash::BuildHasher>(
    buffer: &[u32],
    palette: &[u32],
    cache: &mut HashMap<u32, u8, S>,
) -> Vec<u8> {
    buffer
        .iter()
        .map(|color| {
            *cache.entry(*color).or_insert_with(|| {
                let index = palette
                    .iter()
                    .enumerate()
                    .min_by_key(|(_, candidate)| {
                        [16, 8, 0]
                            .into_iter()
                            .map(|shift| {
                                let distance = i64::from((**candidate >> shift) & 255)
                                    - i64::from((*color >> shift) & 255);
                                distance * distance
                            })
                            .sum::<i64>()
                    })
                    .expect("non-empty renderer palette")
                    .0;
                u8::try_from(index).expect("renderer palette has at most 256 entries")
            })
        })
        .collect()
}

pub fn gif_frame_delays_centiseconds(
    trace: &[TraceFrame],
    playback_speed: f64,
) -> Result<Vec<u16>> {
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

pub fn write_gif_manifest(path: &Path, manifest: &GifExportManifest) -> Result<()> {
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

pub fn write_png(path: &Path, buffer: &[u32], width: usize, height: usize) -> Result<()> {
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

pub fn write_png_chunk(file: &mut fs::File, kind: [u8; 4], data: &[u8]) -> Result<()> {
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

#[must_use]
pub fn png_crc32(bytes: &[u8]) -> u32 {
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
