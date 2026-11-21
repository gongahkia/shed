# Track B1B.2 — Missing-glyph rendering qualification

## A. Executive verdict

**PARTIAL: the missing-glyph finding is reproduced and attributed, but remains unresolved. NO-GO for production integration of the investigated small font-lookup changes.** No production code, font, setting or renderer was changed. This completes the bounded investigation; it does not qualify the default editor for the affected international text or change Shed's general-public-release NO-GO.

The bundled physical-font path produces missing glyph code **0** for B0's affected characters before capture. Both independent Java text layout and Swing source-view painting show placeholders. Same-host logical/system-font controls can display those characters. Re-looking up the registered family and using public Swing `StyleContext` with that family do not restore coverage. Logical fonts change typography and do not fully join the sampled emoji ZWJ sequences; they are diagnostic controls, not accepted substitutes.

[Evidence, commands, corpus and images](performance/ide-trackb1b2/INDEX.md) preserve both successful checks and the failed geometry assertion described below. Passing tests do **not** mean the corpus renders successfully with the default font.

## B. Reproduction and exact fixture

B0's `mixed.md` is reconstructed byte-for-byte from its generator and checked against the historical fixture record:

```java
"# Source alpha\nUnicode: e\u0301, क्ष, 👩\u200d💻, 中文\n".repeat(10)
```

It is **600 UTF-8 bytes / 430 UTF-16 units**, SHA-256:

`2481b5a8bd7b37aad5d78bc374658f14a26d177c15dd367741ae59e0e35f0545`

The exact file is retained as `fixtures/b0-mixed.md`. Original source hashes, native images and diagnostic logs remain intact; no screenshot was used to infer the file's code points.

| First repeated line: affected source | Code points | First UTF-16 positions |
| --- | --- | --- |
| Devanagari conjunct क्ष | U+0915 U+094D U+0937 | 28, 29, 30 |
| Woman technologist 👩‍💻 | U+1F469 U+200D U+1F4BB | 33, 35, 36 |
| Chinese 中文 | U+4E2D U+6587 | 40, 41 |

U+200D is a joining control, not an independently visible missing character. The accent is preserved as **U+0065 U+0301 at offsets 24/25**, despite rendering as a composed-looking accented glyph. There is no source normalization.

B0: two live shared-document panes, JetBrains Mono Regular 16, Shed Light, document `i18n=true`, actual device transform 2×. The inactive pane has no active syntax foreground overlay; the active pane has the Markdown heading overlay and selection 4…12. Both show placeholders in the body. Native window captures were process-owned/PID-and-dimensions selected by the existing CoreGraphics/JNA helper, then `/usr/sbin/screencapture -l WINDOW_ID`. Component captures used scaled `root.paint` into BufferedImage. These historical execution paths were inspected but **not replayed**.

The new independent probe uses the same bundled resource, 16-point plain font, production rendering hints and `WorkbenchStyle.TextAreaUI`/SourcePlainView adaptation in headless Swing. Complex documents select stock BoxView rather than the ASCII-only width adaptation. The exact B0 source again shows the Indic/emoji/CJK placeholders, without a gutter, syntax overlay, real server, application window or desktop capture.

The deterministic 18-fixture corpus covers ASCII; accented Latin; combining marks; punctuation/math; CJK; Hiragana/Katakana; Hangul; Indic GB9c/ZWJ; Bengali; Arabic; Hebrew; mixed bidi; musical/Deseret supplementary characters; emoji/modifiers; emoji ZWJ sequences; variation selectors; mixed scripts; and noncharacter **U+10FFFF** as an unsupported negative control. `corpus.json` records every exact string, UTF-16 length, U+ notation, code-point offsets, expected behavior and UTF-8 hash.

## C. Attribution and candidate decisions

The established path is:

```text
FileBuffer UTF-16 / PlainDocument, source exact
  → WorkbenchStyle.TextAreaUI / BasicTextAreaUI
  → stock BoxView / paragraph-glyph layout for complex text
  → configured BundledEditorFont.atSize(16)
  → Java font glyph mapping / TextLayout
  → missing glyph 0 for uncovered scalars
  → visible placeholders in Swing and direct layout rasters
  → lossless PNG encoding/decoding of those raster pixels
```

