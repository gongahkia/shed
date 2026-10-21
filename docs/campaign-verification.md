# Campaign verification

Reviewed 21 October 2026. Scope: bounded candidate screening, edit/constraint
enforcement, controlled stress trials, objective accounting, archive integrity,
MCP/CLI delivery and annotated evidence. The campaign model is version 1; the
ordinary runtime remains 0.45.0-alpha.1, with its existing movement semantics.

The [usage guide](campaigns.md) describes the contract. Machine-readable example
results and the independent client check are retained in
[campaign-verification.json](campaign-verification.json).

## Automated checks

**170 tests passed across the relevant suites**, without counting repeated runs:

| Suite | Passed |
| --- | ---: |
| Ordinary CLI integrations, including 14 agent/MCP tests | 41 |
| Replay library, binary and integrations | 39 |
| Core unit tests, including strict campaign reference-audit eligibility | 55 |
| Campaign integrations | 8 |
| Ordinary experiment integrations | 11 |
| Core WFC generation / geometry / stable fingerprints | 9 |
| Core versioned JSON / legacy reconstruction | 7 |

Commands used, with Cargo available on PATH:

```console
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
CARGO_PROFILE_TEST_OPT_LEVEL=2 cargo test -p chiyoda -p chiyoda-replay
CARGO_PROFILE_TEST_OPT_LEVEL=2 cargo test -p chiyoda-core --lib --test campaigns --test experiments -p chiyoda --test agent
CARGO_PROFILE_TEST_OPT_LEVEL=2 cargo test -p chiyoda-core --test campaigns --test experiments
CARGO_PROFILE_TEST_OPT_LEVEL=2 cargo test -p chiyoda-core --test spaces --test scenario_json
cargo build --release -p chiyoda --locked
```

Campaign and agent tests were repeated after the final evidence/accounting
refinements. A two-person stress fixture demonstrates a service-wait regression
and checks that adding a failed outcome constraint preserves the valid measured
loss while withholding a final rank. A malformed, hash-resealed image receipt
returns an integrity error and leaves the MCP server available for the next call.

Coverage includes:

- Work reservation boundaries, generation caps checked before invoking WFC,
  fixed population controls, numeric bounds, explicit recipes and permission
  rejection without blocking other candidates.
- Deterministic plans, changed-input rejection before output creation, unchanged
  controls, ordered promotion, seed/load/WFC stress cases and complete coverage
  requirements.
- Generation exhaustion with its exact attempt inventory; missing metrics and
  unfinished clearance; performance failures retained in objective observations;
  missing/overlapping reference audits excluded from ranking.
- Positive screening results that regress under stress, losses retained after a
  constraint failure, and hash-resealed false promotions/conclusions rejected by
  independent re-derivation.
- Portable archives verified after moving the directory and deleting original
  configuration/scenario sources; image corruption, workspace escape, page
  totals, exact source links, and MCP image bytes checked against saved PNGs.

The ordinary WFC reference JSON and motion fingerprints remain unchanged.
No movement algorithm, scenario fixture or README.md edit was needed. The full
expensive movement integration corpus was not repeated; core units, existing
experiment/WFC fingerprints, CLI suites and replay suites were exercised.

## Independent MCP client

The official Python MCP SDK **2.3.0** negotiated **2025-11-25** and discovered
all **13 tools**. jsonschema **4.26.0** validated generated schemas and the
checked-in authored/generated campaign configurations. A configuration containing
both WFC cardinal directions and campaign objective directions also validated,
checking that the two Rust enum names do not collide in schema references.

The client inspected a generated case, planned and ran a small controlled
campaign, paginated its trials, retrieved annotated evidence, decoded the MCP
image and matched its bytes/SHA-256 to the archived PNG. Full verification
reconstructed the runs and re-rendered the pixels and measurements. The client
uses an ignored local virtual environment; no Python dependency is added to
the product.

## Real campaigns

Both checked-in examples completed through the release CLI and independently
verified every saved run and image:

```console
python3 examples/campaigns/run_campaign.py --output out/campaign-validation-20261021/station-release --replay
python3 examples/campaigns/run_campaign.py --config examples/campaigns/generated-concourse.json --output out/campaign-validation-20261021/concourse-release --replay
```

The station screened three candidates and promoted the announcement. The
baseline completed **5/24** journeys and the announcement **24/24**, both in the
screening case and the second seed. Under double demand, the announcement
completed **8/48**, compared with **6/48** for its baseline, missing the required
complete evacuation. The campaign retains all five executed trial pairs,
reports **2/3 eligible conditions**, and withholds a final rank. Its objective
summaries include all three valid paired observations, including the failed
completion requirement; the lower-performing case is not omitted from the mean.
The report labels this as incomplete eligible coverage, not missing execution.

The generated concourse tested two candidates across its screening case, another
seed, a larger footprint and increased demand: **eight trial pairs**. Both
candidates had **4/4 eligible conditions**. Faster gate processing reduced
service wait in screening and the busier case, and tied in the other two cases.
Widening the exit tied on the declared objectives in all four conditions. Both
have no observed stress regression; only the gate candidate has positive
observed benefits. The example explicitly permits exploratory promotion of
screening ties, so the exit-width tie is not represented as an improvement.

Each campaign exported and verified four annotated moments, with complete
source runs, journey findings, individual differences, queue audits and exact
replay steps available in its archive. The automatic image budget is smaller
than the complete evidence inventory; agent queries can inspect the other
people and resources.

These observations apply to the declared model, candidates and cases. They do
not establish an architectural optimum, statistical generalization or real-world
calibration. Campaign calls remain synchronous, and this version does not resume
an interrupted archive. Verification is additional work outside the original
execution reservation.
