use super::{ensure_parent, read_json, write_json, write_text};
use anyhow::{Context, Result, ensure};
use chiyoda_core::{
    BundleVerification, RunBundle,
    journeys::{self, JourneyAnalysis, JourneyOptions, JourneyReport, JourneySummary, rendering},
    verify_run_bundle,
};
use clap::{Args, Subcommand};
use std::{
    fs,
    path::{Path, PathBuf},
};

#[derive(Debug, Args)]
pub(super) struct DiagnosisArgs {
    #[command(subcommand)]
    command: DiagnosisCommand,
}

#[derive(Debug, Subcommand)]
enum DiagnosisCommand {
    /// Analyze a full-step run, optionally simulating bounded isolated controls.
    Run {
        bundle: PathBuf,
        #[arg(short, long)]
        output: PathBuf,
        /// JSON journey-analysis options, including optional control budgets.
        #[arg(long)]
        options: Option<PathBuf>,
        /// Original generated identity; repeat to request multiple controls.
        #[arg(long = "isolated-agent")]
        isolated_agents: Vec<String>,
        #[arg(long)]
        isolate_low_progress: bool,
        #[arg(long)]
        isolate_all: bool,
        /// Reconstruct the source run before analysis (controls default to replay).
        #[arg(long)]
        replay: bool,
    },
    /// Re-derive a saved analysis from its source and isolated bundles.
    Verify {
        report: PathBuf,
        /// Also reconstruct the source and every saved isolated run.
        #[arg(long)]
        replay: bool,
    },
}

pub(super) fn execute(args: &DiagnosisArgs) -> Result<()> {
    match &args.command {
        DiagnosisCommand::Run {
            bundle,
            output,
            options,
            isolated_agents,
            isolate_low_progress,
            isolate_all,
            replay,
        } => {
            let source: RunBundle = read_json(bundle)?;
            let mut options: JourneyOptions = options
                .as_deref()
                .map_or_else(|| Ok(JourneyOptions::default()), read_json)?;
            if !isolated_agents.is_empty() || *isolate_low_progress || *isolate_all {
                let controls = options
                    .isolated_controls
                    .get_or_insert_with(Default::default);
                controls.agents.extend(isolated_agents.iter().cloned());
                controls.low_progress_agents |= *isolate_low_progress;
                controls.all_agents |= *isolate_all;
            }
            options.validate().map_err(anyhow::Error::msg)?;
            if *replay {
                reconstruct(&source)?;
            }
            ensure!(
                !output.exists(),
                "destination must not already exist: {}",
                output.display()
            );
            let analysis =
                journeys::analyze_with_controls(&source, &options).map_err(anyhow::Error::msg)?;
            ensure_parent(output)?;
            fs::create_dir(output).with_context(|| {
                format!("creating new diagnostic directory {}", output.display())
            })?;
            write_json(&output.join("run.json"), &source)?;
            save(output, &analysis)?;
            let summary = analysis.report.summary();
            println!(
                "journeys: {}; complete: {}; isolated controls: {}/{}",
                summary.people, summary.completed, summary.controls_run, summary.controls_requested
            );
            println!("report: {}", output.join("journeys.md").display());
            Ok(())
        }
        DiagnosisCommand::Verify { report, replay } => {
            let saved: JourneyReport = read_json(report)?;
            let root = report.parent().unwrap_or_else(|| Path::new("."));
            let source: RunBundle = read_json(&root.join("run.json"))?;
            verify_saved(root, &source, &saved.summary(), &saved.options, *replay)?;
            println!(
                "verified journeys: {}; source/control reconstruction: {}",
                saved.report_hash, replay
            );
            Ok(())
        }
    }
}

pub(super) fn save(root: &Path, analysis: &JourneyAnalysis) -> Result<()> {
    write_json(&root.join("journeys.json"), &analysis.report)?;
    write_text(
        &root.join("journeys.csv"),
        &rendering::csv(&analysis.report).map_err(anyhow::Error::msg)?,
    )?;
    write_text(
        &root.join("journeys.md"),
        &rendering::markdown(&analysis.report).map_err(anyhow::Error::msg)?,
    )?;
    for (index, control) in analysis.report.isolated_controls.iter().enumerate() {
        let Some(hash) = &control.bundle_hash else {
            continue;
        };
        let bundle = analysis
            .isolated_runs
            .iter()
            .find(|bundle| bundle.bundle_hash == *hash)
            .context("missing generated isolated artifact")?;
        let directory = root.join(format!("isolated-{index:04}"));
        fs::create_dir(&directory)?;
        write_json(&directory.join("run.json"), bundle)?;
    }
    Ok(())
}

pub(super) fn verify_saved(
    root: &Path,
    source: &RunBundle,
    summary: &JourneySummary,
    options: &JourneyOptions,
    replay: bool,
) -> Result<()> {
    let report: JourneyReport = read_json(&root.join("journeys.json"))?;
    report.check_hash().map_err(anyhow::Error::msg)?;
    ensure!(
        report.summary() == *summary && report.options == *options,
        "journey report disagrees with its case or analysis options"
    );
    let mut isolated = Vec::new();
    for (index, control) in report.isolated_controls.iter().enumerate() {
        if let Some(hash) = &control.bundle_hash {
            let bundle: RunBundle = read_json(&root.join(format!("isolated-{index:04}/run.json")))?;
            ensure!(
                bundle.bundle_hash == *hash,
                "isolated bundle hash disagrees with journey evidence"
            );
            if replay {
                reconstruct(&bundle)?;
            }
            isolated.push(bundle);
        }
    }
    report
        .verify_evidence(source, &isolated)
        .map_err(anyhow::Error::msg)?;
    if replay {
        reconstruct(source)?;
    }
    Ok(())
}

fn reconstruct(bundle: &RunBundle) -> Result<()> {
    ensure!(
        verify_run_bundle(bundle)? == BundleVerification::Reconstructed,
        "bundle cannot be reconstructed with the installed runtime"
    );
    Ok(())
}
