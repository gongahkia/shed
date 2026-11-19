# Tranche 7C qualification evidence

[Final report A–N](../../MARKDOWN_PRODUCT_READINESS.md).

Readiness: **CONDITIONAL GO for personal/development use; NO-GO for general public release and the original performance-first ambition.** An isolated 8A feasibility study is conditionally reasonable under separate authorization and explicit research gates; no appearance shipping approval follows.

This tranche adds documentation/evidence only. It does not optimize the editor, alter a performance target, restrict Unicode, modify preview, activate a prototype or start another initiative. All **1,410 pre-existing files** matched their initial SHA-256, including uncommitted 7B.2–7B.4 work and prior evidence. Starting/final HEAD is `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`.

Current/frozen production JAR SHA-256: `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`. Protected README before/after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`.

## Files

| File | Contents |
| --- | --- |
| `baseline.json.gz` | Starting HEAD/status, packaged identity, all pre-existing file hashes |
| `current-controls.json.gz` | Unmodified five-run desktop and three-size source reports, exact child commands, environment and wrapper exit codes |
| `measurements.json` | Current startup/heap/RSS/typing summaries and source metadata, retaining failed startup gates |
| `corpus.json.gz` | Fresh Unicode 15.1 corpus results and every mismatch; exact equality with prior failure records; explicitly failed complete conformance |
| `evidence-review.json` | Reviewed report/index hashes, production source/API and regression-class map, readiness/architecture decisions |
| `diagnostics.json.gz` | Current Maven/tooling/packaged/benchmark logs and evidence-generation script; failed startup command retained |
| `packaged-smoke.json` | Packaged-only real Mermaid/math/image test result and identities |
| `verification.json` | All suite totals, every skip reason, source/README/HEAD/fixture preservation and failed/incomplete qualification results |
| `inventory.json` | Every new documentation/evidence file and purpose |
| `manifest.json` | New-deliverable checksums excluding the checksum file itself |

Gzip files contain UTF-8 JSON. For example:

```sh
gzip -dc docs/performance/markdown-tranche7c/current-controls.json.gz | python3 -m json.tool
```

Historical artifacts, raw profiles, sample counts and environments remain in their original indexes. The current report cites 7A for background/preview/native controls, 7B.1 for Unicode boundaries, 7B.2 for demonstrated retention roots, and **stock** 7B.3/7B.4 for current Unicode latency. Prototype performance is never relabeled as production improvement. Original Intel/Monospaced evidence is not pooled with current M3/JetBrains Mono observations.

## Current verification and measurement boundaries

- **Maven: 1,565 total; 1,560 passed; five existing skips; zero failures/errors.** Counts include isolated experimental tests and do not imply shipped live presentation.
- **Tooling: 25 passed. Packaged real media: PASS.** No new test or code is added in 7C.
- **Corpus: 1,187 cases, 1,136 matches, 51 mismatches, seven GB9c cases passing.** The read-only process exits 0 while reporting mismatches; this is a **failed complete conformance result**, not a passing regression test. All failure records match 7B.1.
- **Desktop command exits 1:** the first of five fresh processes misses 2 s visible / 3 s source targets (3.602 / 4.118 s). The remaining four pass those gates; full UI stays below 10 s. No outlier was dropped or threshold changed.
- **Source control exits 0:** 100 samples/10 warmups per mixed-ASCII size; completed-paint typing p95 0.869 / 1.130 / 2.359 ms at 100 KiB / 1 MiB / 10 MiB.
- **Memory boundaries:** desktop RSS 617.50–673.45 MiB is observed after the established one-second settling interval, without forced GC. Source active sampled peaks 723.11 / 751.47 / 957.59 MiB are different observations. Neither is whole-system low-memory qualification.
- **Known blockers remain:** production multisecond Unicode first use, synchronous preview stalls, incomplete corpus/native-memory/cross-platform qualification. Deferred live typography, inline media and editable 50–100 MiB files remain deferred.

The existing desktop harness uses five isolated homes, 64–512 MiB heaps, a 20,000-line source fixture and 125 pooled document-edit/forced-paint samples. Its edit metric is not the attributed input/paint metric of the separate source harness. Fresh processes are not controlled cold-cache starts. Host load, pressure and swap are recorded without disrupting unrelated applications. Unicode/preview/JFR investigations were not expensively repeated because their confirmed failures and current artifact identity already establish the decision.

## Commands

Use fresh external output paths. These are the executed measurement/regression forms:

```sh
source /tmp/shed-tranche2c-run/toolchain.env
"$MVN" test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche7c-run/production.jar --output /tmp/shed-tranche7c-run/packaged-smoke.json
python3 scripts/benchmark-editor.py --jar /tmp/shed-tranche7c-run/production.jar --label tranche7c-production --runs 5 --output /tmp/shed-tranche7c-run/desktop.json
python3 scripts/benchmark-markdown.py --jar /tmp/shed-tranche7c-run/production.jar --mode ui --shapes mixed --sizes 102400 1048576 10485760 --samples 100 --warmups 10 --output /tmp/shed-tranche7c-source
"$JAVA_HOME/bin/javac" -cp /tmp/shed-tranche7c-run/production.jar -d /tmp/shed-tranche7c-run/corpus-classes src/benchmark/java/shed/GraphemeQualificationProbe.java
"$JAVA_HOME/bin/java" -Xms32m -Xmx128m -cp /tmp/shed-tranche7c-run/corpus-classes:/tmp/shed-tranche7c-run/production.jar shed.GraphemeQualificationProbe src/test/resources/unicode/15.1.0/GraphemeBreakTest.txt
git diff --check
shasum -a 256 README.md
```

The corpus JSON was redirected to `/tmp/shed-tranche7c-run/corpus.json`. Compiler/source hashes and pinned corpus identity are retained. Prototypes remain absent from the packaged application. No production/test/benchmark edits, dependency/JVM changes, commits, pushes or later-tranche execution occurred.
