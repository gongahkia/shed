# Track A2.2 evidence index

Report: [Authenticated, context-validated editor navigation](../../AUTHENTICATED_EDITOR_NAVIGATION.md).

**CONDITIONAL GO for existing-pane Insert-mode headless/component navigation.** Caret, selection and reveal are implemented; file opening is deferred. Real window/controller integration, desktop and multi-platform acceptance, RSS and consistent cold-tail metadata timing remain OPEN. Native-shell qualification remains NO-GO. No A3 is implemented.

## Final evidence

Compressed reports retain complete expanded commands/output and fixture cleanup, including failures. Repeated/staged results are not added to the final test count.

| File | Evidence |
|---|---|
| `baseline.json.gz` | Clean entry HEAD/status and original input hashes. Entry `a5c8f71ae9f0b674a2cd1d7629fad8c6b022d298`. |
| `environment.json` | Linux x64/Python/JDK identity, reused task-owned Java 21/Maven/cache, exclusions. |
| `frozen-safe-tests.json.gz` | Final reviewed allowlist: **305 cases, 299 passed, six opt-in skips, zero failures/errors**, 59 classes. |
| `release-tooling.json.gz` | **86 applicable passes**; exactly one macOS-only case excluded on Linux. |
| `tooling.json.gz` | Unfiltered attempt: 87 cases, 86 passes, one macOS-only backend error; not relabeled as passing. |
| `release-package.json.gz` | Offline production packaging; tests deliberately skipped because separately verified. |
| `release-media.json.gz`, `release-media-detail.json.gz` | Final packaged image/math/Mermaid smoke and invalid-render recovery; no GUI/native-window qualification. |
| `entry-status.json.gz`, `entry-metadata.json.gz` | Current-host A2.1 probes from an archive of entry HEAD; no checkout reset/revert. |
| `final-status.json.gz` | Same status probe on A2.2 production source, separate fresh JVM. |
| `final-metadata.json.gz` | **One failed maximum assertion**: existing metadata EDT maximum exceeded 4 ms. The probe stopped before printing full metrics. |
| `final-metadata-recheck.json.gz` | Unchanged repeat passes. Cold-tail consistency/cause remains unverified; the failure is retained. |
| `final-navigation-applied.json.gz` | Final navigation round trips, EDT/allocation, stale-race and applied-command typing series, 24 disposal cycles, 0/168 tracked retention, threads/heap/descriptors/CPU. One opt-in test passes. |
| `measurements-summary.json` | Derived summaries; raw sample arrays remain in measurement logs. No pooling across JVMs. |
| `test-results.json` | Parsed final/intermediate counts, failures, skips and cleanup. |
| `artifact.json` | Final JAR SHA-256 and exact match of all **1,455** production classes; no navigation test fixtures shipped. Entry source archive hash also recorded. |
| `source-hashes.json`, `source-versions/` | Exact final hashes/compressed source versions for **22** production/test/tooling inputs (17 changed originals and five new files). These describe final versions, not every intermediate probe input. |
| `failure-notes.json` | Initial contract/fixture failures, Linux exclusion, metadata maximum failure and remaining gates. |
| `repository-verification.json` | README before/after hash, preserved-input audit, changed/new inventory, observed external commits/HEAD/status and whitespace results. |
| `checksums.sha256` | Integrity manifest for this directory and implementation report. |

The final navigation probe applies all 183 stable small-document commands. Its first concurrent-human series rejects all 51 stale revisions; its second series applies all 51 commands and queues typing at EDT apply admission through a notification-only hook. The EDT never waits for that benchmark driver. Warm typing p95 is **4.113 ms** for the applied series; cold control paint **167.748 ms** is retained. No lower warm sample is claimed as a speedup.

Measurements ran sequentially without simultaneous benchmark/compilation jobs. The existing metadata maximum failed once and passed on an unchanged repeat; no assertion/production guard was weakened for the repeat. Whole-JVM idle samples include JIT activity and do not qualify idle CPU. Heap/weak-reference observations are not RSS/native-memory bounds. The source report documents transport-timeout/lost-acknowledgment unknown outcomes, including an admitted request that outlives transport during filesystem validation.

## Intermediate evidence

