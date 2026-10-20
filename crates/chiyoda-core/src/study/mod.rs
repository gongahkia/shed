//! Controlled studies of architectural patterns, scale, demand, and information.
mod config;
mod policy;
mod prepare;
pub mod rendering;
mod report;
mod runner;

pub use config::*;
pub use policy::{OriginAnchor, PolicyCountermeasure, PolicyMessage, PolicyOrigin};
pub use prepare::{FloorPopulation, PreparedInput, StudyContext, StudyFailure};
pub use report::{Distribution, GroupKey, GroupSummary, StudyComparison, StudyReport};
pub use runner::{CaseKey, StudyCaseArtifacts, StudyCaseResult, StudyRunner};

pub const STUDY_VERSION: &str = "1";
pub const EXAMPLE_CONFIG_JSON: &str = include_str!("example.json");
