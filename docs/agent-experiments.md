# Agent experiments

Chiyoda exposes the same headless experiment capability to Rust applications,
command-line agents, and MCP clients. A caller can inspect a space, propose
changes, check the complete plan, run controlled alternatives, retrieve
evidence, and revise its next experiment. Chiyoda supplies simulation and
observations; the calling agent supplies hypotheses and interpretation. No
language-model API or credentials are required.

The current API and experiment versions are **1**. This adds an analysis and
intervention contract; ordinary scenario, movement, and run-bundle contracts
remain unchanged.

## Discover and try

Build the CLI with `cargo build --release -p chiyoda --locked`, then discover
the tools and their generated input schemas:

```console
target/release/chiyoda agent tools
target/release/chiyoda agent call chiyoda_capabilities --input -
{}
```

For the second command, send the JSON followed by EOF. One-shot calls also
accept an arguments file. Standard output contains exactly one JSON envelope:

```json
{"api_version":"1","ok":true,"result":{}}
```

Failures have `ok: false` and an `error` containing `code`, `message`, and
optional `details`; the one-shot process exits nonzero. Semantic failures in a
*plan* are an ordinary result with `runnable: false` and structured issues.
A successfully executed experiment can contain failed performance checks:
inspect every arm's `evaluation.passed`, failures, measurements, and completion
counts. Tool success does not mean an architecture performed well.

All tool artifact paths are relative to `--workspace`, which defaults to the
current directory. Existing destinations are preserved. Absolute paths,
parent traversal, and symlinks resolving outside that workspace are rejected.
The local process still has the invoking user's OS permissions; this path
convention is not an operating-system sandbox.

The checked-in station example compares three independent interventions with
one baseline: moving a kiosk, announcing a scheduled exit closure, and doubling
the number of arrivals. Run this from the repository root:

```console
python3 examples/experiments/compare_station.py --output out/agent-station --replay
```

The script uses only Python's standard library. The equivalent calls are:

```python
import json
import subprocess
from pathlib import Path

binary = "./target/release/chiyoda"

def call(tool, arguments):
    process = subprocess.run(
        [binary, "agent", "call", tool, "--input", "-", "--workspace", "."],
        input=json.dumps(arguments), text=True, capture_output=True,
    )
    envelope = json.loads(process.stdout)
    if not envelope["ok"]:
        raise RuntimeError(envelope["error"])
    return envelope["result"]

experiment = json.loads(Path("examples/experiments/station.plan.json").read_text())
plan = call("chiyoda_plan", experiment)
if not plan["runnable"]:
    raise RuntimeError(plan)

output = "out/agent-station"  # must be a new directory
result = call("chiyoda_run", {
    "experiment": experiment,
    "expected_input_hash": plan["input_hash"],
    "output": output,
})
print([(arm["id"], arm["evaluation"]["passed"]) for arm in result["arms"]])
print(call("chiyoda_query", {
    "experiment": output, "view": "comparisons", "arm": "announcement",
    "offset": 0, "limit": 10,
}))
print(call("chiyoda_verify", {"experiment": output, "replay": True}))
```

## Campaigns

For permissioned candidate screening, held-out WFC/seed/load stress cases,
explicit work budgets and annotated evidence, use the
[budgeted campaign workflow](campaigns.md). Campaigns build on the same paired
experiment archives; agents continue to supply hypotheses and candidate edits.

## Tools

| Tool | Capability |
|---|---|
| `chiyoda_capabilities` | Versions, workflow, limits and interpretation rules |
| `chiyoda_schema` | Generated current scenario or complete experiment JSON Schema |
| `chiyoda_inspect` | Full authored scenario, stable object IDs, hash, population, step count, validation and geometric reachability |
| `chiyoda_generate` | Seeded WFC generation into a new directory, with provenance and rejected attempts |
| `chiyoda_plan` | Independent variants, actual changed fields, population controls, preflight and total cost |
| `chiyoda_run` | Execute exactly the planned input and save complete comparison evidence |
| `chiyoda_query` | Bounded pages of measurements, differences, people, queues, decisions and replay states |
| `chiyoda_verify` | Independently re-derive evidence; optionally reconstruct every run |
| `chiyoda_campaign_plan` | Permission, constraint, generation and worst-case work preflight |
| `chiyoda_campaign_run` | Screen candidates, promote, stress-test and export bounded evidence |
| `chiyoda_campaign_query` | Paginated campaign rankings, trials and annotated moments |
| `chiyoda_campaign_verify` | Re-derive campaign conclusions; optionally reconstruct runs/image pixels |
| `chiyoda_render_evidence` | Annotated exact-step PNG and source-linked person/resource evidence |

