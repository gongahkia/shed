use super::{
    API_VERSION, ToolError, ToolResult,
    files::{self, Workspace},
    query,
};
use chiyoda_core::{
    CanonicalScenario,
    experiment::{self, ExperimentOptions, ExperimentRequest, Variant},
    generator::spaces::{self, SpaceConfig},
};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
struct Empty {}

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
struct InspectInput {
    scenario: String,
}

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ExperimentInput {
    name: String,
    /// Existing versioned JSON scenario, relative to the configured workspace.
    scenario: String,
    variants: Vec<Variant>,
    #[serde(default)]
    options: ExperimentOptions,
}

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
struct RunInput {
    experiment: ExperimentInput,
    /// From `chiyoda_plan`. Prevents execution against changed inputs.
    expected_input_hash: String,
    /// New workspace-relative directory. Existing outputs are preserved.
    output: String,
}

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
struct VerifyInput {
    experiment: String,
    #[serde(default)]
    replay: bool,
}

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
struct GenerateInput {
    seed: u64,
    /// Existing `SpaceConfig` JSON; omission uses the concourse defaults.
    config: Option<String>,
    output: String,
}

#[derive(Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
struct SchemaInput {
    kind: SchemaKind,
}

#[derive(Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
enum SchemaKind {
    Scenario,
    Experiment,
    Campaign,
}

fn tool<T: JsonSchema>(name: &str, description: &str, read_only: bool) -> Value {
    json!({"name":name,"description":description,"inputSchema":schemars::schema_for!(T),
        "annotations":{"readOnlyHint":read_only,"destructiveHint":false,"idempotentHint":read_only,"openWorldHint":false}})
}

pub(super) fn catalog() -> Vec<Value> {
    vec![
        tool::<Empty>(
            "chiyoda_capabilities",
            "Discover versions, limits, evidence semantics and the recommended experiment loop.",
            true,
        ),
        tool::<SchemaInput>(
            "chiyoda_schema",
            "Get the generated JSON Schema for current scenarios, complete experiment requests or budgeted campaign configurations. Semantic validation remains separate.",
            true,
        ),
        tool::<InspectInput>(
            "chiyoda_inspect",
            "Inspect an existing scenario: authored objects and IDs, population, integration steps, validation issues and geometric reachability. Does not simulate.",
            true,
        ),
        tool::<GenerateInput>(
            "chiyoda_generate",
            "Generate a deterministic WFC space in a new directory, retaining source, generation provenance and rejected attempts. Use a SpaceConfig file from chiyoda space-rules for custom rules.",
            false,
        ),
        tool::<ExperimentInput>(
            "chiyoda_plan",
            "Plan independent variants of one baseline using ID-based changes. Returns exact changed fields, population controls, preflight issues, total budgets and the input hash needed to run. Does not simulate.",
            true,
        ),
        tool::<RunInput>(
            "chiyoda_run",
            "Run a planned bounded experiment into a new directory. Returns compact measurements and comparisons; preserves full scenarios, traces and journey evidence. Failed thresholds remain visible. Never selects an architectural winner.",
            false,
        ),
        tool::<query::QueryInput>(
            "chiyoda_query",
            "Read paginated experiment evidence: summary, changes, agents, comparisons, hotspots, resources, events or an exact trace step. Follow artifact pointers to support explanations. Checks linked hashes; use verify for independent re-derivation.",
            true,
        ),
        tool::<super::replay::RenderInput>(
            "chiyoda_render_evidence",
            "Reconstruct a bounded saved run and write a simulation PNG highlighting one exact person/resource at a recorded step, plus source-linked JSON evidence. The image shows only the simulation and timeline; measurements and diagnostics are in the evidence. MCP returns the same PNG as image content. Whole-run totals remain separate from frame state and sparse-trace accounting stays unavailable.",
            false,
        ),
        tool::<super::campaign::PlanInput>(
            "chiyoda_campaign_plan",
            "Preflight a permissioned campaign with explicit objectives and worst-case screening, stress, generation and evidence-image budgets. Bounded WFC preparation does not simulate people.",
            true,
        ),
        tool::<super::campaign::RunInput>(
            "chiyoda_campaign_run",
            "Run a preflighted campaign into a new directory: retain rejected trials, promote by declared objectives, stress-test selected candidates, and export bounded annotated evidence. Returns explicit coverage and regressions, not an architectural recommendation.",
            false,
        ),
        tool::<super::campaign::QueryInput>(
            "chiyoda_campaign_query",
            "Read paginated campaign candidates, trials and annotated evidence, or its compact summary. Follow trial experiment paths with chiyoda_query for people, resources and trace details.",
            true,
        ),
        tool::<super::campaign::VerifyInput>(
            "chiyoda_campaign_verify",
            "Re-derive a saved campaign's trials, selection, rankings, coverage and budgets. Check image/source hashes; replay additionally reconstructs runs and re-renders image pixels. Verification work is additional to the original execution budget.",
            true,
        ),
        tool::<VerifyInput>(
            "chiyoda_verify",
            "Re-derive saved experiment evaluations, journey evidence and comparisons. Optionally reconstruct each run. Original replay flags are preserved.",
            true,
        ),
    ]
}

