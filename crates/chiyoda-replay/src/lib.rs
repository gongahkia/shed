//! Shared replay rendering and recorded-evidence access for native and agent clients.
pub mod comparison;
pub mod evidence;
pub mod export;
pub mod inspection;
pub mod navigation;
pub mod render;
use chiyoda_core::{RunBundle, RunOptions, decode_scenario, run, validate};
use clap::ValueEnum;
use serde::Serialize;
pub const WIDTH: usize = 1200;
pub const HEIGHT: usize = 800;
pub fn render_bundle_frame(
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

#[derive(
    Debug,
    Default,
    Clone,
    Copy,
    PartialEq,
    Eq,
    ValueEnum,
    Serialize,
    serde::Deserialize,
    schemars::JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum ViewMode {
    #[default]
    Surface,
    Overview,
}

impl ViewMode {
    #[must_use]
    pub fn toggle(self) -> Self {
        match self {
            Self::Surface => Self::Overview,
            Self::Overview => Self::Surface,
        }
    }

    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Surface => "surface",
            Self::Overview => "overview",
        }
    }
}

pub fn load_watch_scenario(
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

#[must_use]
pub fn watch_status_is_error(status: &str) -> bool {
    status.starts_with("cannot read") || status.contains(" failed:")
}
