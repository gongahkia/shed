# Markdown highlighting — Tranche 1D

## A. Root-cause confirmation

The original production path copied `VersionedTextSnapshot.substring(0, scanEnd)` into a prefix string, constructed a new `GrammarHighlightService` for every job, then called its viewport API. Markdown took that API's full-snapshot fallback, creating tokens and regex matchers for all preceding lines. `SyntaxUiController` converted those tokens into retained foreground spans. Both scanning and installed span counts consequently grew with viewport depth.

The preserved baseline reproduces the problem. At the end of the 10 MiB deterministic mixed fixture it installs **104,690 spans**, including **104,609 outside the context**. The final pipeline installs the same **81 relevant spans**, with zero outside the context. This is a scan/allocation change, not merely filtering tokens after a full-prefix tokenization.

Separate JFR runs support the call path: `SyntaxUiController.highlightSnapshot → GrammarHighlightService.highlightViewport → highlightSnapshot → tokenizeLine → markdown → String.matches/Pattern.matcher`. The old recording contains 233 worker stack samples involving `GrammarHighlightService`, versus four in the checkpoint candidate recording. Counts overlap, recording durations differ, and they are not CPU percentages. Raw profiles and summaries are indexed in [the evidence directory](performance/markdown-tranche1d/INDEX.md).

The historical Tranche 1 evidence remains unchanged. Fresh paired baseline/candidate runs were collected on 14 November; final-artifact GUI and editing controls were repeated on 16 November after the interrupted session resumed. These groups are distinguished, not pooled.

## B. Architecture and implementation

The authoritative Swing document, `FileBuffer`, immutable snapshot tree, undo, persistence, recovery and LSP ownership are unchanged. Current implementation locations: `SyntaxUiController.java:547` (capture/coalescing), `:593` (change invalidation), `:642` (installation checks); `GrammarHighlightService.java:138` (metadata ownership), `:202` (worker scan), `:274` (chunk reads); `PaneBufferController.java:36` (existing change records) and `EditorUiController.java:1957` (paint validation).

Markdown workers now consume the existing immutable snapshot through sequential **16 KiB reads**. They do not flatten its prefix. They begin at a valid sparse checkpoint, advance lexical state without producing prefix tokens, and emit tokens only for complete lines in the existing viewport/context range. Markdown retains the existing 120-line viewport context; other languages keep their existing range policy. The old string-based Markdown viewport API also emits only its line context, but remains stateless because it has no document/revision identity.

Checkpoints store a line index, absolute UTF-16 offset and the existing immutable lexer state: fence marker, embedded language and embedded comment/string mode. The same lexer still supplies the compatibility oracle. No Markdown parser dependency, semantic AST, alternate source store or rendering engine was introduced.

On an incremental source change, the cache preserves checkpoints through the changed line's start. Later checkpoints become shifted, unverified suffix candidates. After scanning reaches an unchanged line boundary with the same lexical state, the rest of that unchanged suffix is valid and can be reused without rescanning it. This is justified by the trusted immutable replacement range and deterministic lexer state, not by a hash heuristic. An opening/closing fence edit can prevent convergence and correctly require a larger state scan.

Multiple edits before analysis conservatively discard earlier unverified candidates. Unknown/reloaded snapshot identities reset the cache. Undo/redo follows the same source-change path. Cancellation checks occur between lines and chunk reads; a single legacy line-tokenization call remains indivisible.

## C. Files changed

| File | Responsibility |
| --- | --- |
| `src/main/java/shed/GrammarHighlightService.java` | Snapshot streaming, sparse Markdown checkpoints, suffix convergence, scan statistics and context-only token emission. |
| `src/main/java/shed/SyntaxUiController.java` | Per-buffer ownership, bounded request coalescing, reuse of the existing grammar service, revision/document/generation validation and weak installed-result identity. |
| `src/main/java/shed/PaneBufferController.java` | Forward existing incremental change records and release cache entries on the three normal buffer-removal paths. |
| `src/main/java/shed/EditorUiController.java` | Do not paint Markdown spans belonging to an obsolete source revision/document. Existing rendering remains in place. |
| `src/test/java/shed/MarkdownHighlightTest.java` | Deterministic differential, randomized edit, scan-bound, metadata-bound and concurrent invalidation coverage. |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | Worker saturation/coalescing, buffer switching, split/undo/reopen/disposal and exact viewport pixels/colours against full scans. |
| `src/benchmark/java/shed/MarkdownLexicalProbe.java` | Opt-in allocation/scan/edit replay using existing fixtures and both old/new packaged implementations. |
| `src/benchmark/java/shed/MarkdownPerformanceProbe.java` | Honour syntax warmups/sample counts, measure actual context/outside spans, and dispatch the lexical replay. |
| `scripts/benchmark-markdown.py` | Register and bound the lexical mode; use a headless JVM for it. Reuse the existing runner and resource safeguards. |
| `scripts/test_benchmark_markdown.py` | Verify syntax warmups are excluded from percentile summaries. |
| This report and `docs/performance/markdown-tranche1d/` | Commands, environment, raw samples, profiles, failures, summaries and repository verification. |