`EditorUiController.resolveEditorFont` respects configured family/size. `resolveInstalledFont` routes the canonical bundled family to `BundledEditorFont`, whose existing lazy holder calls `Font.createFont`, registers the resource within the JVM, and derives the physical font. No incorrect preference override or wrong resource was observed. `SourcePlainView.adapt` leaves complex stock views intact. The affected plain body reproduces before any syntax/semantic overlay. Foreground/background changes cannot account for glyph code 0 occurring before drawing.

| Font path, test-only | Measured finding | Production decision |
| --- | --- | --- |
| Existing bundled resource | ASCII, accented/combining Latin and sampled math/punctuation have coverage; affected assigned scripts/emoji map to missing glyph 0 and draw tofu | Confirmed unresolved production-relevant finding |
| `new Font` with registered JetBrains Mono family | Same sampled missing scalars/glyphs and placeholders | **NO-GO**, no benefit |
| Public `new StyleContext().getFont(sameFamily, PLAIN, 16).deriveFont(16f)` | Same sampled missing scalars/glyphs and placeholders | **NO-GO**, no benefit; not integrated |
| Logical Dialog/Monospaced | Restore sampled scalar coverage and visible international text, but change primary typography; sampled ZWJ emoji remain separated in the logical-font output | Reference only; **NO-GO as a replacement default** |
| Resolved installed script-font paths | Positive CJK/Indic/emoji examples, including joined emoji in Apple Color Emoji control; all assigned corpus scalars have a capable reference path on this host | Establish host capability, not a new font policy or universal shaping oracle |

The installed OpenJDK 21 sources were inspected: `StyleContext.getFont`, `FontUtilities.getCompositeFontUIResource`, `CFont`, `CCharToGlyphMapper` and `CCompositeGlyphMapper`. They distinguish physical-font mapping from native cascading/composite mechanisms. The composite helper has a platform-dependent early-return path when the logical font is not a CompositeFont. **[Inference]** Those implementation distinctions explain why a created physical font and this same-family StyleContext lookup do not acquire the native fallback seen in the reference paths. No internal Font2D handles were inspected, no `sun.*` imports/module exports were introduced, and exact per-glyph fallback face identity is not available from these public checks.

Confirmed conclusion: **effective fallback is absent for the affected characters in this configured production font path on this runtime.** This is not merely “the computer has no CJK font.” No source encoding corruption, wrong configured family, appearance-transition dependency or PNG round-trip loss was observed. The bundled resource loads and sampled Latin renders; no additional font-file validation beyond loading and hashes was performed. It is also not proof that every future supported JDK fallback approach is impossible. No maintainable small correction passed the gate here; internal font patching, a new run-layout system or changed defaults were not attempted.

## D. Files changed

**Production: none.** All 407 production Java files remain entry-byte-identical. The bundled asset and current application JAR remain unchanged.

| File | Scope |
| --- | --- |
| `src/test/java/shed/GlyphRenderingQualificationTest.java` | New bounded corpus/coverage/layout/raster records; PNG round-trip checks; exact B0 source, scaled capture, font/palette round-trip, source/split/history checks |
| `src/test/java/shed/GlyphRenderingMeasurementTest.java` | New opt-in first/warm paint, selection, scroll, font/palette transition and disposal diagnostics; no native child/window |
| `src/test/resources/shed/portable-fixture-tests.properties` | Extend 38 to 43 source-reviewed classes: two new classes and unchanged existing BundledEditorFontTest, IndicConjunctBoundaryTest and UnicodeEditingRegressionTest |
| `scripts/run-portable-tests.py` | Explicit `--measure-glyph` opt-in; mutually exclusive with package-only execution |
| `scripts/test_native_fixture.py` | Exact expanded admission set and package/measurement rejection case; retain native exclusions |
| `docs/MISSING_GLYPH_RENDERING_QUALIFICATION.md`, `docs/performance/ide-trackb1b2/` | Report, raw logs/results, source versions, corpus, hashes, images and compliance |

No production settings, preview, font selection, dependencies, native integrations or background services were added.

## E. Rendering and capture correctness

