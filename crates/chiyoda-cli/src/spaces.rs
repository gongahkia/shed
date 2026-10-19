use super::{ensure_parent, read_json, read_text, write_json, write_text};
use anyhow::{Context, Result, bail};
use chiyoda_core::{
    Scenario,
    evaluation::{
        self, CaseEvaluation, CaseReport, ExpectedReachability, Fixture, SuiteConfig, SuiteReport,
    },
    generator::spaces::{self, Preset, SpaceConfig, SpaceGenerationError},
    reachability,
};
use clap::{Args, ValueEnum};
use std::{
    fs,
    path::{Path, PathBuf},
};

#[derive(Debug, Clone, Copy, ValueEnum)]
pub(super) enum PresetArg {
    Concourse,
    Corridors,
    Bottleneck,
}

impl From<PresetArg> for Preset {
    fn from(preset: PresetArg) -> Self {
        match preset {
            PresetArg::Concourse => Self::Concourse,
            PresetArg::Corridors => Self::Corridors,
            PresetArg::Bottleneck => Self::Bottleneck,
        }
    }
}

#[derive(Debug, Args)]
pub(super) struct GenerateArgs {
    #[arg(long)]
    seed: u64,
    /// JSON `SpaceConfig`; explicit CLI values override its fields.
    #[arg(long)]
    config: Option<PathBuf>,
    #[arg(long, value_enum)]
    preset: Option<PresetArg>,
    #[arg(long)]
    width: Option<u32>,
    #[arg(long)]
    height: Option<u32>,
    #[arg(long)]
    floors: Option<u32>,
    #[arg(long)]
    agents_per_floor: Option<u32>,
    /// Must be a new directory; existing artifacts are never overwritten.
    #[arg(short, long)]
    output: PathBuf,
}

#[derive(Debug, Args)]
pub(super) struct EvaluateArgs {
    /// JSON `SuiteConfig`, including generation rules, seeds, and thresholds.
    #[arg(long)]
    config: Option<PathBuf>,
    /// Override seeds with a consecutive range starting here.
    #[arg(long)]
    seed: Option<u64>,
    /// Number of consecutive seeds. Zero runs only authored fixtures.
    #[arg(long)]
    cases: Option<u32>,
    /// Add a .chy fixture expected to have geometrically reachable journeys.
    #[arg(long)]
    fixture: Vec<PathBuf>,
    /// Add a valid .chy fixture expected to fail geometric reachability.
    #[arg(long)]
    unreachable_fixture: Vec<PathBuf>,
    #[arg(short, long)]
    output: PathBuf,
}

pub(super) fn print_config(preset: PresetArg) -> Result<()> {
    let mut config = SpaceConfig {
        preset: preset.into(),
        ..SpaceConfig::default()
    };
    config.modules = config.catalog();
    println!("{}", serde_json::to_string_pretty(&config)?);
    Ok(())
}

pub(super) fn generate(args: &GenerateArgs) -> Result<()> {
    let mut config: SpaceConfig = args
        .config
        .as_ref()
        .map_or_else(|| Ok(SpaceConfig::default()), |path| read_json(path))?;
    if let Some(preset) = args.preset {
        config.preset = preset.into();
    }
    if let Some(width) = args.width {
        config.width = width;
    }
    if let Some(height) = args.height {
        config.height = height;
    }
    if let Some(floors) = args.floors {
        config.floors = floors;
    }
    if let Some(agents) = args.agents_per_floor {
        config.agents_per_floor = agents;
    }
    config.validate()?;
    create_destination(&args.output)?;
    match spaces::generate(args.seed, &config) {
        Ok(space) => {
            write_text(&args.output.join("scenario.chy"), &space.source)?;
            write_json(&args.output.join("generation.json"), &space.manifest)?;
            println!("generated: {}", args.output.join("scenario.chy").display());
            println!(
                "attempts: {}; geometric journeys: {}",
                space.manifest.attempts.len(),
                space.manifest.reachability.checked_journeys
            );
        }
        Err(error) => {
            let attempts = match &error {
                SpaceGenerationError::Exhausted { attempts } => attempts.clone(),
                SpaceGenerationError::InvalidConfig(_) => vec![],
            };
            write_json(
                &args.output.join("generation-failure.json"),
                &serde_json::json!({ "generator_version": spaces::GENERATOR_VERSION, "solver_version": chiyoda_core::wfc::SOLVER_VERSION, "runtime_version": chiyoda_core::RUNTIME_VERSION, "seed": args.seed, "config": config, "attempts": attempts, "error": error.to_string() }),
            )?;
            bail!(
                "{error}; details: {}",
                args.output.join("generation-failure.json").display()
            );
        }
    }
    Ok(())
}

