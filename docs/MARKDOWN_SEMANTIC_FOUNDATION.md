# Tranche 2A — shared Markdown semantic foundation

## Architecture and ownership

This tranche adds an **unused, on-demand internal API**. No existing editor, command, navigation, highlighting or preview consumer is migrated. There is no source listener, event subscription, startup hook, worker, cache or result installer.

`MarkdownSemanticSnapshot.Source.capture(workspaceId, buffer)` captures the existing workspace document ID, `FileBuffer.documentRevision()` and **the actual immutable `VersionedTextSnapshot` object** on the EDT, after the ordinary source/snapshot update. It retains no buffer, workspace, pane, document or other mutable Swing reference. The ID must be resolved by the owning workspace. Read-only projected sources are explicitly marked incomplete and rejected for full analysis.

An existing worker may pass that input to `MarkdownSemanticParser.parse(source, cancelled)`. The synchronous adapter performs bounded eligibility checks, constructs an independent CommonMark parser, reads the immutable snapshot in chunks, and projects the temporary AST into small immutable records. The AST and its code literals do not escape. The caller owns the returned snapshot and must release obsolete results. Multiple panes may share the same immutable result; selection and viewport state remain pane-owned.

This is an interpretation of the canonical source, never another editable source store. No new revision counter or document identifier allocator exists.

## Source and revision contract

- Ranges are half-open **UTF-16 offsets** `[start, end)` into the exact captured snapshot. They are not byte offsets, code-point indexes, grapheme indexes or visual columns.
- FileBuffer inputs use the existing codec's editable representation: LF-normalized text, with BOM/encoding/line-ending metadata outside that text. The adapter does not normalize or rewrite its input. Raw synthetic LF, CRLF, mixed and CR inputs retain their original absolute offsets; CommonMark's absolute `SourceSpan.inputIndex` is used, never a reconstructed offset from parser line/column values.
- `MarkdownSourceRange.at` follows existing `VersionedTextSnapshot` zero-based **LF-based** line/UTF-16-character positions. Tabs count as one unit. For synthetic CRLF sources, CR remains a character before LF; a lone CR is not a line boundary in this position API. This differs from CommonMark's physical-line numbering, which is not exposed as the snapshot position contract.
- Invalid/negative/out-of-bounds positions fail rather than clamp. Empty ranges and `[length, length)` at EOF are valid. Boundaries splitting a valid surrogate pair are rejected. Combining sequences are preserved without Unicode normalization; grapheme segmentation is not provided.
- A `Location` contains immutable, ordered, potentially **disjoint source segments**. Newlines and quote/list prefixes can lie between them. Its envelope is a navigation extent, **not a content-only or authorized replacement range**. Marker ranges are supplied only where locally verified against the captured characters. Exact heading-content and link-destination token ranges are not claimed.
- A naked range is a coordinate, not a document identity or edit capability. Its owner is the enclosing semantic snapshot. Before installing results or using them to prepare an edit, callers must validate document ID, document revision and **snapshot object identity**, using `isCurrent`/`requireCurrent`. Snapshot `.version()` alone is insufficient across loads. Existing `MarkdownEdit` checks must still protect any later mutation; this tranche does not connect the APIs.
- Workspace IDs are store-scoped. Exact snapshot identity prevents a same-number ID, same revision and identical text in another buffer/workspace/reload from being accepted as current.
- `isCurrent` is a source comparison, not proof that a document remains open or that a pane/job is still active. A future installer must resolve the ID in its owning workspace, freshly capture the current source and validate pane lifetime/request generation separately.

For example, decoded `# 😀\n[x](a)` has heading `[0,4)` and link `[5,11)`. The same synthetic text supplied with CRLF has link `[6,12)`; no byte-index conversion occurs. In `> first\n> second`, paragraph segments are `[2,7)` and `[10,16)`, while the navigation envelope `[2,16)` also contains the intervening newline and quote prefix.

## Status, coverage and limits

`SUCCEEDED / COMPLETE` means the complete supplied source was parsed, with explicit feature/range limitations. It does **not** mean every extension has a complete editable semantic representation. `UNSUPPORTED`, `CANCELLED` and `FAILED` return `NONE` coverage, `[0,0)`, an explanatory detail and **zero semantic records**. No prefix-only result is presented as a complete document. Partial record publication is intentionally not implemented in 2A.

Initial full-parse eligibility is deliberately conservative:

| Limit | Initial policy |
| --- | --- |
| Captured source | 1,048,576 UTF-16 units maximum; complete source required |
| Physical line | 65,536 UTF-16 units maximum |
| Physical lines | 50,000 maximum; CRLF counted once |
| Open CommonMark block parsers | 128; reaching semantic depth 127 rejects the result |
| AST nodes visited | 100,000 maximum |
| Projected parser spans processed | 200,000 maximum |
| Leading literal BOM | Unsupported; capture decoded source instead |

These are semantic-analysis limits, **not new editor/file limits or user settings**. Oversize input is rejected before materialization or parsing. Node/span/depth checks happen after AST construction begins; they are not a hard peak-heap or execution-time guarantee. Dense small inputs can still allocate heavily before rejection. No `OutOfMemoryError` is caught as normal validation. The benchmark bounds each child to 512 MiB and a watchdog.

Cancellation is cooperative before eligibility checks, between source chunks and during projection. CommonMark's own processing between reads is not forcibly preempted. No worker or unbounded request backlog is introduced. Future consumers must use existing bounded worker/generation conventions and validate current identity again on the EDT.

## Semantic records and supported syntax

| Records | Support and precision |
| --- | --- |
| Blocks | Headings, paragraphs, fenced/indented code, bullet/ordered lists, list items, quotes, tables/head/body/rows/cells, thematic breaks and opaque HTML. Flat parser-traversal records, not a general AST API. Extensions can relocate nodes; consumers needing source order must use verified locations. |
| Headings | ATX/Setext, levels, decoded Text/Code-derived labels, verified opening/underline markers. Line breaks become spaces in labels. Fenced examples are not headings. |
| Heading anchors | Existing public CommonMark `IdGenerator`, fresh duplicate-ID state per parse, matching heading-anchor's Text/Code-only input and current default-locale policy. That extension ignores line breaks for IDs even though labels include spaces. This is a CommonMark-source anchor contract; preview math preprocessing can change the anchor input and must be reconciled during migration. |
| Fenced code | Info string, first language word, closed/unclosed state, exact verified fence markers and original line segments. Code-body strings are not retained. Backticks, tildes, differing valid lengths and nested container prefixes use CommonMark's interpretation. |
| Inlines | Emphasis, strong emphasis, strikethrough, inline code and opaque inline HTML. Verified marker pairs where available; no fabricated content-only ranges. |
| Links/images | Decoded destination, optional title, kind and occurrence source segments when reliable. Inline/reference/autolink destinations are interpreted by CommonMark. No filesystem/network resolution or URL safety claim; destinations remain **untrusted**. |
| Reference definitions | Label, destination, title and definition source segments. CommonMark leaves invalid/unresolved reference uses as text; no false link record is synthesized. The AST does not preserve the original resolved-reference label or syntactic link form, which this API explicitly leaves unavailable. |
| Task markers | Reliable checked state. The extension provides no marker spans, so its location is explicitly empty. No guessed checkbox range. |
| Rich media | Image occurrences and Mermaid-tagged fenced records, without renderer execution or asset access. Math/wiki-link semantics are explicitly deferred. No rendering, backlinks or resolution. |
| Additional extensions | Existing autolink, footnote, alert, strikethrough, table and task-list parser extensions enabled. Unprojected extension details are flagged; custom blocks remain generic extension records. HTML internals remain opaque. |

Tables expose a demonstrated dependency source-span limitation. For `| a\|b *c* [l](x) | d |`, CommonMark 0.28.0's table extension removes the escaped pipe before inline parsing. The emphasis span selects ` *c` and the link span selects ` [l](x`, not their actual complete syntax. Table/cell spans remain useful, but **all table-descendant inline/link locations are unavailable** in this initial adapter. Their semantic kinds/destinations are retained. See the raw probe evidence; no incorrect editing offsets are exported.

Math is deliberately deferred: the preview's math preprocessing changes source before CommonMark parsing and cannot supply a trustworthy source-range contract by reuse alone. Wiki-link parsing/resolution belongs to 3A. Link destinations are deliberately opaque strings rather than URI/filesystem objects, allowing future wiki-link occurrence metadata to reuse the source/revision/location contract without premature resolution. Neither omission silently masquerades as full feature support.

## CommonMark integration and existing disagreements

