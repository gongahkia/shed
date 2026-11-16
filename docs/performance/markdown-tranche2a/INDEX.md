# Tranche 2A — semantic foundation evidence

Read [the architecture, contracts, limitations and 2B handoff](../../MARKDOWN_SEMANTIC_FOUNDATION.md). No consumer migration or production scheduling is included.

## Files and scope

| Added file | Responsibility |
| --- | --- |
| `src/main/java/shed/MarkdownSourceRange.java` | Strict half-open UTF-16 range/position validation, including surrogate boundaries. |
| `src/main/java/shed/MarkdownSemanticSnapshot.java` | Immutable captured source identity, status/coverage, typed semantic records and current-revision checks. |
| `src/main/java/shed/MarkdownSemanticParser.java` | On-demand CommonMark adapter, bounded eligibility/projection, conservative missing-location handling. |
| `src/test/java/shed/MarkdownSemanticParserTest.java` | Thirteen deterministic cases, including 400 differential comparisons, parallel isolation and real buffer invariants. |
| `src/benchmark/java/shed/MarkdownSemanticProbe.java` | Opt-in headless full-API allocation/latency and GC observations. |
| `scripts/benchmark-markdown-semantics.py` | Reuses existing corpus/environment/statistics; bounded disposable children, fixture hashes, watchdog/process-group cleanup and explicit unsupported states. |
| `docs/MARKDOWN_SEMANTIC_FOUNDATION.md` | Semantic contracts, supported/deferred constructs, existing disagreements, costs and proposed 2B scope. |
| This evidence directory | Raw results and repository verification described below. |

No previously existing production/test/build file was changed. At task start, `docs/MARKDOWN_HIGHLIGHTING_PERFORMANCE.md` and `docs/performance/markdown-tranche1d/` were uncommitted prior work; their bytes remain unchanged. A separate commit appeared during verification, incorporating those and earlier task files. No commit/push command was executed by this task and no history was changed to undo it.

## Reproduce

Use the existing Java 21 resolver, cached Maven dependencies and a new output directory each time. Routine semantic tests are headless:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=true -DargLine=-Xmx512m \
-Dtest=MarkdownSemanticParserTest test

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-DskipTests -Dmaven.jar.forceCreation=true package

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown-semantics.py \
  --jar target/shed-2.0.0.jar --sizes 102400 1048576 10485760 --shapes mixed \
  --samples 20 --warmups 3 --timeout 90 --output /tmp/new-shed-semantic-mixed

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown-semantics.py \
  --jar target/shed-2.0.0.jar --sizes 102400 1048576 \
  --shapes single-fence table nested headings markers long-line \
  --samples 5 --warmups 1 --timeout 90 --output /tmp/new-shed-semantic-adverse

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-packaged-markdown.py \
  --jar target/shed-2.0.0.jar --output /tmp/new-shed-semantic-package.json

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_benchmark*.py'
```

The full existing Swing integration suite requires a display and is a final qualification step, not a requirement for each semantic edit:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m test

git diff --check
shasum -a 256 README.md
```

Final result: **1,348 total Maven cases: 1,343 passed, five existing skips, zero failures/errors**. All 13 new headless tests passed; existing Python tooling ran ten tests successfully. The independent packaged smoke passed using only its compiled probe and the final shaded JAR. It verifies both JavaScript/regex provider registrations, normal Markdown, math, local images, valid/repeated/changed Mermaid and invalid-input recovery.

The earlier full run also passed (1,342 passed/five skips), before adding the multiline heading-label case. A final headless run and final complete run cover that correction. Initial compilation ambiguity and a new-test expectation failure exposed during development are retained in the logs; no existing test was weakened or skipped. Five final skips are the established DOS-attribute, fish, CTest environment cases and opt-in AI-native/benchmark cases, as captured in `regression-suites.json`.

## Measurement boundary and safeguards

These are synchronous, caller-thread **semantic API** measurements. They include preflight, independent parser/extension creation, source reading, CommonMark AST processing and immutable record projection. They exclude source acquisition (separate row), EDT scheduling, editor painting, highlight convergence and preview rendering. No before/after editor-speed claim is made for an unused API.

Each child uses `-Xms64m -Xmx512m -Djava.awt.headless=true`, an isolated home, a 90-second watchdog and a known fixture. Maximum selectable input is 10 MiB, maximum 12 cases per invocation; fixtures live in an isolated temporary directory and are removed by its owner. Only this runner's child process group is killed on timeout, including Darwin `time`'s child JVM. No other process is targeted. Ordinary Maven tests never execute these benchmarks.

The existing deterministic fixture generator remains unchanged. `single-fence` supplements it with one continuous code block containing tabs, `<`, `>` and `&`, exact byte size and a bounded final line. Fixture generation parameters and SHA-256 are in every raw report. All 24 recorded cases retained their exact fixture hashes and completed without child failure/timeout: nine initial exploratory cases plus 15 final-artifact cases.

