# Source-view invalidation — Tranche 1B

This tranche retains JTextArea, PlainDocument, FileBuffer, authoritative snapshots, undo, source rendering and the existing UI delegates. Historical [Tranche 1 results](MARKDOWN_PERFORMANCE_BASELINE.md) and [packaged Mermaid verification](PACKAGED_MARKDOWN_TEST.md) remain unchanged. [Raw evidence and reproduction](performance/markdown-tranche1b/INDEX.md) distinguish the selected implementation from the rejected first candidate.

The selected bounded-width implementation reduces 10 MiB unwrapped undo to completed Swing paint from **83.35 to 5.69 ms p95**, confirmed by a reverse-order **54.86 to 3.82 ms** pair. Full regression verification passes. This is a selective source-view improvement; wrapped/complex-text paths and extreme-line painting remain explicit limitations.

## Confirmed operation and root cause

Keyboard/controller editing mutates the shared Swing document on EDT. FileBuffer tracks revisions and undo; `PaneBufferController.handleDocumentChange` updates the immutable snapshot, dirty state, LSP/completion/search and deferred syntax work. Swing delivers the same event to every attached view. Document-model timings therefore exclude important source-view costs.

The inspected Temurin/OpenJDK 21 source is `java.desktop/javax/swing/text/PlainView.java` in the installed JDK's `lib/src.zip`. Its `updateDamage` (approximately lines 668–722) calls private `calculateLongestLine` on REMOVE from its cached widest line, or when a structural change removes that Element. `calculateLongestLine` (approximately lines 780–796) measures every root line. INSERT generally only compares affected widths. Undo of insertion arrives as REMOVE through `AbstractDocument.DefaultDocumentEvent.undo` and the normal view notification path.

The measured call path is `PlainView.removeUpdate → updateDamage → calculateLongestLine → getLineWidth → Utilities.getTabbedTextWidth → FontDesignMetrics`. Inserting a character at the start of a non-first line can replace two root Elements even without a newline; undo reverses this structural change. Both local and structural paths matter. Splits independently pay the view cost. Glyph drawing on extreme lines, snapshot/listener work, Markdown prefix highlighting and HTML preview layout are separate costs.

## Selected optimization

`SourcePlainView` is a narrowly scoped PlainView subclass. It inherits drawing, selection, caret geometry, model-to-view, hit testing, vertical preferred span and sizing. It replaces only horizontal preferred-span bookkeeping and document-event damage handling for tab-free ASCII source:

1. On initialization/font change, compute at most 64 widest-line candidates and an upper bound for all untracked widths.
2. Local changes remeasure the affected line. Small structural changes remove the old Element identities and measure added Elements. Candidate lookup/min/max work is bounded by 64.
3. The candidate maximum is exact while it is at least the untracked upper bound. If successive maximum shrinkage exhausts that proof, rebuild the summary once. Large structural replacements also rebuild.
4. Horizontal extent changes issue preference notifications; structural changes retain full repaint/vertical preference notifications. Ordinary local edits damage the affected line.
5. Fonts/metrics invalidate the summary. Wrapping recreates the stock wrapped view. Document reattachment clears candidate references. Each split owns its own metrics and summary.

No source document, snapshot, undo or document-event behavior changes. No reflection/module-opening flags, worker, timer, added document listener or dependency is used. Metadata is bounded independently of document size: two arrays of 64 entries (Element references and integer widths), a reusable Segment, and scalar/font fields. Segment backing-array references are cleared after measurements. Detachment clears all Element references.

One attachment-time partial-Segment scan identifies tab-free ASCII source without making a full-document string. Insertions scan only inserted characters. Existing tabbed/non-ASCII content, insertion of such text, specialized PlainView subclasses and bidi/international views retain stock behavior. Lines over one million characters or widths at/above 2^23 pixels also fall back, conservatively avoiding extreme-coordinate rounding/overflow and giant transient line copies. Fallback invalidates inherited metrics so PlainView reconstructs its private widest-line reference before resuming. It remains stock for that view's lifetime. This tranche does not claim improved complex-text/tab/extreme-line performance.

Initialization/font changes perform an additional width pass after inherited metrics initialization. Exhausting the bounded summary can rescan the document. These are explicit tradeoffs, not claims of universal constant-time layout. Common local insert/delete/undo and small line-boundary changes avoid full scans while the width proof remains valid.

