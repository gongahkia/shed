# Markdown Tranche 3A.2 — verification and performance evidence

The [implementation report](../../MARKDOWN_LINK_NAVIGATION.md) contains the A–O handoff and complete source-file inventory. This directory records actual artifact-based measurements, including development failures; it does not replace the independent 3A.1 evidence.

## Evidence files

- `cases.json.gz`: raw reports, samples, commands, host/load/swap metadata, source fixture hashes, RSS series and probe hashes. `matched-before` / `matched-after` are the final paired navigation results; `index-before` / `index-final-verified` are unchanged-methodology index controls. Earlier pilots and failures remain identified separately.
- `measurements.json`: final summaries, memory/lifecycle observations and startup controls.
- `measured-probe.json`: exact measured navigation probe and final hash; final whitespace check removed one trailing space, with no executable change.
- `regression-suites.json`: exact full-suite totals, suite counts and five skip reasons.
- `packaged-smoke.json`: final JAR Mermaid/math/image smoke, artifact identity and process output.
- `verification-logs.json.gz`: all task log files, including corrected failures and final successful runs.
- `baseline-files.json.gz`: starting HEAD/status and 1,132 preimplementation file hashes.
- `verification.json`: final preservation checks, file inventory, artifact hashes and toolchain.
- `manifest.json`: SHA-256 and byte counts for this directory's evidence.

## Method and reproduction

Same host: Apple M3, 8 CPUs, 16 GiB, macOS 26.5.2, Java 21.0.13, Maven 3.9.11. JVM fixture limit `-Xms64m -Xmx512m`; existing benchmark defaults retained. Runs are sequential. Native host load, swap and filesystem cache are uncontrolled and captured for each case. This is one paired workload per size, not a statistical population of machines.

The before JAR was copied before production edits. The identical final runner/probe sources compile against both artifacts. Sources are exactly 4 KiB/100 KiB/1 MiB, with 21/101/1,001 project Markdown files. Link prefixes replace equal-length source text, keeping the semantic cap. The source remains deliberately unsaved during navigation; fixture files remain unchanged. Each case uses 30 ordinary follow measurements, 100 typing/undo samples and 20 cycles of five picker interactions (100 windows), after warmup. Both sides open the same number of existing picker windows. The before side has legacy ordinary follow and generic picker controls over the same headless index; it does not have the new user commands, resolver validation or outgoing/backlink selection navigation. Generic controls therefore cannot establish old feature latency.

The after side invokes production commands and existing modal pickers. A 5 ms Swing timer drives the existing Enter or Escape actions. Filtering is verified by the separate Swing tests. Timing includes completed forced Swing paint, not native compositor or human-keyboard latency. Source/backlink selection measurements include validation/opening through the real opener; no direct document installation substitutes for navigation. Row construction is measured separately from modal visibility. Snapshot checks and index metadata refresh add asynchronous completion time without a synchronous semantic parse on the EDT.

The 100 typing samples during refresh rapidly supersede revisions; only one overlaps an active parse in each navigation case because work is coalesced. Do not interpret this as 100 continuously concurrent parses. The unchanged independent project control includes actual independent-worker overlap: 63 before / 49 after typing samples. Deterministic Swing tests separately saturate workers and validate supersession/out-of-order completion.

```sh
# use a Java 21 installation and Maven 3.9.11; MVN identifies its executable
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p test_benchmark_markdown.py
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar

# repeat for a preserved before JAR, then the final JAR; keep separate output directories
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar \
  --mode link-navigation --shapes plain --sizes 4096 102400 1048576 \
  --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-navigation-after
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar \
  --mode project-links --shapes plain --sizes 1048576 \
  --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-index-after
```

The archived commands retain exact executable paths, options, process identities and artifact hashes. The before artifact is not committed; its SHA is recorded. Existing 3A.1 `MarkdownLinkProbe` methodology was not changed by this tranche.

## Navigation and EDT measurements

