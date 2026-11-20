# Track B1A evidence

Read [LSP_RESPONSIVENESS_REMEDIATION.md](../../LSP_RESPONSIVENESS_REMEDIATION.md). Native-shell qualification remains NO-GO. No real language server or shell fixture was launched for these controls.

## Primary results

- `before.json.gz`: three synchronous real-client/in-memory-peer EDT reproductions, 200 ms scheduled replies, full EDT stacks and 205–207 ms queued barriers. Constructor injection only; no asynchronous fix yet.
- `cancellation-before.json.gz`: original monitor-contention cancellation regression fails; cleanup still releases both owned Java threads.
- `queued-cancellation-before.json.gz`: original standard executor retains 32 canceled queued nodes with a deliberately occupied worker; assertion fails before cleanup releases the worker.
- `final-all-safe.json.gz`: final 34 admitted classes with `--measure-lsp`, **162 passed**, zero failures/errors/skips; includes real in-memory JSON-RPC, source/context/Undo/split/cancellation cases, existing safe regression classes and the performance check.
- `final-fresh-performance.json.gz`: only the opt-in performance class in a fresh Maven/JVM process, **one test passed**. First graphics/paint and first command are separate. Not cold-cache launch or a real workbench.
- `measurements.json`: extracted final fresh-process samples, first/warm typing phases, three controls per definition mode, lifecycle endpoints, diagnostic GC observations and exact loaded class resource SHA-256 values. Asynchronous command first 12.87 ms; subsequent 0.108/0.292 ms. The 200 ms peer computation is unchanged.
- `admission-final.json.gz`: the intentionally unreviewed synthetic JUnit class is rejected before its setup writes a sentinel. Maven exit 1 is expected; wrapper exit 0 means the precise rejection and sentinel absence were verified. This is not a native test pass.
- `package.json.gz`: explicit Java/Maven offline package-only build, tests skipped for this build phase; no shell launcher/user Maven rc. Tests were executed separately above.
- `packaged-smoke.json.gz`, `packaged-smoke-driver.json.gz`: new JAR **PASS** for math, Mermaid, local images, providers, invalid-render recovery and a second session. Compiler and runtime homes/temp/preferences are disposable. No development classpath rescues the JAR.
- `tooling-final.json.gz`: **86 Python checks passed** under a disposable parent environment with user site loading disabled. Includes native/real-LSP admission rejection, exact source hashes, package-mode exclusions and compiler/runtime property construction.

## Identities and compliance

- `baseline.json.gz`: entry HEAD/status and every starting source/script/doc/asset/build-input hash, including pre-existing B0.1 changes.
- `environment-and-fixtures.json`: host/OS/JDK/JVM arguments, captured load, exact ASCII fixture hashes and sample counts. The small Unicode context fixture is embedded in the archived helper source; it is not a 1 MiB complex-layout benchmark.
- `source-hashes.json`, `source-versions/final-*`: exact final changed code/test/tooling sources. Test sources are never packaged.
- `source-versions/before-LspClient.java.gz`, `before-InProcessLspFixture.java.gz`, `before-LspDefinitionResponsivenessTest.java.gz`: constructor-seam reproduction sources preserved by external capture commit `608867a7f80f64e44fdb81ec211f25eb8aad740c`, before client lock changes.
- `source-versions/before-AsyncJobService.java.gz`: entry implementation before queue-node removal. The queue regression's final test source still contains the same failing-before/passing-after assertion.
- `test-admission-review.json`: reviewed added existing classes, new in-memory classes, source hashes and explicitly excluded native/window classes. Prior eight approved classes remain approved; no wildcard or gate-disable override was introduced.
- `artifact-verification.json`: new JAR SHA-256, **1,429** production classes identical to the compiled output, no test fixture/gate class leaks.
- `starting-jars.json`: preserved original binary identities. Binary backups remain in `/private/tmp/shed-trackb1a-run/starting-jars`, not in Git/evidence archives.
- `repository-verification.json`: final HEAD/status, external commits preserved, README hashes, scoped changes and preservation of previous work/evidence.
- `checksums.sha256`: integrity of evidence files, excluding itself.

## Intermediate evidence, never pooled with the final metrics

- `focused-1.json.gz`: first nine focused checks passed.
- `focused-and-paired.json.gz`: harness compilation failed because two color getter names did not exist; corrected to existing theme roles.
- `focused-and-paired-2.json.gz`: successful earlier paired/typing run; its typing boundary starts inside the EDT and excludes queue time.
- `performance-final.json.gz`: earlier diagnostic memory/retirement run, before the typing boundary correction.
- `performance-queued-paint.json.gz`, `performance-qualified.json.gz`: intermediate queued-typing controls. Preserved rather than relabeled as the final code/artifact.
- `safe-regressions*.json.gz`, `regressions-qualified.json.gz`: successive admitted regression sets, including the opt-in skip where applicable. Counts overlap; do not sum them as distinct tests.
- `cancellation-and-lsp-final.json.gz`: 40 relevant job/LSP/cancellation checks passed after queue removal.
- `tooling.json.gz`: earlier 84-test run, before the two package/smoke tooling checks.

## Reproduction commands

Run from repository root using the existing toolchain and a **new output file** for each invocation. The runner copies the explicitly supplied offline dependency cache into its disposable fixture (128 MiB/6,000-entry limits), uses empty Maven user/global settings and runs Surefire in-process. Full native classes remain inadmissible.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --measure-lsp --output /private/tmp/shed-trackb1a-run/final-all-safe.json
```

The identical toolchain options with:

- `--tests LspDefinitionPerformanceTest --measure-lsp` → `final-fresh-performance.json`.
- `--verify-gate` → `admission-final.json` (expected synthetic JUnit admission error, verified by wrapper).
- `--package-only` → `package.json` (does not execute any tests).

The independent packaged check was `python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home --timeout 90 --output /private/tmp/shed-trackb1a-run/packaged-smoke.json`, launched by the archived driver command with a fresh disposable environment/cwd. The tooling command was `python3 -m unittest discover -s scripts -p 'test_*.py' -v`, also under a disposable environment. Exact expanded argv and outputs are in the JSON reports.

Original failing controls can be inspected from their source versions and logs. Do not run real server/shell variants to reproduce them. The same in-memory synchronous control remains in the final tests; it deliberately demonstrates why worker-only use of the synchronous client API matters.

No full native/window Maven run, OS-shell startup, actual language-server process, native-memory profiler or Windows/Linux GUI test is claimed. Other synchronous LSP UI routes, real file navigation paint and existing Unicode/HTML-preview limitations remain outside the correction.