The existing source pane marks an internal client property. WorkbenchStyle's existing TextAreaUI applies the adaptation only to source panes; InlineCompletionDisplay adapts its ordinary source view while preserving ghost/projection views. Theme/UI reload, wrapping and document switches retain existing factories and lifecycle. No global look-and-feel replacement or setting is added.

## Rejected candidate and alternatives

The first candidate retained a single width-growth witness and avoided a local undo rescan. It reduced the 10 MiB undo p95, but changed which tied widest line Swing retained. Subsequent newline edits became slow more frequently (median 3.20 → 41.35 ms in that matrix). It was rejected; its separately labeled raw evidence is retained for traceability. Those results do not establish performance of the selected summary.

A document-sized width array/tree could cover more maximum-shrink cases but would add line-count-dependent memory and broader structural invalidation. Replacing the editor/document store, suppressing layout notifications, changing fonts/wrapping, disabling highlighting or changing undo semantics were not acceptable. Private-field reflection was unnecessary.

## Correctness qualification

Differential tests compare preferred sizes, caret rectangles, hit testing and selected pixels with stock Swing across seeded edits, undo/redo, fonts/sizes, tab sizes, wrapping, resizing, Unicode/bidi content and document switches. Candidate exhaustion, structural line-start edits, bulk replacement and fallback after cached edits receive explicit coverage. A read-count test demonstrates bounded local work against a control that reproduces the stock full scan, without timing assertions.

One bulk-removal case exposes stock PlainView retaining a removed widest Element after promoting an added line. For that case the reference is a freshly constructed stock view, which measures the actual remaining source; the optimized extent agrees with it. This avoids treating a stale stock scroll extent as a correctness requirement.

The real editor integration test checks both shared splits, snapshot revisions/identity, dirty state, event counts, undo/redo, theme/wrap rebinding, actual save/reopen and recovery. Existing 0A/0B/0B.1 and packaged Mermaid tests remain required.

## Environment and measurement limits

The selected matrix ran sequentially on MacBookPro15,2, Intel i5-8279U 2.40 GHz (4 cores / 8 logical CPUs), 8 GiB physical RAM, macOS 15.7.7 x86_64, Temurin 21.0.12.1+1-LTS. Source display scaling was 2×; the default source font was Monospaced 16 pt, with a 1158×564 source viewport in the primary runs. Child JVMs used `-Xms64m -Xmx512m`; JFR/NMT were enabled only in separately labeled attribution runs. Every case records load, pressure, swap, VM statistics and geometry/actual font/tab configuration. These are packaged-JAR runs with an independent probe, not the development classpath.

The host had existing swap use around 3.4 GiB and varying background load (already above 3 in the first selected baseline). No memory exhaustion, cache purge, system setting change or unrelated-process termination was performed. Small latency differences, startup and RSS are exploratory host observations; the large undo difference is additionally supported by EDT CPU, sampled stacks and deterministic read-count tests. Physical input/monitor latency, tight statistical non-inferiority, retained-heap census and other operating systems are not qualified here. No historical gate was weakened.

The preserved pre-optimization artifact SHA-256 is `3037b3a8de905931a38c8df30e03fc61d596090beabb191a42752ce771d39c90` (49,958,828 bytes). The selected artifact is `06661ebc00b3bb001c0ae24050d76afc759a88c036bdd0a203d29ed72badf0e0` (49,963,135 bytes, +4,307 bytes). Exact runner/probe/fixture hashes and commands are in the evidence index. The source classes are frozen during timing and verified against the tested classes afterward.

## Files and architecture impact

| File | Purpose |
| --- | --- |
| `src/main/java/shed/SourcePlainView.java` | Bounded horizontal-width summary; inherited Swing source rendering and stock fallbacks. |
| `src/main/java/shed/EditorUiController.java` | One internal source-pane marker. |
| `src/main/java/shed/WorkbenchStyle.java` | Existing TextAreaUI factory adapts marked ordinary source views. |
| `src/main/java/shed/InlineCompletionDisplay.java` | Adapts its ordinary source view; ghost/projection rendering stays unchanged. |
| `src/test/java/shed/SourcePlainViewTest.java` | Differential geometry/pixels, bounded reads, structural/undo/cache exhaustion and fallback coverage. |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | One appended real-editor split/history/snapshot/save/recovery/theme test. |
| `scripts/benchmark-markdown.py` | Reuses the opt-in runner, adding deterministic tabs/unique-width fixtures and bounded font/tab parameters. |
| `scripts/test_benchmark_markdown.py` | Determinism/unique-width/tab fixture check. |
| `src/benchmark/java/shed/MarkdownPerformanceProbe.java` | Explicit view/tab metadata, line-width edits and shared-split workload; normal UI pipeline retained. |
| `src/benchmark/java/shed/MarkdownJfrSummary.java` | Exact global-width-rescan and SourcePlainView stack groups. |
| `docs/SOURCE_VIEW_PERFORMANCE.md`, `docs/EDITING_PERFORMANCE.md` | Report and discoverability link. |
| `docs/performance/markdown-tranche1b/` | Selected measurements/commands/verification and unchanged archived rejected-candidate evidence. |

