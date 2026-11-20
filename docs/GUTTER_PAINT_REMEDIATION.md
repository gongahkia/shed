# Track B1B.1 — Gutter-paint correctness and lifecycle safety

## A. Original failure and safe reproduction

**GO for the bounded gutter correction in the covered component workflows. Full desktop/platform qualification remains incomplete.** This is a rendering reliability correction, not general-release certification or a Unicode-layout improvement. [Raw evidence and reproduction commands](performance/ide-trackb1b1/INDEX.md) retain successful and unsuccessful controls.

B0 and 8H recorded the same exception and production call path:

```text
javax.swing.text.BadLocationException: Can't translate offset to line
    javax.swing.JTextArea.getLineOfOffset(JTextArea.java:381)
    shed.LineNumberPanel.paintComponent(LineNumberPanel.java:106)
    javax.swing.JComponent.paint
    javax.swing.JViewport.paint
    javax.swing.JSplitPane.paintChildren
    javax.swing.RepaintManager.paintDirtyRegions
    java.awt.EventDispatchThread.run
```

8H's visual log records one caught exception between focus/centering and narrow capture. B0 narrowed the failing boundary: four appearance/resize cycles, 430 UTF-16 source units, two live shared-document panes, Comfortable density and focus/center on→off, then narrow resize/Compact and restore. At every failure the active pane was **not disposed**, its start mapping was 0, bottom mapping **431**, and source document length **430**. Its inactive peer mapped the bottom to 430. Full stacks, exact geometry and document identities remain in the original B0/8H evidence; `original-exceptions.json` records their hashes and first stacks.

The safe initial regression run reproduced the adjacent **−1** failure with a real, unlaid-out JTextArea. A controlled mapping subclass then supplied B0's exact **430→431** boundary to the real production gutter. The starting implementation logged the same BadLocationException and painted only its background. The initial three-test run had **two expected regression failures and one passing geometry control**, preserved as `before.json.gz`.

A normally sized headless mixed-script JTextArea mapped to 430 unwrapped and 344 wrapped at the sampled point; it used Swing BoxView. Therefore the original resize transition's transient 431 return is supported by historical production evidence and a deterministic boundary injection, **not newly reproduced as a real desktop transition**. The exact upstream Swing layout sequence that produces that sentinel remains unattributed. No historical desktop or native-shell harness was replayed.

## B. Confirmed root cause

`LineNumberPanel.paintComponent` directly composed `viewToModel2D` with `getLineOfOffset` for both viewport endpoints. These APIs do not share identical availability/range behavior. The installed OpenJDK 21 sources document −1 for unavailable view mapping; JTextArea rejects offsets outside 0…document length. PlainDocument's element coverage extends through an implied final position: this fixture's root end is 431 although source length is 430. The gutter did not distinguish that element sentinel from an actual source offset.

The failing call occurs before the number/marker loop. Its existing BadLocationException catch printed a stack and skipped the complete loop. This is confirmed caller-side range handling; it does not establish a stale buffer, off-EDT mutation, disposed owner, font defect or an appearance-induced source change. The same input boundary exists in breakpoint mouse hit testing. Separately, model-to-view geometry is nullable while unavailable; the loop previously dereferenced it directly.

## C. Severity and visible impact

**P2 rendering/reliability defect before correction.** The preserved starting JAR and current source reproduce a blank gutter for the implied-end control: numbers and all markers are skipped. The corrected image includes lines 1–21 and is exactly equal to a valid-EOF control. Thus incomplete gutter painting is demonstrated, not merely inferred from a logged warning. Repeated stack printing is also unnecessary EDT work, but no general responsiveness improvement is claimed.

No source/history corruption was observed in the historical runs or covered current controls. Missing Unicode glyphs, Unicode first-edit stalls and stock HTML-preview stalls remain independent open issues; gutter number pixels are not evidence that source glyph fallback is correct.

## D. Production changes and rationale

Only **`src/main/java/shed/LineNumberPanel.java`** changes production behavior:

- One private mapping helper rejects unavailable negative offsets and offsets beyond the source that are not the document root's implied end. That specific end maps to actual EOF, preserving the final logical line, including empty/trailing-newline documents. Arbitrary positive outliers are not clamped to an unrelated line.
- Painting skips unavailable/reversed viewport ranges until geometry is available. Unavailable line bounds end that paint without a null dereference. The loop stops at the visible bottom edge so accepting an implied-end sentinel cannot turn a local paint into document-wide geometry work. Valid positions still use the existing model geometry, fonts, number formatting and drawing operations.
- Breakpoint hit testing uses the same validated mapping; unavailable/outlier mappings do not call the toggle listener. An implied-end hit resolves to the existing final source line.

