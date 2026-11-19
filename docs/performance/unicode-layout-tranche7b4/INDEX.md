# Tranche 7B.4 evidence

[Engineering report A–L](../../UNICODE_LAYOUT_FEASIBILITY.md). **Production decision: NO-GO.** The isolated range adapter reduces source reads and allocation, but multisecond first edits remain. No production change or later tranche is delivered.

Starting/final HEAD: `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`. Frozen/current JAR SHA-256: `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`. All **1,393 pre-existing file hashes** matched at completion, including prior uncommitted work and evidence. README before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

## Files

| File | Contents |
| --- | --- |
| `baseline.json.gz` | Original HEAD/status, production artifact identity, all baseline file hashes |
| `reports.json.gz` | Full unmodified reports: exact child commands, fixture/runtime-source hashes, environment, RSS, samples, phase heap, correctness observations |
| `measurements.json` | Readable operation summaries, first-use allocations, heap/EDT/read/disposal phases, fixture byte checks |
| `profiling.json` | Streaming first-edit allocation/EDT stack summaries, explicit windows, source hash and external original JFR identities |
| `diagnostics.json.gz` | All child stdout and Maven/tooling logs, versioned probe/prototype source, diagnostic corrections, export-overhead note and archive script |
| `packaged-smoke.json` | Independently compiled packaged-only Mermaid/math/image smoke |
| `verification.json` | Class totals, all skip reasons, repository/fixture/prototype-isolation checks, failed latency and unqualified memory-release gates |
| `inventory.json` | Every new file and its purpose; no pre-existing or production file modified |
| `manifest.json` | Deliverable checksums excluding the checksum file itself |

Compressed files contain ordinary UTF-8 JSON after gzip decompression. Binary JFR recordings remain in the external scratch paths recorded in `profiling.json`; their hashes and relevant stacks are retained here. No large binary or event-by-event JSON export enters the application or repository.

## Workloads

The nine keys in `reports.json.gz` distinguish **44 serial child processes**:

- `pilot`: 100 KiB mixed source, 20 warm cycles per variant.
- `gate`: 1 MiB ASCII/mixed, two fresh children per variant per fixture, ABBA order, 100 warm inserts/Undo per child. First insert and ASCII-to-complex text action are separate observations.
- `reads`: 1 MiB measured-document controls; same stock/candidate factories, no workbench. Counts are not mixed into latency trials.
- `model`: independent no-view FileBuffer insertion/Undo.
- `profile`: separate JFR stock/candidate children at 1 MiB, ten warm samples; profiled times are not substituted into the uninstrumented gate.
- `scripts`: 100 KiB combining, Indic, emoji, ZWJ, CJK and bidi, 20 warm samples per variant.
- `wrapped`: 1 MiB ASCII/mixed, 100 warm samples, wrap enabled before measured first edit. Initial file-attachment work can precede the wrap setting.
- `long`: 4 KiB/100 KiB single Unicode lines. A cycle exceeding 500 ms stops repetition; individual slow observations do not constitute a meaningful p95. All original slow results remain visible.
- `task-control`: paired 1 MiB follow-up with numeric uninterrupted-EDT-dispatch durations after correcting diagnostic array serialization. Earlier task fields remain unavailable as numeric metrics; their input/paint/allocations are still valid.

Every child completed its protocol, but this is **not** a passing performance result. First-use targets fail, and short weak-reference disposal observations remain inconclusive. The initial pilot/gate probe did not yet close the source buffer separately; later versions add that observation. Source hashes and all three versions are retained. No production algorithm changed between variants.

All timed children are isolated homes with `-Xms64m -Xmx512m`, JetBrains Mono 16 and Shed Light. Explicit heap bounds, a 75-second child timeout, slow-cycle guards and admission limits remain intact. No system memory exhaustion, font/theme substitution, cache purge, unrelated-process termination or personal-file modification occurred. Process peaks, phase used/committed heap, allocated bytes and weighted allocation samples are distinct metrics. Short requested-GC observations are not retained-size or native-resource certification.

## Reproduction

Use the repository JDK 21 resolver, a frozen current JAR and **new external output paths**. Run latency children serially:

```sh
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group pilot --samples 20 --output /tmp/shed-tranche7b4-pilot
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group gate --runs 2 --output /tmp/shed-tranche7b4-gate
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group reads --samples 1 --output /tmp/shed-tranche7b4-reads
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group model --samples 1 --output /tmp/shed-tranche7b4-model
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group gate --samples 10 --profile --output /tmp/shed-tranche7b4-profile
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group scripts --samples 20 --output /tmp/shed-tranche7b4-scripts
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group gate --samples 100 --wrap --output /tmp/shed-tranche7b4-wrapped
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group long --samples 20 --output /tmp/shed-tranche7b4-long
python3 scripts/benchmark-unicode-candidate.py --jar /tmp/shed-tranche7b4-run/before.jar --group gate --samples 20 --output /tmp/shed-tranche7b4-task-control
```

Historical runs use their archived source versions; the current probe emits valid numeric task lists. The factory guard and event recorder exist only in child probes. The prototype stays under `src/test`, is compiled explicitly for these opt-in trials and is absent from the application JAR.

For bounded profile summaries, compile `UnicodeCandidateJfrSummary.java` against the frozen JAR into an external classes directory, then run with `-Xmx256m`. Arguments are `recording.jfr begin_epoch_ms end_epoch_ms`; exact derived first-edit windows are stored in `profiling.json`. The summary streams events and caps distinct stack keys and recording size. Its overlapping stack groups are not CPU percentages. Expanded event JSON was temporarily generated after timed trials, then replaced by the streaming path; original recordings remain preserved.

## Verification

- Maven: **1,565 total, 1,560 passed, five existing skips, zero failures/errors**.
- Focused prototype/source/Unicode/lifecycle/keymap: **46 passed**.
- Tooling discovery: **25 passed**.
- Packaged Mermaid/math/image smoke: **PASS**.
- `git diff --check`, baseline hashes, unchanged fixture bytes and packaged prototype exclusion: **PASS**.
- First-edit target and no-multisecond-stall gate: **FAIL**.
- Full memory-release/retaining-path qualification: **UNQUALIFIED**.

Exact regression commands are in the report. No commit, push, runtime migration, production patch, preview remediation, 7C or appearance feature was started.