Some working changes were committed externally while the session ran. File preservation is checked against the original task-start hashes, independently of HEAD. No commit, push, reset, clean or revert command was issued by this session.

## D. Before/after performance

All GUI convergence measurements include the unchanged **120 ms debounce**, scheduling, worker work, result installation and the existing 10 ms state-polling resolution. They are not isolated lexer timings or physical keyboard-to-paint measurements. Each final GUI position has 100 measured requests after five warmups. Source-edit controls have 100 samples pooled across beginning/middle/end after ten warmups; per-position groups have 33/34 samples and no meaningful p99.

Final-artifact pair (16 November): the 10 MiB end target is met in this measured workload: **574.24 → 136.67 ms p95**. These are fresh processes with warm/uncontrolled OS caches, not cold-cache qualification. The 14 November paired candidate had already measured **376.65 → 136.57 ms p95** at the same position. Candidate and final lexical classes are byte-identical; the final artifact additionally validates installed Markdown spans before painting.

The actual machine is an Intel MacBookPro15,2 (i5-8279U, eight logical CPUs, 8 GiB RAM), macOS 15.7.7, Temurin 21.0.12.1+1-LTS, 2× display scaling, Monospaced 16 pt in isolated benchmark homes, 64–512 MiB heaps. The final runs experienced one-minute load averages as high as approximately 35 and several GiB of existing swap. This is exploratory same-host evidence under substantial variable load, not a quiet-machine product guarantee. Large p99 outliers remain in the table; fewer worker allocations do not eliminate operating-system/application scheduling delays.

Before artifact SHA-256: `8a07b7575367f5ff607e48049addfda7dc9e573dc364ca22946a269fff74cd7f`. Final: `9ebcf97563d9a8e61571a48c8548f26f87c06c6e8a68f2d25d5098c4d4c187d2`.

Final convergence, ms; p50 / p95 / p99; 100 samples per position.

| Size | Position | Before | After | After worker p95 | After spans |
| --- | --- | ---: | ---: | ---: | ---: |
| 102,400 bytes | beginning | 135.60 / 166.87 / 202.80 | 131.68 / 163.95 / 229.01 | 2.98 | 84 |
| 102,400 bytes | middle | 134.67 / 141.02 / 144.63 | 133.69 / 162.92 / 177.55 | 4.22 | 151 |
| 102,400 bytes | end | 134.89 / 142.38 / 872.46 | 133.08 / 185.12 / 248.04 | 2.01 | 81 |
| 1,048,576 bytes | beginning | 131.45 / 138.34 / 145.01 | 130.46 / 141.70 / 201.19 | 1.90 | 84 |
| 1,048,576 bytes | middle | 145.55 / 174.29 / 467.57 | 132.34 / 142.53 / 147.90 | 2.50 | 152 |
| 1,048,576 bytes | end | 158.14 / 216.62 / 286.10 | 132.10 / 147.18 / 161.48 | 2.57 | 81 |
| 10,485,760 bytes | beginning | 131.18 / 162.21 / 732.57 | 130.44 / 137.82 / 139.15 | 2.11 | 84 |
| 10,485,760 bytes | middle | 251.47 / 390.47 / 666.20 | 131.42 / 146.21 / 770.27 | 2.38 | 152 |
| 10,485,760 bytes | end | 342.28 / 574.24 / 881.02 | 130.36 / 136.67 / 137.99 | 1.30 | 81 |

Final source controls: p95 ms, 100 samples pooled over document positions.