There is no new catch or exception suppression. The existing unexpected BadLocationException logging remains, as do the current caret error behavior and marker styles. A final adversarial test found that my initial normalization alone queried 10,001 offscreen lines in a 10,001-line document. That intermediate correction was rejected and its failing log retained. The final visible-bottom bound passes the same test with at most 64 geometry queries. No renderer, view, document, worker, cache, setting or dependency is introduced. No source-overlay implementation outside the gutter is modified.

Other changed files:

| File | Purpose |
| --- | --- |
| `src/test/java/shed/GutterRenderingTest.java` | Deterministic range, pixel, marker, width/read-bound, EOF, geometry and shared/disposed-view regressions |
| `src/test/java/shed/GutterPerformanceTest.java` | Opt-in, pinned starting-JAR comparison, component timing/allocations, visual captures and bounded disposal diagnostics |
| `src/test/resources/shed/portable-fixture-tests.properties` | Extend 34 to 38 explicitly reviewed safe classes: the two new classes and existing LineNumberPanelTest/IncrementalDiffGutterTest; retain all previous admissions |
| `scripts/run-portable-tests.py` | Paired `--measure-gutter` / `--gutter-baseline` opt-in; packaging remains mutually exclusive with measurement execution |
| `scripts/test_native_fixture.py` | Exact updated admission set and rejection checks for missing control artifact/conflicting execution modes |
| `docs/GUTTER_PAINT_REMEDIATION.md`, `docs/performance/ide-trackb1b1/` | Report, raw measurements, source versions, images, integrity and compliance evidence |

The baseline JAR must have SHA-256 `2a1767672c3a08dcc3062be06fef462db338adccd649d8df83f6a1fe7e4a29d9` before isolated class loading. The benchmark instantiates only its gutter with an ordinary Swing text area; it does not construct its main window or start its services. Starting binaries are preserved in the task scratch directory. Candidate classes and packaged artifact identities are checked separately.

## E. Rendering and visual correctness

Verified with EDT-owned real Swing components and BufferedImages:

- Unlaid-out −1 mappings safely produce background and subsequently recover.
- Empty document, EOF, trailing newline and the exact implied end retain correct final logical line coverage. Unrelated invalid offsets and reversed ranges do not paint misleading line numbers.
- Implied-end and valid-EOF images match for all four number modes, diff/Git markers, coverage, breakpoint states and diagnostic dots; comparisons include simulated 1×/2× graphics transforms.
- Starting/corrected **valid** gutter output is pixel-identical in Shed Light and Dracula for ordinary and B0 mixed-script fixtures. Images are in `visual/`; they contain synthetic gutter content only.
- The failure image is blank; the corrected implied-end image matches valid EOF. Both were inspected in addition to pixel assertions.
- Existing preferred-width formula is retained and checked with 100,001 logical lines. The measured-document test bounds Segment reads below 8,192 characters after source-view initialization on a 1.1-million-character fixture. Production mapping adds no text reads or document traversal.
- Actual component geometry exercises empty/EOF, five-digit line numbers, tabs, long Unicode lines, combining marks, emoji/ZWJ and mixed scripts, wrapping/unwrapped modes, 12/24-point bundled font, narrow/wide sizes and centered-writing insets. Viewport clips remain bounded instead of allocating document-height images.

These are component/geometry checks. Physical monitor scaling, native window activation, full focus/density command transitions, actual theme-gallery rollback and mouse delivery in a visible workbench were not rerun. Existing portable theme, density and centered-width regressions pass; no ownership/controller for those features changes. No glyph fallback claim follows from monochrome line-number comparisons.

## F. Split lifecycle and source integrity

The shared-document test paints both live views through repeated resize/pane cycles, with exact snapshot identity, monotonic revision, dirty state, selection offsets and document-listener count unchanged by painting. It then edits through one split, retires it using the existing `EditorPane.disposeSourceView`, and exercises exact Undo/Redo through the survivor without clearing history. A separate EOF insertion/removal test compares restored gutter pixels and exact text through Undo/Redo.

Buffer switching keeps the existing document owner. Idempotent pane disposal leaves the retired text component attached to its private empty document. An explicitly queued late paint completes afterward; subsequent active-document edits/Undo do not resurrect the closed source view. No gutter document listener or asynchronous callback was added. Breakpoint clicking calls only the existing toggle callback and does not mutate source.

The opt-in diagnostic performs 24 creation/paint/disposal/late-paint cycles while keeping the shared source owner live. Its document-listener count is **1 before / 1 after**, and **zero of 24 closed pane weak references remain** after three explicitly labeled diagnostic GC requests. This is bounded Java-object evidence, not native/window-resource certification or proof about every retaining root.

