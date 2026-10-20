//! Controlled studies of architectural patterns, scale, demand, and information.
mod config;
mod prepare;

pub use config::*;
pub use prepare::{FloorPopulation, PreparedInput, StudyContext, StudyFailure};

pub const STUDY_VERSION: &str = "1";