| File | Meaning |
|---|---|
| `compile.json.gz` | Initial implementation compilation. |
| `stage-a.json.gz` | Eight contract passes and three `BUSY` errors caused by admission-release publication ordering. |
| `stage-a-corrected.json.gz` | All 11 initial contracts pass after the release-order correction. |
| `stage-b.json.gz` | Test compilation failure: missing `ArrayList` import; no test cases ran. |
| `stage-b-corrected.json.gz` | 55 passes, one fixture error: escaped request ID too short to exceed response bound. Corrected with a larger admissible ID. |
| `stages-a-d.json.gz` | 70 contract/transport/A1/A2.1 cases pass. |
| `safe-regression.json.gz` | Earlier 302-case run, 296 passes/six skips. |
| `final-safe-regression.json.gz` | 297 passes/six skips/one fixture error: notification rejected locally before transport. |
| `final-safe-corrected.json.gz`, `bounded-final-safe.json.gz` | Subsequent 298-pass/six-skip runs; long-line workload bound covered. |
| `release-safe-tests.json.gz` | 299 passes/six skips after the bounded tracking-capacity test. Final frozen run also includes the revised measurement source/admission hash. |
| `baseline-metadata.json.gz` | Earlier archived-entry metadata probe, not the final paired comparison. |
| `final-navigation.json.gz` | Earlier passing navigation probe before adding the successful-command/input series; not pooled into final percentiles. |
| `final-tooling.json.gz`, `manifest-tooling.json.gz` | Earlier applicable tooling pass and source-hash admission recheck. |
| `final-package.json.gz`, `final-media.json.gz`, `final-media-detail.json.gz` | Earlier package/media check before the final tracking-capacity change; final artifact uses `release-*`. |

## Reproduction and safety

Use a Java 21 JDK, Maven 3.9.11 and a prepared offline cache of the unchanged dependencies. Exact expanded Java/Maven arguments, disposable paths and all selected classes are in each raw report's `command`/`tests` fields. The measured toolchain paths are in `environment.json`. The runner copies the cache into its disposable fixture, validates reviewed source/helper hashes, sets headless mode/private home/temp/preferences, and launches Maven's Java entry without a shell launcher. It is reviewed trusted-code admission, not general process confinement.

```sh
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --output <new-safe-report.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --tests EditorNavigationTest,WorkspaceNavigationTransportTest,WorkspaceStatusContractTest,WorkspaceStatusTransportTest,WorkspaceMetadataTransportTest \
  --output <new-contract-report.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --tests WorkspaceNavigationMeasurementTest --measure-status \
  --output <new-navigation-measurements.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --tests WorkspaceStatusMeasurementTest --measure-status \
  --output <new-status-measurements.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --tests WorkspaceMetadataMeasurementTest --measure-status \
  --output <new-metadata-measurements.json>
python3 scripts/run-portable-tests.py --jdk <jdk-21-home> --maven <maven-home> \
  --repository <offline-cache> --package-only --output <new-package-report.json>
```

For an entry comparison, archive the recorded entry commit into a separate disposable directory and run its unchanged probe there. Do not reset the working checkout. The new navigation measurement reuses `--measure-status`; no native-runner mode is added. Default skips are `GlyphRenderingMeasurementTest`, `GutterPerformanceTest`, `LspDefinitionPerformanceTest`, `WorkspaceStatusMeasurementTest`, `WorkspaceMetadataMeasurementTest`, `WorkspaceNavigationMeasurementTest`.

Tooling runs `unittest.defaultTestLoader.discover("scripts", pattern="test_*.py")` in a fresh `native_fixture.Fixture` environment with Python user-site/bytecode output disabled. The applicable selection asserts Linux, asserts exactly one match and excludes only `test_native_fixture.NativeFixtureTest.test_profile_no_fork_network_or_user_home_access`. Its exact Python selector is preserved in `release-tooling.json.gz`; the unfiltered invocation/error remains in `tooling.json.gz`. No backend/profile assertion is changed to manufacture a Linux pass.

Packaged smoke runs `python3 scripts/test-packaged-markdown.py --jdk <jdk-home> --jar <final-jar> --output <new-report>` in the same reviewed disposable environment. The exact argument array and fixture cleanup are retained. This runs the existing headless provider/media path, not the native-shell fixture.

No full unreviewed Maven suite, native shell/PTY fixture, real language server/model provider, screenshot/capture, developer-project mutation, real `Texteditor` window, native desktop or Windows/macOS execution was used. File-opening path tests assert that every open command is unsupported; they do not qualify a loader. A3 is not started.

## Repository ownership

The implementation report enumerates all changed production/test/tooling files. `source-hashes.json` is exhaustive for those 22 inputs. Documentation is confined to the A2.2 report and this evidence directory. The baseline hash audit verifies every other pre-existing captured input, including README, prior reports/evidence, dependencies, themes/fonts and keyboard implementations.

No commit, push, reset, clean, revert or discard command was issued by this agent. External commits appeared during execution and are recorded/preserved; a clean working diff alone would not identify this task's work, so the evidence compares against entry input hashes and entry HEAD as well.
