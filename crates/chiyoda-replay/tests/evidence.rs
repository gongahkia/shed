use chiyoda_core::{RunBundle, bundle::bundle_hash};
use chiyoda_replay::{
    ViewMode,
    evidence::{EvidenceRequest, export_evidence, render_evidence, sha256},
    export::{gif_manifest_path, write_new_artifact},
    inspection::{ResourceKind, Selection},
    load_watch_scenario,
    navigation::CameraControl,
    render::{Camera, MapConfig, pick},
};
use std::{
    fs,
    sync::atomic::{AtomicU64, Ordering},
};
fn fixture() -> RunBundle {
    load_watch_scenario(include_str!("../src/fixtures/main/project.json"), 1).unwrap()
}
fn directory() -> std::path::PathBuf {
    static NUMBER: AtomicU64 = AtomicU64::new(0);
    std::env::temp_dir().join(format!(
        "chiyoda-replay-evidence-{}-{}",
        std::process::id(),
        NUMBER.fetch_add(1, Ordering::Relaxed)
    ))
}
fn request() -> EvidenceRequest {
    EvidenceRequest {
        step: 2,
        selection: Selection::Agent {
            id: "passengers:0".into(),
        },
        surface: None,
        view: ViewMode::Surface,
        trail_start_step: Some(1),
        camera: CameraControl::default(),
    }
}
#[test]
fn annotated_png_and_json_describe_exactly_the_same_immutable_recorded_evidence() {
    let bundle = fixture();
    let before = serde_json::to_vec(&bundle).unwrap();
    let request = request();
    let (png, manifest) = render_evidence(&bundle, &request).unwrap();
    assert_eq!(manifest.image_sha256, sha256(&png));
    assert_eq!(manifest.inspection.recorded_step, 2);
    assert_eq!(manifest.inspection.trail[0].step, 1);
    assert_eq!(manifest.selected_surface, "concourse");
    assert_eq!(manifest.bundle_sha256, bundle.bundle_hash);
    let decoder = png::Decoder::new(std::io::Cursor::new(&png));
    let reader = decoder.read_info().unwrap();
    assert_eq!(reader.info().width, 1200);
    assert_eq!(reader.info().height, 800);
    assert_eq!(serde_json::to_vec(&bundle).unwrap(), before);
    let directory = directory();
    let output = directory.join("evidence.png");
    export_evidence(&bundle, &request, &output).unwrap();
    let metadata: serde_json::Value =
        serde_json::from_slice(&fs::read(gif_manifest_path(&output)).unwrap()).unwrap();
    assert_eq!(
        metadata["image_sha256"],
        sha256(&fs::read(&output).unwrap())
    );
    assert!(export_evidence(&bundle, &request, &output).is_err());
    assert_eq!(fs::read(&output).unwrap(), png);
    fs::remove_dir_all(directory).unwrap();
}
#[test]
fn evidence_rejects_tampered_legacy_unknown_steps_and_invalid_cameras() {
    let mut bundle = fixture();
    bundle.trace[1].agents[0].x_m += 1.0;
    assert!(render_evidence(&bundle, &request()).is_err());
    let mut legacy = fixture();
    legacy.runtime_version = "0.1.0".into();
    legacy.bundle_hash = bundle_hash(&legacy);
    assert!(render_evidence(&legacy, &request()).is_err());
    let bundle = fixture();
    let mut args = request();
    args.step = 999;
    assert!(render_evidence(&bundle, &args).is_err());
    let mut args = request();
    args.camera.zoom = f64::NAN;
    assert!(render_evidence(&bundle, &args).is_err());
    let mut args = request();
    args.selection = Selection::Resource {
        resource_kind: ResourceKind::Gate,
        id: "street".into(),
    };
    assert!(render_evidence(&bundle, &args).is_err());
}
#[test]
fn transformed_hit_testing_and_shift_resource_preference_share_the_drawn_camera() {
    let bundle = fixture();
    let map = MapConfig {
        camera: CameraControl {
            zoom: 1.5,
            pan_x_pixels: 100.0,
            pan_y_pixels: 0.0,
        },
        ..MapConfig::default()
    };
    let scenario = &bundle.scenario.scenario;
    let camera = Camera::controlled(scenario, &scenario.surfaces[0], ViewMode::Surface, map);
    let person = &bundle.trace[0].agents[0];
    let at = camera.point(chiyoda_core::model::Point3 {
        x_m: person.x_m,
        y_m: person.y_m,
        z_m: person.z_m,
    });
    assert_eq!(
        pick(&bundle, 0, 0, ViewMode::Surface, map, at, false),
        Some(Selection::Agent {
            id: person.id.clone()
        })
    );
    let at = camera.point(scenario.exits[0].at);
    assert_eq!(
        pick(&bundle, 0, 0, ViewMode::Surface, map, at, true),
        Some(Selection::Resource {
            resource_kind: ResourceKind::Exit,
            id: "street".into()
        })
    );
    assert!(
        pick(
            &bundle,
            0,
            0,
            ViewMode::Surface,
            map,
            (1200.0, 800.0),
            false
        )
        .is_none()
    );
}
#[test]
fn failed_creation_never_deletes_an_existing_artifact_and_uncommitted_files_are_removed() {
    let directory = directory();
    fs::create_dir_all(&directory).unwrap();
    let target = directory.join("owned");
    {
        let _pending = write_new_artifact(&target, b"temporary").unwrap();
        assert!(target.exists());
    }
    assert!(!target.exists());
    fs::write(&target, b"existing").unwrap();
    assert!(write_new_artifact(&target, b"replacement").is_err());
    assert_eq!(fs::read(&target).unwrap(), b"existing");
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn resource_inspection_retains_operational_changes_without_inferring_a_current_queue() {
    let mut scenario =
        chiyoda_core::decode_scenario(include_str!("../src/fixtures/main/project.json")).unwrap();
    scenario.duration_s = 5.0;
    scenario
        .exit_states
        .push(chiyoda_core::model::ExitStateChange {
            id: "close-street".into(),
            exit: "street".into(),
            open: false,
            at_s: 3.0,
        });
    let bundle = chiyoda_core::run(
        &scenario,
        chiyoda_core::RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let index = chiyoda_replay::inspection::EvidenceIndex::new(&bundle);
    let selection = Selection::Resource {
        resource_kind: ResourceKind::Exit,
        id: "street".into(),
    };
    let initial = index.inspect(&bundle, 0, &selection, None).unwrap();
    assert!(
        initial
            .resource
            .unwrap()
            .latest_availability_event
            .is_none()
    );
    let end = index
        .inspect(&bundle, bundle.trace.len() - 1, &selection, None)
        .unwrap();
    let resource = end.resource.unwrap();
    let change = resource.latest_availability_event.unwrap();
    assert_eq!(change.kind, "exit_state_changed");
    assert!(change.detail.ends_with(": closed"));
    assert_eq!(resource.queue_entries_through_frame, 0);
    assert_eq!(resource.entrants_total, 0);
    assert!(end.events.iter().any(|e| e.event == change));
}
