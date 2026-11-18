# Markdown live presentation — Tranche 4A

## A. Executive verdict

**CONDITIONAL GO for further bounded geometry research. NO-GO for production migration of this prototype.** Source-preserving variable typography is demonstrated over the existing document, but general editing responsiveness and memory acceptance are not established. The 100 KiB Unicode experiment retained 189.24 MiB of heap while open and incurred a 2.48-second first-edit/reveal stall. The matching source control also stalled (1.96 seconds), so the stall is not attributed entirely to the prototype. Its additional retained heap is nevertheless a material gate failure. The failed experiment is preserved rather than expanded into an editor rewrite.

The isolated prototype demonstrates source-offset geometry, native source editing, capped decoration/shaping structures and explicit revision rejection. Those structure caps do not establish a sufficient bound on the complete Swing view hierarchy. It does not maintain styled presentation across edits: editing immediately reveals the stock source view until a separately requested current-revision preparation completes.

That distinction is essential. A fast source fallback is not evidence that continuous styled editing meets the frame budget. Initial/explicit wrapped extent preparation still shapes the admitted document on a worker. Zero-width concealment, integrated block-caret painting, IDE overlays, native IME qualification and a bounded incremental geometry strategy remain gates before production use. No production editor, preview, document, keymap, theme or dependency was changed.

The [evidence index](performance/markdown-tranche4a/INDEX.md) contains exact commands, measurements, failures, artifact identities and the prototype images. This is the requested bounded engineering prototype, not a new editing mode or an implementation of 4B.

## B. Candidate comparison

Only these three strategies were investigated:

| Criterion | A: existing plain view / foreground overlays | B: stock Swing styled text | C: bounded custom Swing view over existing source |
| --- | --- | --- | --- |
| Architectural fit | Already integrated with the IDE and 1B/1D. | `JTextPane` requires `StyledDocument`; canonical source is `PlainDocument`. | Keeps `JTextArea`, canonical `PlainDocument`, native text actions and existing surface seam. |
| Source correctness | Current proven source-preserving path. | A styled copy preserves characters but is another editable document; direct canonical attachment is rejected by Swing. | Decorations/geometry are separate immutable data; no source attributes or characters are changed. |
| Editing compatibility | Existing controllers, undo, persistence and modes work. | Would require a document/surface ownership migration or a non-stock adapter. | Native actions, raw clipboard, shared undo and existing key listeners remain; production overlay/caret integration is incomplete. |
| Typography | One font/line height. Overpainting cannot change caret or selection metrics correctly. | Native variable fonts and paragraph styles. | Variable fonts use the same shaped layout for paint and offset geometry. |
| Concealment | Color changes preserve advance; true concealment needs different geometry. | Possible via custom views, still requiring explicit source mapping. | Marker dimming is demonstrated; zero-advance projection has explicit affinities in a coordinate-only experiment. |
| Inline objects | Foreground overlay cannot reserve layout space. | Native icons/components usually use document attributes/embedded object conventions. | A verified source-anchored placeholder reserves a visual row without inserting a source character. |
| Layout complexity | Uniform geometry, bounded width summary; existing wrapped/complex-text limits remain. | Materialized paragraph/view tree and attribute metadata; source copy and styling have costs. | Explicit initial worker preparation plus bounded viewport shaping; incremental height updates remain unresolved. |
| Memory | Existing 64-candidate width summary; no new metadata required. | Additional document/element/style/view state. | Capped geometry/spans and row cache, with weak source identity and disposal. |
| Performance potential | Excellent fallback; insufficient for variable metrics. | Useful small control, not evidence for a low-memory large source editor. | Credible bounded visible work; no claim of continuously styled low-latency editing yet. |
| Maintenance / platform risk | Lowest; keep unchanged. | Ownership, undo, keymap and per-pane attributes would require broad changes. | More mapping/painting code, font/IME/accessibility qualification and overlay adaptation; stays within Java/Swing. |

A is retained as the fallback. B is rejected as the production strategy within this scope: an actual test verifies `JTextPane.setDocument(canonicalPlainDocument)` throws, and character styling on a separate `DefaultStyledDocument` creates an undoable attribute edit. The bounded B benchmark measures that copy/style/layout experiment without calling it a source-preserving editor.

C is the smallest demonstrated route to variable metrics without replacing source ownership. It is a custom Swing **view**, not a custom input/editor framework. All implementation classes live under test/probe directories and are absent from the packaged application. No WebView, JavaFX, native bridge or new dependency was needed.

