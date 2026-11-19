# Tranche 7B.3 — Unicode source-layout investigation

## A. Root-cause verdict

**Production is affected. No production correction is delivered. The P1 first-edit/layout blocker remains open.** The failure is not confined to the rejected 4A presentation prototype, and warm typing percentiles do not qualify first-use responsiveness. The current packaged source editor reproduces multi-second stalls with the established JetBrains Mono / Shed Light defaults, preview closed, and no experimental presentation installed.

The investigation established two stock-Swing mechanisms: repeated document-wide reads during paragraph minimum-span calculation, and a redundant complex-text hierarchy reconstruction on the first insert after attaching an already internationalized document. Long Unicode lines additionally incur expensive glyph measurement. No safe correction through the existing public view seam was demonstrated. Replacing private Swing paragraph internals, disabling international layout, altering fonts/metrics or adding a source-copy cache would exceed this tranche's correction gate. No such workaround was installed.

4A independently failed its memory and continuous-presentation gates. Its report already recorded a slow stock-source control. These findings neither rehabilitate that prototype nor attribute its additional heap cost to production. The prototype remains isolated and production migration remains NO-GO.

## B. Reproduction and methodology

The [evidence index](performance/unicode-layout-tranche7b3/INDEX.md) provides complete commands, fixture and source hashes, raw samples, logs, environment, profile summaries and verification. The artifact is the frozen final-7B.2 packaged JAR, SHA-256 `6ee56710a5c6961860d8533bd916f9deef4ca445df71dadf5a9fa550c12bf3ee`. No production edits separate these controls; there is no before/after optimization claim.

Host: Apple M3, Mac15,12, 16 GiB physical RAM, macOS 26.5.2, Retina 2× display, Temurin 21.0.13+9 arm64. Children use `-Xms64m -Xmx512m`, isolated scratch homes, explicit Shed Light and the default bundled JetBrains Mono 16. Dialog controls use only a scratch saved font override. Preview stays closed; no external LSP is attached. Font/filesystem caches were not purged. Host load, memory pressure and swap are recorded per original reproduction and around each matrix invocation; this is not a quiet-host or cross-platform certification.

The unchanged `qualify-markdown.py --group unicode` first reran the original 100 KiB / 1 MiB fixture and a separately profiled 100 KiB child. It uses the established production startup/key path and exact UTF-16 source/Undo assertions. The original generic UI harness's UTF-8-byte versus UTF-16-length assertion is not used for Unicode and was not weakened.

The new opt-in probe then covers ASCII, accented Latin, combining marks, emoji, ZWJ emoji, Indic conjuncts, CJK, mixed scripts, Arabic/Hebrew bidi and one long Unicode line, at 4 KiB and 100 KiB. ASCII/mixed controls expand to 1 MiB. Separate 100 KiB controls cover wrapping, saved Dialog font and JFR. Fixtures retain complete UTF-8 pattern blocks, pad with ASCII and end in LF; manifests record byte size, UTF-16 units and longest physical line. The established 7A fixture is deliberately kept separate from these new category fixtures.

Each normal matrix child attempts 20 warm insert/Undo/Redo/Backspace/newline/Delete cycles, validates exact source after round trips, then invokes a multi-character Unicode Swing text-input action and checks Undo. Caret/selection actions have separate forced-paint observations. The original startup probe measures its first controller/native-action insertion separately before these cycles. The Unicode text-action sample is a synthetic Swing input action, not native IME delivery.

One initial 100 KiB single-line child exceeded the 90-second watchdog while repeating multi-second operations. Its partial results and termination remain failed evidence. The final probe stops repetition after a completed cycle exceeds 500 ms; the separately labeled rerun retains that guard and does not manufacture a 20-sample percentile. Neither timeout nor guard is a passing responsiveness result.

## C. Profiling attribution

The installed JDK's `lib/src.zip` establishes the following paths; source file hashes and representative complete stacks are retained in the evidence.

