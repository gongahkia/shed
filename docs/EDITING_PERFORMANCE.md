# Editing Performance

Shed keeps files at or below 25 MiB and 500,000 logical lines editable. Changes update an immutable piece-tree snapshot on the Swing event thread; syntax, symbols, diff markers, bracket matching, LSP decoration mapping, and the initial open-buffer completion index consume that snapshot off the event thread. Ordinary word-index edits update only the affected lines on the event thread. Older background results are discarded when their snapshot is no longer current. Syntax virtualizes token spans for files above the normal lexical-cache boundary: it advances multiline lexical state in the background and materializes the visible viewport plus context, then refreshes after scrolling.

The live `:perf` view records `edit.input-to-document` and `edit.input-to-paint` for edits made through the active editor keyboard. The latter ends after Swing finishes painting the editor component; it does not measure display scanout. If Swing combines several edits into one repaint, that paint records the latest edit. `edit.document-to-paint-start` includes later event-thread work and time waiting for a repaint; `edit.paint-duration` measures the editor paint itself. Document or paint stages taking at least 16 ms appear individually with the background jobs active at that stage. Those jobs are context, not proof of a cause. Each metric reports the p95 of its latest 256 samples. Compare the same file and typing workload before and after a change. Indent guides read only visible lines during paint. Automatic LSP startup runs on a worker, and the open-buffer completion index updates words from changed lines for ordinary edits; large replacements rebuild that index in the background.
It also records `startup.window-visible` from editor construction to the first visible window, `startup.files-installed` when command-line files have been loaded into buffers, and `lsp.startup` for successful automatic language-server launches. Command-line files load after the window opens. JVM launch time is outside `startup.window-visible`.

The diff gutter updates a changed line without scanning the full file when an edit leaves line boundaries intact. Structural edits rebuild its markers in the background. Large-file symbol refresh waits for a longer typing pause, and canceled scans stop at line boundaries; the cache is rebuilt once after the document settles.

Files above either configured limit continue through the existing read-only large-file projection.

Run the fixed local workload after building the JAR:

```sh
java -cp target/shed-2.0.0.jar shed.EditorResponsivenessBenchmark
```

It creates a deterministic Java-like 500,000-line fixture and takes 25 warm samples. Exit status is non-zero when either edit or caret p95 exceeds 16 ms, or when deferred visual convergence p95 exceeds 250 ms. The measurement is intentionally outside Maven's normal test suite because it is hardware-sensitive.

The convergence sample covers virtualized syntax tokens, symbols, open-buffer completion words, and diff markers. It reports the slowest independent background task, as those jobs run concurrently. It measures model and background-work time, not Swing painting or language-server network latency.
