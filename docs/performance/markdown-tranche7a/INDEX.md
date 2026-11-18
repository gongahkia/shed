# Tranche 7A evidence

Read [the engineering verdict](../../MARKDOWN_FINAL_QUALIFICATION.md) first. This is an audit, not a production remediation tranche. Default JetBrains Mono and Shed Light are separately authorized changes, measured against a new packaged artifact.

## Artifact epochs

- Original audit JAR SHA-256: `f51440f1e8aee98e48c71f611c0f48983a296c55540f1230f0f2dae2c9ed4acf`.
- New bundled-font/light JAR SHA-256: `8d9a08e892d10536c769fd83dc72ed948620c65d2fd57def69e87e2664d772ed`.
- All reports retain their actual artifact hash, command, source/probe hashes, environment snapshots and sample counts. Source versions used before the diagnostic probes grew additional modes are preserved in `diagnostics-and-logs.json.gz`.
- Old production-default runs used Monospaced/Shed Dark. Later diagnostic fresh homes explicitly use Shed Light even with the original JAR. `defaults-*` reports use the new JAR with JetBrains Mono/Shed Light; custom startup controls deliberately override the font with Dialog. No historical run is relabeled with the new defaults.

## Files and provenance

| Evidence | Contents |
|---|---|
| `measurements.json` | Per-case states, startup durations from process launch, complete timing summaries and lifecycle/heap metadata; source JSON reports are preserved below |
| `implementation-inventory.json` | Complete source, test, asset, tooling and evidence path inventory |
| `constrained-save-verification.json` | Independent byte/hash check of the 10 MiB saved file after the diagnostic reopen OOM |
| `verification.json` | Original/end repository identities, README hashes, suite totals, baseline preservation and original/new JAR identities |
| `baseline-files.json.gz` | Original status and SHA-256 inventory, including pre-existing user work |
| `regression-suites.json` | Final full-suite class totals and every skip reason |
| `diagnostics-and-logs.json.gz` | All probe stdout, Maven/tooling logs, refusal/failure logs, NMT/vmmap/heap/class/thread text, orchestration scripts and versioned diagnostic sources |
| `matrix.json`, `diagnostic-matrix.json`, `final-measurements.json` | Exact serial command inventories and return codes |
| `desktop.json` | Unmodified established three-launch desktop startup/workflow harness |
| `packaged-smoke.json` | Packaged-only Mermaid/math/image functional smoke, PASS |
| `package-difference.json` | Changed application JAR entries and byte-exact bundled TTF/license validation |
| `jfr-summaries.json` | Streaming summaries of original baseline, Unicode, media service and renderer control recordings; stack-presence counts overlap; weighted allocation samples are not retained bytes |
| `recordings.json` | JFR paths, sizes and hashes; large binary recordings stay outside the checkout |
| `manifest.json` | Evidence checksums plus font/license provenance and identities |

Compressed JSON is ordinary UTF-8 JSON after gzip decompression. For example:

```sh
gzip -dc docs/performance/markdown-tranche7a/unicode.json.gz | python3 -m json.tool
```

## Workload reports

| Reports (`.json.gz`) | Workload and qualification |
|---|---|
| `memory`, `default` | Instrumented/uninstrumented packaged baselines, empty/code/4 KiB/100 KiB/1 MiB/10 MiB; separate controls without heap flags |
| `startup`, `fonts` | Three launches per startup configuration; isolated JDK-only font catalogue/draw order controls |
| `ui-mixed`, `ui-adverse` | Original source operations and completed Swing paints, adverse long-line/headings/table/fence/Unicode |
| `keyboard`, `insertion`, `subtree` | Existing Tranche 5 source-integrity, checked-edit and Undo/Redo performance controls |
| `semantics`, `semantic-memory`, `lexical` | Shared semantic lifetime/worker controls and viewport/checkpoint lexical work |
| `index`, `index-native`, `navigation` | Original 1,001-file dense index/navigation benchmark; separate 1,002-file low-density NMT/disposal diagnostic |
| `media`, `rich` | Original media service and matched NMT/JFR service-vs-renderer controls; bounded admission/disposal |
| `preview-plain`, `preview-rich`, `preview-typing`, `preview` | Existing preview probes plus actual production preview/resize and queued source-edit delay |
| `search` | Existing find/replace convergence and buffer-switch workload |
| `lifecycle`, `lifecycle-qualified` | Repeated switching/preview/picker operations and read-only retention-root inspection |
| `retention`, `retention-qualified` | Eight closed indexed copies; existing completion query and existing job-history turnover causal controls |
| `unicode` | Correct UTF-16-content/Undo assertion adapter, preserving original startup and latency measurement |
| `constrained` | Bounded 128 MiB heap persistence/Undo/recovery probes, including confounded 10 MiB verification OOM |
| `defaults-startup`, `defaults-ui`, `defaults-memory`, `defaults-unicode` | Separate checks of the newly requested bundled font/light defaults; Unicode first-use blocker remains |

Failures remain evidence. The unchanged Unicode UI harness's UTF-8-byte/UTF-16-length assertion failed after severe production latency; the new adapter verified exact source rather than treating that assertion as corruption. The 10 MiB constrained probe timed out after heap exhaustion while retaining duplicate verification state; it is not a passing low-memory qualification. One diagnostic invocation rejected a pre-existing output directory and was rerun to a new path. Intermediate Maven failures and their final passing verification are preserved.

## Reproduction

Use the repository JDK resolver and a fresh output path outside the checkout. Examples:

```sh
python3 scripts/qualify-markdown.py --jar /path/to/frozen-audit.jar --group memory --output /tmp/shed-7a-new-memory
python3 scripts/qualify-markdown.py --jar /path/to/frozen-audit.jar --group retention --output /tmp/shed-7a-new-retention
python3 scripts/qualify-markdown.py --group unicode --output /tmp/shed-7a-new-unicode
python3 scripts/benchmark-markdown.py --mode ui --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 10 --output /tmp/shed-7a-new-ui
python3 scripts/benchmark-editor.py --runs 3 --output /tmp/shed-7a-new-desktop.json
```

These are headful, opt-in, serial workloads with disposable scratch homes/files. They do not use a user's existing workspace/session. Do not run them concurrently with each other for latency comparisons. The qualification runner's plain-launcher controls intentionally terminate their own observed process after seven seconds; diagnostic runs have 120-second bounds. Existing benchmark runner timeouts are recorded per invocation. Never create system-wide memory exhaustion to reproduce the constrained-heap observation.

## Verification

- Original Maven: **1,535 passed, five skipped, zero failures/errors** (1,540 total).
- Final defaults Maven: **1,537 passed, five skipped, zero failures/errors** (1,542 total).
- Final focused defaults/palette regression: **65 passed**.
- Final tooling discovery: **19 passed**.
- Packaged Mermaid/math/image smoke: **PASS**.
- Intermediate assertion failures and one transient picker timeout remain in logs; no assertions were disabled.
- README SHA-256 remains `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

See the engineering report for P1 blockers, inference boundaries, unqualified platforms, low-system-RAM limits and proposed 7B/7C gates. Passing tests is not full release qualification. No later tranche was implemented.