1. `BasicTextAreaUI.create` selects `BoxView` / private `PlainParagraph` when the document's `i18n` property is true. ASCII uses Shed's adapted `PlainView`. `SourcePlainView.adapt` intentionally leaves complex-text and wrapped views unchanged.
2. `PlainParagraph.calculateMinorAxisRequirements` delegates to `ParagraphView`; `GlyphView.getMinimumSpan(X_AXIS)` calls private `getBreakSpot`.
3. `GlyphView.getBreakSpot` reads its element's **parent** range. In this plain-document hierarchy the leaf is a paragraph and its parent is the document root. Every paragraph therefore requests the entire source, including the Swing sentinel. `GapContent.getChars` allocates/copies a full-range `char[]` if the range crosses the gap. With P paragraphs and N source units, a hierarchy pass can request roughly P×N units. This is transient allocation, not an assertion of P retained source documents.
4. `BasicTextUI.UpdateHandler.i18nView` starts false and is reset on document replacement. Attachment/font rebuilds do not synchronize it. First insert into a pre-populated i18n document compares true against false and invokes `modelChanged()` again. Subsequent inserts update existing views. The stock control counts this actual extra factory invocation even though the document was already i18n before insertion.
5. Font changes also rebuild the stock hierarchy. Shaping, glyph fallback and `TextLayoutStrategy.sync` are real additional costs. The separately sampled long-line stack passes through `Font.getStringBounds`, `SwingUtilities2.getFontCharWidth`, `Utilities.getTabbedTextOffset`, `GlyphPainter1.getBoundedPosition` and `ParagraphView.findEdgeSpan`. A single stack identifies active work, not its exact share of the full interval.

The production 100 KiB JFR reproduction sampled about **1.19 billion weighted allocated bytes** at `GapContent.getChars → AbstractDocument.getText → GlyphView.getText → getBreakSpot → getMinimumSpan`. It also sampled font outlines, glyph shaping and initial font registration. Weighted JFR samples are estimates; they are separate from exact current-thread allocation counters. Highlight/snapshot worker activity does not explain the demonstrated repeated root reads or the duplicate root construction in a stock-only child without Shed's listeners.

The instrumented stock control keeps the ordinary `PlainDocument`, `BasicTextAreaUI` and bundled font. At 100 KiB mixed script it observed **1,530 root reads** on attachment, **1,530 on first insert**, **one on warm insert**, and **1,530 after a diagnostic font change**. Corresponding requested characters were about 110.7 million per full pass. First insert constructed one new root; warm insert constructed zero. ASCII constructed no root on either insert and made no full-document break reads. StackWalker/counting overhead makes these controls causal evidence, not competitive timing measurements. Array-shape observations corroborate copying; the JDK source and production allocation stacks establish the copy mechanism.

These mechanisms do not involve GB9c or the 51 outstanding grapheme corpus mismatches. No evidence connects those mismatches to the measured layout stalls.

## D. Changes made and correction gate

New files only:

- `src/benchmark/java/shed/UnicodeLayoutProbe.java`: isolated production action observations, exact source/Undo checks, and a separate stock-Swing read/factory-count control. No application installation or production instrumentation.
- `scripts/investigate-unicode-layout.py`: serial opt-in runner, deterministic bounded fixtures, isolated preferences, 90-second child timeout, heap bounds, RSS/environment collection and provenance.
- `scripts/test_unicode_layout_tooling.py`: fast admission, output protection and UTF-8/UTF-16 fixture tests; launches no GUI/JVM.
- This report and every evidence file listed in the [inventory](performance/unicode-layout-tranche7b3/inventory.json).

No production source, Maven configuration, dependency, existing benchmark method, appearance default, Unicode table or previous evidence was changed by 7B.3. Pre-existing 7B.2 production/test/tooling changes remain byte-for-byte as found.

The duplicate-update state and paragraph-break implementation are private JDK internals. Simply reusing a view without forwarding its insert would leave stale layout; bypassing i18n could break bidi/shaping; copying the private paragraph implementation would introduce a substantially different view adapter requiring separate geometry qualification. Caching a full gap-spanning copy adds ownership/invalidation/memory obligations. None is justified as a small verified correction here. This is a limit of the demonstrated safe intervention, not proof that Swing can never be improved.

## E. Performance and memory

All exact operation/phase measurements, allocations and RSS samples appear in [measurements.json](performance/unicode-layout-tranche7b3/measurements.json). First-use values below are individual observations, not p95 estimates across launches. Paint means completed Swing component paint, not monitor scanout. EDT wall time includes the input operation's synchronous listeners/layout; queue and paint time remain separate.

| Unchanged established fixture | First edit EDT / completed paint | Warm insert p50 / p95, completed paint | First edit EDT allocated bytes |
| --- | ---: | ---: | ---: |
| 100 KiB Unicode | 462.42 / 464.04 ms | 2.11 / 3.80 ms, n=30 | 826,559,520 |
| 1 MiB Unicode | 7,143.17 / 7,145.14 ms | 4.45 / 10.37 ms, n=30 | 64,814,329,352 |