pub(super) fn check_geometry(scenario: &Scenario) -> Result<()> {
    let report = reachability::check(scenario)?;
    if !report.is_reachable() {
        bail!(
            "geometrically unreachable journeys: {:?}",
            report.unreachable
        );
    }
    println!(
        "geometry: {} journeys from {} spawns are reachable",
        report.checked_journeys, report.checked_spawns
    );
    Ok(())
}

pub(super) fn evaluate(args: &EvaluateArgs) -> Result<()> {
    let mut config: SuiteConfig = args
        .config
        .as_ref()
        .map_or_else(|| Ok(SuiteConfig::default()), |path| read_json(path))?;
    override_seeds(&mut config, args.seed, args.cases)?;
    for (paths, expected) in [
        (&args.fixture, ExpectedReachability::Reachable),
        (&args.unreachable_fixture, ExpectedReachability::Unreachable),
    ] {
        for path in paths {
            config.fixtures.push(Fixture {
                name: path
                    .file_stem()
                    .and_then(|stem| stem.to_str())
                    .context("fixture needs a UTF-8 file stem")?
                    .into(),
                source: read_text(path)?,
                expected,
            });
        }
    }
    config.validate().map_err(anyhow::Error::msg)?;
    create_destination(&args.output)?;
    write_json(&args.output.join("config.json"), &config)?;
    let mut reports = Vec::new();
    for seed in &config.seeds {
        let case = evaluation::evaluate_seed(*seed, &config.generation, &config.evaluation);
        save_case(&args.output, &mut reports, case)?;
    }
    for fixture in &config.fixtures {
        let case = evaluation::evaluate_source(
            &fixture.name,
            &fixture.source,
            fixture.expected,
            &config.evaluation,
        );
        save_case(&args.output, &mut reports, case)?;
    }
    let report = SuiteReport::from_cases(config, reports).map_err(anyhow::Error::msg)?;
    let path = args.output.join("report.json");
    write_json(&path, &report)?;
    println!(
        "passed: {}; failed: {}; unique layouts: {}; rejected generation attempts: {}",
        report.passed, report.failed, report.unique_layouts, report.rejected_generation_attempts
    );
    println!("report: {}", path.display());
    if report.failed > 0 {
        bail!(
            "{} evaluation cases failed; artifacts retained at {}",
            report.failed,
            args.output.display()
        );
    }
    Ok(())
}

fn override_seeds(config: &mut SuiteConfig, seed: Option<u64>, cases: Option<u32>) -> Result<()> {
    if seed.is_none() && cases.is_none() {
        return Ok(());
    }
    let count = cases.unwrap_or(u32::try_from(config.seeds.len())?);
    if count > 256 {
        bail!("at most 256 generated cases are supported");
    }
    let start = seed.or_else(|| config.seeds.first().copied()).unwrap_or(0);
    config.seeds = (0..count)
        .map(|offset| {
            start
                .checked_add(u64::from(offset))
                .context("seed range overflows u64")
        })
        .collect::<Result<_>>()?;
    Ok(())
}

fn create_destination(output: &Path) -> Result<()> {
    ensure_parent(output)?;
    fs::create_dir(output).with_context(|| {
        format!(
            "creating {} (destination must not already exist)",
            output.display()
        )
    })
}

fn save_case(output: &Path, reports: &mut Vec<CaseReport>, case: CaseEvaluation) -> Result<()> {
    let directory = output.join(format!("case-{:04}", reports.len()));
    fs::create_dir(&directory)?;
    if let Some(source) = &case.source {
        write_text(&directory.join("scenario.chy"), source)?;
    }
    if let Some(generation) = &case.generation {
        write_json(&directory.join("generation.json"), generation)?;
    }
    if let Some(bundle) = &case.bundle {
        write_json(&directory.join("run.json"), bundle)?;
    }
    write_json(&directory.join("report.json"), &case.report)?;
    println!(
        "{}: {}",
        case.report.name,
        if case.report.passed {
            "passed"
        } else {
            "FAILED"
        }
    );
    reports.push(case.report);
    // Persist partial progress after every case, including failures. The final
    // report additionally enforces that no requested case has been dropped.
    write_json(&output.join("progress.json"), reports)
}