fn arguments<T: serde::de::DeserializeOwned>(value: Value) -> Result<T, ToolError> {
    serde_json::from_value(value).map_err(|error| ToolError::new("arguments", error.to_string()))
}

fn request(workspace: &Workspace, input: ExperimentInput) -> Result<ExperimentRequest, ToolError> {
    let baseline: CanonicalScenario = files::read(&workspace.path(&input.scenario)?)?;
    Ok(ExperimentRequest {
        experiment_version: experiment::EXPERIMENT_VERSION.into(),
        name: input.name,
        baseline,
        variants: input.variants,
        options: input.options,
    })
}

pub(super) fn call(workspace: &Workspace, name: &str, input: Value) -> ToolResult {
    let result = dispatch(workspace, name, input)?;
    if serde_json::to_vec(&result).expect("tool result JSON").len() > 1024 * 1024 {
        return Err(ToolError::new(
            "resource_limit",
            "tool result exceeds 1 MiB; use narrower inputs or smaller evidence queries",
        ));
    }
    Ok(result)
}

fn dispatch(workspace: &Workspace, name: &str, input: Value) -> ToolResult {
    match name {
        "chiyoda_campaign_plan" => super::campaign::plan(workspace, input),
        "chiyoda_campaign_run" => super::campaign::run(workspace, input),
        "chiyoda_campaign_query" => super::campaign::query(workspace, input),
        "chiyoda_campaign_verify" => super::campaign::verify(workspace, input),
        "chiyoda_render_evidence" => {
            let input: super::replay::RenderInput = arguments(input)?;
            super::replay::execute(workspace, &input)
        }
        "chiyoda_capabilities" => {
            let _: Empty = arguments(input)?;
            Ok(
                json!({"api_version":API_VERSION,"experiment_version":experiment::EXPERIMENT_VERSION,
                "runtime_version":chiyoda_core::RUNTIME_VERSION,"bundle_version":chiyoda_core::bundle::BUNDLE_VERSION,
                "campaign_version":chiyoda_core::campaign::CAMPAIGN_VERSION,"campaign_limits":{"candidates":32,"stress_cases":16,"trials":256,"evidence_images":16,"max_agent_steps":2_000_000,"max_trace_samples":2_000_000},
                "replay_evidence_version":"1","replay_renderer_version":chiyoda_replay::render::RENDERER_VERSION,
                "scenario_schema_version":chiyoda_core::SCENARIO_SCHEMA_VERSION,"generator_version":spaces::GENERATOR_VERSION,
                "workflow":["inspect or generate a baseline","plan independent ID-based variants","run with the exact planned input hash","query paginated evidence and compare outcomes","verify and optionally reconstruct saved runs"],
                "limits":{"arms":16,"variants":15,"changes_per_variant":64,"people_per_arm":1024,"steps_per_arm":10000,"declared_objects_per_arm":1024,"max_agent_steps":2_000_000,"max_trace_samples":2_000_000,"query_page_items":200,"tool_result_bytes":1_048_576},
                "semantics":["Every candidate starts independently from the same baseline; seed and integration settings remain fixed.","Population pairing retains physical traits, goals, releases and seed; knowledge may be an intervention. Changed population inputs disable individual pairing.","Incomplete journeys have no completed elapsed or clearance time. Low-progress spans overlap moving exposure.","Threshold failures remain measured; invalid trajectories are excluded from comparisons.","Observed differences and isolated/static references are not calibrated real-world causal or safety claims.","No model API credentials or language model are required. Tools return evidence for the calling agent to reason about."]}),
            )
        }
        "chiyoda_schema" => {
            let input: SchemaInput = arguments(input)?;
            Ok(match input.kind {
                SchemaKind::Scenario => chiyoda_core::scenario_json::json_schema(),
                SchemaKind::Experiment => experiment::json_schema(),
                SchemaKind::Campaign => chiyoda_core::campaign::json_schema(),
            })
        }
        "chiyoda_inspect" => {
            let input: InspectInput = arguments(input)?;
            let document: CanonicalScenario = files::read(&workspace.path(&input.scenario)?)?;
            Ok(serde_json::to_value(experiment::inspect(&document)).expect("inspection JSON"))
        }
        "chiyoda_plan" => {
            let request = request(workspace, arguments(input)?)?;
            Ok(serde_json::to_value(experiment::plan(&request)?).expect("plan JSON"))
        }
        "chiyoda_run" => {
            let input: RunInput = arguments(input)?;
            let request = request(workspace, input.experiment)?;
            let plan = experiment::plan(&request)?;
            if plan.input_hash != input.expected_input_hash {
                return Err(ToolError::new(
                    "stale_plan",
                    "input changed since planning; request a new plan",
                ));
            }
            if !plan.runnable {
                let mut error = ToolError::new(
                    "invalid_plan",
                    "preflight failed; no simulations were executed",
                );
                error.details = Some(serde_json::to_value(plan).expect("plan JSON"));
                return Err(error);
            }
            let directory = workspace.new_directory(&input.output)?;
            files::write(&directory.join("request.json"), &request)?;
            let execution = match experiment::execute(&request, &input.expected_input_hash) {
                Ok(execution) => execution,
                Err(error) => {
                    files::write(&directory.join("failure.json"), &error)?;
                    let mut error = ToolError::from(error);
                    error.details = Some(json!({"output":input.output,"failure":"failure.json"}));
                    return Err(error);
                }
            };
            files::save(&directory, &request, &execution)?;
            let mut summary = query::summary(&execution.report);
            summary["output"] = input.output.into();
            Ok(summary)
        }
        "chiyoda_query" => query::execute(workspace, &arguments(input)?),
        "chiyoda_verify" => {
            let input: VerifyInput = arguments(input)?;
            files::verify(workspace, &input.experiment, input.replay)
        }
        "chiyoda_generate" => generate(workspace, &arguments(input)?),
        _ => Err(ToolError::new(
            "unknown_tool",
            format!("unknown tool `{name}`; discover tools with `chiyoda agent tools`"),
        )),
    }
}

