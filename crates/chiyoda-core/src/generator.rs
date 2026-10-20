//! Deterministic, constraint-preserving scenario generation.

use crate::{Scenario, decode_scenario, encode_scenario, model::Connector, validate};

pub mod spaces;

/// Generate a small multi-level transit scenario from a stable seed.
/// This is a structural example, not a facility model.
pub fn scenario(seed: u64) -> Result<Scenario, GenerationError> {
    let mut random = SplitMix64::new(seed);
    let crowd = 80 + random.bounded(121);
    let stair_width_m = f64::from(18 + random.bounded(13)) / 10.0;
    let gate_rate = 12 + random.bounded(19);
    let street_closure_s = 35 + random.bounded(21);
    let mut scenario = decode_scenario(include_str!("generator/interchange.json"))?;
    scenario.name = format!("generated-interchange-{seed}");
    scenario.seed = seed;
    scenario.agents[0].count = crowd;
    if let Connector::Stair { width_m, .. } = &mut scenario.connectors[0] {
        *width_m = stair_width_m;
    }
    scenario.gates[0].service_rate_per_s = f64::from(gate_rate);
    scenario.exit_states[0].at_s = f64::from(street_closure_s);
    validate(&scenario).map_err(GenerationError::Invalid)?;
    Ok(scenario)
}

pub fn json(seed: u64) -> Result<String, GenerationError> {
    Ok(encode_scenario(&scenario(seed)?)?)
}

#[derive(Debug, thiserror::Error)]
pub enum GenerationError {
    #[error("generator JSON contract: {0}")]
    Json(#[from] crate::ScenarioJsonError),
    #[error("generator violated constraints: {0:?}")]
    Invalid(Vec<crate::ValidationError>),
}

#[derive(Debug, Clone, Copy)]
struct SplitMix64(u64);

impl SplitMix64 {
    const fn new(seed: u64) -> Self {
        Self(seed)
    }

    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut value = self.0;
        value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        value ^ (value >> 31)
    }

    fn bounded(&mut self, bound: u32) -> u32 {
        u32::try_from(self.next() % u64::from(bound))
            .expect("a modulo result is strictly below its u32 bound")
    }
}
