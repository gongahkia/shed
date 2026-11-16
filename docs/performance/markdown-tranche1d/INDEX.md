# Tranche 1D — reproducible highlighting evidence

Read [the implementation report](../../MARKDOWN_HIGHLIGHTING_PERFORMANCE.md). The source/undo/persistence model, source view and Markdown preview are unchanged; this tranche changes lexical work, request ownership and stale-span validation.

| Artifact | Contents |
| --- | --- |
| `cases.json.gz` | Complete reports for 50 bounded child cases and 16,345 raw samples: commands, input hashes, source/JAR/runner identities, metadata, timings, scan counts, allocations, states and environments. |
| `case-index.json` | Per-case commands, fixture generation/hashes, identities, completion status, environment/load/pressure and sampled RSS. |
| `measurements.json` | Derived nearest-rank statistics by group/case/operation; dates/artifacts are not pooled. Raw fields and existing runner summaries remain separate. |
| `profiles.json` | SHA-256/paths for bounded JFR recordings and the existing streaming decoder's summaries. Stack counts overlap; allocation samples are not retained memory. |
| `executions.json` | Sequential measurement/verification commands, exits, elapsed times and explicit outer watchdogs. |
| `regression-suites.json` | Full-suite counts and five existing skip reasons, captured before the headless subset rerun. |
| `packaged-smoke.json` | Independent final shaded-JAR rendering/service-provider verification. |
| `verification-logs.json.gz` | Focused/new-test failure and correction, final build, full suite, headless subset, Python checks and packaged output. |
| `verification.json` | Baseline preservation, final source/artifact hashes, archive differences, exact commands, fixture/pixel checks and repository compliance. |
| `manifest.json` | Evidence file hashes, excluding the manifest itself. |

Large fixtures, full JFRs, frozen probes, classes and per-child logs remain in the external run root named by commands. No generated large Markdown fixture or profiler binary is committed. No benchmark process is intended to remain running.

## Groups and interpretation

- `historical-control`: three requests per position on the preserved pre-1D distribution, before changing production code.
- `paired-ui-before` / `paired-ui-after`: 14 November, 100 production requests per position after five warmups, across 100 KiB/1 MiB/10 MiB. The after artifact is the initial checkpoint candidate.
- `paired-lexical-before` / `paired-lexical-after`: isolated caller-thread lexer replays, ten samples per position after two warmups plus ten cycles of each edit/jump/switch scenario. These do not instantiate an editor or measure GUI convergence.
- `adverse-before` / `adverse-after`: five lexical samples/one warmup with 1 MiB long-line, nested-list, table, fence, heading and dense-marker fixtures.
- `profile-before` / `profile-after`: separate 10 MiB production JFR runs, ten samples/two warmups per position. Do not pool profiled timings with ordinary latency results.
- `final-ui-before-*` / `final-ui-after-*`: fresh 16 November final-artifact pairs, 100 production samples/five warmups per position. These are the final convergence table.
- `final-source-before` / `final-source-after`: 100 actual source operations pooled over beginning/middle/end after ten warmups; insert/delete/newline/undo/redo, caret, selection, scroll, longest-line and split controls. Every case verifies exact source restoration and file hashes.
- `final-startup-before` / `final-startup-after`: three fresh processes each, 100 KiB Markdown. Input is verified; OS/font caches are uncontrolled. These are not cold-cache results.
- `editing-check-after` / `editing-check-before`: one bounded reverse-order 1 MiB source pair investigating the first final-source outlier. It did not reproduce the 29.68 ms insertion p95. Both original and follow-up remain visible.
- `headless-cache`: final 1 MiB replay with an explicitly headless JVM and synchronous invalidation timings. The cache API runs on the replay caller; these are not live EDT/input measurements.

The candidate and final `GrammarHighlightService` class family are byte-identical. Final code adds the small installed-revision paint guard; final GUI/source runs use the rebuilt final JAR. Probe hashes identify earlier and later diagnostic versions. Early lexical runs used display-capable JVMs but created no UI; the final runner explicitly selects headless mode for `syntax-lexical`.

`WARMUP` rows include requested initial warmups and the separately named `lexical_after_undo` maintenance requests that re-establish state between measured edit cycles. They are excluded from ordinary summaries. Do not call those maintenance requests additional measured viewport samples. Per-edit full-reference checks are outside the timed lexical section. Replay fixture search/restoration and GC observations are also outside that section.

