# Editing Performance

Shed keeps files at or below 25 MiB and 500,000 logical lines editable. Changes update an immutable piece-tree snapshot on the Swing event thread; syntax, symbols, open-buffer completion words, diff markers, bracket matching, and LSP decoration mapping consume that snapshot off the event thread. Older background results are discarded when their snapshot is no longer current.

Files above either configured limit continue through the existing read-only large-file projection.

Run the fixed local workload after building the JAR:

```sh
java -cp target/shed-2.0.0.jar shed.EditorResponsivenessBenchmark
```

It creates a deterministic Java-like 500,000-line fixture and takes 25 warm samples. Exit status is non-zero when either edit or caret p95 exceeds 16 ms, or when deferred visual convergence p95 exceeds 250 ms. The measurement is intentionally outside Maven's normal test suite because it is hardware-sensitive.

The convergence sample covers syntax tokens, symbols, open-buffer completion words, and diff markers. It measures model and background-work time, not Swing painting or language-server network latency.
