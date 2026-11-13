# Markdown performance baseline — Tranche 1

This is an opt-in measurement tranche. It changes no production editor behavior, UI, document ownership, parser, renderer, undo policy, or default performance threshold. The 0A/0B/0B.1 correctness work remains in place. `README.md` is protected.

## Existing infrastructure and measurement definitions

- `scripts/benchmark-editor.py` and `src/benchmark/java/shed/EditorWorkflowProbe.java` remain the desktop workflow reference. Its fresh-process launches do not purge filesystem/font caches. Its edit loop measures document mutation plus a **forced synchronous paint**, not native keyboard-to-screen latency. Its historical 2 s visible / 3 s source / 10 s full UI, 16 ms edit/paint, 50 ms picker, 384 MiB used heap and 768 MiB RSS targets are unchanged diagnostic gates.
- `EditorResponsivenessBenchmark` measures immutable snapshot operations and separately executed background computations. Its reported convergence is the maximum of independent measured stages, not observed concurrent UI convergence. The existing 16 ms edit/caret and 250 ms convergence thresholds remain unchanged. It does not measure Swing painting or Markdown editing.
- `LargeFileBenchmark` is a legacy model/filesystem harness. `coldOpen` means the first open after copying, **not cold OS caches**. `edit` calls whole-content `setContent`, not ordinary typing. `save` of an unmodified read-only large file is a no-op. Do not use these labels to claim local editing or streamed-save performance.
- Existing `PerfService` paint-completion metrics, source snapshot ownership, `SyntaxUiController` worker submissions, `SearchPattern`, `LargeFileProjection`, `AtomicFileWriter`, and `MarkdownPreviewAssets` are reused directly. The new probe is compiled independently against the measured shaded JAR, following the existing desktop probe pattern; it is outside ordinary Maven source/test roots.

## Reproduction

Use Java 21 and the repository's existing Maven resolver. No installation or dependency download is required:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -DskipTests package

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_benchmark_markdown.py

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --output /tmp/shed-md-model-NEW --mode model --samples 100 --warmups 10

PYTHONDONTWRITEBYTECODE=1 python3 scripts/benchmark-markdown.py \
  --output /tmp/shed-md-ui-NEW --mode ui --sizes 102400 1048576 10485760 \
  --samples 100 --warmups 10 --timeout 90
```

Every output directory must be new and outside the checkout. Large files are generated there, not in Git. Each process edits only its own disposable input copy and isolated configuration. The corpus generator records exact bytes, SHA-256, shape, complete blocks and final padding. The main ASCII/UTF-8 corpus includes headings, prose, lists, tasks, quotes, links/references, escaped-pipe tables, code, local images, Mermaid and math. ASCII is intentional for exact streamed document-byte validation; Unicode correctness is covered separately by the regression suites.

Additional runs use the same command with these options and distinct output directories:

| Scenario | Options |
| --- | --- |
| Wrapped source | `--mode ui --wrap --sizes 102400 1048576 10485760 --samples 100 --warmups 10 --timeout 90` |
| Adversarial model | `--mode model --shapes long-line nested table fence headings markers --sizes 1048576 --samples 30 --warmups 3 --timeout 45` |
| Adversarial UI | `--mode ui --shapes long-line headings table --sizes 1048576 --samples 30 --warmups 3 --timeout 60` |
| Whole preview pipeline | `--mode preview --shapes plain --sizes 102400 1048576 --samples 1 --warmups 0 --timeout 30` |
| Rich preview | `--mode preview --sizes 4096 --samples 1 --warmups 0 --timeout 35` |
| Math/Mermaid assets | `--mode assets --sizes 4096 --samples 6 --warmups 0 --timeout 35` |
| Editing with preview | `--mode ui --preview --shapes plain --sizes 102400 --samples 30 --warmups 3 --timeout 60` |
| Empty startup | `--mode startup-empty --shapes plain --sizes 4096 --samples 1 --warmups 0 --runs 3 --timeout 30` |
| Markdown startup | `--mode startup --shapes plain --sizes 102400 1048576 --samples 1 --warmups 0 --runs 3 --timeout 30` |
| Configured-font startup | `--mode startup --shapes plain --sizes 102400 --font Dialog --samples 1 --warmups 0 --runs 3 --timeout 30` |
| Bounded heap, model | `--mode model --sizes 10485760 52428800 104857600 --heap 128 --samples 30 --warmups 3 --timeout 45` |
| Bounded heap, UI | `--mode ui --sizes 102400 --heap 128 --samples 30 --warmups 3 --timeout 60` |
| UI attribution | `--mode ui --sizes 10485760 --samples 30 --warmups 3 --timeout 60 --profile` |
| Preview attribution | `--mode preview --shapes plain --sizes 1048576 --samples 1 --warmups 0 --timeout 30 --profile` |
| Startup/native attribution | `--mode startup-empty --shapes plain --sizes 4096 --samples 1 --warmups 0 --timeout 30 --profile` |

Default child heaps are 64 MiB initial / 512 MiB maximum; allowed maxima are bounded at 768 MiB. Individual child lifetimes are bounded, and the watchdog terminates only its own child. No system-wide stress, cache eviction, swap exhaustion, unrelated process termination, or system configuration change is performed. `--profile` separately enables JFR (profile settings, 64 MiB cap), NMT summary and GC/safepoint logs. Profiled times are attribution runs, not interchangeable with unprofiled latency measurements. RSS is sampled with `ps` about every 250 ms; its maximum is a **sampled peak**, not the kernel high-water mark. NMT does not account for every external graphics/font allocation.

Inspect a recording without installing tools:

```sh
JDK="$(scripts/resolve-toolchain.sh home)"
"$JDK/bin/javac" -cp target/shed-2.0.0.jar -d /tmp/shed-jfr-summary \
  src/benchmark/java/shed/MarkdownJfrSummary.java
