# Tranche 1C.1 — Swing HTML layout feasibility

Read the [engineering verdict](../../SWING_HTML_LAYOUT_FEASIBILITY.md). Three approaches were tested in isolated opt-in code. No production behavior, source file or existing test was changed.

| File | Evidence |
| --- | --- |
| `cases.json` | Per-case commands, fixture generation/hashes, artifact/probe/runner identities, environment, summaries, RSS and completion states. |
| `samples.jsonl.gz` | All raw samples with case, primary/exploratory and instrumentation-version labels. Do not pool versions. |
| `executions.json` | Bounded experiment and verification commands/exit codes. |
| `profile.json` | Separate JFR stack summary; counts overlap and are not CPU percentages. Includes diagnostic work. |
| `diagnostics.json` | Bounded-heap OOM, incomplete repeat and final resource-guard evidence. |
| `regression-suites.json` | Full Maven suite counts and five existing skip reasons. |
| `verification.json` | All initially tracked hashes preserved, README, prototype freezes, tests and packaged smoke. |
| `INSTRUMENTED_RESULTS.md` | Earlier paired matrix with pixel-readback overhead, explicitly excluded from clean latency claims. |
| `manifest.json` | File hashes excluding the manifest itself. |

## Versions and negative results

- `corrected-prose` and `corrected-composite` are the final latency controls: pixel checks occur only in verification. A/B paint to a reused buffer; hidden C staging performs layout and paints only on publication. These reflect the work a staging implementation actually needs.
- Exact `final-prose100`, `final-prose1m`, `final-compact`, `final-rich`, `final-legacy`, `final-repeat`, `final-profile`, `final-resource` are the earlier instrumented primary matrix. They captured screenshot pixels inside timed steps. Their semantic/geometry checks remain evidence, but do not treat their small source-latency differences as clean production costs.
- All other groups are development/pilot runs. They include a corrected width-negotiation harness and earlier callback labels. An initial `pilot-1m` compilation failed before JVM execution (Java var compound declaration); it has no timing report. Its correction did not change production.
- `qualified-blocks-1m` exhausted the child’s fixed 512 MiB heap in the stock HTML reader. No heap increase or repeat exhaustion was used. The final known-risk guard produces `SKIPPED_RESOURCE_GUARD`, not rendering success.
- `final-repeat` exited 0 without HT_DONE during its second pass. Its cause is unresolved; it is ERROR. Partial samples do not establish final convergence/disposal.
- An OK case means the **experiment** completed. C can be explicitly stopped/rejected inside an OK experiment. No truncated/stale document is published as success.

## Reproduce

Python 3, the existing Java 21 toolchain and an interactive graphical desktop are required. Use a new output directory outside the checkout. The wrapper imports existing fixture, environment and percentile helpers read-only; it disables Python bytecode writes. It freezes the probe source into the output directory before compilation and uses only that probe plus the supplied packaged JAR. Bounds: 1 KiB–1 MiB, 5–100 source samples, 15–120 s child lifetime, 64–512 MiB heap, optional 64 MiB JFR. A watchdog affects only its own child. No user documents, system settings, caches or unrelated processes are changed.

```sh
python3 scripts/benchmark-preview-layout.py \
  --jar target/shed-2.0.0.jar --output /tmp/new-html-prose \
  --shape prose --size 1048576 --samples 100 --timeout 120
```

The current probe is the corrected version. Other fixed corpus cases:

| Case | Arguments after `--jar`/`--output` |
| --- | --- |
| 100 KiB prose | `--shape prose --size 102400 --samples 100 --timeout 120` |
| Larger table/code + prose | `--shape compact-blocks --size 1048576 --samples 100 --timeout 120` |
| Existing rich Markdown/images/math/Mermaid | `--shape mixed --size 4096 --samples 20 --timeout 120` |
| Legacy long unbroken padding | `--shape plain --size 1048576 --samples 100 --timeout 120` |
| Separate attribution | `--shape prose --size 1048576 --samples 20 --timeout 120 --profile` |
| Known parser-memory risk, guarded | `--shape blocks --size 1048576 --samples 5 --timeout 120` |

`prose` reuses the exact legacy plain generator and replaces only artificial p-only padding with equal-length p-space text. `plain` retains that unbroken word as an explicit adversarial width-negotiation control. `blocks` combines existing table/fence/plain generators. `compact-blocks` selects 256 code lines from the existing fence, retains the large table, then fills to exactly 1 MiB with existing prose; component hashes and provenance are recorded. Its legacy `shape: blocks` manifest label is disambiguated by the command/parts/selected-code-lines fields. No giant fixture is committed.

Each child compares stock full installation, three same-HTML replacements, A retained state (100/20 repetitions), B 20 local insert/remove pairs, and C one body block per EDT task. C allows its intentionally stale draft to finish only to collect input samples, rejects that revision, then builds/publishes a settled draft. Stop at the first >100 ms chunk and never publish its prefix; 100 ms is a safety stopping rule, **not an acceptance budget**. Target source p95 remains 16 ms.

Input is a queued callback against the real showing Shed source widget. It inserts/paints, cycles undo/redo/undo, and measures caret/scroll separately; every tenth callback adds a bounded five-edit/undo burst. The insertion metric includes queue wait and immediate Swing paint, excludes native keyboard and compositor latency, and is not IME/keymap qualification. Only the first 100/20 staging callbacks mutate source; later navigation callbacks are separately named. Source text is verified after phases. Preview-only tasks assert unchanged document revision/snapshot/dirty/saved baseline/undo availability before their queued source actions.

`dispatch_event` records actual AWT dispatch duration when ≥16 ms. Explicit contexts and `ambient.after.*` are distinguished; the latter are last-known context, not proof of causality. Startup, requested-GC and pixel-verification contexts are not candidate runtime costs. Input callbacks bundle several operations for coverage; do not attribute their entire dispatch time to preview.

The source notification row is held stable, actual source/preview extents and 2× scaling recorded. Hidden staging uses the same JScrollPane dimensions/policies and public viewport size negotiation, without repeatedly forcing a wide component to narrow width. No live Swing component/document is accessed off EDT. Detached HTML read follows the JDK-supported independent-model pattern; it is not permission to layout live components on workers.

Geometry checks use exact preferred sizes, text, links/IDs/image metadata, selected model/hit-test positions and first/middle/last 600×400 RGB crops. They are not full-document screenshot or accessibility certification. Source-scroll formula/disabled-mode checks and a toy queued-generation fence are isolated checks, not production lifecycle integration. Existing real preview synchronization tests are separately rerun unchanged.

Regression commands (the normal runtime JAR is unchanged):

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -Djava.awt.headless=false -DargLine=-Xmx512m test
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/new-html-packaged.json
python3 -m unittest discover -s scripts -p test_benchmark_markdown.py
```

Full JFR/logs/fixtures and frozen probe copies remain in the external run root named by case commands. They are retained evidence, not running resources or production workers. Profile decoding uses the existing MarkdownJfrSummary and includes allocation traffic, never equated with retained memory.
