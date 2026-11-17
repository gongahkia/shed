# Tranche 2B — semantic heading consumers and occurrence views

## A. Architecture

The existing Markdown outline/TOC dialog, next/previous heading commands (including level-specific Vim routes), and **local** Markdown symbol picker now use `MarkdownHeadingAnalysis`. It projects the 2A immutable semantic snapshot into source-ordered, revision-qualified heading rows. Navigation uses verified UTF-16 offsets; the original list, palette, goto-line/jump-history and pane surfaces remain in use.

Analysis is explicit, asynchronous and independent of highlighting. `MarkdownController` lazily creates `MarkdownAnalysisService` on the first heading request, passing the existing `AsyncJobService`. There is no parser invocation, timer, listener or semantic cache construction at startup. Neither scrolling nor ordinary source edits request semantic parsing. Existing breadcrumb scheduling remains unchanged.

## B. Files and responsibilities

| File | Change |
| --- | --- |
| `MarkdownHeadingAnalysis.java` | New worker projection, source-order/level-aware target lookup, explicit semantic/legacy/cancelled/failed provenance, conservative dense-input admission. |
| `MarkdownAnalysisService.java` | New bounded request coordinator over the existing job pool, coalescing, weak reuse and lifecycle checks. No controller or Texteditor dependency. |
| `MarkdownOccurrences.java` | New lazy, immutable, read-only occurrence views over 2A records; no second AST, resolver or retained occurrence index. |
| `MarkdownController.java` | Integrates explicit outline/navigation/local-symbol requests, preserves queued navigation intent, manages analysis lifecycle alongside its existing Markdown ownership. |
| `MarkdownOutlineDialog.java` | Loads rows asynchronously, guards selection/focus refresh/disposal, batches list-model updates, retains existing dialog structure. Standard cell rendering disables Swing HTML interpretation of decoded heading labels. |
| `PaletteController.java` | Routes local Markdown symbols to analysis; reuses the existing picker with current-source checks and exact selected-row targets. Non-Markdown extraction/formatting is unchanged. |
| `Texteditor.java` | Narrow composition-root delegates, source/pane eligibility checks and guarded source-offset navigation. |
| `PaneBufferController.java`, `SessionConfigController.java` | Notify the Markdown owner after existing buffer removal; no replacement of buffer ownership, save or recovery paths. |
| `MarkdownConsumersTest.java` | New headless heading, occurrence, fallback, queue, cache, cancellation and closure tests. |
| `TexteditorSwingIntegrationTest.java` | New actual-workbench navigation/outline/split/stale-buffer tests; the existing fold-navigation test waits for the now-asynchronous exact target without removing assertions. |
| `MarkdownConsumerProbe.java` | New opt-in actual-workbench performance scenarios; isolated EDT dispatch timing and existing completed-paint measurement. |
| `MarkdownPerformanceProbe.java`, `scripts/benchmark-markdown.py` | Reuse the established runner/fixtures/environment/resource controls through `semantic-consumers`; the optional new probe is compiled only for that mode. Other modes still compile against older distributions. |
| This report and `performance/markdown-tranche2b/` | Contracts, measurements, commands, failures/corrections and repository verification. |

The 2A parser/model/range files, `SymbolService`, `MarkdownService`, `MarkdownEdit`, `FileBuffer`, snapshots, 1D lexer/controller, 1B source view, preview renderer/pane/assets and Maven dependencies are unchanged.

## C. Migrated and retained consumers

**Migrated:** `:outline`, `:document outline`, `:markdown outline`, the existing table-of-contents window, next/previous heading and level navigation, and the local fallback of `:document symbols` for ordinary Markdown buffers. Corrected results include Setext, empty/formatted/Unicode headings and exclusion of fenced examples, including unclosed fences. Source locations distinguish duplicates without using titles as identities.

