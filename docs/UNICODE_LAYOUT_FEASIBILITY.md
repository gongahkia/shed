# Tranche 7B.4 — bounded Unicode source-layout feasibility

## A. Root cause

**NO-GO for production integration of the evaluated correction.** A public-API, paragraph-range experiment removes repeated whole-document break reads and substantially reduces allocation, but the representative first edits still take seconds. No production source, default appearance, source ownership or runtime configuration was changed. This is a failed remediation gate with useful attribution, not a completed Unicode performance fix.

The unchanged production artifact is SHA-256 `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`, frozen before experimentation. [7B.3](UNICODE_LAYOUT_INVESTIGATION.md), the actual installed OpenJDK 21 source and new paired controls establish distinct mechanisms:

1. **Document-wide break reads:** `BasicTextAreaUI` creates `BoxView` / private `PlainParagraph` views for an i18n `PlainDocument`. `ParagraphView.calculateMinorAxisRequirements → GlyphView.getMinimumSpan → getBreakSpot` requests the leaf's parent range. With ordinary plain-document elements that parent is the document root. `GapContent.getChars` copies a range spanning the gap, producing approximately paragraph-count × document-length work during a full pass.
2. **First-update reconstruction:** private `BasicTextUI.UpdateHandler.i18nView` starts false on attachment. First insertion into a preloaded i18n document calls `modelChanged` again. For an ASCII-to-complex transition, however, at least one construction of the complex hierarchy is still required by the stock factory. Merely suppressing a redundant preloaded-document rebuild would not address this second counterexample.
3. **Shaping and measurement:** hierarchy construction performs `TextLayoutStrategy.sync → LineBreakMeasurer/TextMeasurer → SunLayoutEngine`. Minor-axis measurement also passes through `ParagraphView.findEdgeSpan → GlyphView.getBreakWeight → GlyphPainter1.getBoundedPosition → Utilities.getTabbedTextOffset → SwingUtilities2.getFontCharWidth → Font.getStringBounds/TextLayout → SunLayoutEngine`. Long Unicode lines remain particularly expensive. Font metrics, fallback, glyph outlines and shaping are substantive costs, not just source-copy symptoms.
4. **Synchronous geometry:** normal caret listeners call Shed's `ensureCaretVisible → modelToView2D`. That can demand layout before the insertion returns. The expensive first-edit work therefore appears inside the normal EDT input operation. Queued repaint and component paint occur afterward. Background highlighting is not the cause of the independently reproduced stock-view break reads.

Shed's `SourcePlainView` remains the optimized tab-free ASCII path. It intentionally does not replace the complex-text hierarchy. The authoritative `FileBuffer`, `PlainDocument`, immutable snapshots, normal listeners and key routing remain unchanged in every workbench child. No CommonMark parse is added to typing.

## B. Candidate comparison

Exactly three approaches were evaluated; only A was prototyped. B/C were rejected at the public-API/scope assessment, rather than assigned invented speedups.

| Candidate | Confirmed work and proposed change | Complexity and compatibility | Measurement and verdict |
| --- | --- | --- | --- |
| **A — range-bounded access** | Give the existing JDK paragraph renderer a view-local parent range instead of the document root for break analysis. | Break-input reads fall from roughly O(P×N) to paragraph-local reads across a pass; existing shaping/layout still occurs. One small leaf adapter per paragraph, temporary edge-context objects, no source string/cache. Canonical elements/document are untouched. Compatibility depends on factory/event/edge-break behavior and requires differential qualification. | Prototyped. Zero full-document break reads in the multi-paragraph counting fixture, large allocation reduction, tested stock geometry/pixels agree. But first edits remain 2.25–5.19 s. **NO-GO for production.** |
| **B — narrower invalidation** | Avoid the duplicate preloaded-i18n first-update rebuild, or retain unaffected views across the global i18n transition. | The state and event handling are private JDK implementation details. A public `modelChanged` override has no insertion event with which to update a reused tree. Skipping it blindly leaves stale geometry. Retaining plain views during a mandatory complex-view transition requires a substantially different per-paragraph factory/extent policy. | Not prototyped; no safe narrow interception was established. A still has a 2.25 s mandatory ASCII-to-complex construction after its copies are removed. No predicted B latency is claimed. **NO-GO within this tranche's restrictions.** |
| **C — bounded glyph/font measurement** | Reuse valid unchanged measurements or avoid discarded minimum-span/edge work. | The existing JDK views already retain paragraph measurements; reconstruction loses that state. The private paragraph implementation calculates minimum requirements even when unwrapped policy subsequently chooses preferred width. A wrapper cannot simply replace its internal calls. Another paragraph/glyph cache would duplicate retained data and need invalidation across source/font/wrap/IME changes. Eager fallback warming shifts costs to startup without addressing the demonstrated reconstruction. | Not prototyped. Candidate A's remaining first-edit profile contains shaping in 144/153 EDT samples. No redundant public measurement seam with proven compatible replacement was found. **NO-GO for a local cache/measurement patch; not a claim that all future Swing changes are impossible.** |

