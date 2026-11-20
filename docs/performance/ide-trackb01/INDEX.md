# Track B0.1 evidence

Read [the isolation report](../../NATIVE_SHELL_FIXTURE_ISOLATION.md). **Native benchmarks remain blocked.** Do not replay historical shell/PTY experiments on a personal environment.

## Results and boundaries

- `baseline.json.gz`: entry HEAD/status and 1,880 starting-file hashes.
- `host.json`, `jdk-release.txt`, `system-executables.json`: current host/load/Python, existing JDK release and exact system executable hashes. Host is Apple M3/macOS 26.5.2; no cold-cache claim or system-setting change.
- `backend-probe.sb`, `backend-result.json`: preliminary non-shell fixed touch controls; `/tmp` alias failed the allowed-write control.
- `backend-canonical.sb`, `backend-canonical-result.json`: canonical `/private/tmp` fixed touch allowed/denied controls. Broad read/process policy is **not** a qualified credential/process sandbox.
- `native-fixture-rejected-version.py.gz`: preserved candidate source version from the external capture commit. Inspect only; no approval to bypass its gate.
- `stage-c-backend.json.gz`: stricter candidate positive-control failure, sandbox-exec/Bash-target launch exit −6 (aborting subsystem unattributed), no output, 9.50 ms, root cleanup complete. Later probes never ran. CPU/fd/file/core limits were requested; their complete effectiveness is not qualified.
- `stage-b.log.gz`: first 27 synthetic checks. `stage-b-final.log.gz`: 33 fixture/admission/dependency-copy checks.
- `stage-a-compile.json.gz`: initial direct Maven launch failed (missing classworlds configuration); `stage-a-compile-2.json.gz`: corrected full test-source compilation succeeds, 281 sources at that point.
- `stage-d-portable.json.gz`: first 27-test non-native run. `stage-d-portable-final.json.gz`: successful cache-copy version. `stage-d-portable-bounded-copy.json.gz`: successful bounded-stream-copy version. These are repeat validations of the same 27 tests, not 81 distinct tests.
- `stage-d-junit-admission.json.gz`: actual JUnit extension rejects the intentionally unreviewed **synthetic, non-native** class before its `@BeforeAll` sentinel write. Maven exits 1 as expected; wrapper exits 0 only after checking exact rejection reason and sentinel absence. Final compile includes 282 test sources. This expected admission error is not a passing native test.
- `stage-d-tooling-final.log.gz`: 84 Python tooling checks, zero failures/skips. Existing suite 51 plus B0.1 33.
- `benchmark-entry-gates.json`: five independent benchmark sources compile; both direct Java entry points reject before server/argument/window activity. No actual service or desktop benchmark run.
- `static-overhead.json.gz`, `static-overhead-summary.json`: 50 purely synthetic lifecycles; raw timings, 4→4 open fd observations and Python max RSS. Nearest-rank p95; no shell startup or JVM heap inference.
- `sentinel-proof.json`: synthetic external hash and mtime before/after owned cleanup; linked external sentinel survives until its own owner disposes it.
- `dependency-cache-before.json`, `dependency-cache-after.json`: aggregate content/size/mtime comparison for the original cache around final copy-based validation. No personal configuration/history contents recorded. Earlier original-cache access has no before-hash.
- `production-artifact.json`: all 1,426 production classes still match frozen packaged JAR `95f27180c5d955217a8dd9e531d81fc4c1f25d6dc873d77fc0c51dca5ae67f4a`.
- `current-source-hashes.json`: exact current guard/runner/probe/test/resource identities.
- `repository-verification.json`: entry/final HEAD/status, file scope, README identity, previous evidence preservation and external commit preservation.
- `checksums.sha256`: hashes of all evidence except the checksum file itself.

## Executed safe commands

From repository root; existing toolchain only, no installations. Exact expanded JVM/Maven argv is retained in each JSON log.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_native_fixture.py -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py' -v

PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --compile-only --output /private/tmp/shed-trackb01-run/stage-a-compile-2.json

# Same arguments without --compile-only: the reviewed 27-test subset.
# --verify-gate selects only the fixed synthetic admission target, not native tests.

git diff --check
shasum -a 256 README.md
```

New output files are mandatory; substitute new disposable output locations when reproducing safe checks. The dependency repository is an explicit input, copied into the fixture before final runs. No Maven shell launcher, personal Maven settings or user rc is invoked; Surefire is in-process and headless. No full-suite override exists in this runner.

The rejected Stage C command was a single call to `Fixture.verify_backend()` after the synthetic suite. It used the archived module's fixed builtin script `printf allowed > "$HOME/allowed"` via `/usr/bin/sandbox-exec -f OWNED_PROFILE /bin/bash --noprofile --norc -p -c SCRIPT`, explicit fresh environment, owned cwd/stdout, stdin `/dev/null` and resource limits. **It failed; do not treat this as a reusable qualified native entry point.** There were no subsequent interactive/login/history/timeout/child-tree benchmarks.

Full Maven native/GUI regressions and packaged-media smoke were not rerun after rejection. B0's 1,616 passes/five skips and media PASS remain historical evidence in the separate B0 index.
