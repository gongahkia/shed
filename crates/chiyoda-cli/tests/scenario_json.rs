use chiyoda_core::{CanonicalScenario, RunBundle};
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    process::{Command, Output},
    sync::atomic::{AtomicU64, Ordering},
};

const INPUT: &str = include_str!("../../chiyoda-core/tests/fixtures/json-free-walk.json");
const LEGACY: &str = include_str!("../../chiyoda-core/tests/fixtures/legacy-0.42-run.json");
static NEXT: AtomicU64 = AtomicU64::new(0);
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "chiyoda-json-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
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

#[test]
fn json_checks_normalizes_runs_and_replays_through_the_cli() {
    let directory = Directory::new();
    let input = directory.path("scenario.json");
    let value: Value = serde_json::from_str(INPUT).unwrap();
    let original = value.to_string();
    fs::write(&input, &original).unwrap();
    success(&["check", text(&input), "--geometry"]);
    assert!(!cli(&["format", text(&input), "--check"]).status.success());
    let formatted = directory.path("formatted.json");
    success(&["format", text(&input), "-o", text(&formatted)]);
    success(&["format", text(&formatted), "--check"]);
    assert_eq!(fs::read_to_string(&input).unwrap(), original);
    let first = directory.path("first");
    let second = directory.path("second");
    for (scenario, output) in [(&input, &first), (&formatted, &second)] {
        success(&[
            "run",
            text(scenario),
            "-o",
            text(output),
            "--trace-every",
            "1",
        ]);
        success(&["replay", text(&output.join("run.json"))]);
        assert!(output.join("scenario.json").exists());
    }
    let read = |root: &Path| -> RunBundle {
        serde_json::from_str(&fs::read_to_string(root.join("run.json")).unwrap()).unwrap()
    };
    let original = read(&first);
    let normalized = read(&second);
    assert_eq!(
        original, normalized,
        "JSON whitespace and object order do not change execution"
    );
    assert_eq!(original.scenario.schema_version, "1");
}

#[test]
fn removed_language_and_invalid_json_inputs_are_rejected_before_output_creation() {
    let directory = Directory::new();
    let input = directory.path("invalid.json");
    let output = directory.path("run");
    let mut future: Value = serde_json::from_str(INPUT).unwrap();
    future["schema_version"] = json!("999");
    let mut bare: Value = serde_json::from_str(INPUT).unwrap();
    bare = bare["scenario"].clone();
    let mut unknown: Value = serde_json::from_str(INPUT).unwrap();
    unknown["scenario"]["agents"][0]["speeed_mps"] = json!(1.2);
    for source in [
        "scenario \"legacy\"\nseed 1\n".to_owned(),
        future.to_string(),
        bare.to_string(),
        unknown.to_string(),
        "{".into(),
    ] {
        fs::write(&input, source).unwrap();
        assert!(!cli(&["check", text(&input)]).status.success());
        assert!(
            !cli(&["run", text(&input), "-o", text(&output)])
                .status
                .success()
        );
        assert!(!output.exists());
    }
    assert!(
        !cli(&["compile", text(&input), "-o", text(&output)])
            .status
            .success()
    );
    assert!(!output.exists());
}

#[test]
fn authentic_legacy_bundles_export_current_scenarios_without_overwriting_or_accepting_corruption() {
    let directory = Directory::new();
    let bundle = directory.path("legacy-run.json");
    fs::write(&bundle, LEGACY).unwrap();
    let output = directory.path("scenario.json");
    let args = ["extract-scenario", text(&bundle), "-o", text(&output)];
    success(&args);
    success(&["check", text(&output)]);
    let exported: CanonicalScenario =
        serde_json::from_str(&fs::read_to_string(&output).unwrap()).unwrap();
    let old: RunBundle = serde_json::from_str(LEGACY).unwrap();
    assert_eq!(exported.schema_version, "1");
    assert_eq!(exported.scenario, old.scenario.scenario);
    let before = fs::read(&output).unwrap();
    assert!(!cli(&args).status.success());
    assert_eq!(before, fs::read(&output).unwrap());
    let mut corrupt = old;
    corrupt.scenario_hash = "corrupt".into();
    corrupt.bundle_hash = chiyoda_core::bundle_hash(&corrupt);
    fs::write(&bundle, serde_json::to_string(&corrupt).unwrap()).unwrap();
    let rejected = directory.path("rejected.json");
    assert!(
        !cli(&["extract-scenario", text(&bundle), "-o", text(&rejected)])
            .status
            .success()
    );
    assert!(!rejected.exists());
}