P is paragraph count, N source length. These are bounds on the identified range-access work, not a proof of total layout complexity. Single extremely long paragraphs can themselves span almost N units; A does not make their shaping constant-time. No B/C optimization was combined with A.

## C. Isolated prototype and method

`ParagraphRangePrototype` exists only under `src/test`. It extends the established workbench text-area UI and delegates to the same JDK views. For marked source panes with ordinary i18n plain-document leaves, it supplies a transparent leaf `Element` adapter. Its view-local break-context parent exposes the preceding LF, the actual paragraph and one following code point. UTF-16 offsets and canonical document attributes delegate to the original element. The small following-code-point read does not split a surrogate pair. Unsupported factory shapes retain the inherited path; stale context lookup falls back to the original end limit.

This is deliberately an experiment in the public `Element`/view-factory seam. It is not a replacement document, parser, renderer or copied JDK paragraph implementation. It nevertheless changes element metadata seen by JDK helpers; passing the bounded tests does not prove every IME, locale or future JDK interpretation equivalent. It adds O(P) leaf wrappers to the already O(P) stock hierarchy, not another complete source representation. No glyph/layout cache or permanent worker is added.

The opt-in workbench probe selects stock or candidate factories in isolated JVMs before file installation. A test-only `UIDefaults` listener keeps the requested factory installed across workbench theme initialization; it is removed during cleanup. Only marked source paragraphs are adapted. Production defaults and user preferences are never edited. Both variants use the unchanged packaged application and normal source painting, syntax, input listeners and Undo. Every warm insert/Undo cycle checks exact UTF-16 source; the complex-text Swing action is checked with one Undo too.

Environment: Apple M3 / Mac15,12, 16 GiB, macOS 26.5.2, Temurin 21.0.13+9 arm64, Retina desktop. All workbench children use `-Xms64m -Xmx512m`, explicit Shed Light, bundled JetBrains Mono 16, isolated homes, preview closed and zero attached external LSP clients. No font catalogue or filesystem cache is purged. Full commands, hashes, environment/load/pressure observations and samples are in [the evidence](performance/unicode-layout-tranche7b4/INDEX.md).

The main 1 MiB gate uses two fresh-process runs per variant/fixture, ordered stock/candidate then candidate/stock (ABBA), with 100 warm insert and Undo samples per child. First edit and the later first complex-text action into ASCII remain separate samples. One MiB here is UTF-8 file size; the mixed fixture has 735,576 UTF-16 units. Separate 100 KiB combining/Indic/emoji/ZWJ/CJK/bidi controls, 1 MiB wrapped controls and 4 KiB/100 KiB long-line controls cover the remaining requested shapes. Slow cycles stop repetition after 500 ms; children have 75-second watchdogs. No 1 MiB long-line stress expansion was needed after the 100 KiB counterexample.

Pure model controls use `FileBuffer` with its normal revision/Undo listeners and no attached view. Separate instrumented `PlainDocument` controls count requested characters and root-size requests without making those counters part of production latency runs. Requested characters are not all copied bytes: non-gap-spanning `Segment` reads can share backing storage. Exact allocated bytes, sampled allocations, heap and RSS remain separate measures.

## D. Production decision

**NO-GO. No production integration.** A passes the bounded range-read demonstration and tested source/presentation comparisons, but fails the proposed sub-100 ms first-use target and the requirement to avoid multisecond EDT tasks. Memory-release qualification is also incomplete. A substantial allocation reduction is not sufficient remediation. There is no claim of a production speedup, startup improvement or release readiness.

## E. Files and scope

New prototype/tests:

