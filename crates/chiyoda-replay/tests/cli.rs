use chiyoda_replay::load_watch_scenario;
use serde_json::Value;
use std::{
    fs,
    process::Command,
    sync::atomic::{AtomicU64, Ordering},
};
struct Directory(std::path::PathBuf);
impl Directory {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "chiyoda-replay-cli-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&path).unwrap();
        for (file, cadence) in [("a.json", 1), ("b.json", 2)] {
            let bundle =
                load_watch_scenario(include_str!("../src/fixtures/main/project.json"), cadence)
                    .unwrap();
            fs::write(path.join(file), serde_json::to_vec(&bundle).unwrap()).unwrap();
        }
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn run(directory: &Directory, args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_chiyoda-replay"))
        .current_dir(&directory.0)
        .env_remove("DISPLAY")
        .env_remove("WAYLAND_DISPLAY")
        .args(args)
        .output()
        .unwrap()
}
#[test]
fn cli_exports_annotated_evidence_and_synchronized_comparison_without_a_display() {
    let directory = Directory::new();
    let source = fs::read(directory.0.join("a.json")).unwrap();
    let output = run(
        &directory,
        &[
            "a.json",
            "--agent",
            "passengers:0",
            "--at-step",
            "2",
            "--zoom",
            "2",
            "--pan-x",
            "-50",
            "--export-png",
            "person.png",
        ],
    );
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let person: Value =
        serde_json::from_slice(&fs::read(directory.0.join("person.png.json")).unwrap()).unwrap();
    assert_eq!(person["inspection"]["recorded_step"], 2);
    assert_eq!(person["request"]["camera"]["zoom"], 2.0);
    assert_eq!(person["request"]["camera"]["pan_x_pixels"], -50.0);
    let output = run(
        &directory,
        &[
            "a.json",
            "--compare",
            "b.json",
            "--at-time",
            "0.75",
            "--surface",
            "concourse",
            "--export-png",
            "compare.png",
        ],
    );
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let comparison: Value =
        serde_json::from_slice(&fs::read(directory.0.join("compare.png.json")).unwrap()).unwrap();
    assert_eq!(comparison["comparison"]["baseline"]["recorded_step"], 1);
    assert_eq!(comparison["comparison"]["candidate"]["recorded_step"], 0);
    let output = run(
        &directory,
        &[
            "a.json",
            "--resource",
            "exit:street",
            "--export-png",
            "resource.png",
        ],
    );
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(fs::read(directory.0.join("a.json")).unwrap(), source);
}
#[test]
fn cli_rejects_bad_selectors_and_export_combinations_before_creating_artifacts() {
    let directory = Directory::new();
    for args in [
        vec!["a.json", "--agent", "missing", "--export-png", "bad.png"],
        vec![
            "a.json",
            "--resource",
            "gate:street",
            "--export-png",
            "bad.png",
        ],
        vec![
            "a.json",
            "--compare",
            "b.json",
            "--at-time",
            "99",
            "--export-png",
            "bad.png",
        ],
        vec![
            "a.json",
            "--agent",
            "passengers:0",
            "--export-gif",
            "bad.gif",
        ],
        vec![
            "a.json",
            "--compare",
            "b.json",
            "--allow-legacy-hash-only",
            "--export-png",
            "bad.png",
        ],
        vec!["a.json", "--zoom", "33", "--export-png", "bad.png"],
        vec![
            "a.json",
            "--compare",
            "b.json",
            "--agent",
            "passengers:0",
            "--trail-start-step",
            "0",
            "--export-png",
            "bad.png",
        ],
    ] {
        let output = run(&directory, &args);
        assert!(!output.status.success(), "{args:?}");
        assert!(!directory.0.join("bad.png").exists());
        assert!(!directory.0.join("bad.gif").exists());
    }
}