## C. Prototype architecture

`MarkdownPresentationFrame.prepare` consumes an existing complete semantic snapshot plus captured font/colors, shaping context, wrap width and generation. It executes off the EDT. The benchmark uses Shed's existing `AsyncJobService` and `MarkdownHeadingAnalysis` admission/parser path, with one explicit request at a time; no new scheduler or per-keystroke semantic work was introduced. A caller with an already-current semantic snapshot can pass it directly.

The resulting frame contains a weak exact-snapshot stamp, document ID/revision/generation, immutable decoration spans, text/object row coordinates and a small interval-search index. Rows carry source UTF-16 boundaries and visual metrics, not another editable string/document or a retained AST. The initial admitted extent calculation shapes lines on the worker and discards those layouts. This gives correct wrapped scroll extents while making its document-wide preparation cost explicit.

`MarkdownPresentationPrototype` installs a test-only `BasicTextAreaUI` view adapter on a supplied `JTextArea`. It attaches to the same canonical document. Its bridge preserves the current stock view for fallback; the styled view paints/hit-tests only visible rows and caches at most 128 layouts / 16,384 source units. The prototype does not own edits, history, persistence, project state or keyboard routing.

Installation checks source ID, revision, exact snapshot identity, complete-source status, document binding, generation, font/colors, width and wrap settings. An obsolete completion cannot replace a newer frame. Edits invalidate geometry immediately before delegating damage to the original stock view. Theme/font/width/document/composition changes invalidate it as well. There is no automatic parse or full geometry rebuild in that invalidation path.

Unsupported semantics, projected sources, source above the unchanged semantic cap, long lines, tabs, raw CR and bidirectional source fall back safely. Incomplete/failed/cancelled analysis does not masquerade as complete empty formatting. Per-pane caret, selection, viewport, generation and shaped-row cache remain independent; immutable geometry can be shared only when its source/settings contract matches.

The retained limits are 32,768 decoration spans, 32,768 visual rows, 4,096 UTF-16 units per admitted physical line and the unchanged approximately 1 MiB semantic envelope. Source-wide geometry is explicitly bounded; it is not a claim of incremental or purely viewport-only initial layout. The reported metadata payload excludes Java object/header overhead; heap/RSS observations are separate.

## D. Implementation inventory

Prototype helpers, all test-only:

- `src/test/java/shed/MarkdownPresentationFrame.java`: immutable decorations, bounded worker extent preparation, shaping attributes and row/source indexes.
- `src/test/java/shed/MarkdownPresentationPrototype.java`: native text-view bridge, checked installation, viewport shaping cache, selection/geometry and lifecycle.
- `src/test/java/shed/MarkdownMarkerProjection.java`: pure coordinate experiment for collapsed marker ranges with explicit forward/backward affinity; never applied to source or production UI.

Tests:

- `src/test/java/shed/MarkdownPresentationPrototypeTest.java`: 19 deterministic candidate, source, geometry, clipboard, Unicode, fallback, stale/lifecycle and disposal cases.
- `src/test/java/shed/TexteditorSwingIntegrationTest.java`: one added isolated-view workbench test through existing Plain/Vim/Emacs input routes. Existing tests are unchanged.

Benchmark/tooling:

- `src/benchmark/java/shed/MarkdownPresentationProbe.java`: opt-in source control, isolated view and rejected styled-document measurements; images and resource/lifecycle evidence.
- `src/benchmark/java/shed/MarkdownPerformanceProbe.java`: reflective dispatch for three prototype/control modes.
- `scripts/benchmark-markdown.py`: opt-in admission and explicit compilation of the test-only helpers; deterministic Unicode fixture. Existing fixture bytes/algorithms remain unchanged for existing shapes.
- `scripts/test_benchmark_markdown.py`: prototype admission and UTF-8 fixture checks.

Documentation: this report and `docs/performance/markdown-tranche4a/`, whose full evidence inventory appears in `manifest.json`. No file under `src/main`, dependency manifest, production resource or startup registration changed. Initial uncommitted 3B report/probe/evidence bytes remain intact.

## E. Visual behavior

The prototype demonstrates H1/H2 and smaller heading typography, normal text, bold, italic, inline-code background/family, underlined links, fenced code treatment and caret-aware marker dimming. Fonts and colors come from the supplied editor and existing syntax/theme colors; there is no new theme engine. Source delimiters remain present and keep their advance. The visual evidence is an isolated diagnostic surface, not a new production layout.