- `src/test/java/shed/ParagraphRangePrototype.java`: isolated public-API range adapter.
- `src/test/java/shed/ParagraphRangePrototypeTest.java`: four deterministic source, differential presentation, read-count and listener/split tests.

New opt-in tooling:

- `src/benchmark/java/shed/UnicodeCandidateProbe.java`: matched workbench input/paint, independent model/read controls, heap/disposal observations and bounded EDT-task recorder.
- `src/benchmark/java/shed/UnicodeCandidateJfrSummary.java`: streaming first-edit profile summaries; 256 MiB diagnostic JVM, recording-size and distinct-stack bounds.
- `scripts/benchmark-unicode-candidate.py`: deterministic paired serial child runner, admission/watchdog/heap bounds, fixture/probe hashes and RSS/environment observations.
- `scripts/test_unicode_candidate_tooling.py`: three quick runner/fixture admission tests; no desktop or JVM benchmark in these tests.

Documentation: this report and every evidence path in [inventory.json](performance/unicode-layout-tranche7b4/inventory.json). No pre-existing file, production source, dependency, JVM option in the application, previous evidence, UI setting or keybinding is changed. Prototype classes are absent from the packaged JAR. All earlier uncommitted work is preserved.

## F. Correctness

Four new deterministic tests pass:

1. Exact preferred sizes, **every UTF-16 caret position**, sampled mouse hits and selected visible pixels against stock across wrapping, JetBrains Mono/Dialog, combining characters, Indic conjuncts, emoji/ZWJ, CJK, bidi, tabs, BOM/CRLF and a long line; repeat after insert/removal.
2. Seeded grapheme-boundary edits, exact Undo/Redo and document switching against stock. The authoritative document is shared, not copied for presentation.
3. A discriminating read-count counterexample: stock first update performs hundreds of whole-root reads; the candidate performs zero, while still explicitly doing initial layout. Warm candidate local reads stay bounded in this fixture.
4. Five pane construction/detachment cycles with shared source and independent caret state, font/tab changes, exact stock presentation and listener counts returning to baseline. Presentation alone emits zero document events, leaves source revision and snapshot identity unchanged, leaves dirty false and creates no Undo entry.

The complete suite retains 7B.1 GB9c/forward-delete Undo, existing grapheme/selection/keymap tests, 7B.2 ownership tests, Tranche 1B geometry/longest-line handling, 1D highlighting, Markdown commands/navigation, media, save/reopen and recovery. Workbench probes validate source and Undo, not every native input pathway. Native IME composition, complete bidi mouse/selection permutations, Windows/Linux headful behavior and arbitrary Unicode line-break locales are not newly certified. The 51 remaining conformance mismatches are unchanged and not blamed for layout.

## G. Performance, allocation, memory and lifecycle

Values below are completed Swing component paint, not display scanout. First-use figures are two individual fresh-process observations per variant, not a launch p95. Warm p95/p99 are nearest-rank observations within each 100-sample child; do not pool different operations or hide first use in warmup.

| 1 MiB unwrapped gate | Stock, run 0 / run 1 | Candidate A, run 0 / run 1 |
| --- | ---: | ---: |
| First edit in already mixed-script source | 6,539.57 / 6,387.83 ms | **5,135.19 / 5,188.63 ms** |
| First complex-text action into ASCII source | 4,614.82 / 4,443.24 ms | **2,250.65 / 2,271.28 ms** |
| Warm ASCII insert p50 | 0.747 / 0.734 ms | 0.826 / 0.838 ms |
| Warm ASCII insert p95 | 1.357 / 1.270 ms | 1.397 / 1.452 ms |
| Warm ASCII insert p99 | 1.833 / 1.694 ms | 2.192 / 1.738 ms |
| Warm mixed-script insert p50 | 2.669 / 2.737 ms | 2.255 / 2.492 ms |
| Warm mixed-script insert p95 | 8.162 / 7.302 ms | 5.768 / 6.238 ms |
| Warm mixed-script insert p99 | 11.456 / 9.287 ms | 6.826 / 10.106 ms |

Warm ordinary samples pass 16 ms; the first-use gate fails decisively. Small ASCII differences do not establish statistical non-inferiority on other hosts. Wrapped first complex insertion still took 4,781.63 / 2,532.03 ms stock/candidate. Wrapped mixed first edit took 6,995.62 / 5,563.10 ms. The wrap setting is active before the measured first edit, although initial asynchronous file attachment can precede that setting; source-opening time is not relabeled as a pure wrapped startup metric.