Three independent observations support the attribution: public font capability data, glyph layout codes, and actual raster output. Capability alone is not treated as shaping success. The diagnostic GlyphVector uses LTR flags and is labeled accordingly; actual TextLayout and Swing separately process bidi. Glyph IDs are opaque and are not compared across font families as a semantic glyph oracle.

New images retain visible default-font tofu and positive system-font controls. Installed reference paths can include their own native cascading, so their displayed family names do not prove the primary face itself contains every glyph. U+10FFFF fails all tested font paths and remains a deliberately unsupported noncharacter. Combining marks, joiners and variation selectors may legitimately have no independent ink; they are not classified as missing merely for that reason.

**Capture checks passed:** PNG encoding/decoding is pixel-exact; repeated 1× capture after 2× painting matches; restoring the bundled font and Shed Light after diagnostic font/palette changes restores the initial pixels and sampled geometry; two shared source views produce matching pixels. These are standalone component captures. They do not certify the original full workbench/native capture pipeline, monitor migration or every shaping sequence.

The new bundled-font **FontMetrics height is 22 logical pixels**, matching the historical metric. Complex-text caret rectangles can be **21 pixels** high; that is a different measurement and is not evidence of a native-scaling regression. Actual device configuration, raster context and full component tree are not matched. B0's ~157,000 root-pixel differences and heading overprinting therefore remain **unattributed P2 capture/render-context findings**. Clean standalone heading text without the live syntax overlay does not establish that the old overprinting was purely a capture defect. No syntax painter was modified.

**Geometry limit retained:** a new probe initially required every raw model→view→model result to fall within 0…430 and failed at EOF: requested 430 returned **431**. Additional instrumentation confirmed the exact root-implied sentinel already documented in B1B.1. The raw failure logs remain archived. The characterization now distinguishes that known root endpoint from valid source offsets and rejects any other outlier; actual caret/selection/source assertions remain strict. `raw_hit_test_is_source_offset=false` is retained at EOF. This is not a claim that all raw hit tests are safe or that JDK geometry was corrected. No existing regression assertion was weakened, and the failed assumption is not relabeled as a passing raw-coordinate contract.

The gutter finding is independent: missing source glyphs reproduce without LineNumberPanel, and fixing its mapping does not change font coverage. The gutter's existing range guard remains intact.

## F. Unicode/source and editing integrity

The exact 600-byte B0 fixture matches the historical SHA-256. Across component capture, simulated scaling, diagnostic font changes and palette restoration, FileBuffer snapshot identity, monotonic revision, dirty state, history object and source are unchanged. Directed selection remains 4…12. Shared split ownership is retained; retirement detaches the closed view, and a subsequent edit/Undo/Redo through the survivor restores exact source without resurrecting the retired document.

Existing admitted Unicode tests verify all seven pinned GB9c cases, full Indic-property lookup, grapheme ranges and exact Unicode deletion/Undo/save/reopen, including BOM and CRLF. Existing safe recovery, persistence, Plain/Emacs/keymap helpers, Markdown, source-view, gutter, lifecycle and LSP context tests pass. Source rendering does not normalize or replace characters with U+FFFD.

No new LSP wire listener or full Texteditor keyboard controller was attached in the glyph probe; live LSP synchronization, physical Vim routing, IME, accessibility announcements and every mouse/bidi selection combination are not newly certified. The 51 remaining Unicode conformance mismatches and multisecond large-document first-edit stalls remain separate open qualifications.

## G. Performance and memory

No production before/after performance change exists. Measurements compare the **unchanged renderer** using the existing primary font against diagnostic Dialog references in the same fresh test JVM. Fonts change geometry/shaping, so these are not interchangeable correctness-qualified performance alternatives or a speedup proposal.

Apple M3 / Mac15,12, 16 GiB, macOS 26.5.2, Temurin Java 21.0.13+9; exact JVM arguments/host metadata are archived. Load average was **3.47 before / 3.59 after** the fresh probe. Buffered source viewport is 600×350, explicit clip and controlled EDT scheduling; no physical display/input timing. Each paint/caret-selection/scroll series keeps one first sample and 50 warm samples; p95 is nearest-rank. No diagnostic GC is requested until all latency samples finish.

