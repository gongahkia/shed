# Tranche 2C — semantic editing and memory evidence

The [A–M implementation report](../../MARKDOWN_SEMANTIC_EDIT_PLANS.md) describes the edit contract, migrated commands, unsupported behavior, source/history safeguards, highlighting compatibility and proposed 3A/3B boundaries. No later tranche was implemented.

## Repository baseline and scope

Task-start HEAD: `9a12c55b41aa7052e4365ffe2d590d70c6ef8166`. The four modified 2B files and ten untracked 2B evidence files were preserved. `baseline-files.json.gz` records their exact bytes alongside all other tracked/nonignored files. The shared workbench test file was extended, preserving its earlier additions. External commits advanced HEAD during this task; no commit, push, reset, clean or revert command was issued by this task.

The pre-2C JAR was copied before production changes: SHA-256 `22b07df9a2f9affc74dbb78aad087ce61c9eb4e30e5c51f125d99dc06e143fc5`. Final artifact/source identities and HEAD movement are in `verification.json`. Previous performance evidence, README, manifests, document/snapshot/undo implementations, semantic parser/ranges and lexical highlighting files retain their original hashes.

## Reproduction

Use the repository's Java 21/Maven resolver with a desktop session. This task reused the isolated Temurin 21.0.13+9 and Maven 3.9.11 installed during 2B; it did not upgrade project dependencies.

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B -Djava.awt.headless=true \
  -DargLine=-Xmx512m \
  -Dtest=MarkdownHeadingEditPlanTest,MarkdownEditTest,MarkdownConsumersTest,MarkdownSemanticParserTest test

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B -Djava.awt.headless=false -DargLine=-Xmx512m test

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B -DskipTests package

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --jar target/shed-2.0.0.jar --output /tmp/shed-2c-NEW-smoke.json

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar /path/to/before.jar --mode semantic-edits --shapes mixed \
  --sizes 4096 102400 1048576 --samples 100 --warmups 5 \
  --timeout 90 --output /tmp/shed-2c-NEW-before

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode semantic-edits --shapes mixed \
  --sizes 4096 102400 1048576 --samples 100 --warmups 5 \
  --timeout 90 --output /tmp/shed-2c-NEW-after

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode semantic-edits --shapes mixed \
  --sizes 2097152 --samples 100 --warmups 5 \
  --timeout 90 --output /tmp/shed-2c-NEW-unsupported

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode semantic-memory --shapes mixed \
  --sizes 102400 1048576 --samples 30 --warmups 0 \
  --timeout 90 --output /tmp/shed-2c-NEW-memory