At 100 KiB on one long Unicode line, first edit took 3,077.53 / 2,867.79 ms, and the guarded subsequent insertion took 2,892.47 / 2,874.93 ms stock/candidate. At 4 KiB long-line warm p95 was 45.96 / 52.48 ms. These are failures of a universal responsive-editing claim. The detailed script, Undo, queue, EDT CPU, paint and allocation rows are preserved in [measurements.json](performance/unicode-layout-tranche7b4/measurements.json).

The 1 MiB mixed first-edit counting control changes from **15,652 root reads / 11,520,372,278 requested characters** to **zero root reads / 7,903,603 requested characters**. Warm reads change from 736,118 to 591 characters. The first-update hierarchy construction and shaping are deliberately unchanged. First-edit EDT allocations change from approximately **26.115 GB** to **3.087 GB** (decimal, cumulative thread allocation). The remaining three gigabytes are not retained heap.

The no-view model-only first insertion was 3.51 ms ASCII / 1.52 ms mixed, including its own first-use initialization and normal FileBuffer/Undo listeners. These individual controls are not a pure hardware store-time measurement, but separate document work from the multisecond view path. Workbench first-insert wall time includes synchronous model listeners/layout; it is not labeled pure mutation. Hot preferred-size and forced-paint probes are also reported separately and must not be interpreted as the total initial layout cost. Source-ready markers include decoding, asynchronous install, layout and painting; no isolated pure-initial-layout stopwatch is claimed.

**Residual profile:** in the candidate's separately profiled mixed first-edit window, 144 of 153 EDT execution/native samples contain `SunLayoutEngine`, 103 contain `ParagraphView.findEdgeSpan`, and 34 contain `TextLayoutStrategy.sync` / first-update reconstruction (overlapping groups). Control first-edit weighted allocation samples include approximately 23.07 GB at the full-range `GapContent` break-read path; candidate leading stacks instead include glyph-outline arrays, `AffineTransform` and glyph metrics. Complete relevant stacks and exact phase windows are preserved. Samples are not CPU percentages or exact retained-byte attribution. JFR overhead is material: profiled first edits were 15.67 s stock / 6.46 s candidate; these are **not** substituted for the uninstrumented gate above.

**EDT tasks:** a follow-up paired 1 MiB control records uninterrupted dispatch maxima of 4,478.35 / 2,520.48 ms for ASCII plus its complex-text transition and 7,834.76 / 5,442.28 ms for mixed source. Those maxima cover the observed child lifecycle, not only one operation. Initial task arrays were serialized incorrectly by the diagnostic encoder; their raw string values are preserved and explicitly unavailable as numeric metrics. The probe was corrected to emit lists and this follow-up was rerun. Input/paint/allocation measurements in earlier runs were unaffected.

**Memory:** requested-GC used heap after the gate's edits was 212.29–212.33 MiB stock / 212.51–212.55 MiB candidate for the ASCII-to-complex children; mixed used heap was 365.50–365.61 / 339.69–365.16 MiB. Committed heap reached 512 MiB in both. Sampled peaks were approximately 1,164.56–1,177.75 MiB stock / 1,170.13–1,188.86 MiB candidate across the main gate. This does not demonstrate an RSS improvement or memory equivalence. Requested-GC phase heap is not a full retained-size census; OS/native/font costs remain unattributed here. The adapter introduces paragraph-dependent metadata, not a second source string or glyph cache.

**Disposal limitation:** the short workbench probe frequently still observed retired buffer/document and disposed editor/UI weak references alive in **both** variants; one follow-up stock mixed child collected its disposed graph. These bounded observations do not establish a retaining path, an adapter-specific leak, or successful full resource-release qualification. They must not be used to overturn 7B.2's differently synchronized buffer-cycle evidence. Deterministic five-cycle listener/source detachment tests pass, but they do not replace a full reachability investigation. This memory/lifecycle gate remains unqualified; no out-of-scope cleanup was implemented.

There is no production startup change. Fresh processes are not cold-cache launches. Profile summaries now stream recordings within explicit memory bounds; initial expanded JFR JSON exports were unnecessarily large and were replaced by streaming summaries after all timed runs. Original recordings and their identities remain intact. Diagnostic export/summary overhead is not included in the application's measured RSS samples.

