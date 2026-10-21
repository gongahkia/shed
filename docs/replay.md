# Native replay viewer

`chiyoda-replay` is a local native viewer for either a hash-valid `run.json`
artifact or an in-memory live JSON scenario run. It visualizes the reference
runtime's recorded state; it is not a simulation engine, a facility survey, a
3D renderer, or an operational display.

```console
$ cargo run -p chiyoda-replay -- out/showcase/run.json --paused
$ cargo run -p chiyoda-replay -- out/showcase/run.json --surface concourse
$ cargo run -p chiyoda-replay -- out/showcase/run.json --speed 10
$ cargo run -p chiyoda-replay -- out/showcase/run.json --surface concourse --export-gif out/showcase/replay.gif --gif-speed 4
```

`--at-step N` starts at an exact recorded integration step. It can be combined
with `--paused` and `--surface` for the replay moments linked from
[journey diagnostics](journeys.md). A missing recorded step is rejected; the
viewer does not invent an interpolated diagnostic frame. The option applies
to saved bundles, including PNG export, rather than live watching or GIF export.

## Live JSON debugging

For the editing loop, pass a source file through `--watch` instead of a run
bundle:

```console
$ cargo run -p chiyoda-replay -- --watch examples/demos/grand-interchange-showcase.json
$ cargo run -p chiyoda-replay -- --watch draft.json --paused --surface platform --trace-every 1
```

The viewer polls saved source text, waits 150 ms for writes to settle, then
loads, validates, and executes the complete deterministic scenario on a
background worker. A newer save supersedes a stale worker result. A successful
revision atomically replaces the displayed trace at simulation time zero; it
does not mutate a running simulation. The default watch trace cadence is one
integration step, which affects display smoothness only.

An invalid edit leaves the most recent valid scene and trace on screen. The
terminal prints the complete JSON, validation, or runtime diagnostic; the
window and an in-canvas diagnostic panel identify the failed revision. Before
the first valid source revision, the viewer displays an empty debug canvas with
the current status and any read error.

Watch runs are intentionally in-memory, unpersisted debug results. They are
not hash-verified bundles and cannot be cited as a replayable experiment
artifact. Run `chiyoda run SOURCE -o DIRECTORY` separately when a durable,
hash-verified JSON bundle is needed.

For a bundle made by the installed runtime, the viewer checks the bundle hash
and reconstructs the complete deterministic run before opening a window. It
rejects an incompatible legacy runtime by default; `--allow-legacy-hash-only`
permits display after hash verification alone and emits a warning. The CLI also
rejects an unknown initial surface and an empty trace. Interactive playback
requires an available native display. PNG and GIF exports do not open a window.

For locally aware groups, `run.json` also retains each agent's familiar exits
and learned availability with its time and source. `resource_observed` and
information events explain changes. The viewer displays the resulting movement;
inspect the saved trace for the knowledge state itself. These fields are
included in full reconstruction; see [local knowledge](knowledge.md).

## Controls

- Space toggles playback.
- Left and right arrows step the trace.
- Tab cycles surfaces in their authored declaration order.
- V toggles between the selected-surface 2D view and an all-surface isometric overview.
- D toggles source details in the fixed sidebar. Playback time, frame and step,
  selected-surface count, all-surface state counts, and the plan key stay visible.
- P writes the current rendered frame as a PNG under `--snapshot-dir` (default
  `out/chiyoda-replay`). No image is written until P is pressed; existing
  snapshots are never overwritten.
- Escape closes the viewer.

The window title identifies the selected surface and current trace time. The
camera extent comes from that authored surface, rather than from the agents
visible in one trace frame, so static geometry remains stable while replaying.
By default it advances according to the recorded simulation-time gaps; use
`--speed N` to show `N` simulation seconds per wall-clock second. This avoids
making a trace recorded at a different sampling frequency appear to have a
different duration.

## Exact-frame PNG export

`--export-png PATH` writes a reviewed frame without opening a window. Use
`--at-step N` for an exact recorded step; otherwise it exports the first frame.
`--surface ID` selects a floor and `--view overview` projects every floor.
The same `--view` option selects the initial interactive or GIF projection.

```console
$ cargo run -p chiyoda-replay -- out/showcase/run.json --at-step 100 --export-png out/showcase/frame.png
$ cargo run -p chiyoda-replay -- out/showcase/run.json --view overview --export-png out/showcase/overview.png
```

The image and its `PATH.json` sidecar require a reconstructed bundle. The
sidecar records its source hashes, runtime contract, selected surface and
explicit list of rendered surfaces,
projection, exact recorded step and timestamp, and image dimensions. Existing
images and sidecars are never overwritten. PNG and GIF export are mutually
exclusive and cannot watch live source.

## Animated GIF export

PNG snapshots remain the smallest and clearest artifact for a single reviewed
state. Use an animated GIF only when the sequence itself matters—for example,
to review movement timing, queue-state transitions, or rerouting. `--export-gif
PATH` verifies and reconstructs the input bundle before rendering every stored
trace frame of the selected projection, writes `PATH`, then exits without opening a
window. It never accepts `--watch`, does not re-run or load a scenario, and does
not modify the canonical bundle.