| Size | Insert before → after | Undo before → after | Scroll paint before → after |
| --- | ---: | ---: | ---: |
| 102400 | 4.23 → 5.55 | 3.63 → 4.87 | 1.90 → 5.52 |
| 1048576 | 5.44 → 29.68 | 4.05 → 14.81 | 1.50 → 3.48 |
| 10485760 | 14.07 → 12.83 | 5.14 → 8.85 | 3.89 → 1.67 |

The initial final-artifact 1 MiB editing pair contains a material outlier: insertion p95 rose to **29.68 ms**. It is retained, not discarded or described as regression-free. Two bounded reverse-order controls at lower observed load did not reproduce it: before/after insertion p95 **7.16 / 6.57 ms**, undo **6.54 / 5.32 ms**, newline **7.00 / 5.78 ms**. The cause of the original spikes is not established. In those controls scroll-paint p95 was **2.57 / 5.51 ms**; small editing/scroll differences are not called zero-cost or statistically equivalent.

A separately labelled headless replay of the synchronous cache invalidation API has 130 observations: **0.085 ms median / 0.315 ms p95 / 6.212 ms maximum**, CPU p95 **0.294 ms**. This includes reflection/diagnostic overhead and runs on the replay caller, not a live EDT. It demonstrates bounded metadata work, not an end-to-end input guarantee. The actual source controls separately include EDT mutation/listener time, queue delay, completed Swing paint, caret/selection/scroll and shared-split operations. They restore exact fixture source hashes. Their typing loops do not guarantee sustained overlap with highlighting; the 120 ms debounce can postpone analysis during rapid input.

Three fresh-process 100 KiB startup observations per artifact show median launch-to-verified-input **3.320 → 2.239 seconds**, and launch-to-full-workbench paint **8.210 → 8.447 seconds**. n=3, ordering/font/OS cache effects and high load prevent an optimization or startup-equivalence claim. No new eager initialization is present by inspection. Full per-launch window/source-paint/input/ready marks and heap/RSS/GC observations remain in the raw data.


The real GUI request includes the existing viewport geometry. The lexical replay uses a separately labelled ±60-line context. Its isolated calling-thread allocation numbers include prefix materialization/service construction in the old path, and snapshot/state/cache operations in the new path. They exclude colour-span installation, painting and unrelated concurrent IDE work. Do not substitute those numbers for a full concurrent worker allocation census.

Convergence ends at validated result application, not at a compositor-visible highlight paint. Source-input-to-Swing-paint is measured separately. Per-operation EDT wall/CPU/allocation and paint observations are retained; a global maximum EDT-dispatch census and isolated timing of every syntax installation callback were not collected.

Representative paired replay medians at 10 MiB:

| Request | Before → after scanned characters | Before → after allocated bytes | Before → after emitted tokens |
| --- | ---: | ---: | ---: |
| Warm end viewport | 10,485,760 → 2,434 | 468,701,104 → 166,576 | 104,690 → 32 |
| Visible insertion | 10,485,761 → 2,435 | 468,701,120 → 167,008 | 104,690 → 32 |
| Insertion before viewport | 10,485,761 → 23,311 | 468,702,744 → 326,648 | 104,689 → 32 |
| Rapid edits before analysis | 10,485,769 → 10,485,769 | 468,702,768 → 58,428,144 | 104,689 → 32 |
| Unmatched fence edit | 10,485,776 → 10,485,776 | 163,050,320 → 61,493,112 | 55,112 → 18 |

The last two rows are intentional conservative rescans, not constant-work claims. The fence-change replay also remains expensive: one paired group measured a 173 ms median against 78 ms for the old lexer, despite lower allocation. This semantic-change path is not claimed as a latency win. Ordinary edits and unchanged viewport requests show the principal benefit.

Adversarial 1 MiB replays cover long lines, deeply nested lists, large tables, large fenced blocks, headings and dense markers. Reference checks pass. A document with only a few very long lines can put the whole file inside the existing line-count context; its scan remains large. The implementation does not truncate lines or omit colourable source to manufacture constant byte counts.

`scannedCharacters` counts logically processed lines, including their newline where present; `readCharacters` separately records actual snapshot materialization and up to one chunk of lookahead. Verified suffix jumps count neither skipped source nor nonexistent tokens as scanned work. Early cold requests, warmups and later cache hits remain distinct in raw samples.

## E. Correctness evidence

The full `highlightSnapshot` lexer is the reference for equivalent supported syntax. Incremental output is compared exactly for offsets, scope, order and complete line context. GUI checks compare exact colour values as well. The existing lexer is not promoted to a CommonMark conformance oracle.

