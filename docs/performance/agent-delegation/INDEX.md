# Agent delegation evidence

See [the workflow and implementation report](../../AGENT_DELEGATION.md). These results use the existing JDK 21/Maven installations and controlled disposable homes, temp files and preferences. No model, real language server, interactive shell or B0.1 native benchmark was run.

## Reproduction

Use `scripts/run-portable-tests.py` with:

```text
--jdk /private/tmp/shed-a222c1-tgamqlld/tools/jdk-21.0.13.1+2/Contents/Home
--maven /private/tmp/shed-a222c1-tgamqlld/tools/apache-maven-3.9.11
--repository /private/var/folders/55/fw1sdntj20v8650vsmkn7s8c0000gn/T/shed-animation-edge-dependencies-mxqwtgld/repository
--output <new-result.json>
```

- Freshness: `--tests EditorFreshnessTest,EditorControlTest,ShedControlCliTest,WorkspaceStatusContractTest`.
- Session/launch: `--tests AgentDelegationTest,AgentTaskLaunchTest,ArchitectureBoundaryTest`.
- Safe regressions: every name in `src/test/resources/shed/portable-fixture-tests.properties` except `ProjectFileOpenPrerequisiteTest`, joined by commas for `--tests`. The complete selected command and reviewed source hashes are preserved in the raw report. The established retired prerequisite characterization remains excluded.
- Package: `--package-only`; this skips tests only for artifact creation, not as test qualification.
- Tooling: `python3 -m unittest discover -s scripts -p 'test_*.py' -v` under a fresh `native_fixture.Fixture` environment plus `PYTHONNOUSERSITE=1` and `PYTHONDONTWRITEBYTECODE=1`, with a 45-second bound and cleanup in `finally`.
- Packaged media: the existing `scripts/test-packaged-markdown.py --jdk <same-JDK> --jar <fresh-JAR> --output <new-json>`, executed directly under that disposable environment, with a 100-second outer deadline.
- Live fixture: `python3 scripts/test-agent-delegation.py --jdk <same-JDK> --jar <fresh-JAR> --output <new-canonical-directory>`.
- Paced demonstration: `JAVA_HOME=<same-JDK> make demo-delegation`. This runs the same fixed Java fixture with `--demo`. It is not a real model demonstration.

The desktop driver compiles one source-reviewed benchmark class against the packaged JAR, starts fixed Java commands only, checks fixture-contained project/write locations, supplies isolated preferences/home/temp and a PATH with no external executables, and monitors a synthetic external sentinel's content hash and modification time. Capture is confined to owned Swing surfaces. No desktop-wide capture or mouse automation is used. Process/session secrets are not logged. A fixed Java subprocess supplies the agent behavior; stdin/stdout, real mutual authentication, actual document loading and filesystem change observation remain production paths.

## Evidence inventory

- `environment.json`: host/artifact identity, starting HEAD and README hash.
- `sync-tests-final.json.gz`: six new freshness tests plus existing control/CLI/protocol checks; 34 passes.
- `lease-tests.json.gz`: initial test-source compilation error, retained.
- `lease-tests-02.json.gz`: 38 targeted passes before the later revocation-observation case.
- `launch-tests.json.gz`: launch/bootstrap and existing targeted checks; 41 passes.
- `rebind-tests.json.gz`, `boundary-tests.json.gz`, `ui-final.json.gz`: focused checks for the live-test findings.
- `safe-regression.json.gz`: first broad run, 544 total / one unchanged architecture-rule failure / seven skips. The composition ownership was corrected; the assertion was not changed.
- `release-regression.json.gz`: final 545-total selection, 538 passes and seven existing skips, including disposed-view ownership cleanup.
- `safe-final.json.gz`, `safe-qualified.json.gz`: subsequent 544-total safe suite passes; 537 passes and seven skips. Counts overlap, not additive.
- `package-08.json.gz`: packaged artifact for the initial complete live acceptance; later release packaging is recorded separately.
- `tooling.json.gz`, `tooling-final.json.gz`: 107 synthetic tooling passes; source guard admission remains intact.
- `media.json.gz`, `media-driver.json.gz`: packaged Mermaid/math/image smoke and its controlled driver.
- `live-01-*`: the incorrect active-file assertion after following other files.
- `live-02-*`: the real editable-state reset during buffer rebinding.
- `live-03-*`: background-child cleanup returned before the child's exit.
- `live-04-*`: first successful live round trip, cancellation, completed paint and background-child cleanup.
- `live-05-*`: expanded passing live run, including pending-save guard, actual external `delegate --connect`, timeout and failed-exit cleanup. The 40-character completed-paint probe measured first 3.90 ms and p95 1.76 ms; packaged media was also running, so this is not an idle-machine control.
- `00-task-setup.png`, `00-approval.png`, `01-agent-reading.png`, `02-handback-changes.png`: latest qualified captures of the app's own Swing components, not source-of-truth text.

