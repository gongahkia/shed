use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    process::{Command, Output},
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "chiyoda-journeys-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        Self(root)
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
fn text(path: &Path) -> &str {
    path.to_str().unwrap()
}
fn cli(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_chiyoda"))
        .args(args)
        .output()
        .unwrap()
}
fn success(args: &[&str]) {
    let output = cli(args);
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
}
fn read(path: &Path) -> Value {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}
fn write(path: &Path, value: &Value) {
    fs::write(path, serde_json::to_vec_pretty(value).unwrap()).unwrap();
}

fn source(directory: &Directory, trace_every: &str) -> PathBuf {
    let input = directory.path("scenario.json");
    fs::write(
        &input,
        include_str!("../../chiyoda-core/tests/fixtures/json-free-walk.json"),
    )
    .unwrap();
    let output = directory.path("source-run");
    success(&[
        "run",
        text(&input),
        "--trace-every",
        trace_every,
        "-o",
        text(&output),
    ]);
    output.join("run.json")
}

#[test]
fn standalone_analysis_saves_portable_evidence_and_reconstructs_every_isolated_control() {
    let directory = Directory::new();
    let bundle = source(&directory, "1");
    let original = fs::read(&bundle).unwrap();
    let output = directory.path("diagnosis");
    success(&[
        "diagnose",
        "run",
        text(&bundle),
        "-o",
        text(&output),
        "--isolate-all",
        "--replay",
    ]);
    let report_path = output.join("journeys.json");
    let report = read(&report_path);
    assert_eq!(report["agents"].as_array().unwrap().len(), 1);
    assert_eq!(report["isolated_controls"][0]["agent"], "person:0");
    assert_eq!(report["isolated_controls"][0]["status"], "completed");
    assert_eq!(report["isolated_controls"][0]["replay_verified"], true);
    assert!(output.join("isolated-0000/run.json").exists());
    assert!(
        fs::read_to_string(output.join("journeys.md"))
            .unwrap()
            .contains("--at-step")
    );
    assert!(
        fs::read_to_string(output.join("journeys.csv"))
            .unwrap()
            .contains("static_centre_route_s")
    );
    success(&["diagnose", "verify", text(&report_path), "--replay"]);
    assert_eq!(original, fs::read(&bundle).unwrap());
    assert!(
        !cli(&["diagnose", "run", text(&bundle), "-o", text(&output)])
            .status
            .success()
    );
}

#[test]
fn resealed_false_observations_and_changed_control_sources_do_not_verify() {
    let directory = Directory::new();
    let bundle = source(&directory, "1");
    let output = directory.path("diagnosis");
    success(&[
        "diagnose",
        "run",
        text(&bundle),
        "-o",
        text(&output),
        "--isolate-all",
    ]);
    let path = output.join("journeys.json");
    let bytes = fs::read(&path).unwrap();
    let mut report: chiyoda_core::journeys::JourneyReport = serde_json::from_slice(&bytes).unwrap();
    report.agents[0].walking_distance_m += 1.0;
    report.report_hash.clear();
    report.report_hash = chiyoda_core::bundle::canonical_hash(&report);
    fs::write(&path, serde_json::to_vec(&report).unwrap()).unwrap();
    assert!(!cli(&["diagnose", "verify", text(&path)]).status.success());
    fs::write(&path, bytes).unwrap();
    let control_path = output.join("isolated-0000/run.json");
    let mut control: chiyoda_core::RunBundle =
        serde_json::from_slice(&fs::read(&control_path).unwrap()).unwrap();
    control.scenario.scenario.seed += 1;
    control.bundle_hash = chiyoda_core::bundle_hash(&control);
    fs::write(control_path, serde_json::to_vec(&control).unwrap()).unwrap();
    assert!(!cli(&["diagnose", "verify", text(&path)]).status.success());
}

#[test]
fn sparse_traces_and_invalid_options_fail_without_creating_a_diagnostic_directory() {
    let directory = Directory::new();
    let bundle = source(&directory, "10");
    let output = directory.path("diagnosis");
    let result = cli(&["diagnose", "run", text(&bundle), "-o", text(&output)]);
    assert!(!result.status.success());
    assert!(String::from_utf8_lossy(&result.stderr).contains("trace-every 1"));
    assert!(!output.exists());
    let options = directory.path("options.json");
    write(&options, &json!({"isolated_controls":{"max_agents":0}}));
    assert!(
        !cli(&[
            "diagnose",
            "run",
            text(&bundle),
            "-o",
            text(&output),
            "--options",
            text(&options)
        ])
        .status
        .success()
    );
    assert!(!output.exists());
}

#[test]
fn requested_budget_limited_controls_remain_explicit_and_verify_without_fabricated_runs() {
    let directory = Directory::new();
    let bundle = source(&directory, "1");
    let output = directory.path("diagnosis");
    let options = directory.path("options.json");
    write(
        &options,
        &json!({"isolated_controls":{"all_agents":true,"max_simulated_agent_steps":1}}),
    );
    success(&[
        "diagnose",
        "run",
        text(&bundle),
        "-o",
        text(&output),
        "--options",
        text(&options),
    ]);
    let report = output.join("journeys.json");
    assert_eq!(
        read(&report)["isolated_controls"][0]["status"],
        "budget_limit"
    );
    assert!(read(&report)["isolated_controls"][0]["elapsed_journey_s"].is_null());
    assert!(!output.join("isolated-0000").exists());
    success(&["diagnose", "verify", text(&report), "--replay"]);
}

#[test]
fn studies_save_and_verify_journey_artifacts_and_expose_policy_distance_comparisons() {
    let directory = Directory::new();
    let config = directory.path("study.json");
    write(
        &config,
        &json!({"study_version":"1","name":"journey-study",
            "environment":{"duration_s":0.2,"timestep_s":0.2},
            "patterns":[{"id":"halls","preset":"concourse"}],
            "scales":[{"id":"small","width":4,"height":4,"floors":1,"service_rate_multiplier":1}],
            "loads":[{"id":"light","population":{"kind":"fixed_per_floor","agents":2}}],
            "policies":[{"id":"quiet"},{"id":"same"}],"seeds":[73],
            "journey_diagnostics":{"isolated_controls":{"all_agents":true,"max_agents":1}}
        }),
    );
    let output = directory.path("study");
    success(&[
        "study",
        "run",
        "--config",
        text(&config),
        "-o",
        text(&output),
    ]);
    let report = output.join("report.json");
    success(&["study", "verify", text(&report), "--replay"]);
    let result = read(&report);
    assert_eq!(result["cases"][0]["journeys"]["controls_run"], 1);
    assert_eq!(result["cases"][0]["journeys"]["controls_requested"], 2);
    assert!(
        result["comparisons"][0]["deltas"]
            .get("mean_walking_distance_m")
            .is_some()
    );
    let markdown = fs::read_to_string(output.join("report.md")).unwrap();
    assert!(markdown.contains("Observed changes between arms"));
    assert!(markdown.contains("case-0000/journeys.md"));
    let path = output.join("case-0000/journeys.json");
    let mut altered = read(&path);
    altered["agents"][0]["walking_distance_m"] = json!(50);
    write(&path, &altered);
    assert!(!cli(&["study", "verify", text(&report)]).status.success());
}
