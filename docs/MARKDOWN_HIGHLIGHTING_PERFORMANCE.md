# Markdown highlighting — Tranche 1D

## A. Root-cause confirmation

The original production path copied `VersionedTextSnapshot.substring(0, scanEnd)` into a prefix string, constructed a new `GrammarHighlightService` for every job, then called its viewport API. Markdown took that API's full-snapshot fallback, creating tokens and regex matchers for all preceding lines. `SyntaxUiController` converted those tokens into retained foreground spans. Both scanning and installed span counts consequently grew with viewport depth.

The preserved baseline reproduces the problem. At the end of the 10 MiB deterministic mixed fixture it installs **104,690 spans**, including **104,609 outside the context**. The final pipeline installs the same **81 relevant spans**, with zero outside the context. This is a scan/allocation change, not merely filtering tokens after a full-prefix tokenization.

Separate JFR runs support the call path: `SyntaxUiController.highlightSnapshot → GrammarHighlightService.highlightViewport → highlightSnapshot → tokenizeLine → markdown → String.matches/Pattern.matcher`. The old recording contains 233 worker stack samples involving `GrammarHighlightService`, versus four in the checkpoint candidate recording. Counts overlap, recording durations differ, and they are not CPU percentages. Raw profiles and summaries are indexed in [the evidence directory](performance/markdown-tranche1d/INDEX.md).

The historical Tranche 1 evidence remains unchanged. Fresh paired baseline/candidate runs were collected on 14 November; final-artifact GUI and editing controls were repeated on 16 November after the interrupted session resumed. These groups are distinguished, not pooled.

## B. Architecture and implementation

The authoritative Swing document, `FileBuffer`, immutable snapshot tree, undo, persistence, recovery and LSP ownership are unchanged.

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

<!-- FINAL_MEASUREMENTS -->

The real GUI request includes the existing viewport geometry. The lexical replay uses a separately labelled ±60-line context. Its isolated calling-thread allocation numbers include prefix materialization/service construction in the old path, and snapshot/state/cache operations in the new path. They exclude colour-span installation, painting and unrelated concurrent IDE work. Do not substitute those numbers for a full concurrent worker allocation census.

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

<!-- FINAL_VERIFICATION -->

## I. UI/UX compliance

The same editor, view implementation, palette, themes, fonts, typography, layout, menus, navigation and Plain/Vim/Emacs routes remain in place. No component, control, panel, dialog, framework or setting was added. Snapshot/source ownership and IDE integrations are preserved. Stale Markdown colour ranges are withheld until a current result is validated; no visible syntax feature is removed from settled current output.

## J. README and repository compliance

README before/after SHA-256 is `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. It was not edited, regenerated, renamed or replaced. Original task-start files and previous tranche evidence are checked by hash; only the listed 1D source/tool/test files are intentionally changed. No dependencies, document engine, save/recovery architecture or LSP protocol changes were introduced. No commits or pushes were created by this session.

## K. Remaining limitations

This is a lexer optimization, not a new semantic model. Existing Markdown limitations remain: three-character fence recognition instead of full CommonMark fence-length rules; simple indentation/blockquote/list interpretation; a simple inline-backtick matcher; no new dedicated colouring for Setext headings, emphasis, strikethrough, tables, links, images, reference definitions or HTML blocks. Where those constructs previously rendered as ordinary source, they still do. Mermaid/math remain ordinary fenced-language text here; rich rendering is unchanged.

Cold state recovery and genuinely nonlocal edits are not constant-time. Multiple unanalysed edits intentionally lose unverified suffix reuse. The line-based context and an indivisible line lexer remain constraints for extreme lines. Shared-worker contention and host scheduling still affect end-to-end convergence even when the lexical work is small.

The broader Tranche 1C HTML-layout, large-table, resize and rich-asset lifecycle limitations remain deferred. This optimization does not complete preview responsiveness or implement inline rendering, backlinks or wiki-links.

## L. Final verdict

<!-- FINAL_VERDICT -->

Tranche 2 should build on the existing source/snapshot/revision contracts, and may eventually replace this lexical state with shared semantic state. The cache is intentionally narrow and does not create a competing Markdown AST or document authority. No Tranche 2 implementation was begun.
