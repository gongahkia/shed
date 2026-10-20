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
