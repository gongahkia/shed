use super::{PreparedInput, StudyConfig, StudyContext, StudyFailure};
use crate::{
    CanonicalScenario, RunBundle,
    bundle::canonical_hash,
    evaluation::{self, CaseReport, ExpectedReachability},
    generator::spaces::{
        self, GeneratedSpace, GenerationAttempt, GenerationManifest, SpaceGenerationError,
    },
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CaseKey {
    pub pattern: String,
    pub scale: String,
    pub seed: u64,
    pub load: String,
    pub policy: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StudyCaseResult {
    pub key: CaseKey,
    pub passed: bool,
    pub setup_failure: Option<StudyFailure>,
    pub context: Option<StudyContext>,
    pub evaluation: Option<CaseReport>,
    pub generation_attempts: Vec<GenerationAttempt>,
    pub tile_counts: BTreeMap<String, u32>,
    /// Physical geometry only: dimensions, obstacle rectangles, and resource
    /// locations. Excludes seeds, population, service limits, and policies.
    pub layout_hash: Option<String>,
    pub input_sha256: Option<String>,
    pub generation_hash: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub journeys: Option<crate::journeys::JourneySummary>,
}

impl StudyCaseResult {
    /// Failed thresholds remain measurable outcomes. Invalid trajectories,
    /// runtime errors, and reconstruction failures are excluded from summaries.
    #[must_use]
    pub fn observations(&self) -> BTreeMap<String, f64> {
        let mut values = BTreeMap::new();
        let (Some(context), Some(evaluation)) = (&self.context, &self.evaluation) else {
            return values;
        };
        let allowed = [
            "min_evacuated_fraction",
            "max_clearance_time_s",
            "max_queue_wait_agent_seconds",
            "max_avoidance_fallback_steps",
            "max_disc_overlap_m",
            "max_swept_disc_overlap_m",
        ];
        if evaluation
            .failures
            .iter()
            .any(|failure| !allowed.contains(&failure.check.as_str()))
        {
            return values;
        }
        let Some(metrics) = &evaluation.metrics else {
            return values;
        };
        let count = f64::from(metrics.total_agents);
        if count <= 0.0
            || metrics.total_agents != context.total_people
            || metrics.evacuated_agents > metrics.total_agents
        {
            return values;
        }
        values.insert(
            "evacuated_fraction".into(),
            f64::from(metrics.evacuated_agents) / count,
        );
        values.insert("people".into(), count);
        let gross = context
            .floors
            .iter()
            .map(|floor| floor.gross_area_m2)
            .sum::<f64>();
        let walkable = context
            .floors
            .iter()
            .map(|floor| floor.walkable_area_m2)
            .sum::<f64>();
        values.insert("gross_area_m2".into(), gross);
        values.insert("walkable_area_m2".into(), walkable);
        values.insert("gross_density_per_m2".into(), count / gross);
        values.insert("walkable_density_per_m2".into(), count / walkable);
        values.insert(
            "mean_direct_exit_distance_m".into(),
            context.mean_direct_exit_distance_m,
        );
        values.insert("mean_release_time_s".into(), context.mean_release_time_s);
        if let Some(time) = metrics.clearance_time_s {
            values.insert("clearance_time_s".into(), time);
        }
        if let Some(time) = metrics.mean_exit_time_s {
            values.insert("mean_exit_time_s".into(), time);
        }
        if let Some(queue) = &metrics.queue_metrics {
            values.insert(
                "queue_wait_per_person_s".into(),
                (queue.lift.cumulative_wait_agent_seconds
                    + queue.connector.cumulative_wait_agent_seconds
                    + queue.gate.cumulative_wait_agent_seconds
                    + queue.exit.cumulative_wait_agent_seconds)
                    / count,
            );
        }
        information_observations(metrics, &mut values);
        if let Some(journeys) = &self.journeys {
            values.extend(journeys.observations());
        }
        if let Some(movement) = &metrics.movement_metrics {
            if let Some(audit) = &movement.on_surface_clearance_audit {
                values.insert("max_disc_overlap_m".into(), audit.maximum_disc_overlap_m);
            }
            if let Some(audit) = &movement.swept_on_surface_clearance_audit {
                values.insert(
                    "max_swept_disc_overlap_m".into(),
                    audit.maximum_swept_disc_overlap_m,
                );
            }
        }
        if values
            .values()
            .any(|value| !value.is_finite() || *value < 0.0)
        {
            return BTreeMap::new();
        }
        values
    }
}

#[derive(Debug)]
pub struct StudyCaseArtifacts {
    pub result: StudyCaseResult,
    pub input_json: Option<String>,
    pub bundle: Option<RunBundle>,
    /// Original generation manifest, describing geometry with a placeholder
    /// population. The actual evaluated input is `input_json` and its context.
    pub generation: Option<GenerationManifest>,
    pub generated_json: Option<String>,
    pub journeys: Option<crate::journeys::JourneyAnalysis>,
}

/// Streams one case at a time, caching exactly one layout and one population.
/// Geometry is searched once per pattern/scale/seed, including on failure.
pub struct StudyRunner {
    config: StudyConfig,
    coordinates: Vec<[usize; 5]>,
    next: usize,
    layout: Option<([usize; 3], Result<GeneratedSpace, StudyFailure>)>,
    population: Option<([usize; 4], Result<PreparedInput, StudyFailure>)>,
}

impl StudyConfig {
    pub(super) fn coordinates(&self) -> Vec<[usize; 5]> {
        let mut keys = Vec::new();
        for pattern in 0..self.patterns.len() {
            for scale in 0..self.scales.len() {
                for seed in 0..self.seeds.len() {
                    for load in 0..self.loads.len() {
                        for policy in 0..self.policies.len() {
                            keys.push([pattern, scale, seed, load, policy]);
                        }
                    }
                }
            }
        }
        keys
    }

    pub(super) fn key(&self, [pattern, scale, seed, load, policy]: [usize; 5]) -> CaseKey {
        CaseKey {
            pattern: self.patterns[pattern].id.clone(),
            scale: self.scales[scale].id.clone(),
            seed: self.seeds[seed],
            load: self.loads[load].id.clone(),
            policy: self.policies[policy].id.clone(),
        }
    }
}

impl StudyRunner {
    pub fn new(config: StudyConfig) -> Result<Self, String> {
        config.validate()?;
        Ok(Self {
            coordinates: config.coordinates(),
            config,
            next: 0,
            layout: None,
            population: None,
        })
    }

    fn prepare(&mut self, [pattern, scale, seed, load, _]: [usize; 5]) {
        let layout_key = [pattern, scale, seed];
        if self
            .layout
            .as_ref()
            .is_none_or(|(key, _)| *key != layout_key)
        {
            let generation = self
                .config
                .generation(&self.config.patterns[pattern], &self.config.scales[scale]);
            let result = spaces::generate(self.config.seeds[seed], &generation).map_err(|error| {
                let detail = error.to_string();
                let mut failure = StudyFailure::new("generation", detail);
                if let SpaceGenerationError::Exhausted { attempts } = error {
                    failure.generation_attempts = attempts;
                }
                failure
            });
            self.layout = Some((layout_key, result));
            self.population = None;
        }
        let population_key = [pattern, scale, seed, load];
        if self
            .population
            .as_ref()
            .is_none_or(|(key, _)| *key != population_key)
        {
            let result = match &self.layout.as_ref().expect("prepared layout").1 {
                Ok(space) => PreparedInput::from_space(
                    &self.config,
                    space,
                    &self.config.scales[scale],
                    &self.config.loads[load],
                ),
                Err(failure) => Err(failure.clone()),
            };
            self.population = Some((population_key, result));
        }
    }

    fn evaluate(&self, coordinate: [usize; 5]) -> StudyCaseArtifacts {
        let key = self.config.key(coordinate);
        let space = self
            .layout
            .as_ref()
            .expect("prepared layout")
            .1
            .as_ref()
            .ok();
        let mut artifacts = StudyCaseArtifacts {
            result: StudyCaseResult {
                key,
                passed: false,
                setup_failure: None,
                context: None,
                evaluation: None,
                generation_attempts: space
                    .map_or_else(Vec::new, |space| space.manifest.attempts.clone()),
                tile_counts: space
                    .map_or_else(BTreeMap::new, |space| space.manifest.tile_counts.clone()),
                layout_hash: space.map(|space| geometry_hash(&space.scenario)),
                input_sha256: None,
                generation_hash: space.map(|space| canonical_hash(&space.manifest)),
                journeys: None,
            },
            input_json: None,
            bundle: None,
            generation: space.map(|space| space.manifest.clone()),
            generated_json: space.map(|space| space.json.clone()),
            journeys: None,
        };
        let input = match &self.population.as_ref().expect("prepared population").1 {
            Ok(input) => input,
            Err(failure) => {
                if space.is_none() {
                    artifacts
                        .result
                        .generation_attempts
                        .clone_from(&failure.generation_attempts);
                }
                artifacts.result.setup_failure = Some(failure.clone());
                return artifacts;
            }
        };
        artifacts.result.context = Some(input.context.clone());
        let policy = &self.config.policies[coordinate[4]];
        let scenario = input.with_policy(policy);
        let json = serde_json::to_string_pretty(&CanonicalScenario::from(scenario))
            .expect("validated finite study config");
        let mut evaluation = evaluation::evaluate_json(
            &case_name(coordinate),
            &json,
            ExpectedReachability::Reachable,
            &self.config.evaluation,
        );
        if let Some(bundle) = &evaluation.bundle {
            match crate::journeys::analyze_with_controls(
                bundle,
                &self.config.journey_diagnostics.clone().unwrap_or_default(),
            ) {
                Ok(analysis) => {
                    artifacts.result.journeys = Some(analysis.report.summary());
                    artifacts.journeys = Some(analysis);
                }
                Err(detail) => {
                    evaluation.report.failures.push(evaluation::CheckFailure {
                        check: "journey_diagnostics".into(),
                        detail,
                    });
                    evaluation.report.passed = false;
                }
            }
        }
        artifacts.result.passed = evaluation.report.passed;
        artifacts.result.evaluation = Some(evaluation.report);
        let input_json = json + "\n";
        artifacts.result.input_sha256 =
            Some(format!("{:x}", Sha256::digest(input_json.as_bytes())));
        artifacts.input_json = Some(input_json);
        artifacts.bundle = evaluation.bundle;
        artifacts
    }
}

impl Iterator for StudyRunner {
    type Item = StudyCaseArtifacts;
    fn next(&mut self) -> Option<Self::Item> {
        let coordinate = *self.coordinates.get(self.next)?;
        self.next += 1;
        self.prepare(coordinate);
        Some(self.evaluate(coordinate))
    }
    fn size_hint(&self) -> (usize, Option<usize>) {
        let left = self.coordinates.len() - self.next;
        (left, Some(left))
    }
}

impl ExactSizeIterator for StudyRunner {}

pub(super) fn case_name([pattern, scale, seed, load, policy]: [usize; 5]) -> String {
    format!("p{pattern}-s{scale}-r{seed}-l{load}-c{policy}")
}

fn geometry_hash(scenario: &crate::Scenario) -> String {
    canonical_hash(&(
        &scenario.surfaces,
        &scenario.obstacles,
        scenario
            .exits
            .iter()
            .map(|exit| (&exit.id, &exit.surface, exit.at, exit.width_m))
            .collect::<Vec<_>>(),
        scenario
            .gates
            .iter()
            .map(|gate| {
                (
                    &gate.id,
                    &gate.surface,
                    gate.at,
                    gate.width_m,
                    &gate.destination,
                )
            })
            .collect::<Vec<_>>(),
        scenario
            .connectors
            .iter()
            .map(|connector| {
                (
                    connector.id(),
                    connector.kind(),
                    connector.from_surface(),
                    connector.from(),
                    connector.to_surface(),
                    connector.to(),
                )
            })
            .collect::<Vec<_>>(),
    ))
}

fn information_observations(
    metrics: &crate::bundle::RunMetrics,
    values: &mut BTreeMap<String, f64>,
) {
    values.insert(
        "information_received_deliveries".into(),
        metrics
            .information_delivery
            .values()
            .map(|delivery| f64::from(delivery.received_agents))
            .sum(),
    );
    values.insert(
        "information_accepted_deliveries".into(),
        metrics
            .information_delivery
            .values()
            .map(|delivery| f64::from(delivery.accepted_agents))
            .sum(),
    );
}