One image occurrence can reserve a labeled placeholder row attached to its verified source range. Clicking that row returns the source occurrence's start. The raw image syntax remains editable above it. This establishes a **source-anchored block allocation**, not a finished inline atom/image replacement or a rendered-media integration. No image file, math renderer or Mermaid runtime is initialized by this prototype.

Tables, lists, tasks and blockquotes remain raw source. Code is styled as a semantic region; the prototype does not replace the 1D lexer or duplicate its token stream. General rich Markdown layout and syntax concealment are future work.

## F. Source and editing correctness

Tests verify zero presentation-induced document events, unchanged source/snapshot/revision/dirty state and no history entries. Raw copy includes all Markdown syntax; paste and native typed actions use the canonical document and its existing undo manager. Save/reopen retains BOM, CRLF metadata and EOF bytes. Formatting adds no LSP text-change call; zero document events establish that formatting does not invoke source-change listeners.

Caret/source round trips cover every ASCII boundary across headings, emphasis, code, links and wrapped rows. Native vertical movement and selection use custom source geometry. Emoji/surrogate boundaries, combining marks and CJK/mixed LTR text are exercised; mouse results are validated against UTF-16 source boundaries. Tabs and bidi explicitly use stock geometry. Actual native-language IME and cross-platform shaping are not qualified by the synthetic composition-invalidation test.

Two panes share one source/history while keeping independent caret/selection and layout state. Out-of-order worker completion, edits including undo returning to identical text, foreign documents, closure, composition, font/theme/width changes and cancelled preparation are tested. A scrolling stress case checks cache limits after each visible paint. Closed prototypes restore the old UI/caret and detach their listeners.

The workbench case installs the isolated view on the existing area in a test process and verifies existing Plain/Vim/Emacs routing still produces one normal source edit and undo. The test uses native text actions after unconsumed key listeners, matching Swing's event path; it does not replace input handling. This is not comprehensive mode/visual certification. Production `BlockCaret` redraws one character using the uniform font, so the prototype uses a native bar caret and explicitly defers correct styled block-caret painting. Existing syntax/search/LSP/diagnostic/inlay/gutter overlays also need geometry-aware integration before migration.

### Marker-coordinate design

The rendered prototype only dims verified markers. Their source/visual advance is unchanged, so caret entry/exit changes ink without moving text or creating history. Full concealment would use a separate piecewise projection: outside hidden intervals, subtract their total advance; inside a collapsed interval, multiple source offsets share one visual boundary. A visual hit at that boundary requires explicit affinity: backward selects the marker start, forward its end. Adjacent collapsed intervals need a consistent end rule.

`MarkdownMarkerProjection` tests this map, including surrogate-boundary rejection, without mutating source. A future presenter must reveal the complete editing region on caret/selection/IME entry, preserve raw clipboard ranges, keep semantic ranges distinct from view positions, and validate the revision before every installation. Keyboard movement needs logical-source and visual-affinity policies; bidi and grapheme clusters must not be reduced to character widths. Selection across concealed markers must still select/copy the corresponding raw source. No deletion/reinsertion or object-replacement character is an acceptable implementation of concealment or folding.

## G. Performance

All times below are milliseconds; forced completed Swing paint is measured separately from operating-system display presentation. Primary typing/scroll distributions use 100 samples; preparation has 5 samples (8 for the small repeated lifecycle case). These are same-host paired processes, not confidence intervals.

| Fixture | Control typing p95 | Prototype fallback typing p95 | First styled edit maximum (n=3) | Worker geometry median / maximum | Styled scroll action / paint p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4 KiB, unwrapped | 0.96 | 1.79 | 5.14 | 9.95 / 45.85 | 0.80 / 1.03 |
| 100 KiB, unwrapped | 1.49 | 1.59 | 4.20 | 260.30 / 343.37 | 2.64 / 1.10 |
| 1 MiB, unwrapped | 1.15 | 1.70 | 8.44 | 3821.92 / 3932.03 | 2.55 / 1.12 |
| 4 KiB, wrapped | 1.13 | 1.71 | 4.91 | 16.38 / 54.86 | 1.11 / 0.83 |
| 100 KiB, wrapped | 1.54 | 9.72 | 12.68 | 413.69 / 491.74 | 10.22 / 1.05 |
| 1 MiB, wrapped | 1.47 | 4.37 | 4.66 | 5451.78 / 6612.49 | 2.07 / 0.94 |