All work above belongs to Tranche 1B. The task initially had a clean working tree at `ad5d996e8`; user-created commits `1fa28b6c9` and `f03ed442c` captured the earlier experiment during the investigation. They were preserved; the agent created no commits. The final uncommitted changes replace that experiment with the measured summary and extend its tests/evidence. Earlier 0A/0B/0B.1/1/1A implementations and source ownership/persistence are preserved. No additional startup service, document copy, new dependency or background worker was introduced.

## Paired performance results

The representative 10 MiB unwrapped undo goal is met locally: completed source paint p95 **83.35 → 5.69 ms**, 93.2% lower in this matched workload. Reverse-order repetition gives **54.86 → 3.82 ms**. Historical 43.96 ms is a reference, not the denominator. This is two process pairs on one loaded host, not universal hardware qualification.

### Normal source edits

Values are **p50 / p95 / p99 ms to completed Swing paint**, n=100 per row/artifact after 10 warmups. Positions are pooled (34 beginning, 33 middle, 33 end); raw per-position summaries have no p99. Physical keyboard delivery and display scanout are excluded. Syntax highlighting remains enabled; preview is closed.

| Document | Operation | Before | After |
| --- | --- | ---: | ---: |
| 100 KiB | Insert character | 3.37 / 5.57 / 13.29 | 4.36 / 5.75 / 6.49 |
| 100 KiB | Delete character | 3.08 / 5.63 / 9.72 | 4.15 / 5.31 / 5.75 |
| 100 KiB | Undo character | 3.23 / 5.41 / 15.97 | 4.10 / 5.19 / 6.38 |
| 100 KiB | Redo character | 3.05 / 4.30 / 9.92 | 4.02 / 5.73 / 6.10 |
| 100 KiB | Insert newline | 3.61 / 5.84 / 11.19 | 4.59 / 6.38 / 6.84 |
| 100 KiB | Undo newline | 2.98 / 5.21 / 10.45 | 3.84 / 5.43 / 5.94 |
| 1 MiB | Insert character | 5.30 / 7.65 / 13.46 | 4.63 / 6.16 / 7.13 |
| 1 MiB | Delete character | 11.47 / 14.08 / 14.72 | 3.95 / 5.39 / 6.04 |
| 1 MiB | Undo character | 11.58 / 13.94 / 15.25 | 3.70 / 5.54 / 6.42 |
| 1 MiB | Redo character | 4.38 / 7.15 / 13.10 | 3.97 / 5.31 / 7.50 |
| 1 MiB | Insert newline | 6.26 / 12.89 / 15.39 | 4.07 / 5.93 / 6.86 |
| 1 MiB | Undo newline | 4.64 / 6.79 / 12.97 | 3.74 / 5.33 / 5.65 |
| 10 MiB | Insert character | 7.86 / 11.18 / 11.45 | 8.26 / 11.78 / 12.43 |
| 10 MiB | Delete character | 60.73 / 82.14 / 85.23 | 4.08 / 5.90 / 6.37 |
| 10 MiB | Undo character | 60.39 / 83.35 / 84.96 | 3.95 / 5.69 / 5.97 |
| 10 MiB | Redo character | 3.60 / 4.93 / 5.28 | 3.91 / 5.68 / 6.34 |
| 10 MiB | Insert newline | 5.43 / 80.21 / 82.80 | 4.55 / 6.15 / 7.30 |
| 10 MiB | Undo newline | 3.92 / 5.31 / 6.12 | 4.01 / 5.57 / 8.33 |

The reverse-order 100 KiB typing control is 5.17 → 3.94 ms p95. Small absolute movements should be read alongside the unchanged wrapped/tab controls and host load. Two process pairs do not establish a tight statistical non-inferiority bound; the large undo improvement is much larger than this noise. The existing 16 ms gate is unchanged.

### EDT action versus final paint