`chiyoda_generate` accepts an optional workspace-relative `config` path to the
existing `SpaceConfig` JSON format. Obtain an editable configuration with
`chiyoda space-rules --preset concourse`. Generation is the existing WFC
implementation, including its bounded attempts and rejection record; tool
calls do not introduce a second generator. Exhaustion writes
`generation-failure.json` and returns its location in the error details.

## Controlled changes

`chiyoda_plan` takes a name, a scenario path, variants, and optional execution
options. See [station.plan.json](../examples/experiments/station.plan.json).
Each variant starts from the original baseline, then applies its own ordered
changes. An empty change list is an explicit identical-input control.

Changes target object IDs, rather than array positions:

| Change kind | Required payload |
|---|---|
| `move_obstacle` | `id`, `at` point |
| `resize_obstacle` | `id`, `width_m`, `depth_m` |
| `add_obstacle` | complete `obstacle` |
| `remove_obstacle` | `id` |
| `set_exit_width` | `id`, `width_m` |
| `set_exit_capacity` | `id`, `capacity_per_s`; null removes the limit |
| `set_gate_rate` | `id`, `service_rate_per_s` |
| `set_connector_width` | `id`, `width_m`; stairs, ramps and escalators only |
| `set_group_count` | `id`, `count` |
| `set_group_knowledge` | `id`, `knowledge`; null restores global awareness |
| `replace_information` | complete ordered `messages` and `countermeasures` arrays |

Width and service capacity remain independent inputs. Moving an obstacle does
not silently relocate people or repair invalid geometry. Lift cabins retain
their separate geometry. Existing semantic validation and independent
radius-clear geometric preflight apply after edits. Unknown IDs, unsupported
edits, blocked journeys and overlapping initial populations remain explicit
issues. Arrays retain their authored order and event tie-breaking semantics.

Plans hash the entire resolved request, including the embedded scenario,
interventions, defaults, budgets, thresholds, and replay setting. A run requires
that exact `expected_input_hash`. A changed file or proposed intervention
requires a new plan; formatting alone does not alter the resolved scenario.
This is an input-consistency check, not a separate approval mechanism.

The seed and integration schedule are fixed across an experiment. Population
hashes include group order, IDs, physical traits, starts, goals, release rules
and seed. Initial knowledge is excluded because it can itself be the
intervention. Changing demand disables individual pairing entirely, even when
some IDs appear in both arms. It retains aggregate measurements and their
separate denominators. Kiosk and announcement variants can retain person pairs.

## Evidence queries

`chiyoda_run` returns a compact report without per-person comparison arrays.
The complete report is saved. `chiyoda_query` supplies these views:

| View | Scope |
|---|---|
| `summary` | All arm outcomes and aggregate comparisons; no arm required |
| `changes` | Actual changed top-level fields and before/after hashes for an arm |
| `agents` | Person summaries, time budgets, completion, nominal references and evidence locations |
| `comparisons` | Individual candidate-minus-baseline differences, matching and completed-pair counts |
| `hotspots` | Spatial low-progress exposure and affected people |
| `resources` | Per-resource queue exposure and peaks |
| `low_progress` | Individual recorded low-progress windows |
| `events` | Journey decision evidence in deterministic time/person/event order |
| `trace` | Agent states at one exact recorded integration step |

All detail views require an `arm`. Comparisons require a candidate arm. People,
comparisons, low-progress windows, events and traces accept an `agent` filter.
Trace queries additionally require `step`; they return structured replay
program arguments, suitable for opening the existing paused native viewer.

Pages contain `total`, `offset`, `limit`, `next_offset`, and `items`; follow
`next_offset` until null. Default page size is 50, maximum 200. Source artifact
paths are relative to the experiment directory, and JSON Pointers identify
the complete underlying evidence. A person summary is a projection of its
source journey, not a replacement for that full record. Querying checks linked
hashes; independent re-derivation requires `chiyoda_verify`.

Comparisons report raw measurements and candidate-minus-baseline differences.
There is no automatic overall winner. Incomplete journeys have no completed
elapsed or clearance time. Differences in elapsed time exist only for people
who completed both journeys, with the count retained. Low-progress spans
overlap moving exposure. Static nominal references and observed differences
retain the interpretation limits in [journeys.md](journeys.md).