"$JDK/bin/java" -Xmx256m -cp /tmp/shed-jfr-summary:target/shed-2.0.0.jar \
  shed.MarkdownJfrSummary /path/to/profile.jfr > /tmp/shed-jfr-summary.json
```

JFR stack-presence groups overlap. Execution/native sample counts are not wall-clock percentages. Weighted allocation samples are estimates of allocation traffic, not retained heap. Full recordings and timeout thread dumps remain outside the repository.

## Interpretation and safeguards

- `wall_ms` on UI text operations includes controller/action dispatch, document mutation, document/view listeners and synchronous EDT work. Snapshot-only replacement is separately measured in model mode; it is not subtracted from UI wall time to fabricate an isolated storage cost.
- `input_to_real_paint_ms` comes from Shed's existing `edit.input-to-paint` metric, ending after the source component's actual normal Swing paint. The origin is a programmatically delivered controller/Swing action, not physical keyboard hardware, IME, native event delivery, monitor scanout, or a queued repaint. Queue time is recorded separately. Samples wait for their paint to avoid coalescing away observations; this measures sequential interactive edits, not burst throughput. Background syntax work is not drained between edits.
- Caret/selection/scroll/split operations report EDT action time and separately named forced visible paint time. They are **not** physical event-to-paint or compositor frame-rate measurements.
- Warmups are excluded. Each ordinary UI edit category has 100 measured operations pooled across beginning/middle/end, with separate per-position samples. Report p99 only at n >= 100. Longest-line, search, stage and first-use measurements have smaller n and no meaningful p99. The longest logical line is selected by UTF-16 length; ties need not select Swing's internally cached maximum-width line.
- The production syntax probe requests real `SyntaxUiController` jobs and records status/generation plus `scanChars`/total source from `PerfService`. Cancelled or superseded requests are not successful near-zero-latency measurements. Isolated lexer samples are clearly separate.
- Preview opening/refresh includes real asset preparation, parsing, sanitization and `JEditorPane.setText`. Layout/forced paint is separate. Warm stage replays call the same production methods but are **not additive partitions of the first-use total**. Offscreen installation/layout replays are not substitutes for the actual preview surface.
- The existing notification row is held stable during source UI measurements, and viewport/font/DPI transform are recorded. This avoids confusing the 0B.1 footer-geometry clamp with scroll synchronization work. Preview rendering may independently change HTML view geometry; no pixel tolerance or behavior is changed.
- Search/replace model operations measure the matching/computation engine, not complete find-bar interaction or replacement installation. Projection moves are bounded reader-window operations, not full source-view scrolling. Read-only editing is explicitly unsupported. Clean large-file save and a genuine independently verified streamed atomic copy are separate operations. Dirty editable saves are verified against the authoritative document hash.
- Heap snapshots are taken without forced GC. Used heap after close includes garbage awaiting collection; neither allocation counters nor these snapshots establish retained memory or leaks. Source/probe/fixture/JAR hashes distinguish runs. OS cache and background host load remain uncontrolled; fresh JVM does not mean cold cache.