The 1 MiB first edit used 6,390.50 ms of EDT CPU; its paint itself took approximately 1.00 ms and initial queue wait 0.64 ms. The bulk of the delay therefore preceded paint rather than being slow monitor output. Visible-window to source-forced-paint was 9.21 seconds; this includes asynchronous file loading and layout and is **not** labeled pure layout time. First source-ready observation and first-edit time are separately retained.

| New category fixture | First edit completed paint | Warm insert p95 | First mixed Unicode text action after ASCII warmup |
| --- | ---: | ---: | ---: |
| 1 MiB ASCII | 5.62 ms | 1.74 ms | 4,854.32 ms |
| 1 MiB mixed scripts | 6,702.79 ms | 7.66 ms | 9.53 ms |

The ASCII-to-complex-text transition is another production counterexample: the document need not initially contain Unicode to encounter a stall later. Accented Latin alone is distinct from combining/complex scripts. The full ten-category table, opening-vs-warm behavior, Delete/Backspace/newline/Undo/Redo and wrapping/Dialog controls are in the evidence. The standard fallback-font experiment does not justify changing the bundled default. The existing opening probe applies the requested wrap setting after its initial startup edit: those first-edit observations must not be described as wrapped-startup measurements. The later ASCII-to-Unicode action occurs with wrapping active and still took 425.38 ms at 100 KiB. The Dialog mixed-script first edit took 1,321.46 ms; changing the font would not resolve this counterexample.

The unchanged ordinary mixed-ASCII source benchmark, with ten warmups and **100 measured inserts across beginning/middle/end**, recorded completed-paint p50/p95 **0.603/0.846 ms at 100 KiB** and **0.826/1.218 ms at 1 MiB**. This meets the representative warm ordinary-typing gate while remaining separate from the failed first-use and long-line gates. The new category matrix's 20 samples per ordinary operation are exploratory controls, not the 100-sample release qualification.

The single-line workload remains a separate failure. Multi-second warm operations and hundreds of megabytes of EDT allocation per operation occurred in the initial 100 KiB child before timeout. The guarded rerun completed one checked cycle: first edit 2,824.27 ms, subsequent insert 2,916.65 ms, Backspace 4,026.69 ms and Delete 4,573.02 ms to completed paint. These are individual observations, not meaningful p95 estimates. They are outside the 16 ms target; the final slow-cycle guard does not make them acceptable. One `jcmd Thread.print` request was made during the original timed-out child and is explicitly an intrusive diagnostic, not an uninstrumented timing control. No 1 MiB long-line stress expansion was attempted.

In the original reproduction, uncollected full-UI heap was **135.44 MiB used / 177 MiB committed** at 100 KiB and **425.70 / 512 MiB** at 1 MiB. Sampled process peaks were **777.73 / 1,149.89 MiB**. The profiled 100 KiB control peaked at 900.97 MiB. These are active, GC-dependent phase/peak observations, not settled idle footprint or retained graph sizes. First-edit allocated bytes are cumulative allocation, not retained heap. The 1 MiB child recorded 1,516 collections by full readiness. No new production cache or retained asset was introduced; this investigation does not certify all native resources or attribute all RSS. 7B.2's closed-buffer reachability results remain independent.

Startup milestones are captured, but there was no production change and no new startup speedup/non-inferiority claim. Fresh process does not mean a cold filesystem/font catalogue. A separate full startup qualification remains necessary. Preview-on interference is unnecessary to establish this source-only counterexample and was not rerun here.

## F. Unicode and editing correctness

Exact UTF-16 source and Undo round trips succeeded in completed normal production controls. The initial long-line timeout is incomplete verification, not source-loss evidence. New action observations cover Plain routing; existing focused/full regressions cover Unicode 15.1 GB9c, character deletion, Plain/Vim/Emacs, selections, split ownership, Undo, save/reopen, BOM/line endings, recovery and stale lifecycle handling. No Unicode behavior or source offsets were changed.

The synthetic multi-character Swing text action is not a native IME test. Native composition, all mouse/bidi hit-testing combinations, screen-reader geometry and Windows/Linux headful behavior remain unqualified. The existing 51 conformance mismatches remain outside this tranche. The new diagnostic fixture checks are not an extended-grapheme conformance suite.