All 50 child cases completed with unchanged fixture hashes. No physical keyboard, compositor or quiet-machine confidence claim is made. Final GUI runs experienced substantial variable host load; the implementation report preserves p99 spikes and source-edit/scroll regressions in individual pairs instead of claiming every number improved.

## Routine headless correctness checks

Java 21, the existing Maven toolchain and existing cached dependencies are sufficient:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=true -DargLine=-Xmx512m \
-Dtest=MarkdownHighlightTest,GrammarHighlightServiceTest test

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
```

The lexer subset passes 16 cases without skips. A headless full-suite run would skip actual window/viewport integration and is not a replacement for the final GUI run.

## Build and headless lexical replay

Build with the established offline command:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-DskipTests -Dmaven.jar.forceCreation=true package

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode syntax-lexical --shapes mixed \
  --sizes 102400 1048576 10485760 --samples 10 --warmups 2 --timeout 120 \
  --output /tmp/new-shed-lexical
```

The wrapper chooses `-Djava.awt.headless=true` for this mode. Limits are at most 10 MiB per lexical fixture, 25 requested samples, at most ten edit cycles per scenario, bounded heap and child watchdog. Scenarios cover visible/before-range insertion/deletion, fence changes, an unmatched fence, rapid edits, distant jumps and a separate buffer. Replays compare visible tokens against full reference scans and restore exact source after each edit cycle. The old-JAR control detects absence of checkpoint support and executes the actual former full/prefix API; it does not add a new implementation to the old artifact.

Use `--shapes long-line nested table fence headings markers --sizes 1048576 --samples 5 --warmups 1` for the adversarial matrix. With an explicitly new output directory and a preserved old JAR, the same command provides a paired control. The baseline JAR SHA-256 and external path are archived. Do not reset the working tree to reconstruct a baseline.

## Explicit GUI qualification

These commands need a real/virtual display because they measure actual workbench geometry and painting:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode syntax --shapes mixed \
  --sizes 102400 1048576 10485760 --samples 100 --warmups 5 --timeout 180 \
  --output /tmp/new-shed-syntax-ui

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode ui --shapes mixed \
  --sizes 102400 1048576 10485760 --samples 100 --warmups 10 --timeout 180 \
  --output /tmp/new-shed-source-controls
```

Syntax convergence includes caret/scroll dispatch, the existing 120 ms debounce, worker completion/application and 10 ms state polling. The notification row is held stable and viewport/font/2× scaling recorded. The source input-to-paint metric begins after EDT dispatch and excludes native keyboard delivery/compositor scanout; queue and mutation/CPU/paint stages are separately recorded. Source loops may postpone highlighting during continuous edits, so they are not proof of every possible overlap with nonlocal analysis.

The syntax mode previously ignored its sample/warmup options and always ran three iterations. It now honours them and excludes warmups from summary percentiles. This changes measurement counts, not the convergence boundary. At n=100 the p99 is a single extreme order statistic, not strong tail qualification.

For startup use `--mode startup --sizes 102400 --samples 1 --warmups 0 --runs 3 --timeout 30`. Do not call a fresh process a cold-cache launch.

## Profiles and final regressions

Add `--profile` to a bounded `syntax` run for a separate attribution case. The existing runner enables capped JFR (64 MiB), GC logs and native-memory tracking. Decode with the existing `MarkdownJfrSummary.java` compiled independently against the JAR into a disposable directory. Whole-recording stack presence and weighted allocation samples include startup and other jobs; they are not additive stage totals or retained heap.

The final suite and packaged smoke commands were:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m test

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --jar target/shed-2.0.0.jar --output /tmp/new-shed-highlighting-packaged.json
git diff --check
shasum -a 256 README.md
```

Full result: **1,330 passed, five existing skips, zero failures/errors**. The packaged smoke is already headless and uses only its probe plus the supplied shaded JAR. No development-classpath rescue is allowed. New full-reference viewport/pixel and worker/cache lifecycle tests run in the full GUI suite. Earlier failures from the new test's intermediate-state wait are retained in logs; the correction waits for exact settled output rather than changing assertions or delays.

All workload outputs are isolated temporary data, not user documents. JVMs use a 64–512 MiB heap in these runs, fixture generation remains deterministic and bounded, and watchdogs affect only owned children. No dependencies were installed, system settings changed, system-memory exhaustion attempted or unrelated process terminated.
