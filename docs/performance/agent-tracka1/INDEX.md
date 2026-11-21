# Track A1 evidence index

Report: [Authenticated local control attachment](../../AUTHENTICATED_CONTROL_ATTACHMENT.md).

**PARTIAL:** production status-only code and guarded headless external-client communication are verified. A real desktop Shed window did not participate. Its enable/status/stop workflow, native startup/shutdown and Windows/Linux qualification remain open. B0.1 native-shell execution remains **NO-GO**.

## Staged evidence

All `.json.gz` files below contain complete commands, output and fixture-cleanup outcomes, not only extracted passing lines.

| Evidence | Outcome / meaning |
|---|---|
| `baseline.json.gz` | Entry HEAD/status and hashes for 2,131 existing inputs, including the pre-existing untracked A0 deliverables. |
| `compile.json.gz` | Stage A compile-only pass before new native transport/process fixtures. No shell/GUI execution. |
| `stage-a.json.gz` | 16 contract/private-file/existing-RPC passes, no process/socket fixture. |
| `stage-b.json.gz` | Initial real-loopback run: 15 passes, one timed-expiration cleanup failure. This is intentionally retained. |
| `stage-b-corrected.json.gz` | Same 16 tests pass after moving self-executor shutdown after credential cleanup. |
| `stage-c-admission.json.gz` | Two pre-launch guard tests pass; the guard checks themselves launch no child. |
| `stage-c.json.gz` | 14 passes: separate headless status CLI and existing CLI/bridge regressions through the guarded direct Java launcher. Server is a production bridge with an application-status fixture. |
| `final-safe-tests.json.gz`, `qualification-safe-tests.json.gz` | Intermediate safe-profile checks, 244 passes/four opt-in measurement skips each. |
| `release-safe-tests.json.gz` | Final frozen-code safe profile: 248 cases, **244 passes, four opt-in skips, zero failures/errors**, 52 classes. Includes the legacy argument-value versus status-flag regression. |
| `measurements.json.gz`, `final-measurements.json.gz` | Earlier bounded component/loopback probes, kept alongside their first-use and whole-JVM idle data. |
| `settled-measurements.json.gz` | Adds compiler/named-thread CPU attribution and warmed enabled/disabled snapshots. Earlier noisy observations remain preserved. |
| `release-measurements.json.gz` | Final opt-in measurement on frozen production code; one measurement test passes. No native window or shell. |
| `measurements-summary.json` | Extracted final structured measurements, with raw samples retained. Performance values are not whole-IDE startup/RSS results. |
| `tooling.json.gz`, `qualification-tooling.json.gz`, `release-tooling.json.gz` | Python admission/benchmark tooling checks under isolated Fixture environments. Final result: **87 passes**. |
| `package.json.gz`, `release-package.json.gz` | Safe offline packaging, tests skipped deliberately; no native fixture admission. Final artifact identity is in `environment.json`. |
| `packaged-smoke.json.gz`, `packaged-smoke-driver.json.gz`, `release-packaged-smoke.json.gz`, `release-packaged-smoke-driver.json.gz` | Existing opt-in packaged Mermaid/math/image/provider/error-recovery smoke, compiler and runtime homes/temp/preferences isolated. Both artifact runs PASS. |

Repeated runs are **not** added together to inflate test counts. Four final skips are `LspDefinitionPerformanceTest`, `GutterPerformanceTest`, `GlyphRenderingMeasurementTest`, and `WorkspaceStatusMeasurementTest` without their opt-in flags. The status measurement was separately executed with its flag. Historical full/native suite counts are not A1 results.

## Reproduction

From the repository root, with the existing JDK 21, Maven and offline cache:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --output <new-report-path>
```

Add `--tests WorkspaceStatusContractTest,WorkspaceRpcTest,PrivateWorkspaceFilesTest` for Stage A; `--tests WorkspaceStatusTransportTest` for B; `--tests WorkspaceCliProcessGuardTest` for pre-launch review; `--tests WorkspaceStatusProcessTest,WorkspaceCliTest,WorkspaceBridgeTest` for the reviewed C path. For measurement, select `WorkspaceStatusMeasurementTest` and add `--measure-status`. `--package-only` builds the artifact without running tests and cannot be mixed with measurement modes.

The normal safe profile now admits two existing CLI/bridge classes only after their child launches were strengthened. `WorkspaceCliTestProcess` admits the installed JDK's fixed headless `shed.Texteditor --workspace-api` route, controlled arguments, fixture-contained homes/paths and reviewed build/copied-dependency classpath. It clears inherited environment and closes/waits for its exact child. It is **not** an arbitrary Java/shell launcher or an OS sandbox. The helper's hash is checked by the Python runner and JUnit, in addition to each reviewed test-source hash. No full-suite/PTY/native override was added.

Python discovery uses a fresh `native_fixture.Fixture`, validation, its controlled environment plus disabled Python user-site/bytecode writes, a direct interpreter argv and a bounded timeout; cleanup runs in `finally`. Exact interpreter invocations are in each tooling report. Packaged smoke similarly runs the unchanged `scripts/test-packaged-markdown.py` with an explicit JDK and JAR through a fresh Fixture; its established memory settings are unrelated to status benchmark settings.

## Supporting inventory

| File/group | Meaning |
|---|---|
| `environment.json` | OS/architecture/JDK/Maven and final JAR identity; explicit native/RSS/OS exclusions. No personal environment dump. |
| `source-map.json` | Paths, hashes, method/test anchors and unchanged supporting interfaces. |
| `source-versions/*.gz` | Exact final changed production/test/tooling inputs, including admission manifests; no generated deployment secrets. |
| `external-client-results.json` | Per-run separate-client results; explicitly identifies the non-desktop server fixture and open main-window gate. |
| `protocol-reference.json` | Transcript ordering, public synthetic independent HMAC vector and primary references. It contains no runtime credential. |
| `failure-notes.json` | Development cleanup failure and final compatibility correction, with verification scope. |
| `test-results.json` | Parsed final/intermediate test outcomes and exclusions. |
| `repository-verification.json` | Exact final status/HEAD, allowed-input changes, preserved A0/user work, README identity and diff/static checks. |
| `checksums.sha256` | Complete file inventory/content checksums for this directory and the A1 report. |

## Interpretation and remaining gates

Measurements use one host and controlled bounded workloads, with first operations separated. Source typing is a 1 MiB ASCII `FileBuffer`/production text-area-UI component painted in memory, first without traffic and then with synchronized status traffic. The fixed order initializes fonts/layout before the second workload. Native compositor/input behavior and multilingual layout are not certified. All raw first spikes remain visible.

CPU snapshots distinguish named attachment-thread time from whole-JVM/compiler time. Heap and FD/thread snapshots are Maven-hosted observations; zero weak references do not establish native-memory equality. No settled or peak RSS result is claimed. Full native/GUI/PTY tests, real language servers/providers, actual desktop window startup/crash/restart and cross-platform acceptance were not run.

The external sentinel was synthetic: `WorkspaceStatusProcessTest` compares its exact bytes and modification time before/after the child workflow. No private shell history/configuration contents were read for a baseline, and no historical personal file was restored. All explicit child write locations are disposable; build output remains under the authorized checkout. Retained crash records are private expired state, never a reason for broad automatic deletion.

The real-window acceptance prerequisite is a separately reviewed isolated startup configuration, explicit command enablement, a real external status client, and bounded application stop/shutdown, with all normal startup side effects accounted for. Do not use the failed B0.1 shell harness or uncontrolled desktop automation to close that gate. A2 was not started.