Existing safe document format/BOM/newline, persistence/recovery, bounded Undo, source-view, Unicode/grapheme, Markdown, keymap and B1A request-context regressions pass. Full Texteditor key routing, physical Vim input, native IME, actual file-watcher/LSP process state and full window disposal remain excluded. The production correction contains no source-write, revision, save/recovery or LSP-sync operation; no direct LSP wire-state measurement was added for painting.

## G. Paired performance and memory

Primary paired component run: same process/host, preserved starting JAR then candidate for each fixture, 800×500 offscreen source image and 70×500 gutter, explicit viewport clips, real viewport changes and SourcePlainView adaptation. Font is bundled JetBrains Mono 16, theme Shed Light. One first operation and **50 warm samples** per operation/fixture/artifact; p95 is nearest-rank. Undo occurs outside the timed insert interval. No GC is requested until all latency samples finish.

| Fixture | Gutter paint p95 before / after | Queued insert → source+gutter paint p95 before / after | Viewport scroll → component paint p95 before / after |
| --- | ---: | ---: | ---: |
| 4 KiB | 0.157 / 0.099 ms | 0.543 / 0.411 ms | 0.548 / 0.427 ms |
| 100 KiB | 0.039 / 0.034 ms | 0.316 / 0.291 ms | 0.301 / 0.286 ms |
| 1 MiB | 0.033 / 0.046 ms | **0.292 / 0.283 ms** | 0.288 / 0.272 ms |

First insert-to-paint samples are **1.158 / 0.353 ms**, **0.454 / 0.330 ms**, **0.539 / 0.658 ms**, respectively. First standalone gutter paints are 0.178/0.064, 0.035/0.031 and 0.031/0.033 ms. Fixture setup and initial source/gutter paint are separate; 1 MiB setup is **56.20 / 19.11 ms**, with the same control-first ordering caveat. Initial control loading/configuration/reproduction took **1,372.51 ms**, and the subsequent explicit bundled-font call took **7.46 ms** in the fresh test JVM. Those first-use boundaries are not included in warm typing or attributed to the small gutter guard. The earlier intermediate probe recorded a 3,005.71 ms font call under a different initialization order; it remains archived, not relabeled as a final startup measurement. No IDE startup result is claimed. The final all-safe run has already warmed fonts/classes and is retained separately.

The control runs first, so JIT/font/cache order can explain small apparent improvements. **No speedup claim is made.** The observed typing component p95 values meet ≤16 ms with no material regression established. The 1 MiB gutter p95 increased by about 0.013 ms; this is reported rather than substituted with the earlier, more favorable intermediate result. They do not qualify physical input, all main-window overlays, multisecond first Unicode edits or whole-IDE responsiveness.

An initial timing probe omitted an explicit graphics clip. Swing traversed outside the intended visible region, yielding approximately 83 ms on 1 MiB for **both** artifacts. This is retained as `paired.json.gz`; it is not silently pooled with the corrected viewport measurements or presented as a production regression. After correcting only that probe boundary, `final-bounded-paired.json.gz` supplies the table above; `paired-clipped.json.gz` is the earlier intermediate correction and is not the final production measurement.

Per-paint thread allocations on 100 KiB/1 MiB were **8,480–8,792 bytes for both artifacts** in that run. Candidate pane creation plus its first gutter paint: first **14.84 ms**, later observed p95 **11.47 ms** over the remaining 23 cycles; disposal first **0.611 ms**, later p95 **0.182 ms**. These are lightweight component-pane costs, not real workbench split commands or a matched pre/post split benchmark.

Combined Maven/test-JVM used heap was **84,062,584 → 125,305,016 bytes** before/after the paired workloads, committed heap **123,731,968 → 150,994,944 bytes**. After all samples and disposal diagnostics, three requested collections ended at **22,452,048 used / 77,594,624 committed bytes**. Threads were 6→11 as Swing initialized, descriptors 175→176, with no attribution of the extra descriptor. The baseline class loader is explicitly closed. No new gutter worker, listener registry or cache exists. There is **no per-artifact retained-heap, RSS, native-memory or full startup comparison**, and no conclusion of whole-process memory equivalence. Host load was sampled after the runs, not before each measurement; this limits precision.

## H. Verification and safety exclusions

