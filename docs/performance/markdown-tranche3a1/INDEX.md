# Tranche 3A.1 — evidence index

The [A–N implementation report](../../MARKDOWN_LINK_INFRASTRUCTURE.md) describes production behavior, ownership, safety limits and the 3A.2 handoff. These measurements qualify the bounded fixtures below; they do not establish arbitrary-project scalability or resolve native-memory attribution.

## Repository and artifacts

Task-start HEAD: `1444c64fda20eacb244a2019626ec654a531f7ed`. Starting changes were the completed 2C report, editing probe, MarkdownController, workbench tests and untracked 2C evidence. All 1,115 baseline files were retained. Prior performance evidence is byte-identical. External commits advanced HEAD through `6a0d53469c38024485aa2917a1ee06bb4e0f2d4a` and `521b6d37e5776c6c06881d8730df82b21092d6cc` during execution. This agent issued no commit, push, reset, clean or revert command.

Before JAR: `7f3d6f0a9cd3523e686c498d9052824a0440c36e0e1591a8a0b5c22cd6b3f19a`. Final verified JAR: `015ad8a09ff2f467518124c73998afde591bcaf10a1f38ecd5b55b993dac81de`. The final `verified` project run and packaged smoke use that exact final JAR. Earlier paired UI/startup runs and development artifacts have their separate hashes in the archived cases; the final changes to retry/disposal guards do not affect the unrequested typing/startup paths.

## Reproduction

Java 21 and Maven are resolved using the existing scripts. This host reused the isolated Temurin 21.0.13+9 and Maven 3.9.11 from earlier verification. No project dependency was changed. Output directories must be new and outside the checkout.

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B -Djava.awt.headless=false \
  -DargLine=-Xmx512m test

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -B -DskipTests package

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --jar target/shed-2.0.0.jar --output /tmp/shed-3a1-NEW-smoke.json

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar target/shed-2.0.0.jar --mode project-links --shapes plain \
  --sizes 4096 102400 1048576 --samples 100 --warmups 5 \
  --timeout 90 --output /tmp/shed-3a1-NEW-projects

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --jar /path/to/before.jar --mode ui --shapes plain \
  --sizes 4096 102400 1048576 --samples 30 --warmups 5 \
  --timeout 90 --output /tmp/shed-3a1-NEW-before

# Repeat ui with the final JAR; use --sizes 1048576 --samples 100 --runs 3 for the repeated pair.
# Repeat startup-empty with both artifacts: --sizes 4096 --samples 1 --warmups 0 --runs 3.

