use super::{ensure_parent, read_json, read_text, write_json, write_text};
use anyhow::{Context, Result, bail};
use chiyoda_core::{
    benchmark::{
        self, BenchmarkReport, BenchmarkSuite, CaseResult,
        comparison::{self, ComparisonPolicy, ComparisonReport},
        freeze, profiles, rendering,
    },
    evaluation::{ExpectedReachability, SuiteReport, Thresholds},
};
use clap::{Args, Subcommand};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Debug, Args)]
pub(super) struct BenchmarkArgs {
    #[command(subcommand)]
    command: BenchmarkCommand,
}

#[derive(Debug, Subcommand)]
enum BenchmarkCommand {
    /// List embedded fixed-input profiles and their case counts.
    Profiles,
    /// Export an embedded profile as an editable suite with scenario documents.
    Export {
        #[arg(long, default_value = "smoke")]
        profile: String,
        /// New JSON file; existing files are never overwritten.
        #[arg(short, long)]
        output: PathBuf,
    },
    /// Freeze every archived evaluation scenario into a fixed-input suite.
    Freeze {
        /// Directory containing an evaluation report and numbered case folders.
        #[arg(long)]
        evaluation: PathBuf,
        #[arg(long)]
        name: String,
        /// Replace reachable-case thresholds with this JSON Thresholds object.
        #[arg(long)]
        thresholds: Option<PathBuf>,
        #[arg(short, long)]
        output: PathBuf,
    },
    /// Run a profile or suite, optionally comparing against a saved baseline.
    Run(RunArgs),
    /// Compare two verified reports without rerunning simulations.
    Compare {
        #[arg(long)]
        baseline: PathBuf,
        #[arg(long)]
        current: PathBuf,
        #[arg(long)]
        policy: Option<PathBuf>,
        /// New artifact directory; existing directories are never overwritten.
        #[arg(short, long)]
        output: PathBuf,
    },
}

#[derive(Debug, Args)]
struct RunArgs {
    /// Embedded profile; defaults to smoke unless a baseline supplies its suite.
    #[arg(long, conflicts_with = "suite")]
    profile: Option<String>,
    /// JSON `BenchmarkSuite` with a scenario document embedded for every case.
    #[arg(long)]
    suite: Option<PathBuf>,
    /// Verified passing report. With no profile/suite, reuse its exact suite.
    #[arg(long)]
    baseline: Option<PathBuf>,
    /// JSON `ComparisonPolicy`; default is zero deterioration on every metric.
    #[arg(long, requires = "baseline")]
    policy: Option<PathBuf>,
    /// Human revision label, such as a Git commit; recorded without verification.
    #[arg(long)]
    label: Option<String>,
    #[arg(short, long)]
    output: PathBuf,
}

pub(super) fn execute(args: &BenchmarkArgs) -> Result<()> {
    match &args.command {
        BenchmarkCommand::Profiles => {
            for name in profiles::NAMES {
                println!("{name}: {} cases", profiles::suite(name)?.cases.len());
            }
            println!("stress includes strict checks that expose current movement limitations");
            Ok(())
        }
        BenchmarkCommand::Export { profile, output } => {
            let suite = profiles::suite(profile)?;
            write_new_json(output, &suite)?;
            println!("suite: {}", output.display());
            Ok(())
        }
        BenchmarkCommand::Freeze {
            evaluation,
            name,
            thresholds,
            output,
        } => freeze_evaluation(evaluation, name, thresholds.as_deref(), output),
        BenchmarkCommand::Run(args) => run(args),
        BenchmarkCommand::Compare {
            baseline,
            current,
            policy,
            output,
        } => compare_saved(baseline, current, policy.as_deref(), output),
    }
}

fn freeze_evaluation(
    directory: &Path,
    name: &str,
    thresholds: Option<&Path>,
    output: &Path,
) -> Result<()> {
    let report: SuiteReport = read_json(&directory.join("report.json"))?;
    let sources = (0..report.cases.len())
        .map(|index| {
            read_text(&directory.join(format!("case-{index:04}/scenario.json"))).context(
                "every evaluation case needs a source; generation failures cannot be frozen",
            )
        })
        .collect::<Result<Vec<_>>>()?;
    let mut suite = freeze::from_evaluation(&report, &sources, name)?;
    if let Some(path) = thresholds {
        let thresholds: Thresholds = read_json(path)?;
        thresholds.validate().map_err(anyhow::Error::msg)?;
        for case in &mut suite.cases {
            if case.expected == ExpectedReachability::Reachable {
                case.evaluation.thresholds.clone_from(&thresholds);
            }
        }
    }
    suite.validate()?;
    write_new_json(output, &suite)?;
    println!(
        "frozen: {} cases in {}",
        suite.cases.len(),
        output.display()
    );
    Ok(())
}