git diff --check
shasum -a 256 README.md
```

Repeat memory with `--mode semantic-memory-control` (same edit/undo source changes without semantic requests), with both artifacts. The diagnostic adds `--profile` and uses only 1 MiB. NMT and macOS `vmmap -summary` run against the probe's own process on its main thread. No production instrumentation or JVM/GC tuning was introduced. Actual commands, fixture/JAR/probe hashes and environments remain in raw reports. Output must be a new directory outside the checkout.

## Correctness and regression boundary

Plan tests cover exact ATX markers, EOF, Unicode/UTF-16, formatting, containers, boundaries, Setext rejection, code/escaped examples, absent/invalid ranges, unsupported/projected/failed/cancelled semantics, stale/foreign plans, undo/redo, source/history/dirty/selection preservation, save/reopen bytes and recovery contents. Lexer tests replay the actual one-character edit events, compare deep-viewport tokens with the full lexer and assert suffix reuse/checkpoint bounds.

Workbench tests exercise all three keymap configurations, actual Vim heading keys, guarded cold edits, repeated pending requests, current-result reuse, source edits elsewhere, caret return, mode/key input, focus loss, buffer return, closure, splits, independent viewports, actual preview refresh and recovery scheduling. A test-only completion observer preserves all existing EOF/compound-undo assertions while waiting for the now-asynchronous command.

Earlier failures are retained rather than counted as success: new split tests initially mixed pane setup/caret activation with the edit, and one existing buffer-picker test timed out. Corrected setup, focused verification and the final full run retain the original assertions. The picker timeout's cause remains unverified. An early 2 MiB benchmark failed because its own typing/undo setup left dirty state set; the corrected harness compares snapshot identity and explicitly resets fixture dirty state. No source validation was weakened.

Exact final totals and skip cases are in `regression-suites.json`; commands/logs are in `verification.json` and `verification-logs.json.gz`. The five established skips remain DOS attributes, Fish environment, CTest-preset environment and the two opt-in AI suites. Rich-media smoke includes both service-provider registrations, local images, math, Mermaid cache/change cases and invalid-input recovery.

**Final complete Maven: 1,377 cases, 1,372 passed, five existing skips, zero failures/errors.** Eleven Python tooling tests passed. Final packaged smoke passed against JAR SHA-256 `7f3d6f0a9cd3523e686c498d9052824a0440c36e0e1591a8a0b5c22cd6b3f19a`. The final workbench suite has 116 cases (one existing skip), and the new plan suite has nine passing cases. `git diff --check`, README and untouched-file preservation passed.

## Performance interpretation

Editing runs use the actual packaged workbench, source actions, existing bounded semantic coordinator, `MarkdownEdit`, ordinary undo and completed Swing paint attribution. They are separate from isolated 2A parser measurements. They use the existing deterministic ASCII mixed fixtures, isolated homes, Monospaced 16 pt, 2× display scaling, default unwrapped source and 64–512 MiB child heaps. The runner samples RSS approximately every 250 ms and imposes a 90-second watchdog per child.

Typing, warm marker editing, undo/redo and pure planning use 100 measured samples after five warmups. Cold requests/completion and natural highlighting convergence have ten observations per eligible size. Highlight convergence includes the existing 120 ms debounce and 10 ms state polling. Planning/rejection include reflective invocation overhead. Normal input is delivered through the existing controller/Swing action, not a physical keyboard/compositor. No cross-platform performance certification is inferred.

Warm edits assert zero extra semantic parse submissions. Ordinary typing never requests semantic analysis. Prewarming is explicit benchmark setup outside the warm command timing; successive source mutations do not make old semantics current. Cold commands include a new worker request and defer application until their original state still matches. Repeated pending edit commands receive busy feedback instead of a delayed mutation queue.

At 2 MiB, the final command rejects before scheduling with explicit size-limit feedback, without mutation/history or a semantic parse. The old command's faster regex behavior is not treated as equivalent semantic correctness. The unsupported probe now excludes warmups; earlier groups with 105 unsupported samples retain their original counts and are not relabelled as 100.

Paired latency summaries are in `measurements.json` and the implementation report. An early 100 KiB typing comparison increased from 0.834 to 2.188 ms p95. Its reverse-order control measured 1.008 ms before and 0.806 ms after, and remains separate from other groups. No noisy run is discarded or used to claim statistical equivalence. Startup rows are single fresh-process observations per fixture with uncontrolled font/filesystem caches; all observed an uninitialized semantic service before explicit requests.

Final qualification uses `paired-qualified-before` / `paired-qualified-after`, the same final probe source, and the final source artifact including inactive-viewport preservation. P95 ms:

| Source | Typing to paint before → after | Heading edit EDT before → after | Undo to paint before → after | Redo to paint before → after | Plan after | Highlight convergence before → after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 KiB | 0.969 → 0.855 | 0.996 → 1.150 | 0.997 → 0.700 | 1.305 → 0.581 | 0.072 | 136.679 → 136.342 |
| 100 KiB | 0.832 → 1.579 | 1.121 → 1.211 | 1.277 → 1.161 | 1.148 → 1.328 | 0.122 | 136.633 → 135.938 |
| 1 MiB | 1.162 → 1.126 | 0.776 → 1.084 | 1.063 → 1.017 | 0.736 → 0.885 | 0.265 | 136.107 → 136.282 |

Final cold request EDT p95 was 0.156 / 0.083 / 0.094 ms, completion 15.849 / 15.282 / 41.591 ms (ten each). Stale rejection p95 was 0.020 / 0.023 / 0.021 ms (100 each). The final 100 KiB typing difference remains 0.75 ms; its cause is unverified. These observations support responsive editing in the measured workloads, not universal latency or equivalence claims. The 2 MiB final rejection took 0.0035 ms p95 and typing-to-paint 1.105 ms p95 (100 each); no rejected command changed source or submitted analysis.

## Memory qualification

The historical 835.23 MiB value was a sampled **peak process RSS**, not settled model retention. Current controls distinguish five application phases: before semantic use, first analysis, 30 changed-revision outlines, buffer switching, and outline/source closure. Each records used/committed heap and its own `ps` RSS before and after explicit GC. The final probe waits for existing nonrepeating syntax, symbol, completion, recovery and LSP timers as well as jobs; initial jobs-only observations remain exploratory because delayed work could allocate after the check. JVM/native maintenance can still overlap observations.

Timer-aware unprofiled pair, MiB. Maximum RSS is the maximum of runner samples and explicit phase observations, not an unsampled absolute peak.

| Source / artifact | Initial settled RSS | Maximum observed RSS | RSS after closure | Used heap with outline | Used heap after closure | Median worker allocation per revision |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 KiB before | 632.59 | 660.86 | 660.86 | 14.311 | 14.074 | 5.79 |
| 100 KiB after | 629.61 | 662.36 | 662.36 | 14.295 | 14.056 | 5.78 |
| 1 MiB before | 660.83 | 785.09 | 717.62 | 26.873 | 24.254 | 61.21 |
| 1 MiB after | 655.67 | 772.47 | 717.02 | 26.997 | 24.286 | 59.95 |

The 1 MiB semantic pair committed 66 MiB heap initially, 97 MiB after repeated outlines and 84 MiB after closure. Application-observed model/outline release was 2.62 / 2.71 MiB. Both weak result references were collected; both sources were closed, with exactly 30 analyses. The benchmark still holds its original `FileBuffer` for verification, so it is not a source-buffer deep-size/leak census.

The final source artifact was checked again after viewport preservation: 30 analyses, maximum observed RSS 770.53 MiB, used heap with outline / after closure 26.796 / 24.176 MiB, committed heap 100 / 87 MiB, and verified result collection/source closure. The table's earlier paired/profiled artifact identities remain explicit; this final control is a separate group (`memory-qualified-after`).

Without semantic requests, 1 MiB controls reached maximum observed RSS 660.12 / 666.88 MiB and post-closure used heap 23.886 / 23.943 MiB, with zero analyses. A substantial fixed footprint therefore exists before semantic use on this host. Thirty admitted parses also allocate substantial temporary objects; this is not a hard allocation-budget parser. No heap cap, parser cap or concurrency limit was increased.

The separate profiled pair is diagnostic, not pooled with timings. VM-map graphics residency was about 120.7 MiB before the outline, 125.8 MiB with it and 120.7 MiB after closure. NMT distinguishes thread stacks, GC metadata, metaspace/code, tracing and heap commitment; mapped/shared libraries/resources contribute further footprint. `vmmap` physical footprint and resident mapping totals differ from `ps` RSS. These numbers must not be summed as an exact RSS attribution. MXBean, NMT and VM-map snapshots occur at different instants; raw captures retain those differences.

**Disposition:** no new retained semantic-model regression from 2C is established by this pair. High desktop RSS and repeated-parse peaks remain a qualification, including prior 835 MiB evidence. Model collection is verified, not equated with complete native-resource release. No broad font/renderer/startup/GC/cache change was authorized or made. A separate fixed-footprint investigation remains appropriate before widening persistent semantic/media consumers.

Host: Apple M3 `Mac15,12`, eight logical CPUs, 16 GiB RAM, macOS 26.5 arm64, Temurin 21.0.13+9. Raw reports retain actual load, memory pressure, swap, font/display, JVM, fixture and artifact identities. Comparisons with the earlier Intel host or historical settled footprint are not claimed as speedups or memory equivalence.

## Evidence inventory

| File | Contents |
| --- | --- |
| `cases.json.gz` | Separate exploratory, failed/corrected, paired, reverse-order, memory/control and profiled cases, with commands/environments, stdout, GC logs and hashes. |
| `measurements.json` | Per-case latency summaries, states and counters; no pooling across groups/artifacts. |
| `memory.json` | Phase heap/RSS, maximum observed RSS, allocations and collection checks, with explicit quiescence definitions. |
| `profiles.json` | NMT/VM-map captures, JFR summaries and binary-recording identities. Binary recordings stay outside the checkout. |
| `regression-suites.json` | Final complete Maven suite counts and skips. |
| `packaged-smoke.json` | Final packaged-only runtime/Markdown/math/image/Mermaid smoke. |
| `verification-logs.json.gz` | Test/build/tooling/smoke and benchmark logs, including earlier failures; measured probe source versions. |
| `baseline-files.json.gz` | Task-start HEAD/status and tracked/nonignored file hashes, including prior 2B work. |
| `verification.json` | Exact commands, final artifact/source identities, external HEAD movement and preservation checks. |
| `manifest.json` | Hashes of all evidence except the manifest itself. |

No new UI components, dependencies, source ownership, highlighting pipeline, startup service, workers or persistent caches were added. README was never edited; before/after SHA-256 is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Tranches 3A/3B/3C/4 remain unimplemented.
