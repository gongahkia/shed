use super::{CaseReport, EVALUATION_VERSION, SuiteConfig};
use crate::{
    bundle::{RunMetrics, canonical_hash},
    generator::spaces::{GenerationManifest, ModuleKind, RejectionKind},
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Coverage {
    pub tiles: BTreeMap<String, u64>,
    pub module_kinds: BTreeMap<String, u64>,
    pub passage_shapes: BTreeMap<String, u64>,
    pub connector_kinds: BTreeMap<String, u64>,
    pub cases_with_queues: BTreeMap<String, u64>,
}

impl Coverage {
    pub(super) fn add_layout(&mut self, manifest: &GenerationManifest) {
        for module in &manifest.catalog {
            let count = u64::from(manifest.tile_counts[&module.id]);
            *self.tiles.entry(module.id.clone()).or_default() += count;
            let kind = match module.kind {
                ModuleKind::Solid => "solid",
                ModuleKind::Corridor => "corridor",
                ModuleKind::Hall => "hall",
                ModuleKind::Kiosk => "kiosk",
            };
            *self.module_kinds.entry(kind.into()).or_default() += count;
            let shape = match module.openings.iter().filter(|open| **open).count() {
                0 => "sealed",
                1 => "dead_end",
                2 if module.openings[0] == module.openings[2] => "straight",
                2 => "corner",
                3 => "junction",
                _ => "cross",
            };
            *self.passage_shapes.entry(shape.into()).or_default() += count;
        }
    }

    pub(super) fn add_metrics(&mut self, metrics: &RunMetrics) {
        for (kind, queued) in [
            ("lift", metrics.queued_for_lift_agents),
            ("connector", metrics.queued_for_connector_agents),
            ("gate", metrics.queued_for_gate_agents),
            ("exit", metrics.queued_for_exit_agents),
        ] {
            *self.cases_with_queues.entry(kind.into()).or_default() += u64::from(queued > 0);
        }
    }

    fn merge(&mut self, other: &Self) {
        for (target, source) in [
            (&mut self.tiles, &other.tiles),
            (&mut self.module_kinds, &other.module_kinds),
            (&mut self.passage_shapes, &other.passage_shapes),
            (&mut self.connector_kinds, &other.connector_kinds),
            (&mut self.cases_with_queues, &other.cases_with_queues),
        ] {
            for (key, value) in source {
                *target.entry(key.clone()).or_default() += value;
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SuiteReport {
    pub evaluation_version: String,
    pub runtime_version: String,
    pub config_hash: String,
    pub config: SuiteConfig,
    pub passed: u32,
    pub failed: u32,
    pub total_generation_attempts: u32,
    pub rejected_generation_attempts: u32,
    pub rejection_counts: BTreeMap<String, u32>,
    pub unique_layouts: u32,
    pub coverage: Coverage,
    pub cases: Vec<CaseReport>,
}

impl SuiteReport {
    /// A report must account for every requested case, in input order. Failed
    /// generation attempts and expected-unreachable fixtures remain visible.
    pub fn from_cases(config: SuiteConfig, cases: Vec<CaseReport>) -> Result<Self, String> {
        config.validate()?;
        let expected: Vec<_> = config
            .seeds
            .iter()
            .map(|seed| format!("seed-{seed}"))
            .chain(config.fixtures.iter().map(|fixture| fixture.name.clone()))
            .collect();
        if expected
            != cases
                .iter()
                .map(|case| case.name.clone())
                .collect::<Vec<_>>()
        {
            return Err("suite report must include every requested case in input order".into());
        }
        let mut coverage = Coverage::default();
        let mut layouts = BTreeSet::new();
        let mut total_generation_attempts = 0;
        let mut rejected_generation_attempts = 0;
        let mut rejection_counts = BTreeMap::new();
        for case in &cases {
            coverage.merge(&case.coverage);
            if let Some(hash) = &case.layout_hash {
                layouts.insert(hash);
            }
            for attempt in &case.generation_attempts {
                total_generation_attempts += 1;
                if let Some(rejection) = &attempt.rejection {
                    rejected_generation_attempts += 1;
                    let reason = match rejection.kind {
                        RejectionKind::Contradiction => "contradiction",
                        RejectionKind::SearchLimit => "search_limit",
                        RejectionKind::DisconnectedLayout => "disconnected_layout",
                        RejectionKind::InvalidGeometry => "invalid_geometry",
                        RejectionKind::UnreachableJourney => "unreachable_journey",
                    };
                    *rejection_counts.entry(reason.into()).or_default() += 1;
                }
            }
        }
        let passed = u32::try_from(cases.iter().filter(|case| case.passed).count())
            .expect("suite population is bounded");
        Ok(Self {
            evaluation_version: EVALUATION_VERSION.into(),
            runtime_version: crate::RUNTIME_VERSION.into(),
            config_hash: canonical_hash(&config),
            config,
            passed,
            failed: u32::try_from(cases.len()).expect("bounded case count") - passed,
            total_generation_attempts,
            rejected_generation_attempts,
            rejection_counts,
            unique_layouts: u32::try_from(layouts.len()).expect("bounded case count"),
            coverage,
            cases,
        })
    }
}
