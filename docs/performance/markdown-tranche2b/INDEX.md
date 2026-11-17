# Tranche 2B — production semantic-consumer evidence

The [implementation report](../../MARKDOWN_SEMANTIC_CONSUMERS.md) contains the A–M architecture, scope, consumer, occurrence, fallback, lifecycle, compatibility and next-tranche handoff. This directory completes the evidence missing when work resumed at clean HEAD `9a12c55b41aa7052e4365ffe2d590d70c6ef8166`.

## Verification

Final Maven: **1,364 cases, 1,359 passed, five existing skips, zero failures/errors**. The complete suite includes 12 consumer cases, 13 semantic-parser cases and 112 workbench cases (one skipped). It also covers Markdown editing/folding, syntax/checkpoint highlighting, source-view metrics, previews, FileBuffer, undo, recovery and LSP. The five skips are the established DOS-attribute, Fish environment, CTest-preset environment and two explicitly opt-in AI cases; exact names are in `regression-suites.json`.

The expanded focused headless consumer/parser run passed 25 cases. The two original navigation tests and two added symbol-picker/closure tests passed independently. Ten Python benchmark-tooling tests passed, and the packaged-only Mermaid/math/image smoke passed, including both runtime provider registrations and invalid-input recovery. Expected invalid Mermaid diagnostics are retained in that successful smoke report.

No JUnit failures, errors, benchmark child failures or watchdog timeouts occurred in this continuation. The first Maven attempt could not launch because Maven and the configured JDK were missing; that prerequisite failure is recorded rather than counted as a test result. Java 21 and Maven were unpacked under `/tmp` without changing project dependencies. No existing tests or assertions were weakened. A pre-final full suite passed 1,363 cases/five skips; the final suite adds the buffer-closure test.

## Reproduce

Use a working JDK 21 and Maven with the existing resolver. This host used Temurin 21.0.13+9 and Maven 3.9.11. Set `JAVA_HOME` and `MVN` to their local locations if needed. GUI runs require a desktop session.

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B \
  -Djava.awt.headless=true -DargLine=-Xmx512m \
  -Dtest=MarkdownConsumersTest,MarkdownSemanticParserTest test

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B \
  -Djava.awt.headless=false -DargLine=-Xmx512m test

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B -DskipTests package

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --jar target/shed-2.0.0.jar --output /tmp/shed-2b-NEW-smoke.json

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode semantic-consumers \
  --sizes 4096 102400 1048576 2097152 --shapes mixed \
  --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-2b-NEW-mixed

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode semantic-consumers \
  --sizes 102400 1048576 --shapes markers table fence headings \
  --samples 5 --warmups 1 --timeout 90 --output /tmp/shed-2b-NEW-adverse