| Measurement (ms; p50 / p95) | 4 KiB / 21 files | 100 KiB / 101 files | 1 MiB / 1,001 files |
| --- | ---: | ---: | ---: |
| Legacy follow EDT (n=30) | 0.799 / 1.194 | 0.665 / 1.118 | 1.105 / 2.101 |
| Semantic follow EDT (n=30) | 0.094 / 0.143 | 0.089 / 0.162 | 0.122 / 0.266 |
| Legacy follow to paint (n=30) | 12.264 / 14.701 | 12.149 / 13.792 | 14.010 / 15.417 |
| Semantic follow to paint (n=30) | 14.252 / 14.756 | 14.550 / 15.724 | 30.573 / 42.074 |
| Outgoing row EDT (n=40) | 0.069 / 0.358 | 0.062 / 0.188 | 0.058 / 0.175 |
| Backlink row EDT (n=40) | 0.106 / 0.341 | 0.267 / 0.660 | 0.439 / 0.926 |
| Outgoing picker to paint (n=20) | 15.441 / 28.494 | 17.143 / 45.721 | 27.527 / 93.508 |
| Backlink picker to paint (n=20) | 16.843 / 53.196 | 37.802 / 64.251 | 28.632 / 74.378 |
| Ambiguous picker to paint (n=20) | 15.506 / 39.075 | 21.037 / 52.209 | 30.465 / 96.528 |
| Outgoing selection to paint (n=20) | 20.042 / 35.564 | 19.881 / 36.197 | 19.758 / 35.383 |
| Backlink selection to paint (n=20) | 17.881 / 21.894 | 18.887 / 40.008 | 26.610 / 45.501 |
| Validation worker (n=30) | 0.172 / 0.217 | 0.178 / 0.298 | 0.606 / 0.710 |
| Source identity worker (n=30) | 0.071 / 0.105 | 0.066 / 0.085 | 0.095 / 0.192 |
| Before typing during refresh to paint (n=100) | 0.490 / 1.042 | 0.972 / 1.348 | 0.945 / 1.339 |
| After typing during refresh to paint (n=100) | 0.560 / 0.922 | 0.808 / 1.129 | 0.778 / 1.155 |

Picker acceptance and cancellation distributions are separate in raw summaries. The outgoing-accept picker p95 is 47.70 / 46.74 / 76.59 ms and backlink-accept p95 is 55.87 / 60.66 / 60.22 ms. Plain navigation becomes slower at 1 MiB (15.42 to 42.07 ms p95) because it now refreshes/validates revisioned semantics asynchronously. Dispatch p95 becomes shorter (2.10 to 0.27 ms). Neither figure is a substitute for the other. No claim of universally faster navigation is made.

## Independent 1,001-file index control

| Measurement (ms; p50 / p95) | Before | After | Samples per side |
| --- | ---: | ---: | ---: |
| Cold builder | 168.575 / 224.644 | 168.217 / 219.708 | 3 |
| Unchanged refresh | 14.107 / 17.255 | 14.727 / 16.901 | 20 |
| Single-file refresh | 13.164 / 14.987 | 14.570 / 15.903 | 20 |
| Unsaved overlay refresh | 15.688 / 17.123 | 14.598 / 15.682 | 20 |
| Outgoing query EDT | 0.004 / 0.011 | 0.003 / 0.012 | 100 |
| Backlink query EDT | 0.003 / 0.009 | 0.003 / 0.009 | 100 |
| Typing while independent index runs | 1.045 / 1.670 | 1.259 / 2.617 | 100 |

The cold-builder three observations are descriptive only; the reported p95 is effectively the maximum. After-control status is COMPLETE; its diagnostic stale-rejection count is one. Retained-index heap delta is 5.30 MiB after versus 5.07 MiB before; after disposal the service weak reference is collected. Occurrence line/column metadata adds compact integers, not document text or AST retention.

## Memory and cleanup

