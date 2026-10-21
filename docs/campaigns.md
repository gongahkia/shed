# Budgeted experiment campaigns

A campaign lets an agent propose a finite set of interventions, screen them
against one baseline, then test selected candidates under additional conditions.
Chiyoda enforces the declared edits, constraints, objective order and work
budget. The calling agent supplies the candidates and interprets the evidence;
no model service or credentials are required.

The campaign contract is **1**. Scenario, runtime, ordinary experiment and
run-bundle contracts are unchanged.

## Try a campaign

Build `cargo build --release -p chiyoda --locked`, then run:

```console
python3 examples/campaigns/run_campaign.py --plan-only
python3 examples/campaigns/run_campaign.py --output out/campaign-station --replay
python3 examples/campaigns/run_campaign.py --config examples/campaigns/generated-concourse.json --output out/campaign-concourse --replay
```

The station example permits moving its kiosk and replacing its information
policy, with fixed physical population inputs inside each comparison. It
screens the existing kiosk/announcement interventions and an unchanged control,
then checks selected candidates under another seed and double demand. Its
completion requirement is explicit; an unfinished baseline is retained rather
than preventing a feasible candidate from being compared with it.

The concourse example prepares WFC cases with a different seed, a larger floor
and more people. Candidate edits target stable generated resource IDs. It
explicitly allows promotion without an initial improvement so that tied controls
can also be explored under stress; a tie is not labelled an improvement.
Generation exhaustion, missing targets, invalid movement and unfinished journeys
remain possible recorded outcomes. The examples do not require a candidate to win.

The script uses Python's standard library and the same tools available through
MCP. `--plan-only` prepares the complete bounded plan without simulating people.
`--replay` asks for additional final reconstruction and image verification;
verification is separate work outside the original execution budget.

## Agent workflow

There are four campaign tools in the existing `agent call` and MCP interfaces:

| Tool | Input | Result |
| --- | --- | --- |
| `chiyoda_campaign_plan` | `config` path; optional `case` ID | Work reservations, case/trial preflight, actual changed fields and `plan_hash`. Selecting a case returns its prepared geometry, authored IDs and generation evidence. |
| `chiyoda_campaign_run` | `config`, `expected_plan_hash`, new `output` directory | Screening, promotion, stress comparisons, bounded annotated exports and a compact summary. |
| `chiyoda_campaign_query` | `campaign` directory, `view`, optional filters/page | Summary, candidates, trials or annotated evidence. |
| `chiyoda_campaign_verify` | `campaign` directory, optional `replay` | Independently re-derived selection, measurements, coverage, ranks and costs; image/source checks. |

Example arguments, sent to `chiyoda agent call TOOL --input -` or MCP:

```json
{"config":"examples/campaigns/station.json"}
```

Use the returned `plan_hash` to execute:

```json
{
  "config": "examples/campaigns/station.json",
  "expected_plan_hash": "HASH_FROM_PLAN",
  "output": "out/my-campaign"
}
```

A changed normalized request, runtime or generated plan invalidates that hash
before an output directory is created. A campaign plan can be runnable while
individual candidates fail preflight: those rejected trials are retained and
consume no simulations. Global budget/configuration failures prevent execution.
A plan operation may run bounded WFC generation; it does not run pedestrians.
The execution call prepares each generated case once and holds an immutable
request/plan pair while streaming its trials to disk.

Query the evidence:

```json
{"campaign":"out/my-campaign","view":"trials","candidate":"announcement","limit":10}
```

Views are `summary`, `candidates`, `trials`, and `evidence`. Pages retain total,
offset, next offset and original JSON pointers. `case` filters trials/evidence;
`candidate` filters all paginated views. A declared stress case that was not run
has zero matching trials. Summary and candidate rankings cannot be filtered by
case because their scope spans cases. Limits are 1–200 items, default 20.

Each executed trial also exposes an `experiment` path. Pass it to the existing
`chiyoda_query` tool for complete people, comparisons, queues, hotspots,
low-progress periods, events and exact trace steps. The compact campaign report
keeps at most five changed people and five findings of each hotspot/resource type
per arm; counts and links retain access to the full evidence.