The new nine-test unit suite includes 500 deterministic randomized edit/view cycles, extra multi-edit bursts, undo/redo snapshot changes, Unicode/surrogate/combining text, empty/EOF/CRLF cases, long lines and chunk boundaries, escaped/unusual delimiters, both fence characters, differing fence lengths, embedded multiline comments/strings and nonlocal fence invalidation. Structural assertions require warm deep requests and ordinary early edits to scan bounded regions; they do not depend on machine timing.

Concurrent invalidation is gated with latches: an edit can update the cache while a worker is paused outside its short metadata lock, and that worker cannot publish obsolete checkpoints. Cancelled scans publish no partial state. Unknown revisions reset correctly. A 100,000-line test plus 1,000 viewport requests verifies the metadata cap.

Real Swing tests hold a worker executor occupied while submitting many superseding requests and switching among more than eight Markdown buffers. Only one syntax job is queued/running, cache ownership stays bounded, and the latest buffer obtains exact current output after the gate releases. Shared splits reuse one source cache; edits, undo/redo, closing and reopening a file retain correct source and colour state. Exact viewport pixels match the full-scan presentation. Shutdown clears owned cache/result references.

The initial split test observed an intermediate viewport before its pending debounce completed. Its wait now requires no pending syntax work/timer and exact current viewport spans. No assertion tolerance was loosened. The initial failure and corrected runs are retained.

## F. Worker and lifecycle safety

Lexical scanning remains on the existing `AsyncJobService` workers; all live Swing access and span installation remain on EDT. The grammar's keyword tables are read-only after construction; mutable Markdown metadata is explicitly passed and independently owned.

One syntax task may be queued/running per controller, with one replaceable newest pending request. A volatile generation invalidates superseded work cooperatively. The completion callback identifies its own job before clearing ownership; an obsolete completion cannot clear a newer job ID. Even errors/null results drain the newest pending request. This coalescing applies to the existing shared syntax lane, without modifying the worker service or adding threads.

Installation requires the current generation, `FileBuffer`, snapshot identity, Swing document identity and document revision. Markdown painting additionally validates the installed snapshot/document/revision, so prior-revision ranges are not drawn as current while replacement work is pending. During that interval the existing base foreground may be visible. Current-source colours, palette, metrics and rendering are unchanged.

Caches use weak buffer keys and weak snapshot identity. Values contain no pane, buffer, full source or token-list reference. Normal closure explicitly forgets entries; unavailable keys are pruned on later Markdown requests, and shutdown clears the pool. A queued/running request and the newest pending request can temporarily hold their existing immutable snapshots until the job drains. This is bounded ownership, not a claim that an entirely blocked shared pool can release every in-flight input immediately.

No lock is held across lexical scanning or an EDT callback. Locks protect bounded metadata copies/publication/invalidation only. Invalidation is bounded by checkpoint count and one snapshot-position lookup, not total source length.

## G. Resource and performance trade-offs

Each buffer has at most **512 verified checkpoints plus 512 unverified suffix candidates**. A controller owns at most **eight weakly keyed caches**, with eviction when full. Eviction is intentionally simple rather than an additional LRU subsystem. Worker plans copy only this bounded metadata. Checkpoint count is adaptive/sparse, not one record per line or character.

Persistent metadata therefore has a fixed record bound (8,192 across the pool); typical steady 10 MiB runs retain approximately 258 verified points. JVM object layout determines exact bytes: the expected order is around a MiB at the full pool bound, not a measured retained-heap census. Raw checkpoint counts and requested-GC heap observations are recorded; neither total allocation nor RSS is called retained cache memory.

Transient memory includes snapshot chunks, one complete logical line for the legacy line lexer, visible tokens and the already existing requests/results. Very long individual lines still need proportional transient memory. Cache misses, eviction, unknown revisions, successive unanalysed edits and semantic changes can cause broader state scans. They do not require generating tokens for the preceding prefix.

No parser, eager full-document indexing, new worker, persistent profiler, telemetry service or end-user setting was added. Existing startup grammar initialization is reused. Startup observations and their sample limits are reported with the results; no cold-cache or universal startup improvement is claimed.

