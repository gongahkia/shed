# Visualization startup readiness correction

Starting HEAD: `fef08bd38e1e6a0795a7b1d697404126106cdea6`, clean worktree. A user launching `visualize .` from the Shed repository waited 45 seconds and received only the generic readiness failure.

## Attribution and changes

A read-only in-process snapshot probe against the actual repository reproduced `Watch snapshot byte limit reached` in 137.35 ms before the correction. This initial observation is recorded from the diagnostic tool output; an initial JAR SHA was not captured, so no paired-artifact performance ratio is claimed. Compressed evidence and images accounted for most candidate bytes (approximately 21 MiB `.gz`, 18 MiB `.png`). The child displayed its watch error locally, but the parent discarded output and waited for readiness that could never arrive.

Known opaque asset/archive/font/media extensions now use file identity, creation/modification timestamps and size, without reading their contents. They remain non-text observations. The text-read limit remains 32 MiB; per-file, count, depth, time, private-path, symlink and nested-root safeguards remain. Metadata signatures cannot infer an exact-content move. Same-size in-place binary writes with restored timestamps can be missed; binary comparison remains deferred.

The watch state now reports a bounded initialization `error`. The child publishes a versioned private success/failure record with PID/project identity. The parent validates ownership before accepting a result, reports known failures promptly, removes the matching readiness record and terminates only its owned unsuccessful child. Attachment initialization failures are generic and disclose no credentials. No timeout was increased. Existing asynchronous/EDT ownership and authentication remain.

## Verification

| Evidence | Actual result |
| --- | --- |
| `focused.json` | 23 passes, no failures/errors/skips. Includes asset-heavy baseline, preserved text cap, opaque rename exclusion, startup errors, wrong PID/project, schema version and CLI behavior. |
| `safe-suite.json` | 420 run, 413 passes, seven skips, zero failures/errors. |
| `tooling.json` | 95 passes. |
| `repository-baseline.json` | Actual repository snapshot succeeded: 2,583 files / 1,018 opaque entries / 351.11 ms. Only repository reads; home/temp/compiled probe were disposable. No source contents were logged. |
| `desktop-launcher.json` | PASS, visible Swing child plus real authenticated client. A 40 MiB asset fixture became ready in 2,160.79 ms. A 33 MiB text fixture returned `WATCH_START_FAILED: Watch snapshot byte limit reached` in 2,474.55 ms, rather than timing out. Fixture removed. |
| `package.json`, `artifact.json` | Rebuilt JAR identity, same SHA used by desktop acceptance. |
| `initial-compile-failure.json` | Initial compile rejected inferred Map value types at the extracted publisher boundary. Explicit `Map<String,Object>` corrected this; no tests ran in that failed attempt. |

The safe class list is every entry in `src/test/resources/shed/portable-fixture-tests.properties` except `ProjectFileOpenPrerequisiteTest`. Seven skips cover existing opt-in measurements and unavailable DOS hidden attributes. The unrestricted full Maven/native-shell suite was not run. Windows/Linux GUI and actual personal-preference startup are not qualified by disposable-home tests. No new claim about typing, RSS or cold-cache startup follows from these single-run correctness checks.

## Reproduce

Use the existing JDK 21.0.13.1+2, Maven 3.9.11 and offline cache recorded in the reports. Output paths must be new. No installation or native-shell benchmark is required.

```sh
python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --tests <reviewed-class-list> --output <new.json>
python3 scripts/run-portable-tests.py --jdk <JDK> --maven <Maven> \
  --repository <offline-cache> --package-only --output <new.json>
python3 scripts/test-agent-desktop.py --jdk <JDK> --jar target/shed-2.0.0.jar \
  --output <new-directory> --launch-only
```

The launch probe creates its bounded 40 MiB opaque / 33 MiB text fixtures inside the existing disposable environment. It never uses a personal project for native acceptance. Python tooling uses `python3 -m unittest discover -s scripts -p 'test_*.py' -v` under a validated `native_fixture.Fixture`, with user site/bytecode disabled. The repository probe source is preserved in its JSON and compiles/runs directly under the same disposable Java home/temp policy; it does not open a desktop or mutate the source tree.

Native interactive-shell admission remains BLOCKED. No real language server, shell history, credentials, personal terminal config, user Git config, new dependencies or new production workers were used. Normal UI/design, themes/fonts and document/Undo ownership are unchanged.

README SHA-256 before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Local commits only; no push.

An external commit, `cb1c61363` (`recentcahnges`), captured the implementation and test changes while verification was in progress. It was preserved unchanged rather than rewritten or split. This agent issued no push command. Documentation/evidence was committed separately.
