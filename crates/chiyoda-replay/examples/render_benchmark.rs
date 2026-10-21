//! Reproducible steady-state CPU renderer measurement, using a valid simulated population.
use anyhow::Result;
use chiyoda_core::{RunOptions, decode_scenario, run};
use chiyoda_replay::{
    HEIGHT, WIDTH,
    render::{Options, Renderer},
};
use std::{hint::black_box, time::Instant};
fn main() -> Result<()> {
    let count = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "1000".into())
        .parse::<u32>()?;
    let frames = std::env::args()
        .nth(2)
        .unwrap_or_else(|| "60".into())
        .parse::<usize>()?;
    anyhow::ensure!(
        (1..=10000).contains(&count) && (10..=1000).contains(&frames),
        "count 1..10000, frames 10..1000"
    );
    let mut scenario = decode_scenario(include_str!("../src/fixtures/main/project.json"))?;
    scenario.name = format!("render benchmark / {count} people");
    scenario.duration_s = 0.5;
    scenario.surfaces[0].width_m = 100.0;
    scenario.surfaces[0].depth_m = 100.0;
    scenario.agents[0].count = count;
    scenario.agents[0].at.y_m = 1.0;
    scenario.agents[0].radius_m = 0.25;
    scenario.exits[0].at.x_m = 95.0;
    scenario.exits[0].at.y_m = 95.0;
    let bundle = run(
        &scenario,
        RunOptions {
            trace_every_steps: 1,
        },
    )?;
    let mut renderer = Renderer::new();
    let mut buffer = vec![0; WIDTH * HEIGHT];
    renderer.render(&mut buffer, Some(&bundle), Options::default());
    let mut times = vec![];
    for _ in 0..frames {
        let start = Instant::now();
        renderer.render(&mut buffer, Some(&bundle), Options::default());
        black_box(&buffer);
        times.push(start.elapsed().as_secs_f64() * 1000.0);
    }
    times.sort_by(f64::total_cmp);
    println!(
        "{}",
        serde_json::json!({"people":count,"frames":frames,"width":WIDTH,"height":HEIGHT,"median_ms":times[frames/2],"p95_ms":times[(frames*95/100).min(frames-1)],"maximum_ms":times[frames-1],"scope":"steady-state CPU rendering including framebuffer copy, excludes simulation, verification and window presentation"})
    );
    Ok(())
}
