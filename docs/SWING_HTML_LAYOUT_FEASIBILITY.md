# Swing HTML layout feasibility — Tranche 1C.1

## A. Executive verdict

**NO-GO for completing general-purpose 1C responsiveness through a narrow stock-Swing HTML optimization. CONDITIONAL GO for staged construction as a research building block.** A representative prose document can be constructed progressively without the previous one-second initial stall, preserving exact sampled output. That positive result does not extend to large tables, width changes or arbitrary supported HTML.

The corrected 1 MiB prose experiment achieved queued source insertion-to-Swing-paint **p50 7.62 / p95 9.23 / p99 11.14 / max 13.46 ms** while a draft was being staged. Its first-pass staging tasks peaked at **6.89 ms**. However, a large table blocked one task for **433 ms**, and a prose resize-and-restore callback blocked for **963 ms**. A larger code-block fixture exhausted a bounded 512 MiB child heap inside HTML document construction before layout. No production renderer, behavior, setting, dependency or worker was changed.

Keep the existing preview limitation documented. Do not fund an independent preview-only renderer without deciding its relationship to the planned live Markdown presentation. A small, separately tested HTML-reader memory correction is justified before any broader presentation work. Tranche 1C remains incomplete; this feasibility study does not begin 1D, semantic-model or Tranche 4 implementation.

[Raw evidence/reproduction index](performance/markdown-tranche1c1/INDEX.md) and [earlier instrumented matrix](performance/markdown-tranche1c1/INSTRUMENTED_RESULTS.md) retain both positive and negative outcomes. No unsuccessful case is relabeled as complete rendering.

## B. Attribution

The production path is unchanged: `MarkdownPreviewPane`'s 120 ms Timer and explicit refresh call `renderNow` (line 146), renderer/canonical capture (153), `JEditorPane.setText` (154), then Swing validation/paint. `establishPreviewScrollRange` (220) may force preferred-size/layout. The constructor still uses only `refreshAppearance` (102), preserving the duplicate-render correction. Source editor painting still uses the verified 1B view path.

Installed Temurin/OpenJDK 21 source was inspected directly in `lib/src.zip`, not inferred from class names:

| Work | Confirmed call path / behavior |
| --- | --- |
| Model construction | `HTMLEditorKit.read → HTMLDocument.HTMLReader → ElementSpec` builds the full styled element tree. Detached construction is independent of component View construction. |
| View construction | Installing a document creates a new UI root/HTML View tree. Unchanged model/view reuse avoids this work; fresh models lose their cached requirements. |
| Minimum/preferred widths | `BoxView.checkRequests → ParagraphView.calculateMinorAxisRequirements → findEdgeSpan → GlyphView/GlyphPainter1` examines breakable/unbreakable fragments, using font measurements. Cached requirements make repeated unchanged queries cheap. |
| Wrapping and vertical extent | `BoxView.setSize/layout → FlowView.layout → paragraph rows`. Changed width can invalidate rows across all paragraphs, even if no text changed. |
| Tables | A whole table is an indivisible fragment in this bounded prototype; cell/column/grid requirements produce a long task despite small surrounding prose callbacks. |
| Deferred validation | Revalidation can run in a later AWT invocation, outside the explicit install call. Real dispatch durations are recorded as well as timed work. One corrected run observed a 1,037 ms ambient invocation after retained-phase input; its context alone does not prove attribution to A. A short preferred-size call is not proof that all EDT work is short. |
| Painting | Visible painting is separate from layout. Pixel extraction was measurable diagnostic overhead and was removed from final timed callbacks. |

The separate JFR run collected 5,383 execution/native samples, with overlapping EDT stack-presence counts including ParagraphView 1,039, FlowView 688, FontDesignMetrics 667 and HTMLDocument 26. These are not CPU percentages or additive stage costs; the recording also includes source actions, pixel verification, native rendering and idle network-selector threads. `profile.json` retains the stacks. Timed callbacks and actual EventQueue dispatches establish the long uninterrupted work independently.