Findings identify recorded low-progress locations and resource waits with
artifact pointers and available replay steps. The calling agent can follow
these, inspect route and information events, and state what the evidence
supports. A temporal association alone does not establish why a person chose
a route or isolate every source of delay.

## Artifacts and verification

An experiment directory contains `request.json`, `report.json`, `report.md`,
and an `arms/ID` directory for each arm. Each arm retains its scenario, run,
and the ordinary journey JSON, CSV and Markdown. Requests embed the complete
baseline. The archive can move or be copied without the original source path;
the run and analysis data remain independently verifiable with the compatible
runtime and analysis contracts.

`report.json` is published last. A partial directory without it is incomplete;
unexpected execution errors retain the request and `failure.json` when
possible. Existing directories are never reused. Threshold failures remain
in a completed report, including their measurements. Missing runtime or
analysis evidence is never replaced with a successful result. Verification
refuses an arm whose evidence is missing.

Default verification re-prepares the request, checks the complete artifact
inventory and hashes, rechecks trajectory assertions and thresholds, re-derives
journeys, and rebuilds all comparisons. `replay: true` additionally executes
each saved scenario and compares its complete run. This extra verification
does not rewrite whether the original experiment requested replay.

## Execution limits

Experiments permit 1..=15 variants plus the baseline, at most 64 changes per
variant, 1..=1024 people, 1..=10000 integration steps, and at most 1024 declared
objects/interventions per arm. Complete evidence requires distinct recorded
timestamps, and durations are bounded at 86400 seconds.

Both agent-step and retained-snapshot budgets default to 1000000 and can be
set in 1..=2000000. Agent-step estimates include every requested reconstruction
execution; retained snapshots include each initial frame. These are bounded
work estimates, not wall-clock predictions. Later explicit verification is
additional work. Tool generation permits at most 64 attempts, 10000 backtracks
and 100000 decisions per attempt, alongside ordinary `SpaceConfig` limits.
Tool messages are bounded at 16 MiB, results at 1 MiB, and saved input/artifact
files at 256 MiB. Oversized results return a resource-limit error; they are
never silently truncated.

Stable error codes include `arguments`, `configuration`, `version`, `path`,
`artifact_io`, `resource_limit`, `destination_exists`, `stale_plan`,
`invalid_plan`, `generation`, `analysis`, `unknown_id`, `unknown_tool`,
`step_not_recorded`, `integrity`, `missing_evidence`, and `replay`. Plan issues
also carry object paths, validation/reachability categories, and budget details.

## Annotated replay evidence

After querying a person's low-progress window or a resource event, use its
recorded replay step to request an inspectable image:

```json
{
  "bundle": "out/agent-station/arms/baseline/run.json",
  "output": "out/agent-station/person-delay.png",
  "request": {
    "step": 100,
    "selection": {"kind": "agent", "id": "passengers:4"},
    "trail_start_step": 50,
    "view": "surface",
    "camera": {"zoom": 1.0, "pan_x_pixels": 0.0, "pan_y_pixels": 0.0}
  }
}
```

Pass that object to `chiyoda_render_evidence`, using either `agent call` or MCP.
For a resource, use `{"kind":"resource","resource_kind":"exit","id":"south"}`.
Resource kinds are `exit`, `gate`, `connector` and `waypoint`; lifts use
`connector`. Optional `surface` chooses an authored surface explicitly;
otherwise the selected person's surface or first resource endpoint is used.
Every requested step and trail start must exist in the recorded trace.

The tool reconstructs the saved run, exports a 1200×800 annotated PNG and a
`PATH.json` evidence sidecar, then returns their paths and SHA-256 values with
exact IDs, recorded step/time, source hashes, measurements and JSON pointers.
MCP additionally returns the PNG as `image/png` image content. The one-shot JSON
CLI returns the structured receipt without base64 image data.

The receipt includes at most 20 event references and a pointer to the trail;
the complete sidecar retains up to 100 recent relevant events, 200 low-progress
windows and 2,000 consecutive trail points. Truncation counts are explicit.
Per-step movement audit records stay in the source bundle rather than
crowding out route, information, release and service events. The sidecar records
the re-derived journey report hash, analyzer version and default options when
detailed accounting is available. Custom thresholds and isolated controls from
a separate journey report are not imported by this image tool.