| Text / font | Component setup + layout | First paint | Warm paint p95 | Queued caret/selection → paint p95 | Scroll → paint p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4,200 UTF-16 ASCII / bundled | 53.58 ms | **24.25 ms** | 0.284 ms | 0.709 ms | 0.732 ms |
| ASCII / Dialog reference | 4.70 ms | 2.97 ms | 0.451 ms | 0.656 ms | 0.564 ms |
| 430-unit exact B0 / bundled | 40.03 ms | 0.886 ms | 0.122 ms | 0.529 ms | 0.436 ms |
| B0 / Dialog reference | 63.50 ms | 6.99 ms | 0.164 ms | 0.766 ms | 1.148 ms |
| 24-unit CJK / bundled | 1.41 ms | 0.175 ms | 0.025 ms | 0.213 ms | 0.149 ms |
| CJK / Dialog reference | 1.77 ms | 11.60 ms | 0.031 ms | 0.348 ms | 0.132 ms |

The queued initial bundled-font lookup/registration and EDT-setup interval was **2,480.11 ms**, separately recorded rather than warmed away. The largest directly timed paint EDT operation was **24.25 ms**. Initial setup includes object construction, EDT queuing and layout; it is not pure layout CPU. The largest queued font-size round trip plus two paints was **37.84 ms**. For the B0 fixture, warm font-size round trips observed p95 **22.55 ms bundled / 30.34 ms Dialog** (10 warm samples): these are retained costs, not frame-scale claims. Palette round trips and raw allocations are archived. Default-font timings still include incorrect placeholder rendering; fast tofu is not successful international rendering.

The established ASCII typing probe was rerun separately in the final safe suite: **1 MiB queued typing-to-completed component paint p95 1.005 ms control / 0.428 ms with a controlled in-memory definition pending**, with 50 warm samples each. First samples were 0.741/0.348 ms. Those satisfy the representative ≤16 ms component target without a new full-desktop qualification. No inference of a speedup is made from differing order/cache state. Large Unicode first edit and HTML preview latency were not rerun or declared resolved.

Per-paint allocation observations include **16,728–17,976 bytes** for bundled B0 text versus **19,928–34,904 bytes** for the Dialog reference. First ASCII paint allocated much more (984,816 bytes in the direct paint interval); warm arrays are preserved rather than pooled with first-use values. The fresh test JVM used/committed heap was **32,137,592 / 56,623,104 bytes** before and **61,704,496 / 146,800,640 bytes** after workloads. After three post-timing diagnostic GC requests: **22,314,024 / 83,886,080 bytes**. These are test-JVM observations, not IDE retained-size/RSS equivalence or a before/after improvement. RSS, native/font resource attribution and startup-to-usable-editor remain unmeasured here.

## H. Lifecycle

Twenty-four current component-pane creation/disposal/placeholder-paint cycles leave **zero reachable closed pane weak references after diagnostic GC**, with the active shared document listener count **1→1**. First pane create+paint was 10.13 ms, later p95 11.21 ms; first disposal+placeholder paint 0.374 ms, later p95 1.081 ms. Those are component costs, not full-window split commands.

Global test-JVM threads were 6→11 and descriptors 175→176 as Swing/font infrastructure initialized. The extra descriptor was not attributed. The existing bundled-font singleton and JDK font/strike caches have legitimate process-level lifetimes; this does not prove every native font resource is released per view. No production cache/listener/worker was added and no full heap-root or native-memory census was performed.

## I. Regression results, commands and exclusions

**Final admitted safe Maven run: 192 passed, zero failures/errors/skips across 43 classes**, with the LSP, gutter and glyph measurement opt-ins explicitly enabled. **87 tooling tests passed**, zero failures/skips. The separate fresh glyph measurement case passed. The current unchanged packaged JAR passed Mermaid/math/local-image/service-provider/invalid-render-recovery smoke. No new packaging build was necessary: every compiled production class matches the unchanged JAR.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --measure-lsp --measure-glyph --measure-gutter \
  --gutter-baseline /private/tmp/shed-trackb1b1-run/baseline.jar \
  --output /private/tmp/shed-trackb1b2-run/all-safe.json
