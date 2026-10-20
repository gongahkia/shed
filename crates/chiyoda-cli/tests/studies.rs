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
        let path = std::env::temp_dir().join(format!(
            "chiyoda-study-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn path(&self, file: &str) -> PathBuf {
        self.0.join(file)
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
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}
fn input() -> Value {
    json!({"study_version":"1","name":"study-cli", "environment":{"duration_s":0.2,"timestep_s":0.2},
        "patterns":[{"id":"halls","preset":"concourse"}],
        "scales":[{"id":"small","width":4,"height":4,"floors":1,"service_rate_multiplier":1.0}],
        "loads":[{"id":"light","population":{"kind":"fixed_per_floor","agents":2}}],
        "policies":[{"id":"quiet"},{"id":"same"}],"seeds":[73,74]})
}
fn save(path: &Path, value: &Value) {
    fs::write(path, serde_json::to_string_pretty(value).unwrap()).unwrap();
}

#[test]
fn templates_are_editable_and_existing_configs_and_outputs_are_preserved() {
    let directory = Directory::new();
    let config = directory.path("study.json");
    let args = ["study", "template", "-o", text(&config)];
    success(&args);
    success(&["study", "check", "--config", text(&config)]);
    let before = fs::read(&config).unwrap();
    assert!(!cli(&args).status.success());
    assert_eq!(before, fs::read(&config).unwrap());
    let mut invalid = input();
    invalid["scales"][0]["width"] = json!(17);
    save(&config, &invalid);
    let output = directory.path("run");
    assert!(
        !cli(&[
            "study",
            "run",
            "--config",
            text(&config),
            "-o",
            text(&output)
        ])
        .status
        .success()
    );
    assert!(!output.exists());
    save(&config, &input());
    fs::create_dir(&output).unwrap();
    fs::write(output.join("keep"), "original").unwrap();
    assert!(
        !cli(&[
            "study",
            "run",
            "--config",
            text(&config),
            "-o",
            text(&output)
        ])
        .status
        .success()
    );
    assert_eq!(fs::read_to_string(output.join("keep")).unwrap(), "original");
}

#[test]
fn matrix_runs_write_reports_csv_and_replayable_inputs_and_reject_artifact_corruption() {
    let directory = Directory::new();
    let config = directory.path("study.json");
    let output = directory.path("run");
    save(&config, &input());
    success(&[
        "study",
        "run",
        "--config",
        text(&config),
        "-o",
        text(&output),
    ]);
    let report = output.join("report.json");
    let args = ["study", "verify", text(&report), "--replay"];
    success(&args);
    let saved = read(&report);
    assert_eq!(saved["requested"], 4);
    assert_eq!(saved["requested_layouts"], 2);
    assert_eq!(saved["generated_layouts"], 2);
    assert_eq!(
        read(&output.join("progress.json"))
            .as_array()
            .unwrap()
            .len(),
        4
    );
    assert_eq!(
        fs::read_to_string(output.join("observations.csv"))
            .unwrap()
            .lines()
            .count(),
        5
    );
    assert!(
        fs::read_to_string(output.join("report.md"))
            .unwrap()
            .contains("case-0000/run.json")
    );
    for artifact in [
        "scenario.json",
        "generation.json",
        "generated-scenario.json",
        "run.json",
        "report.json",
    ] {
        let path = output.join("case-0000").join(artifact);
        let before = fs::read(&path).unwrap();
        if artifact == "scenario.json" || artifact == "generated-scenario.json" {
            let mut after = before.clone();
            after.push(b' ');
            fs::write(&path, after).unwrap();
        } else {
            let mut after = read(&path);
            after[if artifact == "generation.json" {
                "seed"
            } else if artifact == "run.json" {
                "bundle_hash"
            } else {
                "passed"
            }] = json!("corrupt");
            save(&path, &after);
        }
        assert!(!cli(&args).status.success(), "{artifact}");
        fs::write(path, before).unwrap();
    }
    success(&args);
    fs::remove_file(output.join("case-0001/run.json")).unwrap();
    assert!(!cli(&args).status.success());
}

#[test]
fn failed_thresholds_finish_the_matrix_and_keep_incomplete_results() {
    let directory = Directory::new();
    let config = directory.path("study.json");
    let output = directory.path("run");
    let mut value = input();
    value["evaluation"] =
        json!({"thresholds":{"min_evacuated_fraction":1.0,"max_clearance_time_s":0.2}});
    save(&config, &value);
    assert!(
        !cli(&[
            "study",
            "run",
            "--config",
            text(&config),
            "-o",
            text(&output)
        ])
        .status
        .success()
    );
    let report = output.join("report.json");
    let saved = read(&report);
    assert_eq!(saved["failed"], 4);
    assert_eq!(saved["groups"][0]["measured"], 2);
    assert_eq!(saved["groups"][0]["fully_evacuated"], 0);
    assert!(
        saved["groups"][0]["metrics"]
            .get("clearance_time_s")
            .is_none()
    );
    assert!(output.join("case-0003/run.json").exists());
    success(&["study", "verify", text(&report), "--replay"]);
}

#[test]
fn impossible_population_and_generation_failures_remain_verifiable_without_simulation() {
    for generation_failure in [false, true] {
        let directory = Directory::new();
        let config = directory.path("study.json");
        let output = directory.path("run");
        let mut value = input();
        if generation_failure {
            value["environment"]["max_attempts"] = json!(2);
            value["patterns"][0]["pins"] =
                json!([{"floor":0,"x":0,"y":0,"allowed_tiles":["hall_nesw"]}]);
        } else {
            value["loads"][0]["population"]["agents"] = json!(200);
            value["loads"][0]["placement"] = json!("arrival_hall");
        }
        save(&config, &value);
        assert!(
            !cli(&[
                "study",
                "run",
                "--config",
                text(&config),
                "-o",
                text(&output)
            ])
            .status
            .success()
        );
        let report = output.join("report.json");
        assert_eq!(read(&report)["failed"], 4);
        assert!(!output.join("case-0000/scenario.json").exists());
        assert_eq!(
            output.join("case-0000/generated-scenario.json").exists(),
            !generation_failure
        );
        if generation_failure {
            assert_eq!(read(&report)["generation_attempts"], 4);
        }
        success(&["study", "verify", text(&report)]);
    }
}

#[test]
fn anchored_policy_sources_are_resolved_and_verified_in_archived_scenarios() {
    let directory = Directory::new();
    let config = directory.path("study.json");
    let output = directory.path("run");
    let mut value = input();
    value["environment"]["duration_s"] = json!(0.4);
    value["seeds"] = json!([73]);
    value["policies"] = json!([{"id":"rumor","messages":[{
        "id":"rumor","source":"Peer","surface":"floor_0","origin":{"anchor":"arrival_hall"},
        "claim":{"ExitAvailability":{"exit":"primary","open":false}},"truthful":false,"at_s":0.2,"reach_m":10000.0,"trust":1.0
    }]}]);
    save(&config, &value);
    success(&[
        "study",
        "run",
        "--config",
        text(&config),
        "-o",
        text(&output),
    ]);
    success(&[
        "study",
        "verify",
        text(&output.join("report.json")),
        "--replay",
    ]);
    let scenario = read(&output.join("case-0000/scenario.json"));
    assert!(
        scenario["scenario"]["messages"][0]["origin"]["x_m"]
            .as_f64()
            .unwrap()
            > 0.0
    );
    let run = read(&output.join("case-0000/run.json"));
    assert!(
        run["metrics"]["information_delivery"]["rumor"]["accepted_agents"]
            .as_u64()
            .unwrap()
            > 0
    );
}