| Phase, 1 MiB / 1,001 files | Before used / committed heap (MiB) | After used / committed heap (MiB) | Before / after RSS (MiB) |
| --- | ---: | ---: | ---: |
| before index | 26.50 / 90 | 26.58 / 94 | 683.62 / 681.66 |
| before navigation | 31.42 / 110 | 31.55 / 110 | 726.69 / 721.30 |
| after follow navigation | 30.35 / 104 | 29.81 / 100 | 733.00 / 776.50 |
| after picker cycles | 30.55 / 104 | 31.20 / 106 | 763.03 / 780.39 |
| after project close | 27.05 / 90 | 26.30 / 94 | 754.09 / 771.00 |
| Sampled peak RSS | — | — | 845.42 / 844.12 |

Both sides collect all 100 picker weak references at every size. Navigation source revision deltas are zero. After project closure all partitions are removed and the interaction reports idle. At 1 MiB, after-navigation used heap is 29.81 MiB, after-picker heap 31.20 MiB, and after closure 26.30 MiB. Before-index heap is 26.58 MiB; source/editor/runtime resources remain intentionally live after partition closure. Heap commitment and RSS do not immediately return to initial values.

Independent index-control RSS before/retained/disposed is 665.45/685.45/703.22 MiB before and 684.05/703.14/724.05 MiB after. Peak sampled RSS is 892.78 versus **912.69 MiB**. This control also renders the existing workbench; it is not isolated index-native allocation. The 1 MiB UI fixture peak is 845.42 versus 844.12 MiB. The 100 KiB UI case settles after closure at 699.12 versus 726.12 MiB despite collected picker objects. These observations do not establish native-memory equivalence or identify a leak; the historical 910.05 MiB qualification remains unresolved. No broad JVM/renderer tuning was attempted. Used/committed heap, settled RSS and sampled peak RSS are separate measurements.

Startup controls (three empty-workbench runs each) use an earlier postintegration artifact, retained by exact hash. They show 19 threads, 64 MiB committed heap and approximately 22.8–23.1 MiB used heap on both sides; no project-index/navigation job appears. Sampled peak RSS is 613.31–632.81 MiB before and 609.59–617.16 MiB after. Full-UI-ready observations are 1.54–1.56 s before and 1.68–3.13 s after; these low-count, load-sensitive runs do not prove timing equivalence. Code inspection and startup job records establish no new startup index request, rather than attributing all elapsed-time differences to this tranche.

## Fixture regression budgets

For repeat runs on a comparable host: p95 dispatch and row EDT below 5 ms; p95 follow/selection/picker-to-forced-paint below 150 ms; p95 typing-to-paint below 5 ms; p95 warm queries below 0.1 ms; p95 single-file/overlay refresh below 50 ms. These are fixture gates with margin, not user-wide latency promises. Inspect outliers, host pressure and sample counts when exceeded. Do not raise semantic admission limits or memory ceilings to satisfy a timing gate. Retention gates are zero live picker weak references after collection, zero partitions/active interactions after closure, and no unexplained sustained heap increase across repeated cycles. RSS remains a separately qualified observation, not a passed native-memory budget.

## Verification and failures

Final full Maven run: **1,440 total, 1,435 passing, five existing skips, zero failures/errors**. Java/Python tooling: 11 passing tests. Final packaged Mermaid/math/image smoke passes against the same final JAR used by `matched-after` and `index-final-verified`. All final fixture hashes match. `git diff --check` passes.

Earlier logs include real failures: invalid HTML wiki-test input; canonical/logical URI mismatch; a probe offset assumption; picker focus handoff; and overlapping packaging output replacement. Each is preserved under its actual run name. Final builds were serialized; successful final logs do not erase the failed attempts. No tests were weakened or disabled. Cross-platform runtime/second-filesystem behavior is not verified on this single macOS host.

Repository preservation uses the initial hash inventory, not an assumption that HEAD stayed fixed. External activity advanced HEAD while this task ran. No agent commit or push was issued. Prior performance evidence and protected source/document/renderer/lexer/dependency files remain byte-identical; final hashes and modified-file inventory are recorded in `verification.json`.