The control and prototype use the same production artifact, source buffer/listeners and diagnostic Swing surface. The stock control uses the existing 1B view. The real workbench is initialized first, then its content is temporarily replaced only inside the disposable probe JVM. The source document remains the existing `FileBuffer`; no application default is changed. Source typing uses native Swing actions; existing workbench document/snapshot/highlighting notifications still observe those edits.

Static styled scrolling/painting, explicit semantic preparation, full initial geometry, installation, first edit/reveal and ordinary **source-fallback** typing are separate measurements. After the first edit, styled geometry is discarded and no semantic request is made automatically. Explicit manual refreshes in the probe are not ordinary per-keystroke behavior. Fast fallback samples must not be presented as continuous live-formatting latency.

The visible cache is bounded, but initial wrapped extents still require admitted document-wide shaping on a worker. Font/width/structural changes currently require another explicit preparation. Changing to raw geometry can change total height/viewport position; visual continuity and incremental extent updates remain mandatory preproduction gates.

The 1 MiB worker geometry median was **3,821.92 ms unwrapped / 5,451.78 ms wrapped**, with maxima of **3,932.03 / 6,612.49 ms**. Explicit semantic analysis medians were 48.74 / 58.96 ms; installation medians were 0.064 / 0.074 ms. Complete explicit convergence medians were 3,880.51 / 5,513.06 ms. Keeping preparation off the EDT does not make a multi-second refresh suitable for continuous editing.

The unwrapped 1 MiB scroll-action p95 increased from **0.82 to 2.55 ms**; wrapped increased from **0.96 to 2.07 ms**. This is a measured regression, although the individual samples remained short. The wrapped 100 KiB fallback typing p95 was 9.72 ms versus 1.54 ms in the control. None of these observations proves the requested continuously styled 16 ms target.

The Unicode fixture contains CJK, emoji and combining text (102,400 UTF-8 bytes / 91,839 UTF-16 units). Three first-edit samples reached **2,482.12 ms** in the prototype and **1,958.18 ms** in the matched source control. Prototype mutation and forced-paint maxima were 1,132.75 and 1,345.83 ms respectively. Ordinary subsequent source-fallback typing p95 was 4.39 / 4.63 ms. The control establishes that complex-text source layout itself has a pathological path; it does not explain the prototype's much larger retained heap. No causal fix or universal latency claim is made.

Other coverage: numerous headings at 100 KiB prepared successfully; the 32 KiB single-line fixture safely rejected geometry. The existing 100 KiB `fence` generator emits no complete fence block at that size, so that case is a padding/long-line fallback test. The 1 MiB fence fixture contains four large blocks but its padding exceeds the semantic line-length limit; it also correctly rejects presentation. Thus supported large-fence performance is **not verified** by these probes. Two 2 MiB fixtures use the unchanged semantic-cap fallback, with no installed geometry; source fallback itself can exceed 16 ms on long lines. Unit tests cover actual supported fenced-code styling.

The styled-document control is a bounded copy/style diagnostic, not a competing editor. Its timed layout replay excludes installation/validation and cannot establish complete layout latency. No large-document performance claim is made for that candidate.

Measurements ran serially on an Apple M3, 16 GiB RAM, macOS 26.5.2, Temurin 21.0.13, 512 MiB child heap, 2x display scale and the inherited Monospaced 16 font. Recorded host load varied substantially, rising above 12 during supplemental controls. These are single-host samples under variable load, not universal budgets. Initial startup occurs before the test-only adapter exists; the 1,406 packaged application classes are byte-identical to the preserved baseline, so no production startup registration was introduced.

## H. Memory and native resources

MiB; heap and phase RSS sampled after three explicit GCs with short settling intervals. These are controlled diagnostic snapshots, not claims about long-term idle equilibrium. Sampled RSS peak may miss short spikes and is independent of phase sampling.

| Fixture / surface | Production heap used / committed | Retained heap used / committed | Disposed heap used / committed | RSS production / retained / disposed / sampled peak |
| --- | ---: | ---: | ---: | ---: |
| 1 MiB source | 23.75 / 66 | 25.78 / 66 | 25.84 / 66 | 661.78 / 665.22 / 683.78 / 683.77 |
| 1 MiB prototype | 23.77 / 66 | 28.06 / 70 | 26.51 / 94 | 668.12 / 687.36 / 749.16 / 768.48 |
| 1 MiB source wrapped | 24.86 / 87 | 28.04 / 97 | 27.00 / 94 | 690.84 / 703.23 / 722.69 / 734.78 |
| 1 MiB prototype wrapped | 24.91 / 87 | 30.73 / 104 | 27.66 / 97 | 692.72 / 731.89 / 749.00 / 785.53 |
| 100 KiB Unicode source wrapped | 35.03 / 120 | 53.64 / 134 | 33.57 / 114 | 698.44 / 720.31 / 725.23 / 808.70 |
| 100 KiB Unicode prototype wrapped | 34.53 / 120 | 189.24 / 344 | 33.53 / 117 | 771.80 / 1011.17 / 807.12 / 1056.78 |