Three warmups precede 20 mixed samples; one precedes five adverse samples. A rejected workload stops immediately and is labeled `UNSUPPORTED`, never a successful near-zero parse. Warmup rows are excluded from summaries. At n=5, reported p95 is the observed maximum; no p99 or confidence interval is inferred. Initial exploratory and final-artifact groups are not pooled.

## Final measurements

| Workload | State / measured n | p50 / p95 / max, ms | Median allocated, MiB | Semantic records |
| --- | --- | --- | --- | --- |
| 100 KiB mixed | Success / 20 | 17.25 / 31.17 / 35.56 | 6.00 | 2,174 |
| 1 MiB mixed | Success / 20 | 76.29 / 139.63 / 159.34 | 57.03 | 22,592 |
| 10 MiB mixed | Unsupported: source cap | — | — | 0 |
| 100 KiB single code block | Success / 5 | 5.78 / 6.40 / 6.40 | 1.21 | 2 |
| 1 MiB single code block | Success / 5 | 25.01 / 30.89 / 30.89 | 13.26 | 2 |
| 100 KiB table | Success / 5 | 34.88 / 54.39 / 54.39 | 11.91 | 6,007 |
| 100 KiB headings/links | Success / 5 | 55.04 / 81.85 / 81.85 | 27.92 | 11,265 |
| 100 KiB dense markers | Success / 5 | 187.44 / 207.69 / 207.69 | 59.47 | 13,843 |

Unsupported adverse controls: 1 MiB tables/dense markers hit the node cap; 1 MiB heading corpus exceeds 50,000 lines; nested corpora hit depth/span limits; long-line corpora exceed 65,536 units per line. Unsupported cold-call durations and allocations remain in raw rows but are not successful-parse percentiles. The 1 MiB marker rejection took **1,553.54 ms / 555.59 MiB cumulative allocation / 264.2 MiB peak process RSS**. Limits after AST construction do not prevent all transient allocation. This is a documented reason to keep consumers on demand and retain the initial full-parse envelope.

The 100 KiB / 1 MiB mixed models retained approximately **0.24 / 2.54 MiB** beyond their already-held source and runtime in the post-GC comparisons. Whole-process peak RSS was **94.0 / 274.8 MiB** during repeated parsing. The code-block model retained about 0.51 MiB for 1 MiB source, mostly line-range records, without a second retained code-body string. Post-GC differences are estimates; negative 24/112-byte differences for unsupported cases are observation noise, not negative memory usage. Source acquisition includes file decoding and snapshot creation, unlike constant-work live-buffer capture.

Host: **MacBookPro15,2, Intel i5-8279U 2.40 GHz, eight logical CPUs, 8 GiB RAM, macOS 15.7.7 x86_64**. Actual Java distribution/version, load, memory pressure, swap and JVM arguments are in final raw reports. Existing swap was about 3.9 GiB. Measurements are exploratory on this host, not cold-cache startup or quiet-machine qualification. No physical input/display latency is inferred from these headless cases.

## Evidence inventory

| Artifact | Contents |
| --- | --- |
| `cases.json.gz` | All 24 raw case reports: samples, environments, commands, stdout/stderr, fixture parameters/hashes and artifact/probe/runner identities. |
| `case-index.json` | Case identity, state, measured/warmup counts, fixture hash, command and eligibility reason. |
| `measurements.json` | Separate per-case summaries, allocation samples, record counts, first-use and GC/whole-process RSS observations. |
| `span-probe.txt` | Independent CommonMark span evidence, including incorrect inline locations after escaped table pipes and correct UTF-16/CRLF offsets. |
| `class-isolation.json` | All 19,117 pre-existing JAR classes byte-identical; 21 new classes, artifact growth 33,739 bytes. |
| `regression-suites.json` | Final Maven per-suite counts and existing skip cases. |
| `packaged-smoke.json` | Final packaged-only runtime/provider and Markdown/rich-media smoke results. |
| `verification-logs.json.gz` | Focused/new-test development failure and correction, both full suites, builds, diagnostic and smoke/tooling logs. |
| `baseline-files.json.gz` | Starting HEAD/status and all existing tracked/nonignored-untracked file hashes, including preserved 1D work. |
| `verification.json` | Final repository/source/artifact identities, exact verification commands, README and baseline-file preservation, external HEAD movement. |
| `manifest.json` | Evidence file hashes, excluding the manifest itself. |

The external run root recorded in commands retains the preserved pre-2A and initial semantic JARs, diagnostic source/classes and original logs. Large generated fixtures and benchmark children are not retained or running. No profiler/telemetry agent or production instrumentation was installed.

## Verdict

**GO for the isolated 2A contracts and eligible on-demand parsing.** No consumer or whole-document parse is added to ordinary editing. Complete general Markdown feature semantics and hard allocation-budget parsing are not claimed. Table-inline precision, unresolved-reference token metadata, math/wiki-link regions, task marker ranges and incremental semantic parsing remain explicit limitations.

Proceed only with the proposed narrow **2B heading/occurrence migration**, including current-revision/pane checks and an agreed unsupported-input policy. Keep the 1D lexer/checkpoint path, editing boundary, preview renderer/security and source view unchanged. The broader Swing preview responsiveness problem remains deferred.
