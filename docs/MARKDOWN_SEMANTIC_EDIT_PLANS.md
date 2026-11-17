# Tranche 2C — revision-safe heading edit plans

## A. Architecture

`MarkdownHeadingEditPlan.plan(semantic, dot, mark, demote)` is a pure planner over one complete captured Markdown revision. It returns an immutable marker proposal or an explicit rejection. It does not access Swing, mutate text, start jobs, create undo entries or resolve files. The proposal carries the existing workspace document ID/revision/snapshot contract, exact verified UTF-16 marker range, expected/replacement strings, operation direction and numeric caret/selection positions. It retains the shared source only for its short application lifetime; it retains no AST, controller, buffer, pane or editor surface.

`MarkdownEdit.applyPlan` performs checked application. It compares the freshly captured owner/source against the plan, checks the buffer's actual revision and snapshot identity, verifies the bound document, selection, range and expected marker characters, then delegates to the existing `MarkdownEdit` range edit. Existing capability, document-filter, compound-undo and history-byte checks still run. No second transaction system or document store was introduced.

Only exact heading markers become mutation ranges. Heading location envelopes are used to find the target line, never as replacement ranges. The planner compares numeric segment bounds without materializing each heading or repeatedly calculating all heading line numbers. The authoritative document and its existing listeners remain responsible for revisions, snapshots, dirty state, highlighting, completion, LSP and recovery.

## B. Files changed

| File | Purpose |
| --- | --- |
| `src/main/java/shed/MarkdownHeadingEditPlan.java` | New immutable proposal/result contract and pure ATX marker planner. |
| `src/main/java/shed/MarkdownEdit.java` | Checked semantic-plan adapter into the existing edit boundary. |
| `src/main/java/shed/MarkdownController.java` | Migrate single-line heading shifts, reuse 2B analysis, guard/release pending edits, and preserve the initiating pane and inactive source viewports after shared caret updates. |
| `src/main/java/shed/InputController.java` | Read-only access to its existing last source-key timestamp for request freshness. No key routing changes. |
| `src/main/java/shed/EditorUiController.java` | Cancel a pending heading edit when binding another pane/document. No layout, painting or style changes. |
| `src/main/java/shed/Texteditor.java` | Narrow cancellation delegate at the existing composition boundary. |
| `src/test/java/shed/MarkdownHeadingEditPlanTest.java` | New deterministic planning, application, persistence, undo and lexical-cache compatibility tests. |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | Add workbench editing, stale/lifecycle, keymap/split/preview/recovery cases; existing command assertions wait for exact asynchronous completion. Earlier 2B additions remain intact. |
| `src/benchmark/java/shed/MarkdownEditingProbe.java` | New optional paired editing and memory probes, compatible with the pre-2C JAR. |
| `src/benchmark/java/shed/MarkdownPerformanceProbe.java` | Reflectively dispatch the optional edit/memory modes. Existing modes remain available. |
| `scripts/benchmark-markdown.py` | Register those modes with at most 100 samples and 2 MiB fixtures. Existing modes/caps are unchanged. |
| `scripts/test_benchmark_markdown.py` | Verify invalid new-mode resource requests fail before creating output. |
| This report and `performance/markdown-tranche2c/` | Contracts, paired measurements, raw evidence, development failures and final verification. |

Starting HEAD was `9a12c55b41aa7052e4365ffe2d590d70c6ef8166`. Four modified 2B files and ten untracked 2B evidence files were already present. Their task-start hashes/status are archived. The shared test file is extended; the other three previously modified files and all prior evidence are preserved byte-for-byte. No commits, pushes, reset, clean or revert commands were issued.

## C. Migrated commands

The existing `MarkdownController.markdownHeadingShift` route now uses semantic plans for verified ATX promotion/demotion. This includes Vim `<<`/`>>` and the existing application delegate; no new commands or shortcuts were added. Indented, empty, formatted, Unicode and verified container headings retain their original content, whitespace, closing hashes and terminators.

For a complete semantic **no-heading** result, the existing ordinary-line indentation branch is retained only for lines without `#`. This branch still uses `MarkdownEdit`; it never reinterprets a semantic no-match as a heading. Hash-containing nonheadings, including fenced/escaped/inactive examples, are rejected rather than falling through to the old heading regex.

Subtree shifts (`<r`/`>r`), table/task/link/image edits, list continuation, fence-language completion, folding containment, preview anchors, navigation, symbols and highlighting retain their existing routes. Subtree regex interpretation is not made semantically correct by this tranche and remains a documented migration boundary.