A new memory attribution is unusually specific. In this installed JDK, `HTMLReader.preContent` (around 3855) passes the **same whole PRE char array** for each line into `addContent`. `DefaultStyledDocument.ElementSpec`'s constructor (around 1277) copies `txt.length`, not merely the supplied offset/length slice. A long many-line block therefore creates repeated whole-buffer copies until parse-buffer flush. The observed OOM stack ends in that constructor's `Arrays.copyOf`. This is independent of Markdown parsing, SourcePlainView and viewport painting. It does not establish that every JDK vendor/version has the same behavior; the exact runtime is recorded.

Images/math/Mermaid are prepared through the unchanged renderer/assets implementation before these layout comparisons. First-use renderer execution is measured separately from layout; GraalJS initialization was not optimized. Remote images remain disabled in the isolated homes. Rich output has no Mermaid-error fallback and uses real local PNG/math/Mermaid assets.

## C. Exactly three candidates

| Candidate | Minimal experiment | Findings | Decision |
| --- | --- | --- | --- |
| A — retain valid layout | Keep installed HTMLDocument/View tree when HTML is unchanged; repeatedly query/layout/paint and compare three full replacements. | Corrected prose retained p95 1.41 ms versus full-replacement median 849 ms. Initial/new-content layout is still required. A same-URL local image changed on disk while retained pixels stayed old; fresh installation differed. Explicit refresh cannot be skipped unconditionally. | **NO-GO as the responsiveness solution.** A conditional no-op filter could be a later small optimization, not a solution to real edits/open/resize. |
| B — incremental text update | Insert/remove one known rendered-text character with its existing attributes. Compare a separately parsed full expected result; count surviving View identities. | Corrected prose insert p95 4.27 ms; sampled geometry/pixels and metadata exact. Almost all old views survive. The composite text-cell update was also much cheaper than full replacement. No general Markdown-to-DOM mapping was built. | **CONDITIONAL GO for text-only warm updates**, after trustworthy semantic/source-region mapping. **NO-GO alone** for full 1C: initial installation, structural changes, links/IDs, global references and resize remain. |
| C — progressive staged construction | Parse existing sanitized HTML into complete top-level block strings, populate a separate hidden JEditorPane on EDT one block per callback, then atomically publish a complete matching revision through the existing viewport. | Prose construction can meet the source p95 goal, and complete sampled output matches. Tables/large blocks are not bounded by this granularity. Width growth/resize still invokes global reflow. Two full display models/View trees coexist temporarily. | **CONDITIONAL GO as a constrained construction primitive; NO-GO for production acceptance in its present form.** Further granularity, width/resize control, resource ownership and integration would be a larger presentation adaptation. |

No custom production ViewFactory, AsyncBoxView replacement, browser or new rendering engine was installed. AsyncBoxView was inspected, but simply replacing HTML container classes does not preserve CSS table/paragraph geometry or enforce a bound on one expensive child. A full such adaptation was not attempted as a fourth candidate.

## D. Corrected paired measurements

Same JVM/artifact/fixture pairs compare stock and candidate work. Times are ms; sources use the showing Shed editor with syntax enabled and the same split. Preview-disabled controls execute the same source actions with no preview task. Timing excludes pixel readback; hidden C staging does layout only, with painting on final publication. Exact pixel verification remains outside timing.

| Corrected workload | 1 MiB prose | 1 MiB table/code/prose |
| --- | ---: | ---: |
| Same-HTML full replacement, median / max, n=3 | 849.42 / 857.57 | 1,202.09 / 1,272.55 |
| A retained task p95 / max, n=100 | 1.41 / 1.99 | 7.17 / 29.24 |
| B local insert p95 / max, n=20 | 4.27 / 4.83 | 9.77 / 10.04 |
| C chunk p50 / p95 / p99 / max | 2.04 / 2.74 / 3.49 / 6.89 (n=1,353) | 432.59 (one chunk, then stopped) |
| Source queued insertion+paint p50 / p95 / p99 / max | 7.62 / 9.23 / 11.14 / 13.46 (n=100) | 437.49 (n=1; no percentile qualification) |
| Preview-disabled source insertion p95 | 7.95 | see raw case control |
| C current-settle draft construction | 19,643 ms | unavailable; rejected before completion |
| C resize-and-restore task | 962.76 | not attempted after stop |