**Retained:** LSP-provided document/workspace symbols, workspace heuristic scanning, status-bar breadcrumbs, syntax highlighting, heading-edit commands, table edits and Markdown preview/link-anchor handling. Normal source files in other languages continue through the existing symbol service and picker. This is not a project-wide semantic index.

Source navigation does not resolve heading fragments. 2A anchors follow CommonMark's public ID generator, but preview preprocessing of math/media can change the anchor input. No preview anchor, URL policy, renderer or fragment-navigation behavior was changed. Occurrences expose syntactically present destination fragments without asserting they resolve to a heading.

## D. Eligibility, admission and fallback

The 2A full-parse limit remains **1,048,576 UTF-16 units**, with its existing line, depth, node and span limits. No limit was raised.

2A measured a dense 1 MiB marker input allocating about 555 MiB before its node-cap rejection. This consumer adds a conservative **worker-only admission check**: inputs above 256 KiB and at most 1 MiB that contain more than 32,768 `* _ [ ] | #` units take the legacy path before creating the AST. This deliberately does not attempt to recognize Markdown grammar and can reject otherwise inexpensive code blocks containing many literal markers. It reports `UNSUPPORTED` with an explicit consumer-admission reason; it never presents a truncated semantic parse as complete. One admitted job occupies at most one existing worker slot per active coordinator.

| Outcome | Heading behavior |
| --- | --- |
| Successful complete analysis, verified headings | `SEMANTIC`; use sorted semantic heading rows. Zero headings is a genuine complete no-match result. |
| Unsupported/capped/admission-rejected | `LEGACY_UNSUPPORTED`; scan the captured snapshot off the EDT with existing heading helpers. Coverage remains `NONE`, not semantic completeness. |
| Parser failure | `LEGACY_FAILED`; preserve the established lexical navigation on the worker. |
| Missing heading source precision | `LEGACY_UNVERIFIED`; do not navigate guessed semantic offsets. |
| Cancellation/supersession/budget rejection | No navigation; current owners receive the cancellation outcome where applicable. |
| Unexpected worker failure without fallback | `FAILED`; existing lightweight feedback, no false empty-match claim or arbitrary jump. |
| Stale source, closed document/pane/frame or replaced request | Discard; do not install or navigate. Diagnostics count stale rejections. |

Read-only large-file projections retain their original projection-local legacy routes. No full semantic coverage or full-file source offsets are claimed for them. The legacy path intentionally retains known limitations, including ATX-only interpretation and possible fenced-example headings. Legacy output remains proportional to heading count, as in the previous outline; it is not a new bounded semantic index or a guarantee of efficient outlines for arbitrarily dense giant files.

The initial consumer prototype called `snapshot.line(i)` for every legacy line. The 2 MiB production benchmark measured about 13 seconds per scan; its bounded watchdog stack captured `VersionedTextSnapshot.countNewlines → offsetOfNewline → line → MarkdownHeadingAnalysis.from`. That traversal repeatedly measured chunk/prefix newline positions. The final fallback reads sequential 8 KiB chunks, tracks UTF-16 offsets/line numbers once and materializes only candidate `#` lines before applying the **unchanged** existing heading helpers. A differential test checks chunk boundaries, raw CRLF, Unicode, empty headings and EOF. No snapshot/store implementation is changed. Failed/timeout benchmark reports remain negative evidence, not successful performance results.

## E. Occurrence contract

`new MarkdownOccurrences(snapshot)` is an O(1) view, not a new parse or a copied AST. Its stream produces immutable records on demand. Callers consuming substantial streams should use an existing worker. It exposes source ID/revision/snapshot, parse status/coverage/limitations, recognized-location completeness and unavailable-link counts.

Each available occurrence carries kind, disjoint verified source segments, optional exact **single-segment** range, destination/reference information, optional label/fragment/language and verified fence markers where available. Multi-line/container navigation envelopes are not reclassified as exact content or mutation ranges. Table-inline locations remain unavailable and are omitted, with their count/precision limitation exposed.