`--gif-speed N` means `N` simulation seconds per playback second. GIF timing is
limited to centiseconds; the companion `PATH.json` records the exact
per-frame rounded delays, the terminal-delay policy, playback speed,
`trace_every_steps`, trace-frame count, selected and rendered surfaces, scenario hash, bundle
hash, runtime and bundle versions, projection, and a clear derived-artifact boundary.
The GIF sidecar uses `chiyoda.replay-gif.v2`. The exporter refuses to overwrite either the GIF or its sidecar. The verified JSON
bundle remains the canonical replay and timing artifact.

GIFs share one palette across the animation. Status colours are preserved
exactly, while antialiased edge colours are quantized to coverage ramps. Use
PNG when the finest text and edge detail matter.

## Preview

![Recorded concourse at 20 seconds](../assets/demo/replay-floor-plan.png)

The same recorded moment is available as an [isometric overview](../assets/demo/replay-overview.png).
Both PNGs and the [showcase GIF](../assets/demo/grand-interchange-showcase.gif)
have adjacent JSON provenance sidecars. They were exported from the current
runtime's `grand-interchange-showcase` run, with a trace cadence of 20 steps.

To reproduce the source and export a reviewed frame:

```console
$ chiyoda run examples/demos/grand-interchange-showcase.json -o out/showcase --trace-every 20
$ chiyoda-replay out/showcase/run.json --surface concourse --at-step 100 --export-png out/showcase/frame.png
$ chiyoda-replay out/showcase/run.json --surface concourse --at-step 100 --view overview --export-png out/showcase/overview.png
```

## Visual presentation

The viewer uses one 1200×800 CPU vector renderer for native playback, PNGs,
and GIFs. The plan, run sidebar, and timeline occupy separate regions. Spatial
layers are clipped to the plan viewport, including markers outside the camera
extent. Watch failures retain the last valid plan and identify the failed
revision below it and in the sidebar.

Floor plans preserve metric proportions with a uniform scale, a quiet metric
grid, and a scale bar. The camera follows authored surface bounds rather than
people's current positions. The overview uses the same fixed isometric
projection for every surface and labels their authored elevations; overlapping
floors can occlude geometry, so use a floor plan for detailed inspection.

Text uses a bundled Inter font under its included SIL Open Font License;
no font installation or network connection is required. Timestamps and counts
use tabular digits. Long names are ellipsized, and unavailable glyphs display
as `?`. This is basic font rasterization, not a full text-shaping system.
Resource labels are placed where space permits; dense plans may omit a label
while retaining its symbol. Connector symbols identify stairs (`S`), ramps
(`R`), escalators (`E`), and lifts (`L`).

The header distinguishes reconstructed bundles, legacy hash-only artifacts,
and unpersisted live previews. Export sidecars record the renderer version and
bundled font hash. The renderer is a derived presentation contract; changes to
its pixels do not change canonical simulation evidence.

## Rendering contract

For the selected surface the viewer draws its rectangular walkable boundary,
rectangular obstacles, line-footprint or serpentine-grid queue paths and slot centres, waypoints, exits,
gates, portal-lane centres, and the endpoint of every authored stair, ramp,
escalator, or lift on that surface. Agents are drawn only
when their recorded `surface` matches the selected surface. Moving agents are
blue, all released waits amber, in-transit agents purple, unreleased people
hollow grey, and completed journeys hollow green. Moving people use filled
circles; waits carry a small horizontal mark and transit markers a white centre.
Inactive records are drawn first so an unreleased or completed record does not
cover an active person at a shared position. Counts always include every
recorded person, including inactive records. The sidebar reports all-surface
state totals separately from the selected-surface count.

People markers follow their authored group radius, with a 2.5-pixel minimum for
legibility. Symbol outlines, minimum-size markers, and resource glyphs are
schematic; their screen sizes do not measure crowd density or portal width.
Static symbols distinguish waypoints, exits, gates, connector class, portal-lane
centres, and queue geometry. The queue path and slots are authored placement
geometry, not a measured queue, a claimed standing obstruction, or a density
visualization.

Connector endpoints are visual cues, not surveyed shapes or a claim that the
space between floors is visible from above. During a connector traversal the
runtime records the agent on its originating surface until arrival; a
floor-specific view therefore shows the recorded horizontal interpolation on
that origin surface. This is trace provenance, not a physical 3D position.

The viewer renders only values explicitly authored in the scenario and recorded
in the bundle. It does not infer floor plans, building boundaries, indoor
connectivity, elevations, widths, density, hazards, visibility, accessibility,
capacity, or behavior from a run.

The isometric overview is a fixed projection of authored surface elevations,
static geometry, connectors, and recorded agent positions. It is a debugging
aid, not a general 3D camera, a physical view of a station, or a new rendering
engine. The selected-surface 2D view remains the detailed rendering of queues,
portal lanes, obstacles, and resource markers.
