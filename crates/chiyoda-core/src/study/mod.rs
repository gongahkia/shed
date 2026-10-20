//! Controlled studies of architectural patterns, scale, demand, and information.
mod config;
mod prepare;
mod runner;

pub use config::*;
pub use prepare::{FloorPopulation, PreparedInput, StudyContext, StudyFailure};
pub use runner::{CaseKey, StudyCaseArtifacts, StudyCaseResult, StudyRunner};

pub const STUDY_VERSION: &str = "1";
