use chiyoda_core::benchmark::{BenchmarkReport, BenchmarkSuite, profiles};
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    process::{Command, Output},
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "chiyoda-benchmark-test-{}-{}",
            std::process::id(),
            NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn path(&self, name: &str) -> PathBuf {
        self.0.join(name)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn cli(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_chiyoda"))
        .args(args)
        .output()
        .unwrap()
}
fn success(args: &[&str]) -> Output {
    let output = cli(args);
    assert!(
        output.status.success(),
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    output
}
fn text(path: &Path) -> &str {
    path.to_str().unwrap()
}
fn write<T: serde::Serialize>(path: &Path, value: &T) {
    fs::write(path, serde_json::to_vec_pretty(value).unwrap()).unwrap();
}
fn read<T: serde::de::DeserializeOwned>(path: &Path) -> T {
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}
fn small_suite() -> BenchmarkSuite {
    let mut suite = profiles::suite("smoke").unwrap();
    suite.cases.truncate(1);
    suite
}
fn run_suite(directory: &Directory, name: &str, suite: &BenchmarkSuite) -> PathBuf {
    let suite_path = directory.path(&format!("{name}.json"));
    let output = directory.path(name);
    write(&suite_path, suite);
    success(&[
        "benchmark",
        "run",
        "--suite",
        text(&suite_path),
        "-o",
        text(&output),
    ]);
    output
}

#[test]
fn profiles_are_exportable_and_export_never_overwrites_existing_files() {
    let directory = Directory::new();
    let output = success(&["benchmark", "profiles"]);
    let names = String::from_utf8(output.stdout).unwrap();
    for profile in profiles::NAMES {
        assert!(names.contains(profile));
    }
    let path = directory.path("smoke.json");
    let args = [
        "benchmark",
        "export",
        "--profile",
        "smoke",
        "-o",
        text(&path),
    ];
    success(&args);
    let exported: BenchmarkSuite = read(&path);
    assert_eq!(exported, profiles::suite("smoke").unwrap());
    let before = fs::read(&path).unwrap();
    assert!(!cli(&args).status.success());
    assert_eq!(before, fs::read(&path).unwrap());
    let bad = directory.path("bad.json");
    assert!(
        !cli(&[
            "benchmark",
            "export",
            "--profile",
            "absent",
            "-o",
            text(&bad)
        ])
        .status
        .success()
    );
    assert!(!bad.exists());
}

#[test]
fn baseline_runs_reuse_exact_inputs_and_offline_comparison_needs_no_simulation() {
    let directory = Directory::new();
    let baseline = run_suite(&directory, "baseline", &small_suite());
    let baseline_report = baseline.join("report.json");
    let candidate = directory.path("candidate");
    let args = [
        "benchmark",
        "run",
        "--baseline",
        text(&baseline_report),
        "--label",
        "candidate-revision",
        "-o",
        text(&candidate),
    ];
    success(&args);
    let original: BenchmarkReport = read(&baseline_report);
    let current: BenchmarkReport = read(&candidate.join("report.json"));
    current.verify().unwrap();
    assert_eq!(current.label.as_deref(), Some("candidate-revision"));
    assert_eq!(original.suite, current.suite);
    assert_eq!(original.cases, current.cases);
    let comparison: Value = read(&candidate.join("comparison.json"));
    assert_eq!(comparison["unchanged"], 1);
    assert_eq!(comparison["regressed"], 0);
    for artifact in [
        "suite.json",
        "progress.json",
        "report.json",
        "report.md",
        "comparison.json",
        "comparison.md",
        "case-0000/scenario.chy",
        "case-0000/run.json",
        "case-0000/report.json",
    ] {
        assert!(candidate.join(artifact).exists(), "{artifact}");
    }
    let before = fs::read(candidate.join("report.json")).unwrap();
    assert!(!cli(&args).status.success());
    assert_eq!(before, fs::read(candidate.join("report.json")).unwrap());
    let offline = directory.path("offline");
    // Reports contain the metrics; the comparison does not read/replay bundles.
    fs::remove_file(candidate.join("case-0000/run.json")).unwrap();
    success(&[
        "benchmark",
        "compare",
        "--baseline",
        text(&baseline_report),
        "--current",
        text(&candidate.join("report.json")),
        "-o",
        text(&offline),
    ]);
    assert_eq!(
        read::<Value>(&offline.join("comparison.json"))["unchanged"],
        1
    );
}

#[test]
fn failed_checks_keep_all_cases_and_cannot_be_used_as_a_baseline() {
    let directory = Directory::new();
    let mut suite = small_suite();
    let mut second = suite.cases[0].clone();
    second.id = "still-runs".into();
    suite.cases.push(second);
    suite.cases[0].evaluation.thresholds.max_clearance_time_s = Some(0.0);
    let suite_path = directory.path("suite.json");
    write(&suite_path, &suite);
    let output = directory.path("failed");
    assert!(
        !cli(&[
            "benchmark",
            "run",
            "--suite",
            text(&suite_path),
            "-o",
            text(&output)
        ])
        .status
        .success()
    );
    let report: BenchmarkReport = read(&output.join("report.json"));
    report.verify().unwrap();
    assert_eq!((report.passed, report.failed), (1, 1));
    assert!(
        fs::read_to_string(output.join("report.md"))
            .unwrap()
            .contains("max\\_clearance\\_time\\_s")
    );
    for index in 0..2 {
        assert!(output.join(format!("case-{index:04}/run.json")).exists());
    }
    let rejected = directory.path("rejected");
    assert!(
        !cli(&[
            "benchmark",
            "run",
            "--baseline",
            text(&output.join("report.json")),
            "-o",
            text(&rejected)
        ])
        .status
        .success()
    );
    assert!(!rejected.exists());
}

#[test]
fn comparisons_preserve_regressions_and_apply_explicit_metric_tolerances() {
    let directory = Directory::new();
    let baseline = run_suite(&directory, "baseline", &small_suite());
    let baseline_path = baseline.join("report.json");
    let mut candidate: BenchmarkReport = read(&baseline_path);
    let time = &mut candidate.cases[0]
        .evaluation
        .metrics
        .as_mut()
        .unwrap()
        .clearance_time_s;
    *time = Some(time.unwrap() + 0.5);
    let candidate = BenchmarkReport::new(candidate.suite, candidate.cases).unwrap();
    let current = directory.path("current.json");
    write(&current, &candidate);
    let output = directory.path("regression");
    assert!(
        !cli(&[
            "benchmark",
            "compare",
            "--baseline",
            text(&baseline_path),
            "--current",
            text(&current),
            "-o",
            text(&output)
        ])
        .status
        .success()
    );
    assert_eq!(
        read::<Value>(&output.join("comparison.json"))["regressed"],
        1
    );
    assert!(
        fs::read_to_string(output.join("comparison.md"))
            .unwrap()
            .contains("REGRESSED")
    );
    let policy = directory.path("policy.json");
    write(
        &policy,
        &json!({"metrics": {"clearance_time_s": {"absolute": 0.5, "relative": 0.0}}}),
    );
    let accepted = directory.path("within-tolerance");
    success(&[
        "benchmark",
        "compare",
        "--baseline",
        text(&baseline_path),
        "--current",
        text(&current),
        "--policy",
        text(&policy),
        "-o",
        text(&accepted),
    ]);
    write(
        &policy,
        &json!({"metrics": {"clearance_time_s": {"absolute": -1, "relative": 0}}}),
    );
    let invalid = directory.path("invalid");
    assert!(
        !cli(&[
            "benchmark",
            "compare",
            "--baseline",
            text(&baseline_path),
            "--current",
            text(&current),
            "--policy",
            text(&policy),
            "-o",
            text(&invalid)
        ])
        .status
        .success()
    );
    assert!(!invalid.exists());
}

#[test]
fn changed_inputs_are_incompatible_even_when_the_candidate_passes() {
    let directory = Directory::new();
    let baseline = run_suite(&directory, "baseline", &small_suite());
    let mut suite = small_suite();
    suite.cases[0].evaluation.thresholds.max_clearance_time_s = Some(20.0);
    let path = directory.path("changed.json");
    write(&path, &suite);
    let output = directory.path("candidate");
    assert!(
        !cli(&[
            "benchmark",
            "run",
            "--suite",
            text(&path),
            "--baseline",
            text(&baseline.join("report.json")),
            "-o",
            text(&output)
        ])
        .status
        .success()
    );
    assert_eq!(read::<Value>(&output.join("report.json"))["failed"], 0);
    assert_eq!(
        read::<Value>(&output.join("comparison.json"))["incompatible"],
        1
    );
    let mut corrupt: Value = read(&baseline.join("report.json"));
    corrupt["passed"] = json!(10);
    let bad = directory.path("corrupt.json");
    write(&bad, &corrupt);
    let rejected = directory.path("rejected");
    assert!(
        !cli(&[
            "benchmark",
            "run",
            "--baseline",
            text(&bad),
            "-o",
            text(&rejected)
        ])
        .status
        .success()
    );
    assert!(!rejected.exists());
}

#[test]
fn generated_evaluations_freeze_with_provenance_and_source_verification() {
    let directory = Directory::new();
    let config = directory.path("generation.json");
    write(
        &config,
        &json!({"seeds": [73], "generation": {"width": 4, "height": 4, "floors": 1, "agents_per_floor": 1, "duration_s": 0.2}}),
    );
    let evaluation = directory.path("evaluation");
    success(&[
        "evaluate",
        "--config",
        text(&config),
        "-o",
        text(&evaluation),
    ]);
    let frozen = directory.path("frozen.json");
    success(&[
        "benchmark",
        "freeze",
        "--evaluation",
        text(&evaluation),
        "--name",
        "generated-73",
        "-o",
        text(&frozen),
    ]);
    let suite: BenchmarkSuite = read(&frozen);
    assert!(suite.origin.is_some());
    let source_path = evaluation.join("case-0000/scenario.chy");
    assert_eq!(
        suite.cases[0].source,
        fs::read_to_string(&source_path).unwrap()
    );
    let output = run_suite(&directory, "frozen-run", &suite);
    assert_eq!(
        fs::read_to_string(output.join("case-0000/scenario.chy")).unwrap(),
        suite.cases[0].source
    );
    let mut scenario = chiyoda_core::parse(&suite.cases[0].source).unwrap();
    scenario.seed = scenario.seed.wrapping_add(1);
    fs::write(&source_path, chiyoda_core::format_scenario(&scenario)).unwrap();
    let rejected = directory.path("edited.json");
    assert!(
        !cli(&[
            "benchmark",
            "freeze",
            "--evaluation",
            text(&evaluation),
            "--name",
            "edited",
            "-o",
            text(&rejected)
        ])
        .status
        .success()
    );
    assert!(!rejected.exists());
    fs::remove_file(&source_path).unwrap();
    assert!(
        !cli(&[
            "benchmark",
            "freeze",
            "--evaluation",
            text(&evaluation),
            "--name",
            "missing",
            "-o",
            text(&rejected)
        ])
        .status
        .success()
    );
    assert!(!rejected.exists());
}

#[test]
fn negative_fixtures_are_reported_without_fake_telemetry_or_replay_links() {
    let directory = Directory::new();
    let mut suite = small_suite();
    suite.cases[0].source = include_str!("../../../examples/generation/blocked.chy").into();
    suite.cases[0].expected = chiyoda_core::evaluation::ExpectedReachability::Unreachable;
    let baseline = run_suite(&directory, "baseline", &suite);
    let output = directory.path("candidate");
    success(&[
        "benchmark",
        "run",
        "--baseline",
        text(&baseline.join("report.json")),
        "-o",
        text(&output),
    ]);
    assert!(!output.join("case-0000/run.json").exists());
    for artifact in ["report.md", "comparison.md"] {
        assert!(
            !fs::read_to_string(output.join(artifact))
                .unwrap()
                .contains("chiyoda-replay")
        );
    }
    assert_eq!(
        read::<Value>(&output.join("comparison.json"))["unchanged"],
        1
    );
}