In MCP, campaign execution and evidence queries additionally return the first
successful annotated PNG in their result/page as an image content block. Its
bytes are checked against the saved receipt before delivery. All images retain
structured paths and hashes; the one-shot JSON CLI does not include base64 data.
The ordinary `chiyoda_render_evidence` tool can request other selected moments.

## Describe the boundaries

Obtain the full generated input schema with
`chiyoda_schema` and `{"kind":"campaign"}`. A configuration embeds its
versioned JSON source scenarios rather than depending on external scenario
files. Authored cases can vary seed or architecture explicitly; generated
cases contain a seed and the existing `SpaceConfig` contract.

Each case has a portable ID and optional integer `population_multiplier` in
1–8. Scaling happens before interventions and applies equally to both arms.
The baseline and candidate therefore face the same demand for that case.
Changing demand between stress cases is distinct from changing demand between
arms of a comparison.

Candidates have an ID and ordered ordinary experiment `changes`. Every trial
starts independently from its case baseline. Optional `case_changes` maps a
case ID to a complete replacement recipe, declared before screening. Without
an override, the exact default recipe is reused; missing IDs are rejected,
not guessed. Explicit adaptations can express the same intended intervention
across different authored geometry, but Chiyoda does not prove their conceptual
equivalence. Inspect prepared cases before choosing those adaptations.

Constraints include:

- `allowed_edits`: change kinds with exact target IDs. `*` explicitly permits
  every target of that kind. Replacing the entire information policy requires
  `replace_information` with an empty target list.
- `preserve_population`: true by default. Physical traits, counts, goals,
  releases and seed must match the case baseline; knowledge can remain an
  intervention. Disabling this control keeps each arm's denominators and
  disables individual pairing when population inputs differ.
- `bounds`: finite inclusive numeric limits on obstacle x/y/width/depth, exit
  width/capacity, gate rate, connector width or group count. A bound's target
  must exist. Optional `case` scopes it to one declared case; omission applies
  it to all cases.
- `outcomes`: candidate metric minima/maxima, optionally scoped by `case`.
  A required metric that is absent fails its bound.

Permissions apply to every authored edit, including one later undone by another
edit. Numeric bounds apply to the resulting candidate. Existing validation and
radius-clear geometric reachability still apply. The edit vocabulary cannot
change floor boundaries, integration settings or simulation duration; each
case retains its own authored footprint and clock.

## Objectives, promotion and stress coverage

Declare 1–6 unique ordered objectives, each with a direction and nonnegative
tolerance. Supported metrics are `completed_fraction`, `unfinished_people`,
`service_wait_per_person_s`, `clearance_time_s`, `low_progress_per_person_s`, and
`mean_completed_journey_s`. The last metric requires everyone to complete in
both arms, so dropping slow unfinished people cannot improve a completed-only
mean. Missing clearance is never zero. Low-progress exposure overlaps moving
time and is not an additional time-budget category.

A trial is eligible only when both runs have measurable evidence and boundary/
swept reference-disc audits at or below 1e-8 m, the candidate passes the declared
evaluation checks and outcome constraints, and all objective values exist in
both arms. An unsuccessful baseline performance check does not automatically
invalidate an otherwise measurable paired comparison. Invalid trajectories
are excluded even when the user relaxes an overlap performance threshold.
Fallback counts remain recorded; a fallback alone is not treated as an overlap.

Screening ranks use exact lexicographic paired benefits: positive means better
under that objective's direction. Ties use candidate ID. Tolerances only classify
wins/ties/losses and the promotion improvement requirement; they are not used as
an approximate sorting comparator. Promotion takes at most `max_candidates`
(default 2); `require_improvement` defaults to true. A candidate is improved when
the first objective outside its tolerance is better. Entirely tied candidates
are not promoted by default.

All selected candidates are tested against a matching baseline in every declared
stress case. Failed generation or rejected edits are retained cases; they do not
quietly reduce the denominator. Nonpromoted candidates have screening evidence
only. Final ranks require eligible coverage of screening and every declared
stress case. They order equally weighted mean paired benefits lexicographically,
with worst benefit and win/tie/loss counts alongside the mean.
Objective summaries retain every available, valid paired measurement, including
trials that miss a performance/outcome requirement. Those trials remain ineligible
for promotion and final ranking. Invalid trajectories are excluded from all
objective summaries; missing metrics contribute only where a paired value exists.
Different metrics are never added together into an undocumented weighted score.

