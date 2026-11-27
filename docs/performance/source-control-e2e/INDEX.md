# Matched Source Control desktop performance qualification

**Verdict: all measured regression gates PASS.** Three alternating pairs / six fresh JVMs compare the pre-Source-Control artifact with the delivered implementation on the same host. This is bounded regression evidence, not proof that every IDE operation or platform is regression-free. No production source was changed in this follow-up.

## Artifacts and execution

- Baseline: commit `5a01e783435112343201eca915a2a811b9eb46be`, JAR SHA-256 `85a83ad649934a968023256f3dcecd516879816f364459af55621099b08c0087`.
- Candidate: delivered Source Control code at task entry `1884a1ea37e9d2ae08789dae0d8d7053fb24f4f1`, unchanged JAR SHA-256 `a176ba1258dce576dd0c89def40c8306e4f1a2e4588f5c0735293b4ba2bd0c3b`.
- macOS 26.5.2 arm64; Temurin 21.0.13.1+2. Same JDK/JVM arguments, production theme/font, ASCII fixtures and probe source for both artifacts. No heap/graphics/OS settings or accepted target was changed.
- Order: baseline, candidate, candidate, baseline, baseline, candidate. One process at a time; OS caches are warm. These are fresh-process measurements, not cold-cache starts. Host load, exact argv, fixture source hashes, artifact/probe identities, first samples and raw timing/EDT-event samples live in `matched.json.gz`.
- A bounded `git archive` exported only build/source/assets/license inputs to a disposable fixture; the reviewed offline Maven runner built the baseline. Its JAR hash exactly reproduces the earlier IDE-essentials qualification artifact. Export/build metadata are retained; the exported tree and both nested fixtures were removed. No checkout, reset, clean, personal configuration change or dependency installation was needed.

## Matched results

Values below are medians of three per-process p95 measurements, except startup/file opening (median duration), memory (post-diagnostic-GC heap), threads and descriptors. They are not pooled percentiles or statistical equivalence intervals. Typing includes queue submission to completed forced Swing source paint, which differs from the earlier probe's narrower in-EDT insertion/paint boundary. Do not compare those historical numbers directly.

| Workload | Baseline | Candidate | Result |
| --- | ---: | ---: | --- |
| buffer_switch (ms) | 2.491 | 2.347 | PASS |
| caret (ms) | 2.196 | 2.136 | PASS |
| deletion (ms) | 1.160 | 1.181 | PASS |
| newline (ms) | 1.623 | 1.832 | PASS |
| redo (ms) | 0.765 | 0.771 | PASS |
| scroll (ms) | 1.746 | 1.408 | PASS |
| selection (ms) | 1.147 | 1.570 | PASS |
| split_cycle (ms) | 3.216 | 2.962 | PASS |
| typing.hidden.100KiB.txt (ms) | 2.135 | 1.870 | PASS |
| typing.hidden.1MiB.txt (ms) | 1.472 | 1.409 | PASS |
| typing.hidden.Small.txt (ms) | 2.779 | 2.600 | PASS |
| typing.source_control_open (ms) | 1.343 | 1.476 | PASS |
| undo (ms) | 0.741 | 0.800 | PASS |
| open.Small.txt (ms) | 6.680 | 7.062 | PASS |
| open.100KiB.txt (ms) | 22.079 | 23.986 | PASS |
| open.1MiB.txt (ms) | 72.932 | 70.010 | PASS |
| startup (ms) | 1884.000 | 1904.000 | PASS |
| Settled used heap (MiB; three open fixture files) | 31.148 | 31.315 | PASS |
| threads | 22 | 22 | PASS |
| open descriptors | 11 | 11 | PASS |

Small-file `open.Small.txt` is an already-loaded file activation; 100 KiB and 1 MiB are first opens in that process. Startup is JVM uptime through project startup, controller-based opening and forced completed painting of the small ordinary source file. It does not measure OS launcher time, physical first key delivery or compositor readiness.

## New background-work and lifecycle coverage