Kinds are links, images, reference definitions, ordinary code fences and Mermaid fences. Destinations are decoded, **untrusted parser values**, never automatically fetched or resolved. For simple already-classified single-line link syntax, bounded local inspection also exposes original destination-token spelling, raw label text and reference identifiers. Complex/escaped/nested forms retain the parser destination and verified location but leave unavailable spelling/labels/reference form explicit; this is not another Markdown parser. Reference status distinguishes non-reference simple inline forms, resolved simple reference uses, definitions and unknown forms.

Unresolved reference uses remain parser text and are explicitly unsupported by this extraction API; they are not fabricated as located links. Math regions and wiki-links remain deferred. A `math` language tag is ordinary code metadata, not a math-expression range. Unclosed Mermaid fences retain absent closing markers; no rendering is performed. Stream order groups kinds; consumers needing source order must sort verified locations themselves.

Extraction performs no filesystem/network access, caret movement, selection changes, writes, source mutations or renderer initialization. `isCurrent` compares the original source contract; later consumers must still validate pane/document lifetime separately.

## F. Concurrency, ownership and disposal

- **One outstanding job**, **one newest pending source**, **eight subscribers** and **one weak cached result** per lazily created coordinator. Different panes can join the same captured revision. Repeated current requests reuse an in-flight or still-live cached result.
- No new executor/thread pool, document listener, automatic parse-after-edit timer, startup scan or persistent cache. Superseded pending work is dropped with explicit cancellation to still-current owners.
- Swing capture/coordination/installation stays on the EDT. Preflight/admission, parsing, semantic projection, legacy scanning and source-position preparation run on the existing worker pool.
- Completion validates request ticket, exact source identity/revision/snapshot and owner predicates. Navigation additionally validates active pane, document binding and original caret/selection; stale work cannot override later input or another buffer.
- Rapid repeated heading keys preserve up to **64 ordered motion intents**, rather than losing repeated commands while sharing one parse. Additional intents receive lightweight busy feedback. Source/pane/caret changes invalidate the old intent batch. The pending-motion stamp references the immutable snapshot weakly.
- CommonMark cancellation is cooperative. Future cancellation is not treated as proof that computation stopped: the coordinator retains the running slot until actual completion. Session resets clear state without opening another slot; shutdown closes the owner.
- Async job-history completion hooks capture only a small ticket/coordinator, **not request snapshots or callbacks**. Old full documents are not retained by up to 200 history hooks. Active/pending owner callbacks are bounded and cleared on completion/cancellation.
- Buffer-removal hooks clear matching cache/subscriptions and close the source's outline. Pane removal cancels pane requests. Dialog disposal releases rows/model/source stamps and unsubscribes. A weak result may be collected at any time; reuse is an opportunity, not a reason to retain closed documents.

An outline refreshes on explicit reopening, source-stale selection, or return of focus after edits/cancellation. It does not launch a parse per keystroke. Stale selection triggers refresh and feedback without moving the caret. Original filtering, selection, double-click, Enter, escape and buttons remain in place. List additions are batched using the existing model.

## G. Correctness and previous-tranche preservation

Headless tests cover semantic ATX/Setext/empty/duplicate/formatted/Unicode headings; fenced and unclosed examples; strict target selection; complete-empty versus fallback/cancelled results; oversize/dense admission; links/images/references/fragments/fences; escaped table-span omission; weak reuse/in-flight joining; saturation, supersession, cancellation, injected failure, document closure and disposal.

Actual-workbench tests exercise existing heading keys/outline selection, source offsets, duplicates, stale selection after edits, shared splits and independent caret state, pending analysis across buffer switches, and ordered repeated navigation. They assert unchanged source, snapshot/revision/history/dirty/saved baseline and ordinary document-event counts except for the deliberate source edit. The existing fold keyboard test retains its assertions and waits for exact asynchronous navigation targets.