Final source-reviewed Maven run: **182 passed, zero failures/errors/skips**, across 38 admitted classes, with both measurement opt-ins enabled. The earlier 180/181-test runs and initial 16 focused passes overlap and are not additional distinct tests. **87 Python tooling tests passed**, zero failures/skips. Offline Java/Maven packaging and the new packaged Mermaid/math/local-image smoke passed; compiler/runtime homes, preferences and temporary files were disposable.

Exact argv and output are archived. The core command was:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/run-portable-tests.py \
  --jdk /tmp/shed-tranche2b-tools/jdk-21.0.13+9/Contents/Home \
  --maven /tmp/shed-tranche2b-tools/apache-maven-3.9.11 \
  --repository /Users/gongahkia/.m2/repository \
  --measure-lsp --measure-gutter \
  --gutter-baseline /private/tmp/shed-trackb1b1-run/baseline.jar \
  --output /private/tmp/shed-trackb1b1-run/final-bounded-regressions.json
```

The runner uses the existing trusted offline Java/Maven path, empty Maven settings, a bounded dependency-cache copy and in-process Surefire. Its source-hash admission is maintained, with no native/full-suite override. Heavy measurements remain opt-in. Python discovery ran under a fresh disposable environment; packaging used the mutually exclusive `--package-only` mode. The packaged smoke used the existing script under that same reviewed environment pattern.

**Not run:** the historical full native/GUI Maven suite (1,616 passes/five skips was a historical baseline), PTYs, native-shell benchmarks, real language-server children, desktop capture of a full main window, Windows/Linux GUI tests or native resource profilers. B0.1's signal-6 positive-control failure remains NO-GO and was not retried. No unsafe fixture is run to obtain a full-suite count. Existing assertions are not weakened.

## I. UI/UX and cross-platform preservation

The correction uses portable Java/Swing range and geometry APIs. No native hook, platform renderer, new setting/component or dependency is added. Colors, font selection, dimensions, syntax styling, gutter markers, active-pane treatment and source layout are retained. The light/dark valid-pixel checks and class/artifact scope support the limited appearance-preservation claim. Shed Light, JetBrains Mono, saved preferences, density/focus/centering/theme-preview ownership and Plain/Vim/Emacs bindings remain unchanged in production source. Windows/Linux/native HiDPI behavior cannot be verified on this host and is not certified.

## J. README and repository compliance

README before **and** after SHA-256:

`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`

Entry HEAD: `f4938aa557c1c64142d78de9b1d2dcbb3e6ded14`. Entry work included the modified B1A report and untracked B1A index/checksums/repository evidence. All are preserved byte-for-byte. The final repository verification records current HEAD/status, any external capture commits, exact file inventory, previous evidence hashes and whitespace checks. **This agent created no commits or pushes** and used no reset/clean/revert/discard. Prior binaries are backed up rather than discarded. Only the gutter and its generated nested classes differ in packaged production bytecode; all 1,429 packaged production classes match the compiled output, and no test fixture is shipped.

## K. Remaining limitations

- The exact transient OpenJDK layout sequence returning 431 during the historical real-window cycle is still unattributed; no full desktop replay is claimed.
- A future supported visible-window matrix must cover mouse/keyboard pane activation, real focus/density/theme-gallery transitions, wrapped scrolling, live diagnostics/breakpoint integration, fullscreen and actual monitor scaling. Unavailable geometry intentionally omits numbers until a later normal paint; no retry timer is installed.
- Existing source overlays outside the gutter have their own mapping code and were not changed or comprehensively qualified.
- Missing-glyph source rendering remains open, independent of numeric gutter pixels. The 51 remaining Unicode conformance mismatches, severe first Unicode edit, HTML-preview EDT stalls, native-memory and cross-platform qualifications remain open.
- Component test GC observations do not prove universal release readiness, native graphics cleanup or whole-process lightweight memory use.

## L. Verdict

**GO for the confirmed local gutter range/availability correction within the safely tested scope.** Original failure inputs are accounted for, the controlled blank gutter is corrected with exact valid-reference pixels, and source/Undo, disposal and bounded responsiveness checks pass. Broader GUI/platform qualification remains incomplete. This is not a blanket GO for Shed's general public release.

## M. Next recommendation

Proceed with separately scoped **B1B.2 — missing-glyph rendering qualification**: establish actual source glyph coverage and distinguish rendering from capture on bounded portable Swing fixtures before proposing any font/layout change. B0 already retained substantive native missing-glyph evidence, and this correction does not address it. No new source-integrity or high-severity lifecycle defect was established that requires an intervening remediation. A0 remains a later read-only/control-boundary audit, not automatic implementation. B0.1 process confinement remains prerequisite to resuming native-shell/real-server/full-native benchmarking.

No B1B.2, A0, Markdown renderer change or other tranche was started.