| 10 MiB unwrapped undo, n=100 | Before p95 ms | After p95 ms |
| --- | ---: | ---: |
| Synchronous EDT operation | 82.20 | 4.39 |
| EDT thread CPU | 79.58 | 4.18 |
| Completed source paint from action origin | 83.35 | 5.69 |
| Final source-component paint duration | 0.36 | 0.48 |

EDT operation includes document mutation, snapshot/listener work and synchronous view invalidation. These measurements do not separately time snapshot reconciliation. Independent percentiles cannot be subtracted to manufacture isolated layout/storage costs. Later syntax convergence is separate.

### Wrapping and line-width sensitivity

| Wrapped source, n=100 | Insert p95 before → after | Undo p95 before → after | Newline p95 before → after |
| --- | ---: | ---: | ---: |
| 100 KiB | 5.53 → 5.18 | 5.35 → 4.98 | 5.49 → 5.94 |
| 1 MiB | 6.87 → 7.54 | 6.09 → 6.63 | 8.87 → 8.59 |
| 10 MiB | 14.37 → 14.38 | 11.69 → 9.24 | 33.08 → 41.42 |

Wrapped views are unchanged; timing movements here are controls, not optimization gains.

| Unwrapped source | Delete widest p95, n=10 | Undo deletion p95, n=10 | Modify widest / undo p95, n=50 each | Modify shorter / undo p95, n=50 each | Shared-split undo p95, n=20 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 100 KiB | 4.62 → 4.92 | 4.08 → 4.77 | 4.56/4.63 → 5.35/4.72 | 3.35/3.18 → 4.93/5.28 | 15.02 → 17.44 |
| 1 MiB | 5.13 → 3.41 | 4.02 → 3.89 | 4.21/13.61 → 4.15/3.45 | 3.38/3.03 → 5.10/4.43 | 29.93 → 12.81 |
| 10 MiB | 4.80 → 5.41 | 4.41 → 14.71 | 4.93/92.66 → 3.85/3.66 | 4.04/3.69 → 3.75/3.31 | 169.38 → 12.91 |

Widest is selected by logical line length; tied lines need not be Swing’s cached widest Element. Modifications occur at a line boundary and alternate with a shorter line; the shared-split workload edits that same boundary. Longest-line n=10 p95 is the sample maximum and especially noisy; those rows do not justify p99 or a general regression claim.

| Additional 1 MiB scenario | Insert p95 before → after | Undo p95 before → after | Completion |
| --- | ---: | ---: | --- |
| Unique 32 KiB widest first line | 10.90 → 10.20 | 14.36 → 8.51 | OK / OK |
| Tabs, actual size 2 (stock path) | 5.29 → 6.00 | 3.50 → 3.63 | OK / OK |
| Tabs, actual size 8 (stock path) | 8.24 → 5.82 | 6.23 → 4.39 | OK / OK |
| Monospaced 12 pt | 5.87 → 4.91 | 12.34 → 4.01 | OK / OK |
| Dialog 20 pt, ordinary prose | 5.67 → 5.49 | 12.63 → 4.49 | OK / OK |
| 256 KiB lines; n=30 / 3 warmups | — (incomplete) | — (incomplete) | TIMEOUT / OK |

The earlier unique-32-KiB-line + Dialog 20 pt experiment timed out at 90 s on both baseline/witness builds. It is archived as TIMEOUT, not a successful selected-summary run. The final font control uses ordinary prose. Very long-line glyph drawing, hit testing and line measurement remain expensive; this tranche does not replace those methods.

### Caret, selection, scrolling and split switching

Each cell is **action p95 / separate forced-visible-paint p95**, before → after, ms. n=100 for caret/selection/scroll, n=20 for split switching. This is not native trackpad frame latency or a combined percentile.

| Document | Distant caret | Selection | Scroll | Split switch |
| --- | ---: | ---: | ---: | ---: |
| 100 KiB | 3.46/1.61 → 12.07/4.48 | 2.40/1.39 → 10.46/4.07 | 2.45/1.53 → 15.51/6.28 | 3.16/1.71 → 4.42/2.49 |
| 1 MiB | 7.69/5.50 → 10.83/4.64 | 7.91/2.29 → 5.60/4.40 | 4.66/5.57 → 6.89/4.68 | 3.59/2.63 → 4.32/2.00 |
| 10 MiB | 10.67/6.22 → 6.47/2.86 | 4.99/6.39 → 3.42/3.93 | 10.45/5.86 → 5.37/3.27 | 4.26/2.63 → 3.52/1.78 |

The primary 100 KiB caret/selection/scroll sample was substantially slower after the change. The reverse-order control below does not reproduce that slowdown. This supports host/process variation, rather than proving a tight cross-host non-regression bound.