The adapter uses the existing **0.28.0** dependencies without upgrading or adding anything. `BLOCKS_AND_INLINES` source spans and `parseReader` preserve absolute indices. Parser and extension instances are constructed only per explicit call, avoiding shared mutable extension state; the parallel test verifies independent duplicate-heading and reference state. HeadingAnchor is a renderer extension, so only its public ID generator is used here. No `MarkdownPreviewRenderer` initialization, GraalJS, math, HTML installation, sanitization or resource loading occurs.

The upstream [source-span contract](https://raw.githubusercontent.com/commonmark/commonmark-java/commonmark-parent-0.28.0/commonmark/src/main/java/org/commonmark/node/SourceSpan.java) specifies Java UTF-16 indices and explains disjoint block spans. The [parser implementation](https://raw.githubusercontent.com/commonmark/commonmark-java/commonmark-parent-0.28.0/commonmark/src/main/java/org/commonmark/parser/Parser.java) supplies independent per-parse document state. The packaged dependency bytecode and isolated probes additionally verify this checkout's behavior. Importantly, the nesting cap changes interpretation instead of throwing; reaching that boundary is therefore rejected explicitly.

Existing interpretations intentionally remain unchanged:

- `MarkdownService.extractHeadings` and `SymbolService.collectMarkdownSymbols` use ATX regexes, do not exclude fenced examples, miss Setext headings and differ on empty headings/closing hashes/formatting.
- Markdown commands use the 0B guarded line/range helpers. Their conservative table safety policy is not replaced by CommonMark's broader recognition.
- The 1D lexer uses its retained fence/checkpoint model and viewport-only spans, not a full semantic parse. Its lexical recognition and fence rules differ from the CommonMark model; they remain untouched.
- Preview rendering preprocesses Mermaid/math, renders and sanitizes HTML, and restricts resource URLs. Semantic parsing analyzes the original source and does none of those transformations. Semantic destinations cannot bypass preview security policy.

These differences are migration work, not reasons to rewrite consumers in 2A.

## Correctness and regression evidence

The new headless unit suite covers headings, duplicate anchors, fenced-code exclusion, closed/unclosed/empty/container fences, emphasis/code markers, links/images/references/autolinks, escaped pipes, task-span absence, quotes with disjoint ranges, CR/LF/CRLF/mixed offsets, tabs, emoji, combining marks, Hebrew, long paragraphs, EOF and strict surrogate boundaries.

Bounded randomized differential testing performs **400 full comparisons** (100 deterministic fixtures × four source revisions) against independent CommonMark heading/link nodes, source spans and decoded destinations. Edits insert headings/fence delimiters at line boundaries; old results become stale. Sixteen parallel parses check isolated anchor/reference state. No optimized incremental semantic parser is claimed.

Clean and dirty real FileBuffer tests capture shared-document splits and prove unchanged text, snapshot identity, revision, dirty state, saved baseline, undo retention, caret and document-event counts. Existing dirty history still undoes/redoes. BOM/mixed-ending bytes remain unchanged, including clean save. Existing full-suite persistence/recovery, editing, highlighting, preview, source-view and keymap coverage is retained.

Exact commands, final counts, measurements, artifact identities and unchanged-file checks are recorded in [the evidence index](performance/markdown-tranche2a/INDEX.md). Earlier focused-test and diagnostic failures remain visible, including the discovery that the parser's nesting cap does not throw.

## Performance and memory

Measurements are isolated caller-thread **full semantic API costs**, including preflight, parser/extension construction, snapshot reads, AST processing and projection. They are not typing, EDT, highlighting-convergence or application-startup measurements. The file-acquisition row measures disposable UTF-8 file reading and snapshot creation; real `Source.capture` shares the existing snapshot rather than reading a file or flattening source.

Final measurements on the recorded 8 GiB Intel MacBookPro15,2 (Java 21, 512 MiB child heap) are:

| Full semantic API | Samples / warmups | Median / p95 | Median allocation | Records | Estimated model retention |
| --- | --- | --- | --- | --- | --- |
| 100 KiB mixed | 20 / 3 | 17.25 / 31.17 ms | 6.00 MiB | 2,174 | 0.24 MiB |
| 1 MiB mixed | 20 / 3 | 76.29 / 139.63 ms | 57.03 MiB | 22,592 | 2.54 MiB |
| 1 MiB single fenced block | 5 / 1 | 25.01 / 30.89 ms | 13.26 MiB | 2 | 0.51 MiB |
| 10 MiB mixed | Unsupported before parsing | No successful-parse statistic | — | 0 | No model retained |

Cold first calls in the mixed children took 200.81 / 470.16 ms, including deferred class initialization. Peak whole-process RSS was 94.0 / 274.8 MiB for the 100 KiB / 1 MiB mixed children, including repeated parses and transient heap. An adversarial 1 MiB marker input was rejected at the node limit after 1,553.54 ms and 555.59 MiB cumulative thread allocation (264.2 MiB peak RSS). A 1 MiB table was similarly rejected after AST work; line/size limits reject before that work. This distinction is important: the adapter is isolated and capped, not a hard allocation-budget parser.

Ordinary fixtures use 20 samples after three warmups; adversarial controls use five after one, or stop at the first unsupported result. No p99 is inferred at these counts; n=5 p95 is just the maximum. Host load, hardware, pressure, Java/artifact/fixture identities and raw samples are retained. The host already had about 3.9 GiB swap used; measurements are exploratory, not quiet-machine qualification. Used-heap observations after explicit GC are estimates, not exact retained-size proofs; RSS is whole-process peak, allocation is cumulative thread allocation, and neither is equated with retained model size. The evidence index includes smaller table/heading/marker controls and all negative cases.

Memory grows with projected records, line segments and metadata strings, plus a reference to the **shared** immutable source. There is no duplicate persistent source text, complete token list, AST, parser state per keystroke or document/pane registry. Temporary CommonMark allocations can be much larger than the retained model; dense-marker/table controls document this limitation. The 1 MiB full-parse ceiling should remain until real consumers justify incrementalization or changed limits.

All **19,117** pre-existing classes are byte-identical to the preserved pre-2A JAR; the 21 added classes increase the shaded artifact by 33,739 bytes. No source-view/highlighting/startup path is modified. Startup is not rebenchmarked; absence of integration/eager initialization establishes isolation, not a newly measured startup improvement. Final verification: **1,343 Maven tests passed, five existing skips, zero failures/errors**; 13 new headless cases passed; ten existing Python benchmark-tooling checks passed; the final packaged Markdown/Mermaid/math/image smoke passed.

## Proposed Tranche 2B handoff — not implemented

1. Migrate **eligible, on-demand heading outline/navigation** first: `MarkdownService`/`MarkdownController` heading reads and `SymbolService` Markdown symbol extraction. Capture existing workspace ID/revision/snapshot on the EDT, parse with existing workers, reject stale completion, and map verified semantic heading locations back to existing navigation components. Do not migrate editing replacements merely because a heading has a navigation envelope.
2. Define and test the unsupported/oversize policy before migration: 2A caps reject dense/large inputs. Preserve existing behavior where a consumer remains unmigrated; do not silently label legacy regex results as complete semantic analysis. Any eligibility expansion needs new memory measurements.
3. Reuse the existing immutable link/image/reference/Mermaid records for occurrence extraction only. Preserve absent table-inline/reference-token precision; add narrowly tested source mapping only if a real consumer needs it. Do not start indexing, resolution, media rendering or wiki-links.
4. Keep `MarkdownEdit`, preview rendering/security, 1D highlighting, source view and document ownership unchanged. Math/preview-anchor differences and task-marker precision need separate tests before those consumers migrate.

Acceptance: current-revision outline/navigation agrees with CommonMark for ATX/Setext/empty/formatted/duplicate headings and ignores fenced examples; stale/switch/closed-pane results never install; source/undo/dirty/recovery/LSP state remains unchanged; existing UI/navigation gestures and themes remain identical; no per-keystroke full parse or extra worker pool; eligibility/caps and retained memory documented. Share one immutable eligible result across splits where a consumer demonstrates the need, rather than adding a speculative global cache.

## Compatibility and disposition

No existing production file, dependency, UI component or consumer is changed. Visual identity, design system, layout, typography, themes, syntax colors, navigation, keymaps, focus, selection, scrolling, split ownership, saves, recovery and LSP behavior remain unchanged. Tranches 0A/0B/0B.1/1A/1B/1C/1D are preserved. The broader Swing preview-layout limitation remains deferred.

README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. No README change or existing-work replacement was performed. No commit or push command was issued by this task. During verification, HEAD advanced externally from `9dbef8d1a` to `74f2a9558`, incorporating earlier files; that commit was preserved. Every pre-existing file's bytes still match the captured starting baseline. This tranche stops at the isolated semantic foundation; Tranche 2B remains a proposal.