The matrix also logged caught `LineNumberPanel.paintComponent` `BadLocationException` warnings around an EOF scroll after the Unicode text-action/Undo transition. Exact source checks still succeeded. This is a separately untriaged diagnostic warning; it was not hidden, repaired speculatively or promoted to a verified data-corruption defect.

## G. Regression verification

Final full Maven: **1,561 run; 1,556 passed; five existing skips; zero failures/errors**. Focused Unicode/view/lifecycle/keymap tests: **42 passed**. Fast tooling discovery: **22 passed** (19 existing plus three new). Packaged-only Mermaid/math/image smoke: **PASS**. Final `git diff --check`: **PASS**. All 1,372 baseline file hashes, including pre-existing user/tranche changes and earlier evidence, matched; the packaged JAR was also unchanged.

Exact output logs, class totals and every skip reason are in [verification.json](performance/unicode-layout-tranche7b3/verification.json) and the compressed diagnostics. Commands:

```sh
source /tmp/shed-tranche2c-run/toolchain.env
"$MVN" -Dtest='GraphemeBoundaryTest,IndicConjunctBoundaryTest,GraphemeEditRangeTest,UnicodeEditingRegressionTest,SourcePlainViewTest,AsyncJobPayloadLifecycleTest,FileWatcherServiceTest,PlainKeymapTest,EmacsKeymapTest,DocumentLifecycleTest' test
"$MVN" test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche7b3-run/before.jar --output /tmp/shed-tranche7b3-run/packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

Performance probes remain outside routine Maven tests. No heavy workload or renderer initialization is added to the test lifecycle. Packaged-only media smoke is separate from the layout probes. The failed long-line child remains recorded even when source-integrity checks and regression suites pass.

## H. UI/UX and defaults

No production UI or input code changed. Source component/view selection, metrics, fonts, default Shed Light, bundled JetBrains Mono, explicit saved-preference precedence, preview, themes, navigation, keymaps, splits and IDE overlays remain as found. Dialog and 17-point font-change controls are isolated diagnostics, not application-default changes. No new production service, cache, worker, setting or dependency was added. All benchmark controls use scratch documents/preferences.

## I. README and repository safety

Starting and final README SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. README is byte-for-byte unchanged. Starting HEAD: `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`. Baseline file hashes, starting status, final comparison and full added-file inventory are preserved with the evidence. No reset, clean, revert, commit or push was performed.

## J. Remaining limitations

- P1: production complex-text attachment/first edit and ASCII-to-complex transition; very long Unicode lines can also stall subsequent edits.
- P1: existing synchronous stock-HTML preview/reflow input stalls remain independently unresolved; no preview code was touched.
- Existing 51 Unicode conformance mismatches and long-prefix grapheme traversal are not corrected or newly attributed to layout.
- Native/font/process-memory attribution, true low-system-RAM operation, native IME/accessibility and other desktop platforms remain qualification gaps.
- Timings come from bounded synthetic serial children on one host; no natural-session stall frequency or universal latency guarantee is claimed.
- The failed 4A live presentation remains isolated; inline rich media and live typography remain deferred.

## K. Readiness verdict

**Investigation complete; correction gate not met. Production Unicode performance remains blocking for an unrestricted responsive-editor claim.** Ordinary warm sample passes must not override the separately failed first-edit gate. This tranche stops without replacing the editor, changing rendering or pretending a partial workaround is a correction.

## L. Next recommendation

Do not begin unconditional 7C release qualification or appearance work. The smallest useful next decision is a separately approved, time-boxed **stock-Swing compatibility experiment** using this minimal reproducer: determine whether an upstream/maintained JDK correction or narrowly reviewable public-API view adaptation can avoid root-range break scans and synchronize first-update initialization while retaining exact shaping/geometry. Do not upgrade the runtime or copy private Swing implementations automatically. Require paired first-use/ASCII-to-Unicode/warm paints, exact stock geometry/pixels across scripts/wrap/fonts/splits, native IME and bounded memory before considering a production patch. Stop if it requires a new editor engine, changed metrics, source-copy cache or Unicode disablement.

The independent preview P1 still requires the previously requested product decision on operating limits and compatibility; fixing it would not qualify source Unicode layout. Buffer retention was already remediated by 7B.2 and should not be reopened based on active RSS alone. If no bounded Swing correction is viable, document the remaining supported-workload limitation explicitly for a product decision rather than silently weakening the 16 ms/first-use gates. No follow-up tranche is implemented here.
