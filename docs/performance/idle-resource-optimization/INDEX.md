# Idle resources and Source Control refresh optimization

**Verdict: PASS for the measured resource and responsiveness gates.** Two local production changes reduce unnecessary idle workers and table notifications. Heap and descriptor savings are not claimed as meaningful improvements.

## Changes and rationale

`AsyncJobService` previously retained its fixed worker threads indefinitely after first use. The baseline profile observed four parked `shed-worker` threads after 35 idle seconds. Default workers now retire after 30 seconds without queued work. Maximum active concurrency remains the existing CPU-dependent two-to-four workers; the current host uses four. Running tasks are not timed out by this policy. New work recreates workers through the existing executor. Caller-supplied executors, cancellation, history, EDT completion and the separate long-running virtual executor retain their existing boundaries. No polling service or new setting is added.

`ScmChangesPanel` previously installed each changed-file/history row separately. A 5,000-row status response delivered 5,000 table notifications in the measured fixture. Its read-only `RowsModel` now constructs a complete replacement and emits one data-change notification. Column objects and user-sized widths remain intact; it does not issue a structure-change event. Malformed rows reject before installation, and replacement requires the EDT. Source documents, revisions, Undo, navigation, colors and provider semantics remain separate and unchanged.

## Matched measurements

Three alternating baseline/current pairs, six fresh JVMs, one process at a time. Same macOS 26.5.2 arm64 host, Temurin 21.0.13.1+2, JVM arguments, fixtures and probe. Warm OS caches; no heap, graphics or OS tuning. Each process includes the existing editing, SCM interference and disposal workloads plus 35 seconds without shared-worker jobs. Used heap follows three diagnostic GC requests, confined to the benchmark. No production GC call was introduced.

| After the idle interval; median of three processes | Before | Optimized |
| --- | ---: | ---: |
| Total Java threads | 22 | 18 |
| Parked Shed workers | 4 | 0 |
| Used heap | 31.432 MiB | 31.388 MiB |
| Open descriptors | 11 | 11 |
| First background request round trip after idle | 0.756 ms | 0.946 ms |
| Model notifications for 5,000-row initial status | 5,000 | 1 |

Heap run ranges overlap: before 31.269–31.555 MiB; optimized 31.373–31.396 MiB. There is no reliable material heap reduction established by this sample. Descriptor counts were stable and were left alone. Thread counts include JVM and test-probe threads and describe this workload, not a universal application count. While work remains active or periodically refreshes, workers remain available and the active count can still be 22.

The first optimized request after idle completed in 0.626–1.252 ms. Recreating a worker has a small observed cost; it remained within the predefined 16 ms EDT-submission and 50 ms completion budgets. No claim that retiring threads makes ordinary typing faster follows from this result.

All existing matched regression gates passed, including typing p95 ≤16 ms, common editing, startup, file opening and memory/resource comparisons. Candidate typing p95 across covered workloads ranged from 0.729 to 2.506 ms. One first sample, five warmups and 100 timed samples per typing row are retained. Raw first-use/max values remain visible. These timings measure queue submission through forced completed Swing component paint, not physical keyboard-to-compositor latency. Batching reduces notification work deterministically; the small sample does not establish a universal perceived speedup.

## Verification

- Focused worker/model/service checks: **21 passes**, no failures/errors/skips (`focused1.json.gz`). Added coverage verifies retirement after active work, resumed jobs, unchanged concurrency, daemon ownership, timeout validation, a single row-change event, read-only cells, preserved column identity/width, atomic rejection and EDT ownership.
- Full source-reviewed safe Maven selection with LSP measurement: **621 total; 615 passes, six existing skips**, zero failures/errors (`maven.json.gz`). Native/unreviewed tests and retired `ProjectFileOpenPrerequisiteTest` remain excluded.
- Python tooling: **132 passes**, zero failures (`tooling-media.json.gz`), including rejection when workers do not retire or resumption exceeds its budget.
- Live Chat/Source Control workflow: PASS (`chat.json.gz`): provider changes/history, confirmations, stale contexts, source integrity and agent interaction.
- Live disposable Git/watcher workflow: PASS (`watcher.json.gz`): external reads/edits, conflicts, saves/Undo, appearance, splits, EOF follow and cleanup.
- Packaged Mermaid/math/local-image smoke: PASS (`tooling-media.json.gz`).
- The six matched processes passed source/sentinel integrity, hidden polling, disposal and descendant checks (`matched.json.gz`).

Selections overlap; counts are not additive. The earlier intermittent EOF-follow failure remains unresolved despite this passing run. Real Mercurial/Subversion clients, native-shell confinement, native-memory/RSS attribution, Unicode first-use stalls, preview stalls and Windows/Linux desktop qualification remain open. No shell, real language server or real coding model was launched for this work. Native-shell NO-GO restrictions were not bypassed.

## Reproduce

The pre-optimization JAR is preserved locally as ignored build output at `target/resource-idle-baseline.jar`.

```sh
make check-scm-performance \
 SCM_BASELINE_JAR=target/resource-idle-baseline.jar SCM_PERF_IDLE=1
```

`SCM_PERF_IDLE=1` adds the 35-second idle check to each process; ordinary benchmark runs and normal tests do not wait for it. Equivalent explicit invocation:

```sh
python3 scripts/test-scm-performance.py \
 --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21 \
 --jar target/shed-2.0.0.jar --baseline-jar target/resource-idle-baseline.jar \
 --pairs 3 --idle-workers --output <new-absolute-directory>
```

The reviewed Maven runner uses the existing JDK, offline Maven 3.9.11 installation and bounded dependency cache, with the same command pattern documented in the preceding qualification reports. Exact expanded selections, JVM argv, fixture identities, host load, thread names, samples and process cleanup are preserved in the raw evidence. No personal history/configuration was used as a probe.

## Artifact and repository compliance

Entry HEAD: `979f784960b5f2442ec1965d38098dbe0a0f78b9`, clean working tree. Baseline JAR SHA-256: `a176ba1258dce576dd0c89def40c8306e4f1a2e4588f5c0735293b4ba2bd0c3b`. Optimized JAR SHA-256: `1fedffb99194bbc633e7fc7bb37d68f0d5469725995e0a44fe61f9af2b356268`. All 1,627 compiled main classes match packaged bytes (`artifact.json`).

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Existing user work/evidence, UI design, themes/fonts, saved preferences, keymaps and authoritative document ownership are preserved within the tested boundaries. No dependency installation, new framework, reset/clean/revert/discard or push. `git diff --check` passes.

Production commits are separate: `f5f702a38` (idle workers) and `e78410800` (table snapshots). Benchmark extensions and this evidence are committed separately.