Prose undo+paint p95 was 4.71 ms during staging versus control 5.97; redo 4.65 versus 6.10. Distant caret+paint p95 was 5.96 versus 2.35, and source scroll+paint 2.60 versus 2.13. Caret/scroll have 1,353 C observations versus 100 controls; they are separate operations, not end-to-end native gesture measurements.

Insertion latency includes EDT queueing, document/listener work and synchronous paint of the showing source widget. It excludes physical keyboard/native dispatch/compositor scanout. The first 100 staging callbacks perform insert/undo/redo; later callbacks perform navigation only. It therefore does not prove random input latency throughout every later layout state. The legacy fixture's late ~456 ms width-change stall is a concrete example that a favorable early input p95 could miss.

C deliberately lets a stale draft continue for measurements, rejects its revision, then constructs the settled draft. Its approximately 19 s settle pass includes conservative one-step scheduling, logging and source-action pacing; it is not an optimized preview completion-time estimate. Complete current publication is marked explicitly, but launch-to-first-meaningful-paint is not separately qualified. No claim substitutes HTML availability for finished presentation.

`C.resize` narrows by 120 px **and restores in the same callback**. The 963 ms total is not a single native resize-event measurement. It demonstrates that the prototype still permits a long uninterrupted global reflow batch; per-direction native resize timing remains a gap. The earlier `stock.resize` offscreen/reparented control is not a valid paired resize baseline and is excluded from that comparison.

### Earlier matrix and negative cases

The [instrumented results](performance/markdown-tranche1c1/INSTRUMENTED_RESULTS.md) cover 100 KiB, ordinary/legacy 1 MiB, the composite, rich media and a repeat. Their timed screenshot copies inflated small costs; they are preserved, not pooled with corrected timings. Geometry checks remain useful. The corrected runs confirm the same large-table/resize limits without that overhead.

- The legacy generator ends with an unbroken padding word. Its minimum-width change produced a ~456 ms late chunk. Ordinary `prose` changes only artificial p-only padding to equal-length p-space text; both hashes/variants are retained.
- A 1 MiB composite containing a ~242,000-character (~236 KiB) / 2,000-line PRE exhausted the child’s **fixed 512 MiB heap before layout**. It was not repeated with a larger heap. The final guard skips this known unsafe parse workload. A separately labeled exactly-1-MiB composite uses the same large table plus 256 code lines and prose, allowing layout costs to be separated from the reader failure. Its first table chunk still takes 433 ms in the corrected run.
- One repeat exited 0 without the completion marker during its second pass. Cause remains unresolved. It is ERROR, not green; partial samples do not establish full convergence/disposal. No additional rerun was used to erase that result.

The actual machine is MacBookPro15,2, Intel i5-8279U 2.40 GHz / eight logical CPUs, 8 GiB RAM, macOS 15.7.7 x86_64, Temurin 21.0.12.1+1-LTS, 2× display scaling, UI Dialog 13 and source Monospaced 16. The source notification row is held stable; actual viewports are recorded (typically preview 597×574, depending on scrollbars). Children use 64–512 MiB heaps; optional JFR is capped at 64 MiB. Load, existing swap/pressure and fixture/probe/JAR hashes are recorded per case. OS caches are not purged. One JVM per fixture and fixed variant order do not establish a cross-process confidence interval or universal target compliance.

## E. Correctness and resource assessment