fn generate(workspace: &Workspace, input: &GenerateInput) -> ToolResult {
    let config: SpaceConfig = input.config.as_deref().map_or_else(
        || Ok(SpaceConfig::default()),
        |path| files::read(&workspace.path(path)?),
    )?;
    config
        .validate()
        .map_err(|error| ToolError::new("configuration", error.to_string()))?;
    if config.max_attempts > 64 || config.max_backtracks > 10_000 || config.max_decisions > 100_000
    {
        return Err(ToolError::new(
            "resource_limit",
            "agent generation permits at most 64 attempts, 10000 backtracks and 100000 decisions per attempt",
        ));
    }
    let directory = workspace.new_directory(&input.output)?;
    match spaces::generate(input.seed, &config) {
        Ok(space) => {
            std::fs::write(directory.join("scenario.json"), space.json).map_err(ToolError::from)?;
            files::write(&directory.join("generation.json"), &space.manifest)?;
            Ok(
                json!({"output":input.output,"scenario":format!("{}/scenario.json",input.output),"generation":format!("{}/generation.json",input.output),"manifest_hash":chiyoda_core::bundle::canonical_hash(&space.manifest),"attempts":space.manifest.attempts.len(),"seed":input.seed}),
            )
        }
        Err(error) => {
            files::write(
                &directory.join("generation-failure.json"),
                &json!({"seed":input.seed,"config":config,"error":error.to_string(),"attempts":match &error {spaces::SpaceGenerationError::Exhausted { attempts } => attempts.clone(),spaces::SpaceGenerationError::InvalidConfig(_) => vec![]}}),
            )?;
            let mut error = ToolError::new("generation", error.to_string());
            error.details =
                Some(json!({"output":input.output,"artifact":"generation-failure.json"}));
            Err(error)
        }
    }
}