The candidate runs the real Swing application and new Source Control controllers, with only the client runner replaced by fixed in-process responses. No native Mercurial/Subversion client is executed. Each typing row records one first sample, five separately retained warmups and 100 measured samples. Each common typing workload therefore has 300 measured samples per artifact; each current-only workload has 300. Other editor operations have 30 Undo/Redo, 20 split cycles and 50 deletion/newline/caret/selection/scroll/buffer-switch samples per process; p99 is reported only for the 100-sample typing rows.

| Current-only workload | p95 range across processes (ms) | Worst source insertion/paint delay, including first use/warmups (ms) |
| --- | ---: | ---: |
| typing.pending.MERCURIAL | 0.895–1.156 | 1.489 |
| typing.installing.MERCURIAL | 0.862–1.302 | 17.892 |
| typing.5000_rows.MERCURIAL | 0.840–0.915 | 5.877 |
| typing.failed.MERCURIAL | 0.848–1.318 | 2.400 |
| typing.pending.SUBVERSION | 0.758–1.350 | 2.421 |
| typing.installing.SUBVERSION | 0.918–1.545 | 7.006 |
| typing.5000_rows.SUBVERSION | 0.687–0.826 | 1.305 |
| typing.failed.SUBVERSION | 0.707–0.816 | 1.121 |

Pending requests remain held by a verified latch for the entire typing measurement; they are not fast successes labeled as background load. Installation releases a held 5,000-row response while source editing continues. A last-row/next-EDT-callback marker forces the SCM component paint and records response-release-to-installed-paint time: Mercurial 30.727–38.049 ms; Subversion 75.939–81.826 ms. These include background record/XML processing and are not language-server/network/native-client response times. Largest EDT event in those installation phases: Mercurial 6.912–9.682 ms; Subversion 3.646–4.009 ms. The longest observed source insertion/paint delay during bulk installation was 17.892 ms; p95 remained below the existing 16 ms target. First-use and other maximum delays remain in the raw results rather than being hidden by warmup.

Failed status responses leave source editing usable and recover through manual Refresh. Hidden providers stop their timer and make no additional client calls across 2.7 seconds, exceeding the normal 2.5-second poll interval. Twelve temporary real SCM panels per candidate process are closed with pending work, cancelled and checked through weak references: **36 total cycles, zero retained temporary panels, zero active fixed-client calls** after bounded diagnostic GC. Cancellation completed in 1.486–2.124 ms in the recorded provider cases. All candidate runs had 22 threads and 11 open descriptors after the diagnostic panel-disposal cycles; application shutdown reduced threads to 18, with 11 open descriptors and zero native descendants remaining at that boundary. This does not establish zero whole-process native-memory growth or qualify real-client process cleanup.

All source fixtures retain their original on-disk SHA-256. Controlled in-memory typing, deletion, newline, Undo/Redo, selection, scrolling, file switching and shared splits check expected source/document ownership. Synthetic external sentinel hashes and metadata remain unchanged. Every disposable fixture was removed.

## Gates fixed before collection

- Existing accepted target: every typing and queued-input row has p95 ≤16 ms.
- Proposed comparison budgets: common typing p95 may not exceed `max(baseline ×1.5, baseline +1 ms)`; other common editor p95 may not exceed `max(baseline ×1.5, baseline +2 ms)`. Split-cycle budget is `max(baseline ×1.25, baseline +5 ms)`.
- Proposed startup budget: `max(baseline ×1.2, baseline +100 ms)`; file-open budget: `max(baseline ×1.25, baseline +20 ms)`.
- Proposed settled common-memory budget: baseline +8 MiB used heap; baseline +2 threads and +4 open descriptors. These are practical engineering gates, not accepted universal product targets.
- Proposed bulk-install interference gate: no recorded insertion/paint sample, including first use and warmups, may exceed 50 ms.
- Exact sample counts, raw-percentile consistency, required workloads, cancellation/disposal, hidden polling, native-descendant absence, source/sentinel integrity and fixture cleanup are enforced. Missing/failed runs do not become success-shaped comparisons.
- One pair is diagnostic only (`INSUFFICIENT_PAIRS`, exit 2); at least three pairs are required for PASS. No failed native invocation is automatically retried. `--compile-only` is explicitly separate from runtime acceptance.