fn run(args: &RunArgs) -> Result<()> {
    let baseline = args.baseline.as_deref().map(load_baseline).transpose()?;
    let suite = if let Some(path) = &args.suite {
        read_json(path)?
    } else if let Some(profile) = &args.profile {
        profiles::suite(profile)?
    } else if let Some(baseline) = &baseline {
        baseline.suite.clone()
    } else {
        profiles::suite("smoke")?
    };
    suite.validate()?;
    if args
        .label
        .as_ref()
        .is_some_and(|label| label.trim().is_empty())
    {
        bail!("run labels must be nonempty");
    }
    let policy = load_policy(args.policy.as_deref())?;
    create_destination(&args.output)?;
    write_json(&args.output.join("suite.json"), &suite)?;
    let mut results = Vec::new();
    for index in 0..suite.cases.len() {
        save_case(&args.output, &suite, index, &mut results)?;
    }
    let report = BenchmarkReport::new_labeled(suite, results, args.label.clone())?;
    write_json(&args.output.join("report.json"), &report)?;
    write_text(
        &args.output.join("report.md"),
        &rendering::run_markdown(&report)?,
    )?;
    println!("passed: {}; failed: {}", report.passed, report.failed);
    println!("report: {}", args.output.join("report.md").display());
    let comparison = baseline
        .as_ref()
        .map(|baseline| comparison::compare(baseline, &report, &policy))
        .transpose()?;
    if let Some(comparison) = &comparison {
        let baseline_root =
            artifact_root(args.baseline.as_deref().context("missing baseline path")?)?;
        save_comparison(
            &args.output,
            comparison,
            &report,
            &baseline_root,
            Path::new("."),
        )?;
    }
    if report.failed > 0
        || comparison
            .as_ref()
            .is_some_and(|report| !report.is_success())
    {
        bail!(
            "benchmark checks failed; artifacts retained at {}",
            args.output.display()
        );
    }
    Ok(())
}

fn save_case(
    output: &Path,
    suite: &BenchmarkSuite,
    index: usize,
    results: &mut Vec<CaseResult>,
) -> Result<()> {
    let directory = output.join(format!("case-{index:04}"));
    fs::create_dir(&directory)?;
    write_text(
        &directory.join("scenario.json"),
        &serde_json::to_string_pretty(&suite.cases[index].scenario)?,
    )?;
    let run = benchmark::run_case(suite, index)?;
    if let Some(bundle) = &run.artifacts.bundle {
        write_json(&directory.join("run.json"), bundle)?;
    }
    if let Some(analysis) = &run.journeys {
        super::journeys::save(&directory, analysis)?;
    }
    write_json(&directory.join("report.json"), &run.result)?;
    println!(
        "{}: {}; walking alerts: {}",
        run.result.id,
        if run.result.evaluation.passed {
            "passed"
        } else {
            "FAILED"
        },
        run.result.diagnosis.walking_low_progress.len()
    );
    for failure in &run.result.evaluation.failures {
        println!("  {}: {}", failure.check, failure.detail);
    }
    results.push(run.result);
    write_json(&output.join("progress.json"), results)
}

fn compare_saved(
    baseline_path: &Path,
    current_path: &Path,
    policy: Option<&Path>,
    output: &Path,
) -> Result<()> {
    let baseline = load_baseline(baseline_path)?;
    let current: BenchmarkReport = read_json(current_path)?;
    let comparison = comparison::compare(&baseline, &current, &load_policy(policy)?)?;
    let baseline_root = artifact_root(baseline_path)?;
    let current_root = artifact_root(current_path)?;
    create_destination(output)?;
    save_comparison(output, &comparison, &current, &baseline_root, &current_root)?;
    if !comparison.is_success() {
        bail!(
            "benchmark comparison failed; artifacts retained at {}",
            output.display()
        );
    }
    Ok(())
}

fn save_comparison(
    output: &Path,
    report: &ComparisonReport,
    current: &BenchmarkReport,
    baseline_root: &Path,
    current_root: &Path,
) -> Result<()> {
    write_json(&output.join("comparison.json"), report)?;
    write_text(
        &output.join("comparison.md"),
        &rendering::comparison_markdown(report, current, baseline_root, current_root),
    )?;
    println!(
        "regressed: {}; incompatible: {}; improved: {}; unchanged: {}",
        report.regressed, report.incompatible, report.improved, report.unchanged
    );
    for case in &report.cases {
        if matches!(
            case.verdict,
            comparison::CaseVerdict::Regressed | comparison::CaseVerdict::Incompatible
        ) {
            println!("  {}: {:?}", case.id, case.verdict);
            for reason in &case.reasons {
                println!("    {reason}");
            }
            for metric in &case.metrics {
                if metric.verdict == comparison::MetricVerdict::Regressed {
                    println!(
                        "    {}: {:?} -> {:?} (delta {:?}, allowance {:?})",
                        metric.metric.as_str(),
                        metric.baseline,
                        metric.current,
                        metric.delta,
                        metric.allowed_deterioration
                    );
                }
            }
        }
    }
    println!("comparison: {}", output.join("comparison.md").display());
    Ok(())
}

fn load_baseline(path: &Path) -> Result<BenchmarkReport> {
    let report: BenchmarkReport = read_json(path)?;
    report.verify()?;
    if report.failed != 0 {
        bail!("a baseline must pass every absolute check");
    }
    Ok(report)
}

fn load_policy(path: Option<&Path>) -> Result<ComparisonPolicy> {
    let policy = path.map_or_else(|| Ok(ComparisonPolicy::default()), read_json)?;
    policy.validate()?;
    Ok(policy)
}

fn artifact_root(report: &Path) -> Result<PathBuf> {
    let parent = report
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    parent
        .canonicalize()
        .with_context(|| format!("resolving {}", parent.display()))
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

fn write_new_json<T: serde::Serialize>(path: &Path, value: &T) -> Result<()> {
    let mut bytes = serde_json::to_vec_pretty(value)?;
    bytes.push(b'\n');
    ensure_parent(path)?;
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .with_context(|| format!("creating {} (file must not already exist)", path.display()))?;
    file.write_all(&bytes)
        .with_context(|| format!("writing {}", path.display()))
}
