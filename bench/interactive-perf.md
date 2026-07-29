# Interactive performance validation

Run `bash bench/scripts/interactive_perf.sh --runs 10 --state cold` on the target Mac. It generates ignored 10k/50k-file workspaces and 1 MiB/100 MiB/1 GiB syntax fixtures, preserves raw JSONL samples only with `--keep-traces`, and writes an ignored report under `bench/results/`.

Use `--record-baseline` only after manual approval. Comparison is lower-is-better median latency; a regression is greater than `max(5%, 1 ms)`. `cold` uses a fresh temporary home per run; `warm` reuses one temporary home. The script records the requested state, not an inferred cache state.

Automated coverage:

- Cmd-P: exact basename, full relative path, fuzzy query, and no-match correctness on 10k and 50k files; reports query-to-results duration and validates expected top result/result count.
- Scroll: precise delta, page delta, and long jump on 1 MiB/100 MiB/1 GiB fixtures; reports input-to-render-commit and syntax-refresh duration.
- Each report contains raw samples, median, p95, max, app commit, macOS, hardware, display mode/refresh, fixture tree checksum, requested state, and explicit failure reasons.

Manual checklist

Run this after the automated report has no failures. Mark each action pass/fail and record the fixture, query, observed delay, and whether the window remained responsive.

1. Open this repository; wait for indexing to finish; close and reopen it. Verify the tree, tabs, and selected document restore without a blank or stale state.
2. Open the 10k and 50k fixtures. After indexing completes, press Cmd-P and test empty input, exact basename, full relative path, fuzzy input, no match, fast type/backspace, arrow selection, Enter open, and Escape close.
3. In each Cmd-P case, verify the expected file is first when deterministic, no-match has no selection, the current file is found immediately by name, and typing continues without queued keystrokes.
4. In a 1 MiB Swift file, use a trackpad for small fractional deltas, a normal wheel/page movement, Home/End, far jump, and return. Verify continuous movement, stable caret/line numbers, and no visible blank bands.
5. Repeat scrolling on 100 MiB TypeScript and 1 GiB Python fixtures. Test a fling, rapid direction reversal, resize while moving, find with many matches, and scrolling after dismissing Find.
6. For Swift, TypeScript, Python, and Rust files, scroll across syntax-dense regions. Verify highlighting catches up without freezing input, stale colors, or a growing delay after repeated movement.
7. In a normal source file, type, multi-select, undo/redo, save, close without saving, reopen, and verify cursor/selection rendering stays responsive.
8. Open four files, reorder/select tabs, split the editor, move focus between panes, close a pane, and restore one pane. Verify focus, active tab, and pane state remain correct.
9. Open the integrated terminal; run `pwd`, `git status --short`, and `swift --version`. Verify prompt readiness, workspace cwd, output scroll, focus return to editor, and resize behavior.
10. In Git Changes, refresh status, open a changed file, inspect a diff/history view, then return to the editor. Verify no UI stall and no unintended staging/commits.
11. Configure one available LSP. Verify completion, definition, references, diagnostics, symbol palette, and rename/format if supported. If unavailable, record `not configured`; do not treat it as an app failure.
12. Test Cmd-Shift-F on this repository and the 50k fixture. Verify results begin incrementally, cancellation works, opening a result is correct, and large result sets do not destabilize scroll.
13. Restart the app after all tests. Verify it quits promptly, reopens normally, and does not retain a runaway CPU/RSS footprint in Activity Monitor.

Acceptance: no automated scenario failure, no metric regression beyond policy unless manually approved, and no reproducible input lag, scroll jitter, blank content, wrong selection, or data-loss behavior in the manual checklist.
