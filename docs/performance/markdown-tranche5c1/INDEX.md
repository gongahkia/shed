# Tranche 5C.1 evidence

See the [complete A–L report](../../MARKDOWN_KEYBOARD_CONTEXT.md). This is a bounded source-keyboard correction; no live presentation, table-navigation expansion or appearance work was begun.

## Inventory

- `before.json.gz`, `after.json.gz`: complete paired production keyboard reports; 4/100/1,024 KiB fixtures, 50 samples per operation plus five warmups.
- `lexical-before.json.gz`, `lexical-after.json.gz`: independent unchanged lexical replay, 100 KiB/1 MiB, 25 viewport samples plus three warmups and up to ten scenario edit replays.
- `typing-before.json.gz`, `typing-after.json.gz`: independent unchanged semantic-edit probe's beginning-of-source typing controls, 100 KiB/1 MiB, reverse artifact order, 50 samples plus five warmups. Its pre-existing heading phase is separate from ordinary typing.
- `measurements.json`: all unfiltered operation p50/p95, phase memory, EDT allocation medians and source/lifecycle diagnostics.
- `probe-logs.json.gz`: child stdout/GC logs for every group.
- `verification-logs.json.gz`: initial reproduced regressions, focused/full suites, test-harness failures, adversarial audit, packaging, tooling, smoke and runner logs.
- `baseline-files.json.gz`: initial HEAD/status and all initial repository file hashes, including prior uncommitted work.
- `regression-suites.json`: every final Maven suite/count and skip diagnostic.
- `packaged-smoke.json`: Mermaid/math/image smoke against the final guarded JAR.
- `verification.json`: README, preserved prior content, allowed application class families, HEAD and whitespace checks.
- `manifest.json`: implementation/evidence hashes, excluding itself.

## Verification

Final full Maven: **1,540 run / 1,535 passed / five existing skips / zero failures or errors**. Fourteen added tests. Focused: **70 passed**. Tooling: **16 passed**. Packaging, packaged smoke and all fourteen benchmark cases passed. Both original defects were reproduced before production changes. The late empty-bullet guard was followed by a second full suite, not merely a unit rerun.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/shed-tranche5c1-run/packaged-smoke.json
git diff --check
```

The report gives the exact focused command. The before JAR was copied from the verified 5B package; its final smoke hash and initial implementation hashes match. It was not reconstructed by reverting user work.

## Reproduce measurements

Use compatible tools and NEW output directories. Heavy builds/probes ran serially. Each pair uses the same final probe and runner. Existing fixtures, lexical replay and completed-paint methodology were preserved.

```sh
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5c1-run/before.jar --mode keyboard-edits --shapes mixed --sizes 4096 102400 1048576 --samples 50 --warmups 5 --timeout 90 --output /tmp/shed-tranche5c1-before
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode keyboard-edits --shapes mixed --sizes 4096 102400 1048576 --samples 50 --warmups 5 --timeout 90 --output /tmp/shed-tranche5c1-after
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5c1-run/before.jar --mode syntax-lexical --shapes mixed --sizes 102400 1048576 --samples 25 --warmups 3 --timeout 90 --output /tmp/shed-tranche5c1-lexical-before
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode syntax-lexical --shapes mixed --sizes 102400 1048576 --samples 25 --warmups 3 --timeout 90 --output /tmp/shed-tranche5c1-lexical-after
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode semantic-edits --shapes mixed --sizes 102400 1048576 --samples 50 --warmups 5 --timeout 90 --output /tmp/shed-tranche5c1-typing-after
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5c1-run/before.jar --mode semantic-edits --shapes mixed --sizes 102400 1048576 --samples 50 --warmups 5 --timeout 90 --output /tmp/shed-tranche5c1-typing-before
```

Apple M3 / eight CPUs / 16 GiB / macOS 26.5.2 / JDK 21.0.13 / Maven 3.9.11; 512 MiB child heaps, unwrapped source and no preview. The primary workload edits deep source regions; independent typing uses the beginning. Current context is supplied by normal asynchronous highlighting, not a semantic parse on Enter. The opening-completion fixture removes/restores one language character outside timing. Every iteration resets its target, checks exact command/Undo/Redo output and restores source. Closing completion is timed without attributing a paint to a correct rejection.

The after primary group experienced one-minute load approximately 8.1–10.4 versus 2.9–4.2 before. Increased tails remain reported. Primary after typing-to-paint p95: **2.90 / 4.95 / 3.44 ms**, all below 16 ms. The independent reverse-order controls were 0.82 / 1.21 ms after and 1.15 / 1.14 ms before at 100 KiB / 1 MiB. No universal speedup/equivalence claim follows from these workloads.

Companion context stays within existing checkpoint limits and holds no source. Context queries cap line/prefix work and never trigger new jobs/caches. Peak RSS remains high in both artifacts. Requested-GC phase heap and process RSS are separate observations; close did not establish an empty-workbench baseline. Native-memory attribution remains unresolved. Physical keyboard/compositor timing and a second OS are unqualified.

README, previous performance evidence, existing user changes, source ownership, editor geometry, keymaps, preview/media/link systems and the isolated live-presentation experiment remain preserved. No dependencies, UI components, new workers/caches, commits or pushes were introduced.