## D. Supported and unsupported behavior

| Input/outcome | Editing behavior |
| --- | --- |
| Complete current semantics and a verified ATX marker | Replace only the opening `#` run, by one level. |
| H1 promotion / H6 demotion | Existing boundary feedback; no edit/history entry. |
| Complete no-heading result | No heading edit. Only the hash-free ordinary-line indentation behavior above is retained. |
| Setext heading title or underline | Explicit unsupported feedback. No style conversion. |
| Missing, invalid, surrogate-splitting or ambiguous marker/selection | Explicit unverified rejection. |
| More than 1,048,576 UTF-16 units | Immediate size-limit rejection before scheduling analysis or materializing the target line. |
| Other parser/admission caps, projected/incomplete source, failure or cancellation | Explicit unavailable result; no regex heading fallback. |
| Changed revision/snapshot/expected text, foreign document, moved selection, switched/closed pane, mode/keymap/input change | Reject or discard the pending proposal without mutation. |
| Read-only, disabled, preview, alternate or large-file surface; document filter; insufficient undo budget | Existing capability/history rejection. |

The 2A parser cap and 2B conservative dense-source admission remain unchanged. `SUCCEEDED/COMPLETE` still means recognized features over the captured source, not complete edit precision for every Markdown construct. The 2B outline/navigation legacy fallback remains available; editing deliberately makes the stricter choice for uncertain marker transformations.

## E. Editing correctness

Deterministic cases cover ATX markers at start/EOF, absent/present final newline, empty headings, all levels, indentation, blockquote/list containers, inline formatting, combining characters, emoji, Hebrew, raw CRLF positions, fenced/unclosed/escaped/indented code, Setext and missing/invalid spans. Accepted plans preserve exact non-marker source and use one compound undo entry. Tests verify prior history, one-step undo/redo, selection mapping and shared source across two views.

Rejected applications preserve text, snapshot/revision, saved baseline, dirty state, event count, caret/selection and undo/redo retention. Cases include target/elsewhere edits, undo/redo returning to identical text, identical text in another buffer, closed/read-only ownership and insufficient history budget. A stale semantic snapshot may still describe its original revision; it cannot authorize a mutation of the current one.

UTF-8 BOM, LF/CRLF and EOF metadata are exercised through actual save/reopen byte comparisons. Recovery-journal tests retain complete modified source, and workbench tests verify the normal recovery scheduler after accepted edits. No persistence, recovery, external-change or source ownership implementation was replaced.

## F. Highlighting compatibility

`GrammarHighlightService` and `SyntaxUiController` are unchanged. Actual marker edits produce one-character incremental source-change metadata. That metadata follows the existing document listener into checkpoint invalidation, suffix reuse, viewport tokens and revision-checked installation. Unit tests replay actual insertion/undo/redo events and compare the resulting deep-viewport tokens with a full lexer reference while bounding rescans/checkpoint counts. Workbench tests compare current installed spans against the existing lexer after the semantic edit.

The semantic snapshot is neither a highlighting token source nor a painting dependency. Ordinary typing schedules no semantic analysis. No new full-source materialization was added to the lexical pipeline, and no semantic completion triggers a whole-document highlight scan.

## G. Scheduling and performance

An already-retained 2B result applies synchronously after checked planning. A cold heading command uses the existing bounded 2B coordinator and returns lightweight loading feedback. Application occurs only while its original document/revision/snapshot, active pane, caret/selection, keymap/mode and source-key timestamp still match. Caret movement, focus loss, pane rebinding, closure and disposal cancel pending work. Its temporary listeners and source/selection stamp are removed on completion, rejection, cancellation or stale-result pruning.

Only one heading edit can be pending. Additional requests receive busy feedback rather than accumulating delayed mutations. The coordinator still has at most one running job, one newest pending source, eight subscribers and one weak result; no executor, timer, permanent editing listener or strong document cache was added. Explicit outline/navigation/edit consumers may share the same analysis. Plans are not cached.

The optional probe measures the same pre-2C and final JAR on this host using identical fixtures/JDK/heap/probe code. It separately records typing, current-result heading edits, undo/redo, pure planning, stale-plan rejection, cold request/completion, ordinary highlight convergence and source restoration. Warm edits assert zero extra parse requests. Highlight convergence includes the existing 120 ms debounce; pure planning includes reflective invocation overhead. Full paired numbers and sample counts are in the [evidence index](performance/markdown-tranche2c/INDEX.md).

Final paired p95 milliseconds, 100 samples after five warmups except highlight convergence (ten) and cold completion (ten):