git diff --check
shasum -a 256 README.md
```

Exact executed commands and tool/artifact identities are in `verification.json` and the raw case reports. The profiled diagnostic used the same runner with `--sizes 1048576 --shapes mixed --samples 5 --warmups 1 --profile`; it overlapped the final Maven suite and is excluded from latency conclusions.

## Production measurements

These measurements use the actual packaged workbench, existing job pool, outline dialog, navigation, shared splits and editor input-to-completed-Swing-paint instrumentation. They do not substitute the isolated 2A parser benchmark for integration costs. Workbench startup precedes explicit heading requests. All children observed `service_initialized=false` before the first request.

Final mixed cases: 100 samples after five warmups, 64–512 MiB heaps, 90-second watchdogs and isolated homes. The table shows **p95 ms**; extraction is on already-available semantic records and navigation is on the unchanged revision.

| Bytes / result | Outline request EDT | Full heading worker | Observed convergence | Cached navigation EDT | Typing EDT | Typing to Swing paint | Occurrences worker |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4,096 / semantic | 0.512 | 1.677 | 19.593 | 0.435 | 0.560 | 0.645 | 0.020 |
| 102,400 / semantic | 0.306 | 5.106 | 14.801 | 0.315 | 0.949 | 1.204 | 0.165 |
| 1,048,576 / semantic | 0.439 | 40.762 | 57.234 | 0.268 | 0.717 | 1.064 | 0.548 |
| 2,097,152 / legacy unsupported | 0.280 | 4.617 | 16.811 | 0.349 | 0.776 | 1.062 | Unsupported |

The largest measured outline-period EDT dispatches were 9.34 / 1.67 / 11.04 / 1.47 ms. Dispatches include ordinary concurrent Swing work; they are not parser-only timing. Convergence includes worker scheduling, installation and 10 ms polling resolution. Typing samples label a job outstanding at the preceding check, which may mean queued or executing; uninterrupted parser/keyboard overlap is not inferred. Physical key delivery, compositor latency and frame pacing are unmeasured.

All four cases restored the exact source, used the shared source document in both panes, preserved the first pane's caret during navigation in the second and reused analysis without another parse in the cached navigation loop. Each recorded 109 reuse events. Stale rejections were 103 / 6 / 1 / 3. Queue cancellation, pruning and supersession can replace one another depending on scheduling; gated unit/integration tests provide deterministic freshness evidence.

One burst of 16 edit/undo pairs per child had p95 EDT costs 0.724 / 1.493 / 2.637 / 2.505 ms. Final convergence from burst start was 22.27 / 32.25 / 69.83 / 45.53 ms (one observation each). Split navigation took 0.204 / 0.209 / 0.193 / 0.214 ms; buffer switching converged in 17.07 / 4.63 / 14.26 / 16.63 ms (one each). These small groups are scenario checks, not reliable p99 estimates. The API emitted 8 / 212 / 2,204 verified occurrences from the supported fixtures; unsupported 2 MiB coverage emitted none with an explicit unsupported result.

The eight adverse cases all completed. At 100 KiB, marker/table/heading inputs used semantics with worker p95 56.90 / 11.60 / 53.09 ms (five samples; p95 is the maximum). Their 1 MiB counterparts took the conservative marker-admission fallback before AST construction, with worker p95 7.88 / 9.49 / 12.18 ms. The existing `fence` fixture generator leaves a padding line longer than 64 KiB at these sizes; both cases correctly reported the established semantic line-length limit. They are unsupported controls, not successful fenced-block semantic timings. Verified ordinary/Mermaid/unclosed fences are covered by deterministic tests.

The largest observed adverse outline-period EDT dispatch was 17.59 ms in the 1 MiB heading fallback case; this is a whole-dispatch observation, not parser execution on the EDT. Adverse typing-to-paint p95 ranged from 1.15 to 11.33 ms. No universal sub-16-ms dispatch guarantee is claimed. Existing automatic `.md` LSP launch jobs reported failure, with zero connected clients; external-server traffic/performance is not qualified by these benchmark runs.

## Memory, startup and limits

At 1 MiB, post-GC used heap with the outline was 28.87 MiB and after outline disposal 26.26 MiB: about 2.62 MiB released. This is an application observation including outline rows and concurrent caches, not a deep-size measurement. The pre-first-analysis observation was 27.83 MiB, demonstrating that ordinary startup/cache noise makes that subtraction unsuitable as an exact semantic size. Source and pane lifetime remain under existing owners; the benchmark itself still holds its original buffer for source verification.

Sampled whole-process peak RSS: **677.92 / 747.64 / 835.23 / 720.42 MiB**. The 835.23 MiB repeated 1 MiB refresh peak exceeds the historical 768 MiB desktop reference budget. No heap cap was increased; memory equivalence and low-memory qualification are not claimed. The one-running/one-pending coordinator and conservative dense-source admission bound concurrency and avoid the previously measured dense 1 MiB AST burst. They do not constitute a hard allocation budget for every admitted document.

The separate diagnostic captured about 301 MiB JVM-native committed memory early in startup (77 MiB heap, approximately 91 MiB thread stacks). JFR contains 185 execution/native samples and 916 weighted allocation samples; the largest sampled stacks were existing native font initialization and graphics work. Semantic analysis appeared on worker stacks. These observations do not attribute the whole RSS peak or identify an exact retained-byte cause. `profiles.json` retains NMT, JFR summary and recording identity; the binary recording stays outside the checkout. Profiling overlapped Maven and is not pooled with unprofiled measurements.

Host: **Apple M3, Mac15,12, eight logical CPUs, 16 GiB RAM, macOS 26.5 arm64, Temurin 21.0.13+9**. Load/pressure/swap, display/font, JVM and fixture identities are in raw reports. The warm, uncontrolled host differs from 2A's Intel machine; no cross-host speedup, cold startup or second-platform performance claim is made. No eager semantic worker/cache/startup scan was introduced. All 17 cases (four exploratory, four final, eight adverse, one profiled) completed; final/adverse/exploratory/profiled measurements remain separate.

## Inventory and repository compliance

| File | Contents |
| --- | --- |
| `cases.json.gz` | Seventeen raw cases, environments, commands, stdout, GC logs, fixture/probe/artifact identities; groups remain distinct. |
| `measurements.json` | Per-case summaries, outcomes, coverage paths, diagnostics and heap/RSS observations. |
| `profiles.json` | Labelled NMT/JFR diagnostic, summary, recording hash and attribution limitations. |
| `regression-suites.json` | Final per-suite counts and all five skip cases. |
| `packaged-smoke.json` | Independent packaged-only runtime/provider/math/image/Mermaid verification. |
| `verification-logs.json.gz` | Focused, workbench, full-suite, build, Python, smoke and benchmark runner logs. |
| `baseline-files.json.gz` | Starting HEAD, clean status and hashes of every tracked regular file. |
| `verification.json` | Exact commands, final source/artifact identities, untouched-file preservation and README checks. |
| `manifest.json` | Hashes of all evidence files except itself. |

The continuation changes only the two test files, optional consumer probe, report and new evidence above. All production files, document models, dependencies, previous performance evidence and other tracked files match their starting hashes. HEAD is unchanged. No commits/pushes/reset/clean/revert were performed. README was never edited; SHA-256 before and after is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

The existing visual identity, layouts, typography, themes, syntax colors, outline/picker components, command feedback, keymaps and pane/document ownership remain intact. Occurrence extraction stays read-only. Remaining semantic caps, legacy fallback inaccuracies, unresolved-reference/math/wiki-link/table-span limitations and unmigrated breadcrumbs/LSP/workspace consumers are documented in the implementation report. Tranche 2C is a proposal only; no edit-plan integration, backlink/wiki resolution, media rendering or live presentation was started.
