use super::{ensure_parent, read_json, read_text, write_json, write_text};
use anyhow::{Context, Result, bail, ensure};
use chiyoda_core::{
    BundleVerification, CanonicalScenario, RunBundle,
    bundle::canonical_hash,
    decode_scenario,
    generator::spaces::{GeneratedSpace, GenerationManifest},
    study::{
        EXAMPLE_CONFIG_JSON, PreparedInput, StudyCaseArtifacts, StudyCaseResult, StudyConfig,
        StudyReport, StudyRunner, rendering,
    },
    verify_run_bundle,
};
use clap::{Args, Subcommand};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Debug, Args)]
pub(super) struct StudyArgs {
    #[command(subcommand)]
    command: StudyCommand,
}

#[derive(Debug, Subcommand)]
enum StudyCommand {
    /// Write an editable example study to a new JSON file.
    Template {
        #[arg(short, long)]
        output: PathBuf,
    },
    /// Validate study axes, bounds, and generator configs without simulations.
    Check {
        #[arg(long)]
        config: PathBuf,
    },
    /// Run every pattern/size/seed/load/policy case and retain its artifacts.
    Run {
        #[arg(long)]
        config: PathBuf,
        #[arg(short, long)]
        output: PathBuf,
    },
    /// Verify a report, its archived inputs, generation manifests, and bundles.
    Verify {
        report: PathBuf,
        /// Also reconstruct every available run with the current runtime.
        #[arg(long)]
        replay: bool,
    },
}

pub(super) fn execute(args: &StudyArgs) -> Result<()> {
    match &args.command {
        StudyCommand::Template { output } => {
            let config: StudyConfig = serde_json::from_str(EXAMPLE_CONFIG_JSON)?;
            config.validate().map_err(anyhow::Error::msg)?;
            ensure_parent(output)?;
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(output)
                .with_context(|| {
                    format!(
                        "creating {} (file must not already exist)",
                        output.display()
                    )
                })?;
            let json = serde_json::to_string_pretty(&config)? + "\n";
            file.write_all(json.as_bytes())?;
            println!("study config: {}", output.display());
            Ok(())
        }
        StudyCommand::Check { config } => {
            let config: StudyConfig = read_json(config)?;
            config.validate().map_err(anyhow::Error::msg)?;
            println!(
                "valid study: {}; {} cases",
                config.name,
                config.case_count().expect("validated")
            );
            Ok(())
        }
        StudyCommand::Run { config, output } => run(config, output),
        StudyCommand::Verify { report, replay } => verify(report, *replay),
    }
}

fn run(config_path: &Path, output: &Path) -> Result<()> {
    let config: StudyConfig = read_json(config_path)?;
    let runner = StudyRunner::new(config.clone()).map_err(anyhow::Error::msg)?;
    ensure_parent(output)?;
    fs::create_dir(output).with_context(|| {
        format!(
            "creating {} (destination must not already exist)",
            output.display()
        )
    })?;
    write_json(&output.join("config.json"), &config)?;
    let mut cases = Vec::new();
    for case in runner {
        save_case(output, cases.len(), &case)?;
        let key = &case.result.key;
        println!(
            "{}/{}/{}/{} seed {}: {}",
            key.pattern,
            key.scale,
            key.load,
            key.policy,
            key.seed,
            if case.result.passed {
                "passed"
            } else {
                "FAILED"
            }
        );
        cases.push(case.result);
        write_json(&output.join("progress.json"), &cases)?;
    }
    let report = StudyReport::from_cases(config, cases).map_err(anyhow::Error::msg)?;
    write_json(&output.join("report.json"), &report)?;
    write_text(
        &output.join("report.md"),
        &rendering::markdown(&report).map_err(anyhow::Error::msg)?,
    )?;
    write_text(
        &output.join("observations.csv"),
        &rendering::csv(&report).map_err(anyhow::Error::msg)?,
    )?;
    println!(
        "passed: {}; failed: {}; generated layouts: {}/{}; unique layouts: {}",
        report.passed,
        report.failed,
        report.generated_layouts,
        report.requested_layouts,
        report.unique_layouts
    );
    println!("report: {}", output.join("report.md").display());
    ensure!(
        report.failed == 0,
        "{} study cases failed; all artifacts retained at {}",
        report.failed,
        output.display()
    );
    Ok(())
}

fn save_case(output: &Path, index: usize, case: &StudyCaseArtifacts) -> Result<()> {
    let root = output.join(format!("case-{index:04}"));
    fs::create_dir(&root)?;
    if let Some(json) = &case.generated_json {
        write_text(&root.join("generated-scenario.json"), json)?;
    }
    if let Some(generation) = &case.generation {
        write_json(&root.join("generation.json"), generation)?;
    }
    if let Some(json) = &case.input_json {
        write_text(&root.join("scenario.json"), json)?;
    }
    if let Some(bundle) = &case.bundle {
        write_json(&root.join("run.json"), bundle)?;
    }
    write_json(&root.join("report.json"), &case.result)
}