| Source | Typing to paint before → after | Heading edit EDT before → after | Undo to paint before → after | Redo to paint before → after | Pure plan after | Highlight convergence before → after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 KiB | 0.97 → 0.86 | 1.00 → 1.15 | 1.00 → 0.70 | 1.31 → 0.58 | 0.072 | 136.68 → 136.34 |
| 100 KiB | 0.83 → 1.58 | 1.12 → 1.21 | 1.28 → 1.16 | 1.15 → 1.33 | 0.122 | 136.63 → 135.94 |
| 1 MiB | 1.16 → 1.13 | 0.78 → 1.08 | 1.06 → 1.02 | 0.74 → 0.89 | 0.265 | 136.11 → 136.28 |

Stale-plan rejection p95 was 0.020 / 0.023 / 0.021 ms. Cold heading completion was 15.85 / 15.28 / 41.59 ms; cold request EDT p95 was 0.156 / 0.083 / 0.094 ms. The source edit remains deferred while analysis is missing. Repeated warm edits submitted no additional semantic jobs. At 2 MiB, the migrated command rejects before scheduling (0.0035 ms p95); ordinary typing remains available and its measured p95 was 1.10 ms to completed Swing paint. No per-keystroke semantic work was added.

Earlier exploratory measurements and the first 100 KiB typing increase are retained separately from a reverse-order control. No universal latency threshold or speedup is inferred from differing host load. Cold semantic editing has additional worker/convergence latency compared with the old unqualified regex command; it does not perform that parse on the EDT.

The final 100 KiB typing pair still differs by 0.75 ms p95. Earlier forward/reverse groups ranged from 0.81 to 2.19 ms; the cause is unverified, and no typing-equivalence claim is made. The final observed p95s remain below 2 ms. No production per-keystroke path gained a semantic request, scan or listener installation.

## H. Memory qualification

The 835.23 MiB 2B peak is not directly comparable with a historical settled footprint. This tranche adds same-host controls with semantic analysis disabled, before/after semantic analysis, repeated eligible outlines, switching, disposal and closure. Observations distinguish used/committed heap, sampled peak RSS, per-phase settled RSS, worker allocation and collection of the released semantic result. A separate profiled pair captures late-phase NMT and macOS VM-map summaries without changing JVM/GC configuration.

The initial controls already put the old build around 626–655 MiB RSS before semantic use on this host. Repeated 1 MiB parsing reproduced an approximately 805 MiB peak; model collection after closure was observed. This establishes a substantial pre-existing fixed footprint and a transient/repeated-workload component, not a complete native-memory attribution. The evidence index records the final paired observations and qualifications. The 835 MiB concern is not declared resolved, and no renderer, font, GC, startup or cache rewrite was undertaken.

Final memory controls additionally wait for existing nonrepeating syntax/symbol/completion/recovery/LSP timers and their jobs. Early jobs-only observations are kept as exploratory evidence because queued timers could allocate after that check. The final 1 MiB semantic pair measured maximum observed RSS **785.09 → 772.47 MiB**, post-GC used heap with the outline **26.873 → 26.997 MiB**, and after source/outline closure **24.254 → 24.286 MiB**. Committed heap in both was 66 MiB initially, 97 MiB after repeated outlines and 84 MiB after closure. Both released semantic results were collected. The observed outline/model release was 2.62 / 2.71 MiB; it is an application-level observation, not an exact deep-size census.

Without semantic requests, the same-host 1 MiB controls reached maximum observed RSS 660.12 / 666.88 MiB and post-GC used heap after closure 23.886 / 23.943 MiB. Semantic worker allocation medians were 61.21 / 59.95 MiB per 1 MiB revision. These include the 2B heading projection as well as CommonMark and differ from isolated 2A costs.

After the final inactive-viewport preservation change, a final-artifact 1 MiB control again ran 30 analyses: maximum observed RSS 770.53 MiB; used heap with outline 26.796 MiB and after closure 24.176 MiB; committed heap 100 / 87 MiB at those phases. The semantic result was collected and the source was closed. This final observation supports the same bounded-retention conclusion; it does not resolve the fixed-footprint qualification.

The separate timer-aware profiled pair shows roughly 91–93 MiB of NMT thread commitments, 50–53 MiB of GC metadata, changing heap commitment and about 121 MiB of VM-map graphics residency before the outline (126 MiB with it, returning to 121 MiB after closure). Mapped/shared code and resource pages also contribute. RSS, VM-map physical footprint and NMT commitments have different accounting and are not additive. MXBean/NMT/VM-map captures occur at different instants; the probe does not stop all JVM/native maintenance. [Inference] These measurements do not establish a new retained-model regression from 2C; they do establish substantial non-model footprint and repeated-parse allocation. Complete native attribution and the historical 835 MiB qualification remain unresolved.