B and completed C prose/rich cases match **exactly** for document text, preferred size, full link/ID/image metadata, selected model positions/hit tests and RGB crops at first/middle/last viewport locations. No tolerance was widened. The smaller composite passes B equivalence but never completes C. Completed staged publication uses the captured current source revision; deliberately stale drafts are rejected. A queued toy-generation check also refuses its revoked callback.

These checks are not a complete replacement for production integration. No general source-to-rendered-region identity, full structural diff, full-document pixel comparison, screen-reader verification or candidate-specific Plain/Vim/Emacs/IME routing was built. Standard scroll-ratio and disabled-sync formulas pass on completed C panes; actual application notification-geometry, caret/source synchronization, split/buffer-switch/disposal tests run unchanged separately. Candidate hyperlinked components were not integrated into MarkdownPreviewPane's listener/focus ownership. Out-of-order multi-worker rendering, multiple concurrent preview sessions and closed-pane worker retention are not qualified by this single-lane experiment.

Source-only editing actions are intentional and undone. Preview tasks separately assert no revision/snapshot identity/dirty/saved-baseline/undo-availability change before their queued input callback, and phase-end immutable snapshots equal original source. Input callbacks are tagged separately from preview callbacks in EventQueue records; their combined undo/redo/navigation activity must not be attributed to HTML layout. Deferred ambient event contexts are associations, not causal proofs.

C is **progressive, not viewport-memory-bounded**. It still materializes every paragraph and retains a full staging component/tree alongside the displayed one. It also retains HTML/fragments temporarily. Corrected prose used approximately 29.5 MiB heap before preview, 53.1 MiB with the full stock preview, 60.3 MiB after candidates and 41.9 MiB after disposal; composite readings were 29.0 / 69.9 / 76.8 / 58.8 MiB. Requested-GC snapshots are outside timing and are not a retained-heap/dominator or stable leak census; main-method reference lifetimes can affect them. RSS is whole-process sampled peak, not heap or kernel high-water mark. No memory-win claim is made.

Rich local-image pixels differ after changing the file at the same URI and comparing fresh installation with retained state. This validates the warning against an unconditional identical-HTML early return. Rendering, assets and security policy are unchanged. Mermaid/math preparation is off EDT only inside the opt-in owned-data experiment; no production async rendering was shipped or claimed.

## F. Architectural recommendation

Do not productionize these experiments as the complete 1C pipeline. Retain JEditorPane for existing preview compatibility while documenting the limitation; plan the general large-document presentation decision alongside the source-preserving live Markdown presentation. The evidence does **not** justify rewriting Shed, changing Java/Swing, or immediately importing a browser.

| Alternative | Complexity / maintenance | Latency and memory | Mapping, rich content, accessibility / platform implications |
| --- | --- | --- | --- |
| Explicit bounded JEditorPane workload | Lowest engineering cost, but needs an approved product policy, background rendering and defensible structural limits. No safe byte-only cutoff was established. | Small/simple content can be fast; even 100 KiB resize had a >100 ms instrumented round-trip. Source responsiveness cannot be promised if an admitted block still stalls. | Preserves existing HTML/CSS, native Swing links/accessible text and platforms. Restrictions must be explicit; no such policy is installed here. Limited reuse for a large live editor. |
| Shared virtualized Swing Markdown presentation | Highest initial engineering/accessibility cost; avoids patching around an entire general HTML layout engine. Planning estimate: multi-week prototype, substantially longer production qualification. | Viewport/bounded-block work and model memory can be designed explicitly; gains are unmeasured until prototyped. | Share revisioned semantics, source ranges, typography/measurement, tables and media leases with live editing. Use separate readonly/caret-aware projection policies. Selection, screen readers, focus, tables and pixel compatibility need dedicated work. Cross-platform Swing remains. |
| External component, e.g. JavaFX WebView/JCEF | Additional runtime, threading, packaging/security-update and integration obligations. No dependency selected or installed. | Potential mature HTML layout, but startup/RSS/large-doc latency on Shed are unmeasured. No automatic low-memory advantage is assumed. | HTML/CSS/resource/network restrictions, anchors, keyboard/focus and accessibility require requalification. Does not automatically solve source-preserving live editing or map DOM back to source. Visual identity may change unless validated. |