fn verify(path: &Path, replay: bool) -> Result<()> {
    let report: StudyReport = read_json(path)?;
    report.verify().map_err(anyhow::Error::msg)?;
    let root = path.parent().unwrap_or_else(|| Path::new("."));
    let saved_config: StudyConfig = read_json(&root.join("config.json"))?;
    ensure!(
        saved_config == report.config,
        "saved study config disagrees with report"
    );
    for (index, case) in report.cases.iter().enumerate() {
        verify_case(
            &report,
            case,
            &root.join(format!("case-{index:04}")),
            replay,
        )
        .with_context(|| format!("verifying case-{index:04}"))?;
    }
    println!(
        "verified study: {}; {} cases; replay reconstruction: {}",
        report.report_hash, report.requested, replay
    );
    Ok(())
}

fn verify_case(
    report: &StudyReport,
    case: &StudyCaseResult,
    root: &Path,
    replay: bool,
) -> Result<()> {
    let archived: StudyCaseResult = read_json(&root.join("report.json"))?;
    ensure!(archived == *case, "case report disagrees with study report");
    let Some(generation_hash) = &case.generation_hash else {
        ensure!(
            case.setup_failure
                .as_ref()
                .is_some_and(|failure| failure.stage == "generation"),
            "missing generation provenance"
        );
        return Ok(());
    };
    let prepared = prepare_archived(report, case, root, generation_hash)?;
    let Some(input_hash) = &case.input_sha256 else {
        ensure!(
            prepared.as_ref().err() == case.setup_failure.as_ref(),
            "setup failure cannot be reproduced from archived geometry"
        );
        return Ok(());
    };
    let prepared =
        prepared.map_err(|failure| anyhow::anyhow!("{}: {}", failure.stage, failure.detail))?;
    ensure!(
        Some(&prepared.context) == case.context.as_ref(),
        "controlled input context mismatch"
    );
    let policy = report
        .config
        .policies
        .iter()
        .find(|policy| policy.id == case.key.policy)
        .expect("verified report");
    let expected_scenario = prepared.with_policy(policy);
    let input = read_text(&root.join("scenario.json"))?;
    ensure!(sha(&input) == *input_hash, "scenario JSON hash mismatch");
    let scenario = decode_scenario(&input)?;
    ensure!(
        scenario == expected_scenario,
        "evaluated input does not match the configured policy and population"
    );
    let evaluation = case.evaluation.as_ref().expect("verified report");
    let document = CanonicalScenario::from(scenario);
    if let Some(hash) = &evaluation.scenario_hash {
        ensure!(
            canonical_hash(&document) == *hash,
            "evaluated scenario hash mismatch"
        );
    }
    if let Some(hash) = &evaluation.bundle_hash {
        let bundle: RunBundle = read_json(&root.join("run.json"))?;
        ensure!(
            bundle.bundle_hash == *hash && bundle.verifies_hash(),
            "bundle hash mismatch"
        );
        ensure!(
            bundle.scenario == document && bundle.scenario_hash == canonical_hash(&document),
            "bundle scenario mismatch"
        );
        ensure!(
            evaluation.metrics.as_ref() == Some(&bundle.metrics)
                && bundle.runtime_version == report.runtime_version,
            "bundle metrics or runtime mismatch"
        );
        if replay && verify_run_bundle(&bundle)? != BundleVerification::Reconstructed {
            bail!("bundle cannot be reconstructed with the current runtime");
        }
    }
    Ok(())
}

fn sha(json: &str) -> String {
    format!("{:x}", Sha256::digest(json.as_bytes()))
}

fn prepare_archived(
    report: &StudyReport,
    case: &StudyCaseResult,
    root: &Path,
    generation_hash: &str,
) -> Result<Result<PreparedInput, chiyoda_core::study::StudyFailure>> {
    let manifest: GenerationManifest = read_json(&root.join("generation.json"))?;
    ensure!(
        canonical_hash(&manifest) == generation_hash,
        "generation manifest hash mismatch"
    );
    let json = read_text(&root.join("generated-scenario.json"))?;
    ensure!(
        sha(&json) == manifest.json_sha256,
        "generated JSON hash mismatch"
    );
    let generated = decode_scenario(&json)?;
    ensure!(
        canonical_hash(&CanonicalScenario::from(generated.clone())) == manifest.scenario_hash,
        "generated scenario hash mismatch"
    );
    let scale = report
        .config
        .scales
        .iter()
        .find(|scale| scale.id == case.key.scale)
        .expect("verified report");
    let pattern = report
        .config
        .patterns
        .iter()
        .find(|pattern| pattern.id == case.key.pattern)
        .expect("verified report");
    ensure!(
        manifest.seed == case.key.seed
            && manifest.config == report.config.generation(pattern, scale),
        "generation does not match study case"
    );
    let space = GeneratedSpace {
        json,
        scenario: generated,
        manifest,
    };
    let load = report
        .config
        .loads
        .iter()
        .find(|load| load.id == case.key.load)
        .expect("verified report");
    Ok(PreparedInput::from_space(
        &report.config,
        &space,
        scale,
        load,
    ))
}