## I. Regression verification

Exact commands, suite totals, skip names, packaged smoke and benchmark-tooling results are archived in the evidence index. Earlier failures are retained: a new split test initially issued work during deferred pane setup; a later split assertion selected the wrong active pane through the existing caret listener; one existing buffer-picker test timed out and passed in focused re-verification. The 2 MiB probe initially mistook dirty state left by its own typing/undo setup for a source mutation; its corrected control uses snapshot identity and an explicit fixture reset. No production validation or existing assertion was weakened to accommodate those failures.

The existing EOF/undo command helper now waits for the exact in-flight heading completion through a test-only secondary event loop and bounded observer. Its source, dirty-state, history and undo/redo assertions are unchanged. No blocking wait or nested loop was added to production editing.

**Final Maven result: 1,377 cases, 1,372 passed, five existing skips, zero failures/errors.** Eleven Python benchmark-tooling tests and the packaged-only Mermaid/math/image smoke passed. Nine plan/application tests, nine existing highlighting cases, three `MarkdownEdit` cases and five selected workbench tests passed in a 26-case focused run; the later two-case viewport/preview run also passed. The final full suite covers 2A parsing, 2B consumers/navigation, 1B source views, 0A folding containment, 0B editing, FileBuffer/snapshots, history, recovery, preview synchronization, keymaps and LSP. Earlier failures and the existing picker timeout remain visible in archived logs; no root cause is asserted for that timeout.

## J. UI/UX compliance

No controls, settings, panels, dialogs, menus, windows, rendering frameworks or dependencies were added. Visual identity, typography, themes, syntax colors, layouts, outline/navigation components and key routing remain unchanged. Existing command feedback reports pending/unavailable operations. Normal split ownership remains in the existing pane controller; semantic edit completion preserves inactive source viewports through the existing surface API and restores the initiating pane through its ordinary activation path when shared caret updates activate another pane.

## K. README and repository compliance

README SHA-256 before and after implementation: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Final unchanged-file checks and HEAD are recorded in the evidence index. README was never edited. No dependency, document model, parser/range contract, lexical highlighting implementation or prior performance evidence was changed. HEAD advanced externally through `d683529b2`, `9f96c29b4` and `1444c64f`, incorporating 2B and in-progress 2C files; those commits were preserved. Existing 2B work was preserved; this task issued no commit or push command.

## L. Remaining limitations

Setext/style conversion, subtree semantic transforms and uncertain table/task/reference edit precision remain deferred. Unsupported sizes/structures reject heading-marker edits even where legacy navigation remains available. Hash-containing nonheading lines no longer take generic indentation through this heading-specific route. Fast repeated cold edit commands receive busy feedback, and intervening user activity can cancel a cold command; there is no delayed edit queue. Weak-result collection can require another explicit parse.

Plans share their captured snapshot during application and are not retained by the controller afterward. Caller-retained plans can retain that immutable source; they are not intended as an edit history or persistent cache. The CommonMark AST still has transient allocation on admitted inputs. The high fixed desktop RSS/native-resource attribution, uncontrolled caches, physical keyboard/compositor timing, second-platform performance and external Markdown LSP traffic remain separate qualifications.

## M. Next tranche recommendation — not implemented

**3A** can build on revision-qualified outgoing occurrences and the stricter current-source application contract. Before adding an index, define bounded document lifecycle/invalidation, filesystem identity and link/reference precision. Wiki-link syntax, resolution and backlink storage still need their own explicit contracts; do not retain whole semantic snapshots in a workspace index or eagerly parse every project file.

**3B** can proceed independently with a small asynchronous media-job/resource contract over verified image and Mermaid occurrences. It needs bounded admission/cache bytes, cancellation, stale-result disposal, explicit resource release and a measured memory budget. Math-region source precision remains unavailable and must be established before source-driven math integration. Existing preview resource/security behavior remains authoritative until separately migrated.

The workstreams share source identity, revisions, coverage, destination trust and lifecycle/memory budgets, but neither requires implementing the other's index or renderer. Broad persistent semantic/media consumers should remain gated on measured retention and the unresolved fixed-footprint qualification. No 3A/3B/3C features, new rendering or Tranche 4 live presentation began here.
