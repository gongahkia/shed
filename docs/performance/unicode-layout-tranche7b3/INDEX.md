# Tranche 7B.3 evidence

See [the engineering report](../../UNICODE_LAYOUT_INVESTIGATION.md). This is a negative production-correction result: the production stall is reproduced and attributed, but no safe local patch is delivered. No later tranche is implemented.

Artifact: final-7B.2 packaged JAR `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`; starting/final HEAD `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`. The JAR and all 1,372 pre-existing file hashes matched at completion. README remains `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing uncommitted 7B.2 work is preserved, not relabeled as this tranche's changes.

## Inventory

| File | Contents |
| --- | --- |
| `baseline.json.gz` | Starting status, HEAD, JAR identity and every pre-existing file SHA-256 |
| `reports.json.gz` | Complete unmodified reports, exact child commands, probe/fixture hashes, environments, RSS observations and samples |
| `measurements.json` | Readable per-case timing summaries, phase heap, EDT allocations and independent final fixture-byte checks |
| `profiling.json` | Two JFR summaries, representative allocation/EDT stacks, overlapping stack-presence counts, JDK source hashes and external recording identities |
| `diagnostics.json.gz` | All probe stdout including warnings and failed child, Maven/tooling logs, sampled long-line thread stack, exact diagnostic Java source versions and evidence-generation script |
| `packaged-smoke.json` | Independently compiled packaged-only Mermaid/math/image smoke result |
| `verification.json` | Full/focused/tooling totals, Surefire class counts/skip reasons, source/README/HEAD preservation and causal-control assertions |
| `inventory.json` | Every new file and its purpose; distinguishes unchanged pre-existing work |
| `manifest.json` | Checksums for all new deliverables except the checksum file itself |

Gzip files contain ordinary UTF-8 JSON. For example:

```sh
gzip -dc docs/performance/unicode-layout-tranche7b3/reports.json.gz | python3 -m json.tool
```

## Workloads and interpretation

The `reports.json.gz` top-level keys retain separate invocations:

- `reproduction`: unchanged qualification runner, established 100 KiB/1 MiB Unicode fixture, plus independent 100 KiB NMT/JFR control.
- `stock`: initial instrumented JDK-only view control; `stock-final`: repeated with final diagnostic source. Counting/StackWalker work is not a production latency control.
- `matrix`: ten text categories at 4 KiB/100 KiB. Nineteen completed children and one **failed 90-second long-line child**; its partial results remain evidence.
- `large`: 1 MiB ASCII/mixed-script controls.
- `wrap`: requested wrap applied after the original startup phase; warm operations and later ASCII-to-Unicode action are wrapped. First startup insertion is unwrapped and is not relabeled.
- `dialog`: separate explicit saved-font override; default font is never replaced in production.
- `profile`: independent 100 KiB mixed-script JFR process.
- `long-guarded`: final 100 KiB single-line rerun, one slow cycle then guard. Source/Undo exact; latency still fails. Single-operation observations are not meaningful percentile estimates.
- `source-control`: unchanged generic UI benchmark, 100 measured mixed-ASCII inserts per size after ten warmups; no Unicode byte/UTF-16 assertion ambiguity.

The original matrix version lacked the slow-cycle guard. Diagnostic source snapshots are preserved and hash-matched to its reports. No timed production algorithm was changed. Binary JFR recordings remain in the isolated `/tmp` directories identified in `profiling.json`; summaries, relevant stacks and hashes are stored here. Recordings are not part of the application artifact. No heap dump or system-wide pressure experiment was made.

`OK` means a child completed its correctness/measurement protocol, **not** that it met responsiveness thresholds. The standalone `matrix` runner exits 1 because the initial long-line child timed out. The guarded repeat succeeds as a measurement protocol while still observing multi-second operations. All caught line-number-paint warnings remain in stdout. No latency gate or regression assertion was weakened.

The original Unicode reproduction records 30 warm inserts; the category probe normally records 20 cycles. First-edit samples remain separate. Only `source-control` supplies the 100-sample ordinary typing control here. Peak RSS, uncollected phase heap, allocated bytes, weighted JFR allocation and retained memory are not interchangeable. There is no before/after performance claim because production code did not change.

## Reproduction commands

Use a fresh external output directory for every invocation. Run children serially on a working desktop with the repository's JDK 21 resolver and a frozen current JAR:

```sh
python3 scripts/qualify-markdown.py --jar /tmp/shed-tranche7b3-run/before.jar --group unicode --output /tmp/shed-tranche7b3-reproduction
python3 scripts/investigate-unicode-layout.py --jar /tmp/shed-tranche7b3-run/before.jar --mode stock --categories ascii mixed --sizes 4096 102400 --output /tmp/shed-tranche7b3-stock-final
python3 scripts/investigate-unicode-layout.py --jar /tmp/shed-tranche7b3-run/before.jar --categories ascii latin combining emoji zwj indic cjk mixed bidi long --sizes 4096 102400 --output /tmp/shed-tranche7b3-matrix
python3 scripts/investigate-unicode-layout.py --jar /tmp/shed-tranche7b3-run/before.jar --categories ascii mixed --sizes 1048576 --output /tmp/shed-tranche7b3-large
python3 scripts/investigate-unicode-layout.py --jar /tmp/shed-tranche7b3-run/before.jar --categories ascii mixed --sizes 102400 --wrap --output /tmp/shed-tranche7b3-wrap
python3 scripts/investigate-unicode-layout.py --jar /tmp/shed-tranche7b3-run/before.jar --categories ascii mixed --sizes 102400 --font Dialog --output /tmp/shed-tranche7b3-dialog
python3 scripts/investigate-unicode-layout.py --jar /tmp/shed-tranche7b3-run/before.jar --categories mixed --sizes 102400 --jfr --output /tmp/shed-tranche7b3-profile
python3 scripts/investigate-unicode-layout.py --jar /tmp/shed-tranche7b3-run/before.jar --categories long --sizes 102400 --output /tmp/shed-tranche7b3-long-guarded
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche7b3-run/before.jar --mode ui --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 10 --output /tmp/shed-tranche7b3-source-control
```

The historical `matrix` command ran the archived pre-guard version; the current version stops slow repetition. Do not remove the guard to recreate prolonged stalls. Initial/second stock controls have identical scope. Processes have 64–512 MiB heap bounds and 90-second new-runner / 120-second old-qualification watchdogs. No user session, OS appearance or personal source is changed.

Final verification: **1,556 Maven passes, five existing skips, zero failures/errors; 42 focused passes; 22 tooling passes; packaged media PASS; diff check PASS.** Full commands and remaining qualification limitations are in the engineering report.
