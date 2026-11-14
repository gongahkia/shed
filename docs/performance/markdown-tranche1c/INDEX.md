# Tranche 1C — partial result and stopping evidence

Read the [implementation/investigation report](../../MARKDOWN_PREVIEW_RESPONSIVENESS.md). **This is not a completed asynchronous preview implementation.** The only production correction removes a duplicate initial render. The detached HTML experiment establishes the remaining EDT installation/layout barrier.

| File | Evidence |
| --- | --- |
| `runs.json` | Fixture sizes/hashes, generator parameters, probe/runner/JAR hashes, Java version and arguments. |
| `cases.json` | Exact child commands, host environment/load/pressure/swap, memory phases, summaries and explicit completion/error/timeout states. |
| `samples.jsonl.gz` | Raw operation samples, keyed by `run_group` and `case`; deterministic gzip. |
| `executions.json` | Actual invocations and exit codes. |
| `diagnostics.json` | Exploratory 10 MiB timeout EDT stack and initial detached-document probe output. |
| `regression-suites.json` | Complete Maven per-suite counts and skip reasons. |
| `verification.json` | Protected-file/worktree checks, test totals, packaged smoke output and explicit unimplemented requirements. |
| `manifest.json` | Evidence hashes, excluding itself. |

Primary comparisons use `paired-before-*` and `paired-after-*`. Each pair uses identical runner/probe/fixture hashes; only the distribution JAR changes. Initial `before-*` runs are exploratory and have an older probe hash; do not pool them with primary measurements. In particular `before-large` is a **TIMEOUT**, not a successful large-preview qualification. `OK` denotes completed scenario/correctness checks, not a passed latency budget.

The diagnostic's `detached_*_worker` stages run on the probe main thread, distinct from Swing EDT, using an independently owned HTMLDocument and HTMLEditorKit. It never constructs/mutates a live component off EDT. `setDocument`, preferred-size/layout and offscreen painting happen on EDT. A source callback queued immediately before those stages types/undoes a character after dispatch and verifies original source bytes. Its `queue_ms` exposes the installation/layout stall and excludes native keyboard/compositor latency. This is an opt-in experiment, not production instrumentation or a production rendering replacement.

## Reproduction

Python 3, the existing cached Java 21/Maven toolchain and a graphical desktop are required. The runner uses isolated homes/disposable input copies, bounded 64–512 MiB heaps and per-child watchdogs. Large fixtures, logs and optional JFR remain outside the checkout in the run root recorded by the JSON files. No cache purge, system-wide pressure simulation, new dependency installation or user-document editing was performed.

Build with Shed's ordinary offline packaging workflow:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m -Dmaven.jar.forceCreation=true package
```

Use a preserved pre-change JAR and the resulting JAR with the same current runner. Each command needs a new external output directory. Exact executed absolute commands are in `executions.json`.

```sh
python3 scripts/benchmark-markdown.py --jar /path/to/build.jar \
  --output /tmp/new-preview-install --mode preview-install --shapes plain \
  --sizes 102400 1048576 --samples 3 --warmups 0 --timeout 60
```

Other paired workloads, after supplying `--jar` and a fresh `--output`:

| Scenario | Arguments |
| --- | --- |
| Production preview, refreshes/burst/layout/paint/stage replay | `--mode preview --shapes plain --sizes 102400 1048576 --samples 3 --warmups 0 --timeout 60` |
| Source with preview | `--mode ui --preview --shapes plain --sizes 1048576 --samples 100 --warmups 10 --timeout 120` |
| Source without preview | `--mode ui --shapes plain --sizes 1048576 --samples 100 --warmups 10 --timeout 120` |
| Rich Markdown/local image/math/Mermaid | `--mode preview --sizes 4096 --samples 1 --warmups 0 --timeout 60` |
| Font-ready first/cached/changed/invalid assets | `--mode assets-ready --sizes 4096 --samples 6 --warmups 0 --timeout 60` |
| Exploratory large preview (known timeout, not paired) | `--mode preview --shapes plain --sizes 10485760 --samples 1 --warmups 0 --timeout 45 --profile` |

Source insertion rows pool 100 operations across beginning/middle/end (34/33/33), after 10 warmups. Opening has three samples, refresh nine, installation three per stage and first-use assets one per kind; the latter do not support p99. Raw nearest-rank p99 is null below 100. The normal source loop's action-to-paint starts at EDT dispatch and does not force an overlapping refresh; it must not be called responsiveness-under-active-render qualification. Image readiness, HTML presence, forced layout and paint are distinct measurements.

Preview scroll benchmarks preserve their recorded notification-row/viewport context. The detached target uses a fixed 600×600 allocation independent of footer changes; source font/viewport/scaling are recorded by the existing harness. No pixel assertion or synchronization behavior was weakened.

Regression commands:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -Djava.awt.headless=false -DargLine=-Xmx512m \
'-Dtest=MarkdownPreviewPaneTest,MarkdownPreviewRendererTest,MarkdownFoldingTest,MarkdownEditTest,SourcePlainViewTest,TexteditorSwingIntegrationTest#markdownPreview*' \
-Dmaven.jar.forceCreation=true package

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -Djava.awt.headless=false -DargLine=-Xmx512m test

python3 scripts/test-packaged-markdown.py --jar /path/to/after.jar --output /tmp/new-preview-packaged.json
python3 -m unittest discover -s scripts -p test_benchmark_markdown.py
```

Quote the wildcard test selector in zsh. A first unquoted invocation was rejected by the shell before Maven started; the quoted command above is the successful focused run.
