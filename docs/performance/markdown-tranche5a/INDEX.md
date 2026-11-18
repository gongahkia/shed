# Tranche 5A evidence

See the complete [A–N implementation report](../../MARKDOWN_SUBTREE_EDITING.md). This evidence concerns source edits only. Production live presentation remains a NO-GO.

## Final controls

`release-before` and `release-after` are the primary same-probe paired controls for small and near-limit subtrees in 4/100/1,024 KiB sources. `mixed-before` / `mixed-after` add the unchanged existing mixed-Markdown fixtures at 100/1,024 KiB. `wrapped-control` / `release-wrap` qualify wrapped 1 MiB source. `release-limit` records rejection above the new splice limit; the historical `limit-before` shows the former broader acceptance, not a comparable successful-operation speedup.

Every raw report is saved as `<group>.json.gz`, including unsuccessful pilots and initial candidates. `measurements.json` collects unfiltered summaries, per-phase heap/RSS and source/lifetime diagnostics. `probe-logs.json.gz` retains stdout/GC logs; `verification-logs.json.gz` retains baseline, reproduced failure, focused/full suites, packaging, smoke and tooling logs. `regression-suites.json` records all final suite counts and exact skip reasons. `packaged-smoke.json` identifies the tested final JAR. `baseline-files.json.gz` preserves 1,190 initial file hashes and status; `verification.json` records protected files, class scope, HEAD and final checks. `manifest.json` hashes every evidence artifact and implementation file.

## Verification

Starting actual suite: 1,491 run / 1,486 passed / five skips / zero failures/errors. Final: **1,510 run / 1,505 passed / five skips / zero failures/errors**. Nineteen new tests. Python benchmark tooling: **14 passed**. Packaging and packaged Mermaid/math/image smoke passed. Earlier fixture/probe/viewport failures remain archived; they are not hidden or counted as acceptance runs.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar
git diff --check
```

JDK 21.0.13 / Maven 3.9.11; original exact Java commands and classpaths are in the raw reports. Use compatible tools and fresh output directories to reproduce elsewhere. Run heavy builds/probes serially.

## Reproduce final measurements

```sh
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5a-run/before.jar --mode subtree-edits --shapes subtree-small subtree-wide --sizes 4096 102400 1048576 --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-tranche5a-release-before
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode subtree-edits --shapes subtree-small subtree-wide --sizes 4096 102400 1048576 --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-tranche5a-release-after
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode subtree-edits --shapes subtree-capped --sizes 102400 --samples 30 --warmups 3 --timeout 90 --output /tmp/shed-tranche5a-release-limit
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode subtree-edits --shapes subtree-wide --sizes 1048576 --samples 30 --warmups 3 --timeout 90 --output /tmp/shed-tranche5a-release-wrap --wrap
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5a-run/before.jar --mode subtree-edits --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-tranche5a-mixed-before
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode subtree-edits --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-tranche5a-mixed-after
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5a-run/before.jar --mode subtree-edits --shapes subtree-wide --sizes 1048576 --samples 30 --warmups 3 --timeout 90 --output /tmp/shed-tranche5a-wrapped-control --wrap
```

## Method and qualifications

Apple M3 / eight CPUs / 16 GiB / macOS 26.5.2, 64–512 MiB child heaps. Runner snapshots include host pressure/load/swap, font, scale, wrap, fixture hashes and source/probe/artifact identities. Load was variable and sometimes exceeded the CPU count. These single-host observations do not establish cross-platform equivalence or a causal explanation for every latency difference.

Source typing and command paint use the production workbench, FileBuffer, listeners, input actions and source view. Warm controls explicitly prepare the same semantic model in both artifacts; the old command did not need that work in normal use. Cold commands separately expose the new asynchronous cost. Off-EDT pure planning, isolated checked application, production command EDT work, completed Swing paint and remaining highlight convergence are separate. Native key delivery/compositor latency and large wrapped multi-pane timing are not qualified.

The final probe adds five isolated checked-application diagnostics after its repeated command phase. Earlier versions lack that extra diagnostic and retain their distinct hashes. The initial candidate's repeated snapshot line lookups caused substantial planning cost; final standalone checks inspect only a bounded preceding prefix. Early pilots failed because later repetitions did not reset the caret after Undo/Redo; corrected controls explicitly target the same root each time.

Limits are 512 targets and 65,536 UTF-16 units each for expected/replacement source, independently of document eligibility. Near-limit fixtures have hundreds of markers and tens of KiB of copied body; successful small subtrees can leave a large untouched body outside the splice. Complete source restoration, zero ordinary-typing semantic requests and current-result reuse are asserted. Weak plan references clear after operation scope; closing the buffer uses the existing lifecycle. This is not full native-memory attribution or an unlimited subtree-size claim.

No previous performance evidence, README, document model, source view, highlighting, preview/media/link service or isolated 4A prototype was changed. No dependencies, workers, permanent caches, settings or UI components were added. No commits or pushes were created.

## Independent typing control

The unchanged Tranche 2C probe was run in reverse artifact order after the subtree matrix. At 100 KiB / 1 MiB mixed source, typing p95 was 1.71 / 2.02 ms after, versus 2.91 / 3.58 ms before. Raw `typing-after.json.gz` and `typing-before.json.gz` retain samples, artifact/probe identity and load. Primary subtree comparisons with increased tails remain in the report; no timing-equivalence or universal speedup claim is made. Large explicit subtree commands can exceed 16 ms even though all final typing p95 samples meet that target.

```sh
python3 scripts/benchmark-markdown.py --jar target/shed-2.0.0.jar --mode semantic-edits --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-tranche5a-typing-after
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche5a-run/before.jar --mode semantic-edits --shapes mixed --sizes 102400 1048576 --samples 100 --warmups 5 --timeout 90 --output /tmp/shed-tranche5a-typing-before
```