git diff --check
shasum -a 256 README.md
```

The final narrow regression command also used the same JDK/Maven and `-Djava.awt.headless=true -DargLine=-Xmx512m -Dtest=MarkdownLinkInfrastructureTest,MarkdownSemanticParserTest,MarkdownConsumersTest,MarkdownHeadingEditPlanTest,MarkdownEditTest,MarkdownServiceTest,MarkdownHighlightTest,MarkdownFoldingTest,VersionedTextSnapshotTest,FileBufferTest,ProjectFileScannerTest,AsyncJobServiceTest test`. That earlier focused run passed 151 cases; subsequent additions are included in the final complete suite. All exact Maven invocations and outputs are archived in `verification-logs.json.gz`.

## Production workload

Each isolated workbench opens a 4 KiB, 100 KiB or 1 MiB source and creates 20, 100 or 1,000 additional Markdown notes with eight outgoing references each. All final partitions completed. The 1,001-file fixture contains 8,000 note links plus the open document. It is below the explicit file/record/metadata limits. A separate core test verifies the over-cap case remains partial. These are generated, warm-filesystem workloads, not cold disk-cache tests.

The actual facade is requested, typing/undo generates 200 revision changes, and convergence is observed. Its timer postpones new work until quiet; each final case submitted two jobs and rejected one obsolete completion. Only one typing sample overlapped its initial cancelled job. A separate production-facade pass indexes the note subproject without document notifications from the unrelated typed document. Real worker overlap counts are reported below. No artificial worker delay is used in the benchmarks.

| Files / source | Request EDT, ms (1) | Cold worker min–max, ms (3) | Unchanged refresh p50 / p95, ms (20) | File refresh p95, ms (20) | Overlay refresh p95, ms (20) | Outgoing / backlink p95, ms (100) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 21 / 4 KiB | 4.42 | 12.14–13.62 | 0.68 / 1.45 | 1.18 | 1.19 | 0.0131 / 0.0118 |
| 101 / 100 KiB | 5.05 | 20.11–27.26 | 1.81 / 2.56 | 2.91 | 2.44 | 0.0150 / 0.0110 |
| 1001 / 1024 KiB | 4.45 | 158.29–228.40 | 15.36 / 21.74 | 15.92 | 15.62 | 0.0141 / 0.0129 |

Unchanged refreshes parsed zero documents; overlay and single-file refreshes parsed exactly one. Disk metadata discovery remains bounded but runs on refresh. Current 2B semantic results can be reused without another parser call. Edge reuse avoids filesystem work for unchanged direct catalog targets; independent symlink aliases are rechecked. Initial construction after typing includes saved and dirty contributions, hence `parsed=files+1` when the dirty source matches saved text after Undo. The two identities remain separate; no content-equivalence cache was introduced.

| Source | Same-source convergence including typing/quiet period, ms (1) | Overlay convergence including 180 ms debounce, ms (1) | Inactive typing p95, ms | Requested/index-debounced typing p95, ms | Independent worker typing p95, ms | Worker-overlapping typing samples / 100 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 KiB | 469.61 | 194.56 | 1.32 | 0.71 | 1.41 | 5 |
| 100 KiB | 535.89 | 213.30 | 1.44 | 1.33 | 2.31 | 9 |
| 1024 KiB | 853.74 | 263.68 | 2.30 | 1.62 | 3.55 | 44 |

Typing includes action dispatch and completed Swing paint; native input delivery/compositor timing is excluded. The independent worker case has partial overlap, not 100 continuously concurrent samples. Its 1 MiB maximum was 11.20 ms, while p95 was 3.55 ms. No claim that every dispatch meets the percentile budget is made.

## Paired typing and startup

The initial 30-sample UI pairs at three source positions (90 inserts per size) recorded insert-to-paint p95 before/after of 1.57/1.25 ms (4 KiB), 2.40/2.12 ms (100 KiB), and 2.45/2.97 ms (1 MiB). That last observation warranted repeat qualification; it is preserved rather than hidden. Three 100-sample/position 1 MiB runs recorded the following per-child p95 values:

- repeat-before: 2.05 ms, 2.20 ms, 1.66 ms.

- release-typing: 10.22 ms, 2.06 ms, 2.33 ms.

These sequential pairs show run-to-run variability and occasional slower after values; they do not support a general speedup claim. All remain below the proposed 5 ms p95 fixture budget. The new inactive edit path is only a null-service notification guard. No parser is called by ordinary unrequested typing.

Startup observations use three empty-workbench children per artifact. Before sampled peak RSS ranged 608.52–634.28 MiB; after ranged 607.69–614.28 MiB. This small sample cannot establish startup percentile equivalence. The deterministic workbench test verifies the link facade remains uninitialized before request; no worker, partition or scan is created. Existing source-paint/input startup observations and raw environment data are retained.

## Memory and workload gate

Memory observations wait for established one-shot timers and IDE jobs to quiesce, then perform three explicit GCs. They are diagnostic snapshots, not a census of index objects. Normal latency samples do not use forced GC. The RSS sampler polls approximately every 250 ms; these are sampled peaks, not kernel high-water guarantees.

| Source / files | Used heap before / retained / disposed, MiB | Committed heap before / retained / disposed, MiB | Settled RSS before / retained / disposed, MiB | Sampled peak RSS, MiB | Cold builder allocation range, MiB (3) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4 KiB / 21 | 12.84 / 13.40 / 13.37 | 64.00 / 64.00 / 64.00 | 651.09 / 656.94 / 657.34 | 669.97 | 2.59–2.62 |
| 100 KiB / 101 | 13.54 / 14.49 / 14.21 | 64.00 / 64.00 / 64.00 | 664.28 / 670.64 / 671.75 | 692.31 | 13.68–13.84 |
| 1024 KiB / 1001 | 22.28 / 27.25 / 23.87 | 66.00 / 94.00 / 84.00 | 670.06 / 700.91 / 719.80 | 910.05 | 132.18–132.99 |

At 1,001 files, post-GC used heap increased by 4.96 MiB with the project retained and fell by 3.38 MiB on disposal. The facade's weak reference was collected in all three cases; suppliers/listeners, partitions and workers are released. Some application/JIT/graphics/undo work remains warm after disposal, so neither delta is an exact model size. Following repeated builder operations, used heap was 28.60 MiB with 100 MiB committed and RSS 777.56 MiB.

**Native-memory qualification remains unresolved.** Final sampled peak RSS reached 910.05 MiB. This heavier multi-file/repeated-build workload is not equivalent to 2C's single-document peak of 770.53 MiB or its historical settled desktop reference. It exceeds those references and is retained as an open whole-process qualification, not described as resolved. Cold project construction allocated about 132–133 MiB transiently across 1,001 files; retained used-heap growth is much smaller. RSS did not return to baseline after disposal. These data do not attribute native font/graphics memory, heap commitment or the full peak to the compact index. No JVM/GC or rendering optimization was attempted.

Limits were selected to admit this measured 1,001-file workload with about 5 MiB used-heap growth while refusing unrestricted scans/retention. Proposed same-host fixture budgets are cold worker <1 s, refresh p95 <50 ms, query p95 <0.1 ms, request EDT <10 ms, typing p95 <5 ms, retained post-GC used-heap growth <8 MiB and collection after disposal. Current measurements meet these fixture budgets. There is no claim that total RSS meets the historical desktop budget. Production admits one isolated index worker; no scan starts at startup, and no complete source/AST/per-revision cache is retained.

## Regression verification

Final Maven: **1,416 cases; 1,411 passing, five existing skips, zero failures/errors**. All 38 link infrastructure tests and the additional workbench case pass. Eleven Python tooling tests passed. The final shaded-JAR Mermaid/math/image smoke passed, including invalid-Mermaid error recovery (expected diagnostic errors in its stderr). `git diff --check` passed.

Existing skips:
- `shed.DataLossRegressionTest.atomicSavePreservesDosHiddenAttributeWhenSupported`: org.opentest4j.TestAbortedException: Assumption failed: DOS file attributes unavailable
	at org.junit.jupiter.api.Assumptions.throwAssumptionFailed(Assumptions.java:316)
	at org.junit.jupiter.api.Assumptions.assumeTrue(Assumptions.java:115)
	at shed.DataLossRegressionTest.atomicSavePreservesDosHiddenAttributeWhenSupported(DataLossRegressionTest.java:196)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

- `shed.FocusedAiNativeAcceptanceTest.actualEditorAcceptsAcpSuggestionsAndRendersWrappedMultilinePreviews`: System property [shed.ai.native] does not exist
- `shed.FocusedAiPerformanceTest.measurePreviewPreparationPaintingAndDismissalAcrossBufferSizes`: System property [shed.ai.benchmark] does not exist
- `shed.TerminalShellIntegrationTest.preparesAnIsolatedFishStartup`: org.opentest4j.TestAbortedException: Assumption failed: Fish is unavailable
	at org.junit.jupiter.api.Assumptions.throwAssumptionFailed(Assumptions.java:316)
	at org.junit.jupiter.api.Assumptions.assumeTrue(Assumptions.java:115)
	at shed.TerminalShellIntegrationTest.preparesAnIsolatedFishStartup(TerminalShellIntegrationTest.java:48)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

- `shed.TexteditorSwingIntegrationTest.testExplorerUsesAnExplicitCtestPresetWithoutGuessingABuildTree`: org.opentest4j.TestAbortedException: Assumption failed: CMake, CTest, and CPack are unavailable
	at org.junit.jupiter.api.Assumptions.throwAssumptionFailed(Assumptions.java:316)
	at org.junit.jupiter.api.Assumptions.assumeTrue(Assumptions.java:115)
	at shed.TexteditorSwingIntegrationTest.testExplorerUsesAnExplicitCtestPresetWithoutGuessingABuildTree(TexteditorSwingIntegrationTest.java:3584)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)


The full suite includes parser, semantic consumers/edit plans, Markdown editing, lexical highlighting/source view, folding, snapshot/FileBuffer, worker/document lifecycle, recovery/persistence, preview and other IDE regressions. No skipped test was added. Test source and final suite totals correspond to the final production code. Full-suite repeats followed actual fixes to scheduling, anchor compatibility and lifecycle/retry guards. Earlier failures and their logs remain in the archive.

## Evidence files

- `baseline-files.json.gz`: starting HEAD/status and 1,115 file hashes, including prior uncommitted work.
- `cases.json.gz`: final benchmarks, paired controls and development runs with parameters, artifact/probe/fixture hashes, samples, RSS and host data.
- `measurements.json`: selected final project measurements and observations.
- `regression-suites.json`: exact JUnit suites and skip reasons.
- `packaged-smoke.json`: final artifact/provider/math/Mermaid/image verification.
- `verification-logs.json.gz`: exact verification invocations/output and development failures.
- `verification.json`: repository/artifact/README/preservation checks and totals.
- `manifest.json`: complete evidence inventory and SHA-256 hashes.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No missing baseline files, modified prior evidence, new dependencies, eager project scans or visible UI additions were found. Production APIs perform reads only. No later tranche was begun.