## Additional regression verification

- **130 Python tooling tests passed** before the final exit-code guard; **131 passed** with the final guard, including 15 new comparison/admission tests. Forged percentiles, insufficient samples/pairs, malformed/nonfinite data, material slowdowns below 16 ms, absolute typing failures, retained panels/workers, hidden polling and first-use installation stalls are rejected. Final gates re-evaluate the stored six-run data identically as PASS. These selections overlap and their counts are not additive.
- **14 focused safe Maven tests passed**, zero failures/errors/skips: `ScmWorkbenchModelTest,ScmWorkbenchServiceTest,ScmChangesPanelTest`. The prior full safe selection remains 611 passes/six skips on the exact unchanged candidate JAR; it was not rerun just to increase this follow-up's count.
- Same-probe existing watcher/Git E2E passed on **both** artifacts. It covers 5,000 files/142 changes, visible reads, edits, conflicts, saves/Undo, fonts/themes/density/focus/splits, patch refresh, EOF follow and cleanup. Reports are `watch-baseline.json.gz` and `watch-candidate.json.gz`.
- An additional candidate E2E run verifies retained bounded EOF diagnostics (`watch-trace-final.json.gz`): 19 observed event-loop turns over the 500 ms stability interval held the expected y=5226. The exact viewport-position assertion remains; it checks the captured position before collecting failure geometry, so diagnostic layout queries cannot turn a mismatch into success. The earlier trace run is retained separately.
- Initially the new EOF trace was written to a fixture file that the existing driver does not copy. It now lives inside the already-retained result events, including on assertion failure. The additional run verifies this evidence path; no production scrolling change or relaxed assertion was introduced.
- The earlier intermittent EOF-follow failure remains **unresolved**. It did not reproduce in these runs on either artifact; that does not establish its cause or resolve it. Original failure evidence remains in the preceding Source Control report.
- Make target dry-run passed; missing baseline fails with exit 2 before fixture launch. Normal build/test targets do not enable the benchmark.

## Run it again

A preserved, distinct baseline JAR is required. The reproduced pre-change artifact is available locally at `target/scm-performance-baseline.jar` (ignored build output).

```sh
make check-scm-performance SCM_BASELINE_JAR=target/scm-performance-baseline.jar
```

Equivalent explicit command:

```sh
python3 scripts/test-scm-performance.py \
 --jdk /Users/gongahkia/.local/share/shed-tools/jdk-21 \
 --jar target/shed-2.0.0.jar --baseline-jar target/scm-performance-baseline.jar \
 --pairs 3 --output <new-absolute-directory>
```

The optional Make variable `SCM_PERF_PAIRS` supports 1–5; one pair is a non-qualifying pilot. `--compile-only` checks both artifacts without starting a desktop. Raw results retain the exact fixed Java argv and fixture definitions. The normal Maven suite does not start this heavy benchmark.

## Limits and repository compliance

This is synthetic source-insertion-to-forced-component-paint timing on owned native Swing windows, not physical keymap/keyboard-to-compositor timing. It covers bounded ordinary ASCII text through 1 MiB; it does not qualify 10 MiB files, Unicode first-edit stalls, missing glyphs, Markdown HTML-preview stalls, real native SCM/network work, cold OS caches, whole-process RSS/native attribution, every IDE capability or Windows/Linux native GUI. Hg/SVN remain absent on this host. B0.1 native-shell confinement remains NO-GO; it was not used or bypassed. No real coding model/language server, personal repository, credentials or shell history was involved.

Entry working tree was clean. Changes are opt-in benchmark/tooling, one bounded diagnostic addition to the existing watcher fixture, Make discoverability and documentation/evidence. Production source, UI/UX, defaults, preferences and the candidate JAR remain unchanged. No dependencies were installed, no user work was discarded, and no pushes were made. README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. `git diff --check` passes. Local commits separate tests/tooling from the report/evidence.

## Resource follow-up

The subsequent [idle-resource optimization](../idle-resource-optimization/INDEX.md) compares this candidate against worker retirement and batched table installation, retaining the same latency gates. The original measurements above remain historical evidence.
