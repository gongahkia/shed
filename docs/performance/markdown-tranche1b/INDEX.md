# Tranche 1B evidence

Read the [implementation and performance report](../../SOURCE_VIEW_PERFORMANCE.md). The selected implementation is the bounded width summary, not the earlier witness experiment.

Current evidence groups are named `summary-before-*` (preserved pre-optimization 1A distribution) and `summary-after-*` (selected summary). Each case pair uses identical fixture bytes, runner/probe versions, JVM flags and operation counts. Run order is before then after per scenario, sequentially without concurrent builds/tests. Repeated controls are labeled separately. Do not pool profiled and unprofiled timings or the rejected candidate.

| File | Contents |
| --- | --- |
| `runs.json` | Fixture generation/size/hash, JAR, runner/probe identities, arguments and Java version. |
| `cases.json` | Exact process commands, per-case environment, memory/GC phases, completion states, source-integrity checks and statistical summaries. |
| `samples.jsonl.gz` | Raw operation samples (deterministic gzip). `run_group` + `case` join to metadata. |
| `profiles.json` | JFR samples/stacks/allocation traffic; overlapping groups are not CPU percentages or retained sizes. |
| `diagnostics.json` | EDT timeout stacks for incomplete extreme-line cases. |
| `executions.json` | Exact invocation records and exit codes. |
| `verification.json` | Test results, protected-file hashes, source-freeze and repository-integrity checks. |
| `manifest.json` | Evidence-file hashes, excluding the manifest itself. |
| [rejected-witness/](rejected-witness/INDEX.md) | Unmodified earlier experiment, explicitly excluded from selected-candidate claims. |

Large fixtures, JFR recordings, RSS traces, logs and child output remain outside the repository under the temporary run root recorded in `runs.json`. No giant Markdown fixture is committed. The runner retains its own evidence intentionally and closes its children/windows; it does not run a monitoring service.

## Reproduction

Use Python 3, the cached Java 21 toolchain and a working graphical desktop. Build the selected distribution with the established offline packaging command:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m package
```

Supply a preserved baseline JAR and the selected JAR to the same runner. Use a new temporary output directory per command. The executed absolute commands are preserved in `executions.json`; these relative forms reproduce their workloads:

```sh
python3 scripts/benchmark-markdown.py --jar /path/to/build.jar \
  --output /tmp/new-source-view-main --mode ui \
  --sizes 102400 1048576 10485760 --samples 100 --warmups 10 --timeout 120
```

Repeat that main command with `--wrap` and a fresh output directory. Additional commands use these arguments after `--jar` and `--output`:

| Scenario | Arguments |
| --- | --- |
| Unique widest line | `--mode ui --shapes widths --sizes 1048576 --samples 100 --warmups 10 --timeout 90` |
| Tabs | `--mode ui --shapes tabs --sizes 1048576 --samples 100 --warmups 10 --timeout 90 --tab-size 2` (repeat at 8) |
| Larger proportional font | `--mode ui --sizes 1048576 --samples 100 --warmups 10 --timeout 90 --font Dialog --font-size 20` |
| Smaller monospaced font | `--mode ui --sizes 1048576 --samples 100 --warmups 10 --timeout 90 --font Monospaced --font-size 12` |
| Extreme single line | `--mode ui --shapes long-line --sizes 1048576 --samples 30 --warmups 3 --timeout 90` |
| JFR attribution | `--mode ui --sizes 10485760 --samples 30 --warmups 3 --timeout 60 --profile` |
| Fresh-process startup | `--mode startup --shapes plain --sizes 1048576 --samples 1 --warmups 0 --runs 3 --timeout 30` |

Default child heaps are 64–512 MiB. Optional JFR has a 64 MiB cap; timeouts terminate only the runner's own process. No system-wide pressure simulation, cache purge, environment installation or user-document edit is used. Fixtures contain deterministic ASCII/UTF-8 and exact EOF bytes; the UI loop verifies source SHA-256 after all edit/undo operations. `OK` means completed with the scenario’s correctness checks; it does not mean every latency budget passed. A read-only/unsupported/timeout case is not interpreted as a successful edit.

A UI edit's `wall_ms` is EDT operation time including synchronous document listeners/layout invalidation. It does not isolate snapshot time. `input_to_real_paint_ms` waits for an attributed completed Swing paint, excludes native keyboard dispatch/display scanout and does not include later asynchronous syntax convergence. `queue_ms`, paint duration and document-to-paint-start are separate fields. Scroll/caret/selection/split operations report action and forced-visible-paint time separately; they are not physical scrolling latency. Normal edits pool 100 samples across beginning/middle/end (34/33/33); each position alone does not support p99. Width edits have 50 samples each, longest-line cases 10, shared-split edits 20, startup 3. Nearest-rank p99 is null below 100. Warmups precede normal edits; longest-line and split cases have their smaller explicit counts in probe source.

For JFR summaries use the unchanged compilation/execution procedure in the [Tranche 1 report](../../MARKDOWN_PERFORMANCE_BASELINE.md), pointing to the selected recordings. `PlainView.calculateLongestLine` is an exact stack group; a broad `PlainView` substring also matches `SourcePlainView` and must not be treated as the stock global rescan count.

Regression commands:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m test
python3 -m unittest discover -s scripts -p test_benchmark_markdown.py
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar \
  --output /tmp/new-source-view-packaged.json
```

The large-font/unique-32-KiB-line combination timed out in both earlier baseline/witness runs, with partial samples preserved only under `rejected-witness`. It is not a successful selected-summary result or a target met by this tranche. The final matrix uses ordinary mixed prose to qualify Dialog 20 and separately exercises very long lines.