Initial failed runs, evidence and prior work are preserved. No Windows/Linux desktop, whole-process native-memory equivalence, detached-service confinement, arbitrary external CLI behavior or real cloud model qualification is claimed. B0.1 remains NO-GO for native-shell benchmarking.

## Packaged acceptance before the worker-count correction

`qualified-package.json.gz` records the artifact built from code commit `593973c05`. `artifact-check.json` compares all current production classes to that JAR; `environment.json` records its SHA-256. The final `make-demo-final-*` reports come from the actual `JAVA_HOME=<JDK21> make demo-delegation` target, running the paced `--demo` fixture against that artifact. Every listed phase passed, including explicit IO joins; the independent worker-count limitation of that earlier report is corrected below. First post-handback character: 2.21 ms; 40-character p95: 0.64 ms. The synthetic external sentinel's content hash and metadata were unchanged, and the fixture was removed. The captures were later refreshed from the direct-worker-accounting run below. These numbers do not establish a matched before/after performance comparison.

`qualified-media.json.gz` and `qualified-media-driver.json.gz` record the final artifact's packaged media smoke. `make-demo-*` preserves the preceding successful Make invocation separately. No native-shell admission guard was bypassed.

## Worker-count correction

Earlier `remaining_task_workers` values came from `Thread.getAllStackTraces`, which excludes virtual threads; those values are platform-thread samples, not independent IO-worker retention evidence. The runner's existing explicit joins and process-exit checks still passed. The final benchmark instead reads direct lifecycle accounting for the reader/writer virtual threads and names the field `remaining_task_io_workers`. This counter changes only at task IO-worker start/exit, adds no polling or per-keystroke work, and exposes no public endpoint. `worker-tests.json.gz` records 11 overlapping targeted passes after that diagnostic change. The final Make run records the corrected observation.

## Direct worker accounting: final acceptance

`worker-package.json.gz` and `artifact-check.json` identify the final production artifact, with its code commit recorded in `environment.json`. `worker-demo-*` records the actual Make target against this artifact, with a stricter visible-reading highlight/direction assertion and direct virtual IO-worker accounting. All workflow phases passed, `remaining_task_io_workers` was zero, tracked child processes exited, and the synthetic external sentinel's hash/metadata were unchanged. First post-handback component sample: 1.82 ms; p95: 0.54 ms. These samples exclude EDT admission and OS keyboard delivery. Current PNGs are from this run. `release-media*` records this same artifact's media smoke. Earlier artifacts/results remain preserved; their samples are not pooled with the final run.

The final raw runner source hashes and selected test list are part of each report. The initial freshness failure and its immediate subsequent green run are preserved in `sync-tests.json.gz` and `sync-tests-02.json.gz` respectively; the no-newline metadata normalization was corrected without changing editor source state.

## Stale JAVA_HOME repair

The developer shell exported a nonexistent `/opt/homebrew/opt/openjdk@21`. The already available JDK was copied (not symlinked) into `~/.local/share/shed-tools/jdk-21`, preserving the original temporary installation and all shell startup files. Java executable SHA-256 in both copies: `8261edc44ac1643e7aba9f0df5155b6d81f7977cc8a0374bb7630d838d2a3910`.

The shared resolver now warns and continues discovery for a missing JAVA_HOME, accepts Homebrew/macOS bundle prefixes, and prefers the app-owned JDK before the macOS system stub. Explicit invalid JAVA and existing non-21 homes remain errors. Seven isolated POSIX resolver tests passed, using synthetic Java executables and disposable homes. `toolchain-resolver-tests.json.gz` records the complete Python tooling run; `toolchain-fallback-demo.json.gz` records successful actual `JAVA_HOME=/opt/homebrew/opt/openjdk@21 make demo-delegation` execution through the stable JDK. No Java application source changed in this repair; Maven was not rerun. `/bin/sh -n scripts/resolve-toolchain.sh`, `git diff --check` and the unchanged README hash also passed. The unqualified native-shell benchmark harness was not invoked.