The packaged JAR grows by **11,323 bytes**, adding five internal lexical-metadata/helper classes. Changed application classes belong only to the four production files listed above (including their generated nested classes/debug line tables). Other entries, dependencies, service descriptors, `FileBuffer`, snapshot classes, `SourcePlainView` and Markdown preview/rendering classes are byte-preserved. Benchmark classes are not shipped.

## H. Regression verification

Exact commands, test totals, skip reasons, failures and packaged smoke results are recorded in the evidence index and `verification.json`. Heavy benchmarks remain explicitly opt-in.

Routine lexer checks can run headlessly:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B \
-Djava.awt.headless=true -DargLine=-Xmx512m \
-Dtest=MarkdownHighlightTest,GrammarHighlightServiceTest test
```

Real viewport geometry, painting, focus, scroll and split integration require a graphical/virtual display. Running the whole suite headlessly skips that coverage and is not an equivalent final GUI qualification. The packaged Mermaid/math/image smoke is already headless. Headful runs are reserved for those GUI/performance checks.

- Full suite: **1,335 cases; 1,330 passed, five existing skips, zero failures/errors**.
- Final focused headful run: **18 passed** (nine new lexer tests, seven existing grammar tests, two new integration tests).
- Explicit headless lexer/grammar run: **16 passed**, zero skips/failures/errors.
- Python benchmark tooling: **10 passed**.
- Actual shaded-distribution Markdown/Mermaid/math/local-image smoke: **PASS**, with a headless child and no development classpath fallback.
- Every bounded benchmark case completed; source fixture hashes and differential assertions pass. The earlier new-test intermediate-viewport failure is archived.
- Previous folding, guarded Markdown edits, preview synchronization, reader memory correction, source view, persistence/recovery, undo and LSP/IDE tests remain in the complete suite. No existing assertion was weakened or test disabled.


## I. UI/UX compliance

The same editor, view implementation, palette, themes, fonts, typography, layout, menus, navigation and Plain/Vim/Emacs routes remain in place. No component, control, panel, dialog, framework or setting was added. Snapshot/source ownership and IDE integrations are preserved. Stale Markdown colour ranges are withheld until a current result is validated; no visible syntax feature is removed from settled current output.

## J. README and repository compliance

README before/after SHA-256 is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. It was not edited, regenerated, renamed or replaced. Original task-start files and previous tranche evidence are checked by hash; only the listed 1D source/tool/test files are intentionally changed. No dependencies, document engine, save/recovery architecture or LSP protocol changes were introduced. No commits or pushes were created by this session.

## K. Remaining limitations

This is a lexer optimization, not a new semantic model. Existing Markdown limitations remain: three-character fence recognition instead of full CommonMark fence-length rules; simple indentation/blockquote/list interpretation; a simple inline-backtick matcher; no new dedicated colouring for Setext headings, emphasis, strikethrough, tables, links, images, reference definitions or HTML blocks. Where those constructs previously rendered as ordinary source, they still do. Mermaid/math remain ordinary fenced-language text here; rich rendering is unchanged.

Cold state recovery and genuinely nonlocal edits are not constant-time. Multiple unanalysed edits intentionally lose unverified suffix reuse. The line-based context and an indivisible line lexer remain constraints for extreme lines. Shared-worker contention and host scheduling still affect end-to-end convergence even when the lexical work is small.

The broader Tranche 1C HTML-layout, large-table, resize and rich-asset lifecycle limitations remain deferred. This optimization does not complete preview responsiveness or implement inline rendering, backlinks or wiki-links.

## L. Final verdict

**GO for the implemented Tranche 1D optimization.** Differential correctness, bounded ownership/state, full regressions and the measured deep-file convergence target are satisfied. The implementation removes repeated prefix tokenization and materially reduces allocations. It remains compatible with the established document and presentation architecture.

Do not interpret this as universal latency qualification. The high-load 1 MiB source-edit outlier did not reproduce in the bounded reverse-order check; its cause remains unknown. A quiet-machine follow-up should verify small editing/scrolling equivalence and tail latency before setting a hard cross-machine performance gate. All negative/noisy observations are preserved.

The repository is ready to begin **Tranche 2** with these limitations recorded. The deferred 1C presentation work is independent. This report authorizes no automatic next-tranche work.

Tranche 2 should build on the existing source/snapshot/revision contracts, and may eventually replace this lexical state with shared semantic state. The cache is intentionally narrow and does not create a competing Markdown AST or document authority. No Tranche 2 implementation was begun.