## H. Regression verification

Final Maven: **1,565 run, 1,560 passed, five existing skips, zero failures/errors**. Focused tests: **46 passed**. Tooling: **25 passed** (22 existing plus three new). Packaged Mermaid/math/image smoke: **PASS**. All opt-in children completed their protocol; that status does not mean their latency passed. Exact logs, class totals, skip reasons, script commands and artifact/source identities are in [verification.json](performance/unicode-layout-tranche7b4/verification.json) and compressed diagnostics.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
"$MVN" -Dtest='ParagraphRangePrototypeTest,SourcePlainViewTest,GraphemeBoundaryTest,IndicConjunctBoundaryTest,GraphemeEditRangeTest,UnicodeEditingRegressionTest,AsyncJobPayloadLifecycleTest,FileWatcherServiceTest,PlainKeymapTest,EmacsKeymapTest,DocumentLifecycleTest' test
"$MVN" test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche7b4-run/before.jar --output /tmp/shed-tranche7b4-run/packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

No existing assertion was weakened. Large timed workloads remain opt-in; routine tests use small deterministic fixtures and read counts. Streaming profile tooling was separately compiled and exercised against the recorded first-edit windows.

## I. UI/UX compliance

Production design, layout, source editor, preview, typography, glyph renderer, fonts, syntax colors, keymaps, selection/navigation, splits, themes and existing preferences remain unchanged. Shed Light and bundled JetBrains Mono remain the defaults. No setting, panel, dependency, service, worker or production cache is added. The test-only factory selection exists only in disposable diagnostic processes. No appearance feature, 7C, HTML-preview correction or 4A implementation was begun.

## J. README and repository compliance

README SHA-256 before and after: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. README is byte-for-byte unchanged. Starting HEAD is `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`. The baseline inventory preserves all existing uncommitted 7B.2/7B.3 work and prior evidence. Final file-hash comparison and added-file inventory accompany the evidence. No reset, clean, revert, dependency upgrade, commit or push was performed.

## K. Remaining limitations

- P1 first-edit / ASCII-to-complex source-layout stalls remain; long-line warm editing also fails.
- The experiment's view-local parent metadata is tied to tested JDK factory behavior. It is not native IME, arbitrary locale, accessibility, future JDK or cross-platform qualification.
- The 51 grapheme corpus mismatches and existing long-prefix boundary traversal remain separate from layout. No Unicode behavior was reduced.
- Retained-resource release in this short workbench observation is inconclusive, and whole-process/native/font attribution is unresolved.
- Stock HTML preview/reflow latency remains P1; Tranche 1C and production live-presentation NO-GO remain unchanged.
- No claim is made that one unsuccessful bounded experiment proves every Java/Swing architecture incapable of meeting the target.

## L. Next recommendation and bounded architectural alternative

**Do not begin unconditional 7C or appearance work.** The source Unicode P1 is not remediated. Further unreviewed local patches or combining caches/invalidation tricks are not justified by these results.

The next useful step is a separate source-view architecture decision, rather than another optimization campaign. First check whether a maintained, compatible JDK implementation addresses these exact call paths; no runtime migration or vendored patch is authorized here. If not, the bounded alternative for review is **paragraph-local, viewport-lazy source views with exact extent bookkeeping**, retaining `JTextArea`, `PlainDocument`, FileBuffer, native input, existing styles and source offsets. It would need to avoid constructing/shaping all unaffected paragraphs at the global i18n transition, preserve exact glyph/caret/scroll geometry without guessed widths/heights, and release paragraph state deterministically. This is a deeper source-view architecture change, not a small extension of A, not the 4A Markdown typography prototype and not an approved implementation.

Any future experiment should initially cover only the same 1 MiB ASCII/mixed transition with bounded viewport/paragraph state, exact stock pixels/geometry, source/Undo/IME/split tests, first edit under 100 ms, warm p95 under 16 ms and independently qualified disposal/native memory. Stop if exact offscreen extents require an unbounded second layout/source cache, glyph semantics change, or a new editor engine is necessary. Product operating-limit decisions would require separate approval; no supported capability or benchmark target was silently restricted here. The preview P1 also remains independently blocking for an unrestricted responsiveness claim.
