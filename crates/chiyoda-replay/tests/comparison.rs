use chiyoda_core::{RunBundle, RunOptions, decode_scenario, run};
use chiyoda_replay::{
    ViewMode,
    comparison::{ComparisonRequest, prepare, render_comparison},
    inspection::{EvidenceIndex, Selection},
    navigation::CameraControl,
};
fn fixture(cadence: u32, count: u32, width: f64) -> RunBundle {
    let mut scenario = decode_scenario(include_str!("../src/fixtures/main/project.json")).unwrap();
    scenario.duration_s = 10.0;
    scenario.agents[0].count = count;
    scenario.surfaces[0].width_m = width;
    run(
        &scenario,
        RunOptions {
            trace_every_steps: cadence,
        },
    )
    .unwrap()
}
fn request() -> ComparisonRequest {
    ComparisonRequest {
        time_s: 0.75,
        surface: Some("concourse".into()),
        view: ViewMode::Surface,
        selection: Some(Selection::Agent {
            id: "passengers:0".into(),
        }),
        baseline_label: None,
        candidate_label: None,
        camera: CameraControl::default(),
    }
}
#[test]
fn comparison_holds_recorded_states_at_one_clock_and_fits_both_architectures() {
    let a = fixture(1, 1, 12.0);
    let b = fixture(2, 1, 30.0);
    let comparison = prepare(
        &a,
        &b,
        &request(),
        &EvidenceIndex::new(&a),
        &EvidenceIndex::new(&b),
    )
    .unwrap();
    assert_eq!(comparison.baseline.recorded_step, 1);
    assert_eq!(comparison.candidate.recorded_step, 0);
    assert!(
        comparison.baseline.recorded_time_s <= comparison.requested_time_s
            && comparison.candidate.recorded_time_s <= comparison.requested_time_s
    );
    assert!((comparison.baseline.held_for_s - 0.25).abs() < 1e-9);
    assert!((comparison.candidate.held_for_s - 0.75).abs() < 1e-9);
    assert!(comparison.same_population_inputs);
    assert!((comparison.common_camera_bounds.max_x - 30.0).abs() < 1e-9);
    let before = serde_json::to_vec(&a).unwrap();
    let (png, manifest) = render_comparison(&a, &b, &request()).unwrap();
    assert!(png.starts_with(b"\x89PNG"));
    assert_eq!(manifest.comparison.baseline.recorded_step, 1);
    assert_eq!(serde_json::to_vec(&a).unwrap(), before);
}
#[test]
fn changed_population_disables_individual_pairing_and_unfinished_clearance_stays_null() {
    let a = fixture(1, 1, 12.0);
    let b = fixture(1, 2, 12.0);
    let comparison = prepare(
        &a,
        &b,
        &request(),
        &EvidenceIndex::new(&a),
        &EvidenceIndex::new(&b),
    )
    .unwrap();
    assert!(!comparison.same_population_inputs);
    assert!(comparison.paired_completed_elapsed_difference_s.is_none());
    let mut request = request();
    request.selection = Some(Selection::Agent {
        id: "passengers:1".into(),
    });
    let comparison = prepare(
        &a,
        &b,
        &request,
        &EvidenceIndex::new(&a),
        &EvidenceIndex::new(&b),
    )
    .unwrap();
    assert!(comparison.baseline.selection_unavailable.is_some());
    assert!(comparison.candidate.inspection.is_some());
}
#[test]
fn comparison_rejects_missing_common_surfaces_bad_times_and_tampered_candidates() {
    let a = fixture(1, 1, 12.0);
    let mut b = fixture(1, 1, 12.0);
    let ai = EvidenceIndex::new(&a);
    let bi = EvidenceIndex::new(&b);
    let mut args = request();
    args.time_s = 11.0;
    assert!(prepare(&a, &b, &args, &ai, &bi).is_err());
    args.time_s = f64::NAN;
    assert!(prepare(&a, &b, &args, &ai, &bi).is_err());
    args = request();
    args.surface = Some("missing".into());
    assert!(prepare(&a, &b, &args, &ai, &bi).is_err());
    b.trace[0].agents[0].x_m += 1.0;
    assert!(render_comparison(&a, &b, &request()).is_err());
}

#[test]
fn unavailable_clearance_and_unfinished_individual_elapsed_are_never_zero_differences() {
    let baseline = fixture(1, 1, 12.0);
    let mut scenario = baseline.scenario.scenario.clone();
    scenario.duration_s = 1.0;
    let short = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    let evidence = prepare(
        &baseline,
        &short,
        &request(),
        &EvidenceIndex::new(&baseline),
        &EvidenceIndex::new(&short),
    )
    .unwrap();
    assert!(evidence.same_population_inputs);
    assert!(evidence.baseline.full_run_clearance_s.is_some());
    assert!(evidence.candidate.full_run_clearance_s.is_none());
    assert!(evidence.full_run_clearance_difference_s.is_none());
    assert!(evidence.paired_completed_elapsed_difference_s.is_none());
}