The probe records the production editor before the diagnostic surface, retained frame/cache state, and memory after restoring the original surface. Small cases additionally repeat prototype creation/preparation/disposal three times. Frames and presenters are checked through weak references after GC; closed frames retain no strong source snapshot or AST. Cached shaping and geometry payload are reported separately from used/committed heap and whole-process RSS.

The 1 MiB frame payload estimates are 1,002,940 bytes unwrapped and 1,267,420 bytes wrapped, excluding object headers and Swing state. The Unicode estimate is only 422,680 bytes, yet retained process heap reaches 189.24 MiB versus 53.64 MiB in the control. This discrepancy blocks claiming that capped frame/cache data bounds the whole presentation footprint. Heap returns to 33.53 / 33.57 MiB after disposal, with zero surviving prototype/frame weak references. That is cleanup evidence, not acceptance of the open-view footprint or attribution of its cause. Native/RSS attribution remains unresolved.

No media renderers, global startup caches, native graphics framework or background executor were added. Fonts/shaping/Java2D can still initialize native resources. The historical above-1.2-GiB media workload is not comparable with settled prototype RSS. Native-memory attribution remains unresolved; cleanup of these prototype objects does not prove whole-app memory equivalence or complete native-resource attribution.

## I. Future media interface

Keep these stages distinct:

1. A current semantic result identifies a verified media source region.
2. The existing 3B service receives an immutable request and performs rendering off the EDT.
3. The consumer holds an explicit asset lease, charged to 3B's resident byte/pixel budget.
4. A pane-local installation checks source ID/revision/snapshot, pane generation, appearance and resource identity.
5. A visual slot accepts only bounded intrinsic metrics and paint access; changing its metrics invalidates the affected row/block and extent index.
6. Source/project/pane disposal releases the lease and rejects pending results through existing notifications.

The placeholder demonstrates only source anchoring and reserved geometry. It owns no renderer or source text. A future slot must not call the renderer during layout, move focus, change source or create history. Leases/content identity do not substitute for current pane/revision checks. There is no actual media-service wiring in 4A.

## J. Existing preview disposition

The decoration/row and 3B asset contracts could support both editable and read-only native presentations. That is a reason to investigate shared geometry, not proof that this prototype replaces HTML preview capabilities. It does not yet reproduce tables, general HTML, footnotes, accessibility, all link interactions or robust media flow.

Keep the existing preview. Tranche 1C remains incomplete: no live `JEditorPane.setText` moved off EDT, no HTML renderer was replaced, and large-table/resize/layout stalls are not solved here. The duplicate-initial-render and HTML-reader copy corrections remain byte-identical. A later preview strategy should reuse semantic/asset/geometry contracts where justified while allowing a distinct read-only consumer.

## K. Regression verification

