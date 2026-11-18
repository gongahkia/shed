# Tranche 5B evidence

See the complete [A–L implementation report](../../MARKDOWN_SELECTION_INSERTION.md). This tranche changes selected-text insertion through the current source editor. Production live presentation remains deferred.

## Inventory

- `before.json.gz`, `after.json.gz`: complete paired reports with raw samples, fixture/artifact/probe identities, host load, commands and RSS observations.
- `measurements.json`: unfiltered per-operation p50/p95 summaries, EDT allocation medians, heap/RSS phases and integrity diagnostics.
- `probe-logs.json.gz`: all paired child stdout/GC logs.
- `verification-logs.json.gz`: reproduced initial failure, focused/full Maven, tooling, packaging, smoke and runner logs.
- `regression-suites.json`: every final suite/count and skip diagnostic.
- `packaged-smoke.json`: actual final packaged-JAR Mermaid/math/image smoke.
- `baseline-files.json.gz`: initial HEAD/status and 1,227 file hashes, including previous uncommitted work.
- `verification.json`: final protected-content, class-scope, README, HEAD and whitespace checks.
- `manifest.json`: evidence and implementation hashes (excluding itself).

## Verification

Final Maven: **1,526 run / 1,521 passed / five existing skips / zero failures or errors**. Sixteen added tests. Final focused units: **55 passed**. Earlier focused Swing/edit integration: **19 passed**. Tooling: **15 passed**. Packaging, packaged smoke and all six paired benchmark cases passed. The deliberately failing pre-change regression is archived separately; it is not a final passing run.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=true -DargLine=-Xmx512m -Dtest=MarkdownInsertionTest,MarkdownEditTest,MarkdownServiceTest test
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/shed-tranche5b-run/packaged-smoke.json
git diff --check
```

## Reproduce paired controls

The before JAR is the copied, verified 5A packaged artifact; its implementation hashes matched the initial checkout. The same final probe/runner compiles against both JARs. Use fresh output paths. Heavy probes and builds run serially.

```sh
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5b-run/before.jar --mode insertion-edits --shapes mixed --sizes 4096 102400 1048576 --samples 50 --warmups 5 --timeout 90 --output /tmp/shed-tranche5b-before
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode insertion-edits --shapes mixed --sizes 4096 102400 1048576 --samples 50 --warmups 5 --timeout 90 --output /tmp/shed-tranche5b-after
```

Apple M3 / eight CPUs / 16 GiB / macOS 26.5.2 / JDK 21.0.13 / Maven 3.9.11; 512 MiB child heap, unwrapped source, no preview, unchanged mixed fixtures. Fifty measured samples plus five warmups per operation in each fresh fixture process. Link/image and empty/selected controls run in a fixed order. Selected directions alternate. Each command uses the actual command handler and existing Swing completed-paint instrumentation. Source preservation and no semantic/index initialization are asserted.

Before-selected commands insert a template beside the label; after-selected commands replace the label and select the destination. This is a changed transformation, not a like-for-like algorithm speed comparison. Empty controls retain the same behavior. Synchronous command timing includes checked mutation/listeners; a pure Document.replace microbenchmark is not claimed. Allocation includes ordinary listeners, and retained heap is measured separately after explicit GC. Closing a buffer did not return either control to empty-workbench memory. Whole-app native-memory attribution remains unresolved. Native key/compositor latency and another OS were not qualified.

After-change typing completed-paint p95 was 1.98 / 1.62 / 2.21 ms at 4 / 100 / 1,024 KiB. Selected link/image command completed-paint p95 stayed below 2.69 ms. No performance claim extends beyond these bounded single-host workloads.

README bytes, previous performance evidence, document ownership, source layout, lexical highlighting, preview/media/link infrastructure and the isolated 4A experiment remain unchanged. No dependency, worker, permanent cache, UI component, shortcut, commit or push was introduced by this task. An external commit advanced HEAD during work and was preserved.