No source/highlighting, save/recovery/undo or LSP ownership code was replaced. The 1A service-provider packaging, 1B view metrics, 1C partial fixes and 1D checkpoint pipeline remain in place. Final test totals and packaged verification are recorded in the evidence index.

## H. Production measurements

The opt-in `semantic-consumers` mode uses the actual packaged workbench, original deterministic fixtures and existing completed-Swing-paint attribution. It measures outline request EDT duration, worker time, observed current-revision convergence, largest observed EDT dispatch, cached navigation, typing/undo while jobs are outstanding, occurrence extraction, document switches and shared splits. It checks exact source restoration and records reuse/stale counters, heap/GC observations and environment.

This is separate from the 2A isolated parser benchmark. Convergence includes the existing worker queue, UI installation and 10 ms state polling. An outstanding job can be queued or executing; typing samples are labeled accordingly. Native keyboard delivery/compositor timing is excluded. Largest observed EDT dispatch includes concurrent normal UI work and is not a parser-only measurement. Occurrence checksums consume fields to prevent a count-only stream optimization from bypassing extraction.

Heavy benchmarks remain opt-in, bounded by the existing 512 MiB child heap/watchdog/new-output-directory rules. Startup observations test that the service is uninitialized before an explicit request; no true cold-cache claim is made. Measured values, sample counts, host pressure and limitations belong to the final [evidence index](performance/markdown-tranche2b/INDEX.md).

## I. Verification

Exact commands/results and development failures are archived in the evidence directory. Routine new consumer/parser checks run headless. Display-based checks are reserved for actual windows, focus, outline selection, split behavior and completed painting. No assertions are loosened or existing tests disabled.

## J–L. Compatibility and limitations

No new visible controls, settings, panels, menus or rendering framework were added. The existing design system, layout, fonts, themes, syntax colors, navigation gestures, keymaps, source surfaces and cross-platform Swing architecture remain unchanged. Only incorrect eligible Markdown heading contents and their current source navigation are corrected. Decoded labels are displayed as text through the standard renderer, without interpreting them as Swing HTML.

README remains protected; before/after SHA-256 and repository preservation are in final verification. No commit/push commands are issued by this task; externally advancing HEAD is recorded and preserved.

Known limitations: semantic/admission caps and conservative false negatives; lexical fallback's pre-existing heading inaccuracies; LSP/workspace/breadcrumb consumers still unmigrated; missing table-inline/task-marker/reference precision; unresolved-reference/math/wiki-link extraction; weak-cache misses after GC; full CommonMark transient allocation on admitted input; and the previously deferred Swing HTML preview-layout bottleneck. The occurrence view does not resolve fragments, files or anchors.

## M. Proposed Tranche 2C — not implemented

Introduce a small **semantic edit-plan adapter** for already-verified heading markers first. Plans must capture source ID/revision/snapshot, expected original characters, exact verified marker ranges and intentional EOF behavior, then apply exclusively through existing `MarkdownEdit` capability/selection/compound-undo checks. Reject stale or unknown ranges; never turn navigation envelopes into replacement ranges. Leave uncertain table/task/reference edits on 0B guarded helpers until their precision is independently established.

Coordinate invalidation/currentness with existing document-change metadata, not another source store or an always-running parser. Keep 1D lexical checkpoints/suffix reuse/viewport tokens and its worker scheduling intact; semantic completion must not trigger a whole-document highlight scan. At most share immutable identity/range contracts and verified changed regions. Measure typing/undo and highlighting convergence before changing scheduling.

Acceptance: exact source/EOF preservation and one-step undo/redo; read-only/stale/cancelled plans are mutation-free; source/pane checks and range bounds remain strict; no per-keystroke full parse or extra worker; no UI/design change; current heading navigation and occurrence extraction remain correct; 1B/1D paired controls do not regress. Keep project resolution/backlink/wiki-link indexing for 3A, rich-media integration for 3B, and shared live presentation/source-preserving folding for 4. No such work begins here.