Final Maven verification: **1,491 tests run, 1,486 passed, five existing skips, zero failures and zero errors**. The 20 added tests account for the increase from 1,466 passing tests. Python benchmark tooling: **13 passed**. Packaged Mermaid/math/image smoke: **PASS**. Packaging: **BUILD SUCCESS**.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" -B -Djava.awt.headless=false -DargLine=-Xmx512m test
"$MVN" -B -DskipTests package
python3 scripts/test_benchmark_markdown.py
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/shed-tranche4a-run/packaged-smoke.json
git diff --check
```

The smoke artifact records the exact Java command, classpath, renderer events and artifact hash. `regression-suites.json` lists each suite and the five skip reasons. The complete suite covers prior semantic, edit-plan, navigation, media, preview, ownership, undo/recovery and split regressions. Native IME/accessibility and additional OS runs were not performed.

Exact commands, counts, skip reasons, failed development attempts and case identities are archived. No tests were disabled or existing assertions weakened. Corrections to new tests addressed Swing's painted-UI requirement for arrow actions, native typed-action dispatch after key listeners, and preserving the actual dirty-state baseline after ordinary undo rather than assuming undo clears it.

## L. UI/UX compliance

Production Shed's layout, visual identity, themes, font choices, syntax colors, editor surfaces, components, navigation, tabs, panels, controls and interaction conventions remain unchanged. No production setting, command, shortcut, rendering mode or appearance feature was introduced. All experimental typography, bar-caret and placeholder visuals are isolated in test/probe code. No production parser, highlighting, media or source-view optimization was replaced.

## M. README and repository compliance

README before/after SHA-256:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

README was not edited. Original 4A capture: HEAD `e84c674565478d60e6b58100c116aba62fb51c9c`, with pre-existing 3B report/probe/evidence modifications and 1,160 file hashes. After interruption, external HEAD was `b90187e50a2b4843af872c8c982e3916c6f82e39`; those changes were preserved. Final identities and hash checks are archived. No reset, clean, revert, commit or push was issued by this task.

## N. Risks and limitations

- Continuous styled editing is not implemented. Whole-frame invalidation/reveal changes geometry and can move scroll/caret placement visually; a bounded incremental strategy is required before a production default.
- Complex-text first-edit stalls and the Unicode open-view heap increase fail the production gate. Small Unicode correctness tests do not qualify the larger workload. The complete view hierarchy and stock fallback need attribution before accepting this strategy; no speculative correction was attempted in 4A.
- Stock styled-document migration conflicts with source ownership. A custom view avoids that conflict but introduces shaping, selection, damage, extent and accessibility maintenance.
- Production block caret, line-number/gutter baselines, syntax/search/diagnostic/inlay/AI overlays and all visual editing modes are not qualified with variable metrics.
- Bidi, tabs, raw CR and overlong lines use stock source. General grapheme/IME/accessibility behavior and additional OS/font stacks need native qualification. A synthetic composition event is not an IME acceptance test.
- Marker dimming has unchanged advance; the zero-width projection is a coordinate experiment only. Full concealment/affinity and hidden-selection policy remain unshipped.
- Initial/reflow geometry is prepared over the admitted document on a worker. No unbounded row cache is retained, but offscreen initial extent calculation is still real work and is measured explicitly.
- The object experiment is a block allocation anchored to source, not a true inline atom or rendered asset. No media execution or preview replacement is delivered.
- Used/committed heap and RSS are different metrics; native-memory attribution remains open. Single-host timing samples do not establish universal equivalence.

## O. Tranche 4B handoff — not begun

Authorize only a narrow, independently gated implementation after resolving the identified blockers:

- Before any production activation, isolate the Unicode view-hierarchy/retention and first-edit path against the preserved controls. Reject this adapter if the additional retained heap or EDT stalls cannot be narrowly contained. This is a prerequisite investigation, not authorization for a broad editor or JVM rewrite.
- Keep `FileBuffer`/`PlainDocument`, immutable snapshots, undo, persistence, recovery, `EditorSurface`, existing command/key routing, 1B/1D and 3B unchanged as ownership boundaries.
- First establish a bounded local geometry/invalidation contract that preserves unaffected current decorations through supported edits **without** reusing stale semantics. If that cannot be proved, retain source presentation and do not make live formatting the default.
- Limit initial visible changes to verified headings, emphasis, inline code and links, with raw markers or non-collapsing dimming. Defer general concealment, tables, objects and media until their geometry/selection contracts pass.
- Adapt block-caret glyph drawing, gutter baselines and current overlays to the same layout/offset API; test Plain/Vim/Emacs, selections, undo, splits, native IME, accessibility and supported Unicode before enabling the path.
- Reuse the current palette/font/color infrastructure and bounded asynchronous semantic jobs. No new framework, alternate document, startup pass or per-keystroke full parse.
- Require supported 1 MiB **actively styled** typing-to-paint p95 <=16 ms; bounded worst-case EDT tasks, measured scrolling, bounded bytes/rows, stale-result rejection and post-disposal release. Repeat paired controls on representative platforms/fonts.
- Keep a source-view fallback at the view boundary for caps, unsupported constructs, composition and failures. Rollback removes the presentation adapter while keeping the same document/history/selection. Never rewrite source to hide syntax.
- Stop if maintained current semantics, caret/selection, native input, overlay correctness, bounded incremental layout or retained memory require broad editor/document replacement. Request a separate decision instead of expanding scope.

The current prototype is not itself the recommended production patch. No Tranche 4B, later live-editor work, large-file editing or Appearance & Personalization work was begun.