JavaFX has a supported Swing bridge with explicit EDT/FX-thread rules; WebView itself belongs on the FX thread. That is interoperability evidence, not a measured Shed recommendation. [JFXPanel documentation](https://openjfx.io/javadoc/21/javafx.swing/javafx/embed/swing/JFXPanel.html), [WebView documentation](https://openjfx.io/javadoc/21/javafx.web/javafx/scene/web/WebView.html). JCEF embeds Chromium and includes native integration/build work; a deployment would need a separate footprint/security/licensing assessment. [JCEF project](https://github.com/chromiumembedded/java-cef).

A staged Swing approach remains a possible intermediate design, but a production version must address large-child granularity, global width/resize reflow, parser copying, old+staging memory, handler ownership and cancellation together. That is materially larger than wiring an executor around the current renderer. Do not maintain a separate ad hoc Markdown-to-DOM diff engine if the semantic roadmap can provide those identities once.

## G. Roadmap integration

This study completes the bounded feasibility investigation, **not Tranche 1C acceptance**. Carry the parser memory defect as a small correctness/resource fix. Record stock preview layout as an outstanding limitation. General bounded presentation should be a separately reviewed presentation-engine tranche coordinated with planned Tranche 4, rather than an implicit preview rewrite inside 1C.

A common semantic/asset/typography layer can serve both preview and live editing. Sharing one component wholesale is not established: caret-aware source syntax visibility, edit positions and readonly layout differ. Require an explicit two-projection design and ownership contract before funding another substantial renderer. The existing FileBuffer/document/undo/snapshot remains authoritative. Independent roadmap tasks may proceed only with the preview limitation explicitly carried; do not mark 1C complete to unblock them.

## H. Precise smallest next task — bounded HTML-reader copy correctness

**Proposed, not implemented:** investigate and repair the verified PRE array-copy amplification through a narrow preview HTMLDocument/HTMLEditorKit factory, preserving the existing UI and rendering. This is an independent resource/correctness task; it will not solve global EDT layout. Planning estimate: 2–4 engineering days including differential and packaged verification, subject to the public/protected API viability check.

1. In an isolated positive-control test, subclass HTMLDocument.HTMLReader and override its protected `addContent` slice path. When the requested range is smaller than the supplied array, give the parent a bounded exact range copy with offset zero. Retain parent attributes/element specs/parser normalization; do not split semantic text or bypass sanitization. Confirm the actual installed JDK call path and resource behavior first.
2. If supported and exact, introduce only a preview-specific factory, e.g. `MarkdownPreviewHtmlKit`, used where MarkdownPreviewPane currently initializes its text/html kit. Preserve the same stylesheet chain, parser, document properties, asynchronous-load metadata and HONOR_DISPLAY_PROPERTIES. Do not change source documents or unrelated notebook/UI kits.
3. Validate both relevant getReader overloads. Internal HTMLDocument incremental APIs can use a package-private reader path that may bypass an override; **do not assume** the fix covers setInnerHTML/insertBeforeEnd. The immediate production path is full EditorKit.read/setText. Stop rather than adding reflection/module opens or copying a large JDK renderer.
4. Differential tests: prose, multiline/empty/EOF code, mixed inline styles, Unicode/surrogates, nested lists, tables, headings/IDs, tasks, math/Mermaid PNGs and local images. Compare text, element attributes/offsets, exact settled geometry/crops and links against stock on smaller safe controls. Preserve explicit refresh, images changing at the same URI, scroll/notification geometry and source state.
5. Large acceptance: the recorded 1 MiB composite containing the ~242,000-character (~236 KiB) / 2,000-line PRE must parse in a bounded **512 MiB child** without OOM, exact content and metadata retained. Record allocation/heap/GC and parse duration; proposed guardrails are ≤128 MiB additional used heap after requested GC and ≤10 s parser completion on this host, clearly new test budgets. Do not raise the heap, catch OOME as validation, truncate content or relax output assertions. Restore the experiment only after a positive small-case differential control demonstrates correctness.
6. Run existing preview/0A/0B/0B.1/1B/persistence/recovery tests, full Maven and packaged Mermaid smoke. Keep heavy reproduction opt-in. Preserve README and UI. If the protected hook cannot preserve semantics or initial parser/copy attribution is contradicted, stop with that negative evidence.

After this independent task, decide whether to fund a shared presentation prototype. Do not begin an unreviewed large HTML-layout rewrite, semantic engine or new renderer as a side effect.

## I. Repository verification

Only these additions belong to this tranche:

- `src/benchmark/java/shed/SwingHtmlFeasibilityProbe.java`: three isolated experiments, real queued source actions, exact checks, actual dispatch timing and bounded negative-stop/ownership guards. Not compiled into the normal application.
- `scripts/benchmark-preview-layout.py`: opt-in isolated-child wrapper reusing existing deterministic fixtures/environment/statistics, frozen probe copies, heap/watchdog limits and explicit completion/error/resource states.
- This report and `docs/performance/markdown-tranche1c1/`: methodology, raw samples, diagnostics, profile, hashes and regression records.

Every initially tracked file is byte-for-byte unchanged, including 1C's one-render constructor correction, SourcePlainView, previous reports/tests and README. The starting tree was clean. No production or shared test infrastructure changed, but the complete suite was nevertheless run:

```sh
JAVA_HOME="$(scripts/resolve-toolchain.sh home)" \
"$(scripts/resolve-toolchain.sh maven)" -o -B -Djava.awt.headless=false -DargLine=-Xmx512m test
python3 scripts/test-packaged-markdown.py --jar target/shed-2.0.0.jar --output /tmp/new-html-packaged.json
python3 -m unittest discover -s scripts -p test_benchmark_markdown.py
```

Results: **1,317 tests run, 1,312 passed, five existing skips, zero failures/errors**; packaged Markdown/Mermaid smoke **PASS**; five tooling tests **PASS**. The same DOS/Fish/CMake/opt-in-AI skips remain. Existing source/split/keymap/LSP/search/save/recovery/preview synchronization and 1B geometry coverage is retained. Candidate-only integration gaps are not disguised by these unchanged production tests.

README SHA-256 before/after: **`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`**. `git diff --check` passes. The measured JAR is unchanged (`8ff401830509ef3f1f13924a7dde18ba58996f83ccd0f10c8eb6d292cbe401da`). Source identity/hash checks are recorded. One incidental Python bytecode regeneration was restored to its verified clean baseline and future wrapper imports disable bytecode writes. No user changes were discarded, dependencies installed, settings changed, commits or pushes created.

The UI/design system, typography/themes, navigation and editor behavior are unchanged in normal execution. Diagnostic components exist only in independently launched child processes. Children/windows have exited; external fixtures/logs/JFR are intentionally retained evidence, not active workers or runtime instrumentation. No system-wide memory exhaustion, swap manipulation or unrelated process termination was performed. Other operating systems/accessibility/native installers remain unqualified.

## J. Final decision

**NO-GO for shipping these stock-HTML approaches as complete 1C. CONDITIONAL GO for further shared presentation research, with staged prose construction as a proven building block.** The study demonstrates that ordinary 1 MiB construction can be made responsive without changing sampled output; it also demonstrates why that is insufficient for the required large blocks, resizing, lifecycle and memory guarantees.

Do not rewrite Shed or assume a different language/framework solves this. Preserve the current UI and source engine, address the small verified reader-memory defect separately, and make the shared-presentation decision explicitly. No 1D, Tranche 2 or broader implementation was begun.