Statuses distinguish `screening_excluded`, `not_promoted`, `screening_only`,
`stress_incomplete`, `stress_regressed`, and `stress_no_regression`. A candidate
can rank highly by its mean and still regress in a stress case; that status and
the loss remain visible. `stress_incomplete` means incomplete eligible coverage;
it also covers a fully measured trial that fails a required outcome constraint.
Reason counts and trial records distinguish this from missing/invalid evidence.
No rank is a global optimum, a significance test or an automatic architectural
recommendation. Coverage is limited to the declared
candidates and conditions.

## Work reservations and failures

Campaigns support at most 32 candidates, 16 stress cases, 256 candidate/case
trials, 1,024 people and 10,000 integration steps per arm, 64 edits per recipe,
and 16 annotated images. Canonical JSON artifacts use the existing 256 MiB
read limit; an oversized trial archive remains partial and does not publish a
campaign report. The default candidate budget is 20. Agent-step and
retained-snapshot budgets are each capped at 2,000,000.

Reservations count every screening trial and the most expensive possible top-K
stress trials independently for simulation and storage. Each trial runs its own
baseline/candidate pair; repeated deterministic baselines are intentionally
charged rather than treated as free cached runs. Requested initial reconstruction
is charged too. Image exports reconstruct their source once and reserve
`max_evidence_images × maximum eligible arm work`. The report distinguishes
charged simulation work, retained samples and reserved image work; the image
index records conservatively charged export attempts, including failures.

Generation attempt/decision caps are checked before any solver invocation. The
per-case maximum is 64 attempts, 100,000 decisions and 10,000 backtracks per
attempt; global caps are 1,024 attempts and 32,000,000 reserved decisions.
Generation validation/reachability work is still performed. These operation
counts do not promise a wall-clock deadline or a fixed number of output bytes.
Planning, execution and explicit verification are separate bounded calls.

Preflight rejection, performance failure, invalid trajectories, runtime failure
and unavailable metrics retain distinct reasons. Runtime failures conservatively
charge reserved trial work. An interrupted or failed archive keeps its request,
plan, completed trial ledgers and `failure.json` when the process can write it;
`report.json` is published last. A directory without that report is incomplete.
Existing output directories are rejected. This version does not resume a partial
campaign or automatically invent replacement candidates.

## Evidence and verification

A completed directory contains:

- `request.json` and `plan.json`, including permissions, complete prepared cases,
  WFC provenance and all candidate/case preflight results.
- `cases/ID/` with the prepared scenario and generation success/exhaustion record.
- `trials/CASE/CANDIDATE/` with a trial ledger and, for executed trials, the
  ordinary experiment archive: source scenarios, runs, journeys and comparisons.
- `report.json` and `report.md` with coverage, objectives, rankings, changed
  people, queue/low-progress findings and source pointers.
- `evidence-index.json` and bounded `evidence/NNN.png`/`.png.json` artifacts.

Images are chosen deterministically from ranked candidates and their least
favourable primary-objective cases. Person selections prioritize changed
completion, elapsed, service-wait and low-progress evidence; the displayed step
ends the person's longest recorded low-progress window, otherwise the final
frame. Resource selections show the largest whole-run wait audit in an arm;
the final frame is not claimed as the peak queue moment. Retained image slots
may cover only part of the campaign. All trial evidence remains available.

Archive paths are relative to the campaign directory. Moving it and removing
original configuration/scenario files does not invalidate verification.
`chiyoda_campaign_verify` re-derives the prepared cases, ordinary trial evidence,
promotions, eligibility, objective summaries, coverage, rankings, moments and
budgets. Hash-only resealing of false conclusions does not pass.

Default verification additionally checks image/sidecar hashes and their exact
source/request links. With `replay: true`, it reconstructs saved runs and
re-renders the annotated pixels and measurements. A recorded runtime failure
without sufficient evidence requires explicit replay to substantiate it.
Image export failures remain recorded failures; a verified campaign can report
`all_requested_exports_succeeded: false`. This distinction prevents tool success
from being mistaken for a successful intervention or a complete image set.

The [verification record](campaign-verification.md) documents the tests,
independent MCP client, real example outcomes and their limits.