```

Use new output paths when replaying. The existing runner uses empty Maven settings, bounded offline cache copy, isolated Java home/tmp/preferences and in-process Surefire. For a fresh glyph-only probe use `--tests GlyphRenderingMeasurementTest --measure-glyph`; without its flag the heavy measurement case is disabled. Exact argv, sample arrays and setup/disposal results are retained.

Python discovery (`python3 -m unittest discover -s scripts -p 'test_*.py' -v`) and the existing packaged smoke script ran under fresh disposable parent environments. Native admission guards remain fail-closed. The two unsuccessful geometry-assumption runs are explicit: initial focused run **9 passed / 1 failed**, instrumented two-case run **1 passed / 1 failed**; subsequent source/capture contract checks passed. They overlap with final tests and are not summed as extra coverage. No failed capture or missing-glyph finding is converted into a glyph-success assertion.

**Not run:** full historical native/GUI Maven suite (1,616 passes/five skips was historical), native shell/PTY benchmarks, real language servers, actual main-window/root capture, unrestricted screenshots, native input/monitor/accessibility tests, Windows/Linux GUI or native resource profilers. B0.1's signal-6 positive-control NO-GO remains unchanged; it was not rerun or relaxed. Headless output is not full native desktop acceptance.

## J. UI/UX, architecture and repository preservation

No production behavior changed. Shed Light, bundled JetBrains Mono, fonts/colors, saved preferences, themes/preview, density, focus/centering, active-pane behavior, keymaps and document/Undo/LSP ownership remain intact. Existing source/split/gutter lifecycle corrections are retained. New reference fonts exist only in tests; they are never persisted or supplied to the production selector. There is no new renderer, framework, native dependency, setting or polling/background font scanner.

Current JAR SHA-256 remains **`885dd51ae7ca684632f5eb19df04618a372e8bcf85769256c6acb277363315b5`**; all **1,429** production classes match, and no new test class is shipped. Full source/artifact/preservation checks and changed-file inventory are in the evidence. This agent made **no commits or pushes**, and did not reset/clean/revert/discard work. External capture commits are preserved and recorded separately.

## K. README compliance

README before and after SHA-256:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

README was not edited. Pre-existing B1B.1 source, tests, tooling, report and performance evidence are preserved; only explicit additions to the reviewed test manifest/runner checks change their existing infrastructure files.

## L. Remaining limitations and issue register

- **P1 retained:** default-font readability for the affected assigned scripts/emoji; safe fallback correction remains undelivered. Positive reference-font paths are not a replacement default or complete typographic oracle. Logical-font ZWJ joining also remains insufficient in the sampled controls.
- **P2 retained:** native-versus-component heading overprint/context differences; the original root/main-window pipeline was not safely replayed. Actual scaling/monitor and full styling behavior remain unqualified.
- **P2 integration hazard:** raw Swing EOF mapping can expose the root sentinel outside source bounds; consumers must validate identity/range. The gutter does; no global geometry correction is claimed.
- **Separate open qualifications:** 51 Unicode conformance mismatches, multisecond 1 MiB first Unicode edits, stock HTML-preview EDT stalls, native-memory attribution and Windows/Linux performance. No scope expansion into these took place.
- No newly demonstrated source corruption, wrong-buffer Undo or cumulative component retention was found in the covered safe cases. This is not proof against every editor-safety failure.

## M. Next recommendation — Track A0

**A0 may begin as a separately scoped feasibility/security audit of read-only state and navigation boundaries.** Authoritative source, document identity, revision, caret/selection and safe navigation contracts can be inspected without relying on glyph recognition or native shell execution. Missing rendering does not imply corrupt UTF-16 data, and no new P0 source/Undo failure was established that blocks that audit.

A0 must explicitly treat screenshot/OCR output as unqualified for these scripts, retain rendering limitations, and use canonical document-owned UTF-16 offsets. Do not expose unchecked model/view coordinates such as 431 for a 430-unit source. Before shipping control operations, require project/window/document ownership, stale-revision rejection, valid offsets/grapheme policy, predictable cancellation and human/agent conflict handling; source mutations need their own checked-transaction and Undo/recovery gate. Native process/full-workbench automation remains gated on B0.1 confinement. The existing P1 rendering/Unicode/preview release blockers remain essential work before unrestricted general release.

No A0, agent-control implementation, font-system replacement or further Markdown/appearance tranche was started.
