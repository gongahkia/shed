use chiyoda_core::{RunOptions, decode_scenario, run};

#[test]
fn opposing_walkers_near_a_wall_keep_clearance_throughout_their_steps() {
    let scenario =
        decode_scenario(include_str!("fixtures/movement/wall-counterflow.json")).unwrap();
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    assert_eq!(bundle.metrics.evacuated_agents, 2);
    let motion = bundle.metrics.movement_metrics.unwrap();
    assert!(
        motion
            .on_surface_clearance_audit
            .unwrap()
            .maximum_disc_overlap_m
            < 1e-8
    );
    let swept = motion
        .swept_on_surface_clearance_audit
        .unwrap()
        .maximum_swept_disc_overlap_m;
    assert!(swept < 1e-8, "swept overlap {swept}");
}

#[test]
fn exactly_opposing_walkers_can_pass_beside_a_wall_without_overlapping() {
    let scenario = decode_scenario(include_str!(
        "fixtures/movement/symmetric-wall-counterflow.json"
    ))
    .unwrap();
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    assert_eq!(bundle.metrics.evacuated_agents, 2);
    let motion = bundle.metrics.movement_metrics.unwrap();
    assert!(
        motion
            .on_surface_clearance_audit
            .unwrap()
            .maximum_disc_overlap_m
            < 1e-8
    );
    assert!(
        motion
            .swept_on_surface_clearance_audit
            .unwrap()
            .maximum_swept_disc_overlap_m
            < 1e-8
    );
}

#[test]
fn opposing_groups_round_a_shared_virtual_corner_without_stopping_or_overlapping() {
    let scenario =
        decode_scenario(include_str!("fixtures/movement/corner-counterflow.json")).unwrap();
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )
    .unwrap();
    assert_eq!(bundle.metrics.evacuated_agents, 8);
    let motion = bundle.metrics.movement_metrics.as_ref().unwrap();
    assert!(
        motion
            .on_surface_clearance_audit
            .as_ref()
            .unwrap()
            .maximum_disc_overlap_m
            < 1e-8
    );
    assert!(
        motion
            .swept_on_surface_clearance_audit
            .as_ref()
            .unwrap()
            .maximum_swept_disc_overlap_m
            < 1e-8
    );
    assert!(motion.local_avoidance_constraint_fallback_steps.unwrap() > 0);
    let guard_events = bundle
        .events
        .iter()
        .filter(|event| event.kind == "local_motion_limited_for_clearance")
        .count();
    assert!(guard_events > 0);
    assert_eq!(
        motion.swept_motion_guard_steps,
        Some(u64::try_from(guard_events).unwrap())
    );
    chiyoda_core::verify_run_bundle(&bundle).unwrap();
    let mut corrupted = bundle;
    *corrupted
        .metrics
        .movement_metrics
        .as_mut()
        .unwrap()
        .swept_motion_guard_steps
        .as_mut()
        .unwrap() += 1;
    corrupted.bundle_hash = chiyoda_core::bundle::bundle_hash(&corrupted);
    assert!(chiyoda_core::verify_run_bundle(&corrupted).is_err());
}