Whole-run totals are labelled separately from the selected frame. Sparse traces
still expose recorded positions/events, with a reason for unavailable detailed
journey accounting. Historical queue entrants are not claimed as current queue
members. Resource-attributed per-person wait durations are not recorded by the
runtime. See [replay inspection](replay.md#inspect-people-and-resources).

Both destinations must be new workspace-relative artifacts. The tool preserves
existing files and never changes its source bundle. Inputs are bounded to
10,000 people, 2,000,000 simulated agent-steps and 2,000,000 recorded agent
samples, in addition to the existing artifact-size limit. The structured
receipt is capped at 1 MiB and MCP PNG content at 4 MiB. This is a synchronous
export rather than a streaming replay session.

Synchronized two-run comparison is available through the
[`chiyoda view --compare` CLI](replay.md#compare-runs) and the shared Rust replay
library. The current MCP image tool exports one selected run; callers can request
both arms independently or invoke the comparison CLI.

## MCP transport

Start a local stdio server with:

```console
target/release/chiyoda agent serve --workspace /absolute/path/to/chiyoda
```

For clients using the common `mcpServers` configuration shape:

```json
{
  "mcpServers": {
    "chiyoda": {
      "command": "/absolute/path/to/chiyoda/target/release/chiyoda",
      "args": ["agent", "serve", "--workspace", "/absolute/path/to/chiyoda"]
    }
  }
}
```

This binding implements the explicit MCP **2025-11-25** contract:
[lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle),
[stdio framing](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports),
and [tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).
It negotiates that version, requires initialization and the initialized
notification before tool use, exposes generated `tools/list` schemas, and
returns matching text and structured tool envelopes. Successful
`chiyoda_render_evidence` calls additionally include an MCP `image` content
block containing the same hash-checked PNG written to disk. Campaign execution
and evidence queries return the first successful image in their result/page,
with all remaining image receipts available through campaign queries. Stdout
contains only newline-delimited JSON-RPC. Domain failures use tool `isError`; invalid
protocol/arguments use JSON-RPC errors. EOF ends the server.

Tools execute synchronously. The server advertises tools only, with a static
catalog; it does not advertise asynchronous tasks, progress, subscriptions,
resources, prompts, or cancellation support. There is no HTTP listener or
hosted sharing service. A browser or another application can build on the
same experiment API without changing the simulation law.

## Rust API

`chiyoda_core::experiment` exposes `inspect`, `plan`, `execute`, the complete
request/plan/report types, generated schema, and verification from saved
artifacts. The library has no CLI, filesystem, MCP, or model-service dependency:

```rust,ignore
let request: chiyoda_core::experiment::ExperimentRequest =
    serde_json::from_str(&request_json)?;
let plan = chiyoda_core::experiment::plan(&request)?;
let result = chiyoda_core::experiment::execute(&request, &plan.input_hash)?;
result.report.verify(&request, &result.artifacts, true)?;
```

The library request embeds `baseline`; tool planning instead resolves a
`scenario` path into that same request. Neither transport implements its own
mutation, movement, or comparison engine.

## Recorded station example

[station.reference.json](../examples/experiments/station.reference.json)
records the validated inputs, run and analysis hashes, comparison counts,
and verification results. The JSON CLI, official Python MCP SDK 2.3.0, and
release CLI produced identical reports, with report hash
`548d9ebb31140de82d840fa4b8af77a6a2722837911b058b076524600151a8bb`.
The SDK negotiated MCP 2025-11-25, validated the generated schemas and tool
arguments, queried all nine evidence views, and reconstructed every arm.

| Arm | Completed / people at 120 s | Clearance (s) | Completion goal |
|---|---:|---:|---|
| baseline | 5 / 24 | unavailable | failed |
| moved-kiosk | 6 / 24 | unavailable | failed |
| announcement | 24 / 24 | 41.6 | passed |
| busier | 6 / 48 | unavailable | failed |

The unfinished people remain in moving states with prolonged low progress
near the south exit. The evidence retains those movement stalls and does not
reclassify them as service queue waits. These are observations of this
reference model on one layout and seed, with limits to investigate before
drawing architectural or communication-policy conclusions.

The kiosk and announcement comparisons each retain 24 matched people, but
only two and five respectively completed both journeys. Completed-time
differences therefore have those smaller denominators. The busier arm has no
person pairs. All four runs have zero recorded boundary and swept disc overlap.

Validation passed 287 workspace tests, formatting, Clippy with warnings denied,
the release workspace build, and release demo reconstruction. One expensive
unit gate remains ignored by default. Separately, the full ten-case frozen CLI
benchmark passed its original checks: all people completed, every run
reconstructed, and all boundary/swept overlap audits were zero. Two pinned
pre-agent 0.45 cases also reproduced their exact historical run hashes. The
reference retains all ten current frozen scenario and bundle hashes.