| Reverse-order control | Distant caret | Selection | Scroll | Split switch |
| --- | ---: | ---: | ---: | ---: |
| 100 KiB | 4.03/1.74 → 3.01/1.64 | 2.88/2.20 → 2.51/1.44 | 3.22/1.98 → 2.79/1.86 | 3.29/1.83 → 3.33/1.67 |
| 10 MiB | 3.23/1.32 → 3.74/1.72 | 1.99/1.37 → 2.26/1.46 | 2.50/1.45 → 3.14/1.55 | 2.84/1.52 → 3.69/1.98 |

### Startup and memory

Three fresh JVMs per artifact, default fonts, 1 MiB plain Markdown. OS caches were not purged. Startup is median (min–max), seconds from launch; input verification includes typing, normal paint, undo and fixture reset.

| Milestone | Before | After |
| --- | ---: | ---: |
| Visible | 2.304 (1.798–2.548) | 1.974 (1.827–2.088) |
| Source installed and painted | 2.963 (2.305–3.195) | 2.519 (2.384–2.752) |
| Input verified | 3.135 (2.417–3.333) | 2.616 (2.484–2.853) |
| Full workbench | 10.337 (8.077–11.740) | 9.285 (8.756–10.471) |

Initialization includes the additional eligibility/width-summary pass. Three launches cannot prove zero startup overhead or quiet-host qualification.

| Source | Heap used / committed after edits, MiB, before → after | Sampled peak RSS MiB before → after |
| --- | ---: | ---: |
| 100 KiB | 48.9/64 → 45.3/64 | 544.5 → 578.2 |
| 1 MiB | 39.1/84 → 82.7/102 | 581.0 → 617.5 |
| 10 MiB | 221.4/390 → 308.3/426 | 903.6 → 958.5 |

Heap is sampled without forced GC; RSS about every 250 ms is not a kernel high-water mark. These samples cannot attribute the differences; pending garbage, font/native resources and asynchronous jobs may contribute. The reverse-order 10 MiB RSS peak is 925.1 → 880.6 MiB, reversing the primary pair, while heap occupancy is 199.4 → 295.3 MiB without forced GC. This is not retained-heap/leak qualification or evidence of reduced whole-process memory. The implementation’s own arrays are capped at 64 entries each per view (well below 2 KiB of direct metadata with ordinary object layouts), with no document-sized copy.

10 MiB insert: median timed EDT allocation 132,352 → 133,096 bytes (traffic, not retained memory).
10 MiB undo: median timed EDT allocation 131,944 → 129,408 bytes (traffic, not retained memory).

### Profiler attribution

| JFR stack presence | Before samples | After samples |
| --- | ---: | ---: |
| EDT javax.swing.text.PlainView.calculateLongestLine | 608 | 26 |
| EDT SourcePlainView | 0 | 38 |
| EDT undo+global-width-scan | 425 | 0 |
| EDT source-width-summary-rebuild | 0 | 30 |
| EDT UndoManager.undo | 442 | 16 |
| EDT DefaultDocumentEvent.undo | 441 | 16 |
| EDT VersionedTextSnapshot | 13 | 15 |
| EDT GrammarHighlightService | 0 | 0 |

Total execution/native samples: 1189 → 771. These are overlapping sampled stacks across the entire profiled lifecycle, not CPU percentages, per-operation counts or retained bytes. Initialization/font changes still legitimately enter stock width calculation. Read-count regression tests independently prove that common undo and following line-boundary/newline operations do not read the whole document.

## Verification and compliance

The selected source passed the focused packaging run (five SourcePlainView tests plus the appended editor integration test: six passed), then the complete suite with the additional extreme-geometry test: **1,316 run; 1,311 passed, five skipped, zero failures/errors**. The six final SourcePlainView tests all pass; TexteditorSwingIntegrationTest is 105 passed / one skipped. Its existing Plain/Vim/Emacs, Markdown 0A/0B, preview synchronization, split, LSP, search, read-only and lifecycle coverage is retained. FileBuffer, codec, bounded undo, data-loss, large-file store/projection and recovery suites also pass. `regression-suites.json` lists every suite and skip reason. The five existing skips concern DOS attributes, Fish, CMake tooling and two opt-in AI profiles; no test was disabled or weakened.

Commands actually used (cached Java 21/Maven, graphical desktop):

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m \
-Dtest=SourcePlainViewTest,TexteditorSwingIntegrationTest#sourceWidthFastPathKeepsSplitHistorySnapshotsPersistenceAndThemeBinding \
-Dmaven.jar.forceCreation=true package

JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=false -DargLine=-Xmx512m test

python3 scripts/test-packaged-markdown.py --jar /path/to/selected-summary.jar \
  --output /tmp/new-packaged-result.json
python3 -m unittest discover -s scripts -p test_benchmark_markdown.py
```

The packaged smoke check is **PASS**, using only its independent probe plus the measured shaded JAR. It verifies JavaScript/regex service providers, plain Markdown, math, local images, valid/repeated/changed Mermaid and graceful invalid input. The five Python tooling tests pass. Exact absolute commands, artifact identities and smoke output are in `executions.json` and `verification.json`. Heavy benchmarks remain opt-in, outside normal Maven tests.

Development investigation included comparator failures caused by sharing a document-level tab-size property between two reference components, and stock Swing's stale bulk-removal width reference. The tests were corrected to use independent documents for tab setters and a fresh stock extent for the bulk-removal reference; no existing test assertion was suppressed. The witness candidate was rejected on measured newline behavior despite passing correctness checks. Its archive is excluded from final qualification.

There are 36 selected-matrix/repetition cases: 35 completed and one baseline extreme-line timeout, with 32,415 raw samples (including explicit partial timeout samples). All completed UI editing cases verify exact source hashes. Source classes remained byte-identical throughout the paired runs; the measured SourcePlainView class matches the final tested class. Startup services, parsing, highlighting, document/snapshot ownership, revisions, undo/redo, save/recovery and input routing were not changed. The original Tranche 1/1A evidence and unrelated initially tracked files remain intact; `verification.json` lists the complete authorized initial-file delta. Archived witness files are byte-for-byte preserved.

Shed's visual design, layout, typography, themes, drawing, components, navigation, selection/caret and interaction patterns are preserved. Internal horizontal extent/damage bookkeeping is the only presentation change; source-pixel/geometry comparisons and real split/theme/wrap tests verify the relevant contracts. No dependency, user setting, UI control, persistent worker or ordinary startup profiler was introduced. Temporary benchmark windows/processes are closed; the explicitly retained external run artifacts are evidence, not running resources. `git diff --check` passes.

`README.md` before and after SHA-256 is **`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`**. It was not modified. No agent commits or pushes were created; observed user-created commits and previous tranche work were preserved.

## Remaining costs and verdict

The selected implementation meets the local representative **≤16 ms 10 MiB unwrapped undo p95** objective, with no reproducible meaningful small-document typing regression in these paired controls. Normalized undo p95 is 6.8% and 7.0% of its paired baseline in the two process pairs. Those two observations are a descriptive range, **not a 95% confidence interval**; there are too few independent processes for a trustworthy cross-process confidence bound. Small movements in scrolling/painting and startup still require quieter-host qualification. Primary RSS was higher after the change and reversed in the repeat; retained-memory equivalence is not established by unforced heap occupancy. The implementation's own metadata bound is independent of document size.

Remaining work is explicitly outside this patch:

- Wrapped 10 MiB newline layout still exceeds 16 ms (33.08 → 41.42 ms in the unchanged-path control). International/tabbed source retains stock invalidation. Extending the summary requires equally strict geometry/complex-text qualification, not loosening fallbacks.
- Extreme-line rendering remains slow. The baseline 256 KiB-line case timed out at 90 s; its EDT stack is in `SwingUtilities2.drawChars → Utilities.drawTabbedText → PlainView.drawUnselectedText/paint`. The candidate completes in about 84 s, which is still unacceptable and not a paired qualified speedup. Future viewport-aware drawing would require a bounded prototype preserving shaping/hit-testing; it is not implemented here.
- Initial/font-metric setup performs an extra width pass; exhausting 64 widest candidates or making a large structural replacement can still rescan all lines. Neither is hidden behind an ordinary latency claim. Widest-line deletion/undo has only ten samples; its occasional spikes remain visible.
- Gap movement, snapshot/listener allocation, background Markdown prefix highlighting, fixed native/font footprint and preview's EDT-bound HTML/rich-media pipeline remain separate costs. This source-view change does not optimize them or establish editable 50–100 MiB support.

Tranche 1B is a correct, measured, bounded source-view optimization for its supported path. **Tranche 1C can proceed** with these regression tests and paired artifacts as the source-view baseline; quieter-host performance/memory qualification remains advisable. No 1C preview concurrency, semantic-model, backlink or live-presentation work was begun.
