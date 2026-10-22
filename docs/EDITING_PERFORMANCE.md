# Editing Performance

Shed keeps files at or below 25 MiB and 500,000 logical lines editable. Changes update an immutable piece-tree snapshot on the Swing event thread; syntax, symbols, diff markers, bracket matching, LSP decoration mapping, and the initial open-buffer completion index consume that snapshot off the event thread. Ordinary word-index edits update only the affected lines on the event thread. Older background results are discarded when their snapshot is no longer current. Syntax virtualizes token spans for files above the normal lexical-cache boundary: it reads only through the visible viewport plus context, advances multiline lexical state in the background, and refreshes after scrolling. Stale lexical scans stop between lines.

The live `:perf` view records `edit.input-to-document` and `edit.input-to-paint` for edits made through the active editor keyboard. The latter ends after Swing finishes painting the editor component; it does not measure display scanout. If Swing combines several edits into one repaint, that paint records the latest edit. `edit.document-to-paint-start` includes later event-thread work and time waiting for a repaint; `edit.paint-duration` measures the editor paint itself. Document or paint stages taking at least 16 ms appear individually with the background jobs active at that stage. Those jobs are context, not proof of a cause. Each metric reports the p95 of its latest 256 samples. Compare the same file and typing workload before and after a change. Indent guides read only visible lines during paint. Automatic LSP startup runs on a worker, and the open-buffer completion index updates words from changed lines for ordinary edits; large replacements rebuild that index in the background.
It also records `startup.window-visible` from editor construction to the first visible window, `startup.files-installed` when command-line files have been loaded into buffers, and `lsp.startup` or `lsp.startup.failed` for automatic language-server launches. `file.open.load` measures file decoding and document construction on a worker; `file.open.install` measures attaching that buffer to the Swing UI. Command-line files load after the window opens. JVM launch time is outside `startup.window-visible`.

To profile the source-checkout launch path, run `make build` once, then `make profile-startup` (or `make profile-startup ARGS='path/to/file'`). The command uses the same launcher as `make run` and prints phase timings to the terminal when the first Swing window becomes visible. `JVM launch to visible` includes JVM startup; the phase timings begin in the editor constructor. Keep the JAR unchanged between runs to compare launches without Maven build time. Close Shed normally after each run. The visible-window timestamp does not measure display scanout or when an asynchronously opened file finishes loading.

On macOS with the default editor and UI fonts, Shed first shows the editor buffer using a local system font file. It loads Java's system font catalog on a worker, then attaches the menu, footer, and tool panels without replacing the buffer. The profiler prints a second `JVM launch to full UI` timing for this path. The matching `:perf` metrics are `startup.editor-usable` and `startup.full-ui-ready`. Configured font overrides and other platforms use the regular startup path.

`completion.input-to-paint` measures from the edit or completion key press until Swing first paints the suggestion popup. It includes the completion wait and event-thread scheduling, but not display scanout. Compare its p95 while typing the same prefix in the same file.

The diff gutter updates a changed line without scanning the full file when an edit leaves line boundaries intact. Structural edits rebuild its markers in the background. Large-file symbol refresh waits for a longer typing pause, and canceled scans stop at line boundaries; the cache is rebuilt once after the document settles. Java files above 750,000 characters use the lightweight class-and-method outline instead of the full structural parser, so their outline can omit fields and some complex declarations.

Files above either configured limit continue through the existing read-only large-file projection.

Run the fixed local workload after building the JAR:

```sh
java -cp target/shed-2.0.0.jar shed.EditorResponsivenessBenchmark
```

It creates a deterministic Java-like 500,000-line fixture and takes 25 warm samples. Exit status is non-zero when either edit or caret p95 exceeds 16 ms, or when deferred visual convergence p95 exceeds 250 ms. The measurement is intentionally outside Maven's normal test suite because it is hardware-sensitive.

The convergence sample covers virtualized syntax tokens, symbols, open-buffer completion words, and diff markers. It reports the slowest independent background task, as those jobs run concurrently. It measures model and background-work time, not Swing painting or language-server network latency.

## Repeatable desktop workflow benchmark

After `make build`, run:

```sh
python3 scripts/benchmark-editor.py --jar target/shed-2.0.0.jar \
  --label my-revision --runs 5 --output /tmp/shed-workflow-performance.json
```

The runner compiles one independent probe against the supplied JAR, so an older JAR can use the same harness. It creates an isolated home/configuration and a deterministic 20,000-line text fixture, starts five fresh JVMs, and captures raw samples and JAR/probe hashes in JSON. The fixture uses no external language server or user extensions. Heap bounds are 64–512 MiB for every process. Python 3, Java 21, `javac` and a working desktop display are required; RSS is obtained through `ps` when available.

Three launch milestones are separate: the visible window, the requested source document installed and painted, and the complete UI with menus/tool surfaces painted. These include JVM launch time but not probe compilation or fixture creation. Every run is a fresh process and a fresh configuration; OS filesystem/font caches are **not** purged. The first run and later runs are retained individually, without describing them as controlled cold-cache measurements.

After full readiness and a one-second settling interval, the probe records used/committed Java heap without forcing GC and resident process memory. Each JVM then performs 10 warmups and 25 measured document insertions followed by synchronous painting of the visible source area, and the same number of fuzzy-ranking operations over 50,000 synthetic paths. Edit measurements exclude input-device, event-queue and monitor latency, asynchronous syntax convergence, and the cost of resetting the fixture after a sample. Picker measurements cover ranking rather than file discovery or result-window painting. Use `:perf` and the large-file benchmark above for those separate dimensions.

The runner exits nonzero if p95 exceeds any initial target: visible window 2 s, painted source 3 s, complete UI 10 s, edit-to-paint 16 ms, picker ranking 50 ms, used heap 384 MiB, or RSS 768 MiB. These are diagnostic targets, not CI promises or competitor rankings. Missing RSS is reported as unavailable. Raw samples matter: five startup/memory runs provide only a small local sample; the 125 edit/picker samples are pooled across those five processes. Keep the machine, JDK, heap, fixture and harness unchanged when comparing builds, and run without other builds/tests competing for resources.

To isolate changes to fuzzy ranking from UI/background activity, compare two JARs in one JVM:

```sh
java -Xms64m -Xmx512m src/benchmark/java/shed/PickerComparisonProbe.java \
  /path/to/baseline.jar target/shed-2.0.0.jar > /tmp/picker-comparison.json
```

This loads each ranker through an independent classloader, alternates their order, warms each for 30 iterations, and records 50 wall-clock and current-thread CPU samples per version over identical 50,000-path inputs. Every result list must be identical. CPU time excludes other threads and scheduler waits; the wall-clock desktop measurements above remain the measure of user-visible responsiveness. See [recorded results](PERFORMANCE_RESULTS.md).
