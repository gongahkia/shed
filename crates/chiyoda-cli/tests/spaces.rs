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
            "chiyoda-cli-test-{}-{}",
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
fn text(path: &Path) -> &str {
    path.to_str().unwrap()
}
fn read_json(path: &Path) -> Value {
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn generated_spaces_check_and_existing_outputs_are_preserved() {
    let directory = Directory::new();
    let output = directory.path("space");
    let args = [
        "generate-space",
        "--seed",
        "73",
        "--preset",
        "bottleneck",
        "--width",
        "4",
        "--height",
        "4",
        "--floors",
        "1",
        "-o",
        text(&output),
    ];
    let result = cli(&args);
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    let source = output.join("scenario.chy");
    assert!(
        cli(&["check", text(&source), "--geometry"])
            .status
            .success()
    );
    let before = fs::read(&source).unwrap();
    assert!(!cli(&args).status.success());
    assert_eq!(before, fs::read(&source).unwrap());
    assert_eq!(read_json(&output.join("generation.json"))["seed"], 73);
    let legacy = directory.path("legacy.chy");
    assert!(
        cli(&["generate", "--seed", "73", "-o", text(&legacy)])
            .status
            .success()
    );
    assert!(cli(&["check", text(&legacy)]).status.success());
}

#[test]
fn rules_command_outputs_a_complete_editable_catalog() {
    let output = cli(&["space-rules", "--preset", "corridors"]);
    assert!(output.status.success());
    let value: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(value["preset"], "corridors");
    assert_eq!(value["modules"].as_array().unwrap().len(), 32);
}

#[test]
fn failed_evaluations_exit_nonzero_and_retain_source_manifest_bundle_and_reports() {
    let directory = Directory::new();
    let config = directory.path("suite.json");
    fs::write(&config, serde_json::to_vec(&json!({ "seeds": [73], "generation": { "width": 4, "height": 4, "floors": 1, "agents_per_floor": 1, "duration_s": 0.2, "timestep_s": 0.2 }, "evaluation": { "thresholds": { "min_evacuated_fraction": 1.0 } } })).unwrap()).unwrap();
    let output = directory.path("evaluation");
    let result = cli(&["evaluate", "--config", text(&config), "-o", text(&output)]);
    assert!(!result.status.success());
    let report = read_json(&output.join("report.json"));
    assert_eq!(report["failed"], 1);
    assert_eq!(report["cases"][0]["replay_verified"], true);
    for artifact in ["scenario.chy", "generation.json", "run.json", "report.json"] {
        assert!(output.join("case-0000").join(artifact).exists());
    }
    assert!(output.join("config.json").exists());
    assert!(output.join("progress.json").exists());
}

#[test]
fn expected_unreachable_fixture_passes_without_a_runtime_bundle() {
    let directory = Directory::new();
    let fixture = directory.path("blocked.chy");
    fs::write(&fixture, "scenario \"blocked\"\nseed 1\nduration 10s\ntimestep 100ms\nsurface floor at (0m, 0m, 0m) size (10m, 10m)\nobstacle wall on floor at (4m, 0m, 0m) size (2m, 10m)\nexit out on floor at (9m, 5m, 0m) width 1m\nagents people count 1 on floor at (1m, 5m, 0m) to out speed 1m/s radius 0.3m height 1.7m\n").unwrap();
    let output = directory.path("evaluation");
    let result = cli(&[
        "evaluate",
        "--cases",
        "0",
        "--unreachable-fixture",
        text(&fixture),
        "-o",
        text(&output),
    ]);
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    assert_eq!(read_json(&output.join("report.json"))["passed"], 1);
    assert!(!output.join("case-0000/run.json").exists());
    assert!(
        !cli(&["check", text(&fixture), "--geometry"])
            .status
            .success()
    );
}

#[test]
fn exhausted_generation_retains_attempts_and_invalid_ranges_do_not_create_outputs() {
    let directory = Directory::new();
    let config = directory.path("impossible.json");
    fs::write(&config, serde_json::to_vec(&json!({ "max_attempts": 2, "pins": [{ "floor": 0, "x": 0, "y": 2, "allowed_tiles": ["solid"] }] })).unwrap()).unwrap();
    let output = directory.path("space");
    assert!(
        !cli(&[
            "generate-space",
            "--config",
            text(&config),
            "--seed",
            "1",
            "-o",
            text(&output)
        ])
        .status
        .success()
    );
    assert_eq!(
        read_json(&output.join("generation-failure.json"))["attempts"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
    let invalid = directory.path("invalid");
    assert!(
        !cli(&[
            "evaluate",
            "--seed",
            "18446744073709551615",
            "--cases",
            "2",
            "-o",
            text(&invalid)
        ])
        .status
        .success()
    );
    assert!(!invalid.exists());
}
