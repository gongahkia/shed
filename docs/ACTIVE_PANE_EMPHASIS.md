# Active-pane emphasis — Tranche 8G

## A. Existing behavior and architecture

Shed already provides the approved restrained treatment. `Texteditor.activePaneIndex` and its existing pane list own the logical editing target. `EditorUiController.activateEditorPane` binds that pane and refreshes its presentation; mouse presses, source focus/caret events and existing pane-navigation commands use this route. Emphasis is not another focus owner.

`refreshPaneFocus()` counts panes not hidden by focus mode. With multiple visible panes it applies a two-logical-pixel, UI-zoom-scaled **viewport top border**: `ConfigManager.getFocusColor()` for the active pane and `getSubtleBorderColor()` for the others. With one visible pane it removes the viewport border. Existing color roles, including user overrides, remain authoritative. Each scroll pane also receives `shed.activePane` and an accessible name such as `Active editor pane 2`.

This cue identifies the logical editing target, not exclusively the OS keyboard-focus owner. Opening a command picker or deactivating the main window intentionally leaves the last editing pane identified. Returning from the picker restores keyboard focus through the existing route. Source text is not dimmed, recolored or animated by this cue.

Centering uses the outer scroll-pane border; emphasis uses its viewport border, so they compose independently. Density changes chrome spacing and does not redefine the emphasis thickness. Existing theme application refreshes the focus border using the displayed theme, including temporary previews.

The audit reviewed `APPEARANCE_FEASIBILITY.md`, `FOCUS_MODE_CENTERED_WRITING.md`, `THEME_PREVIEW_AND_SYSTEM_APPEARANCE.md`, `UI_DENSITY_PRESETS.md`, `ARCHITECTURE.md`, and the actual pane, focus, input, theme and lifecycle paths. Direct assertions for the border/property/accessibility contract were missing.

## B. Deficiencies found

### Confirmed styling defect: closure can leave the wrong pane marked active

`PaneBufferController.closePane()` removed a pane, then rendered the layout **before** selecting and binding its surviving active pane. `renderWindowLayout()` refreshed emphasis using the old list index. After binding the replacement, a focus event for that already-active pane hit the activation route's early return, leaving the stale cue in place.

Two deterministic tests failed against unchanged production code:

1. Three panes, close the active last pane: the first surviving pane becomes the logical target but its active property remains false.
2. Three panes, close an earlier inactive pane: the surviving active pane's index shifts and its active property remains false.

The mismatch also affects border colors and accessible names. This is a state-correctness defect, not a subjective cosmetic preference. Some close sequences, such as closing the last inactive pane without shifting the active index, already worked.

### Independently observed pre-existing issues

The broader verification exposed two issues in **both the frozen entry JAR and the corrected JAR**:

- **Closed source-view retention while a shared document stays open.** Twelve split-create/close cycles retained all twelve observed closed text areas after bounded GC/EDT-drain controls. Document listeners increased from 18 to 78 in both artifacts. The listener inventory includes `BlockCaret$1`, `BasicTextUI$UpdateHandler`, `DefaultCaret$Handler`, accessible-text and input-method handlers. `BlockCaret` registers its listener on the document and removes it during deinstallation; closing a split does not detach its source text view. [Inference] These document-to-view listener paths explain the observed closed-view retention; this is not an exhaustive VM-root/native-resource attribution.
- **Undo exception after shared split cycling.** A separate scratch-document diagnostic starts with `body\n`, inserts `draft `, undoes, selects offsets 1–3, creates/closes twelve shared splits with pane navigation, then performs Redo followed by Undo. Redo restores `draft body\n`; Undo throws `IndexOutOfBoundsException: offset=5` in `SyntaxUiController.updateMatchingBracketHighlight`, reached through a caret remove-update notification. It reproduces in both JARs. At the observation point document and snapshot lengths are both five; the bracket path nevertheless indexes outside the snapshot using a transient caret offset. No data-loss conclusion is claimed, and the complete history/persistence state after that exception remains unqualified.

These findings were not repaired in 8G: source-editing and split-resource-lifecycle remediation would exceed the authorized styling correction. They are retained as explicit future reliability work, not counted as passing verification.

## C. Decision

**GO for the existing emphasis treatment with the narrow closure correction.** No new style, setting, animation, focus manager or pane architecture is justified. The one-line production change refreshes pane emphasis after the surviving active pane is bound, before requesting its existing focus behavior.

The targeted 8G behavior is verified. This is not a clean product/reliability qualification: the independently reproduced issues above and the previous Markdown qualification blockers remain open.

## D. Files modified

| File | Change |
|---|---|
| `src/main/java/shed/PaneBufferController.java` | One additional `refreshPaneFocus()` call after post-close binding |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | Six new tests and a shared indicator assertion helper |
| `src/benchmark/java/shed/ActivePanePerformanceProbe.java` | Opt-in paired packaged-editor focus, paint, native interaction and lifecycle observations |
| `scripts/benchmark-active-pane.py` | Three bounded document sizes, isolated homes, process timeouts, host/RSS/raw evidence |
| `scripts/test_active_pane_tooling.py` | Two fast fixture/safety/matrix tests |
| `docs/ACTIVE_PANE_EMPHASIS.md` | This report |
| `docs/performance/appearance-tranche8g/` | Raw results, failed controls, diagnostic reproduction, images and repository verification; inventory in `INDEX.md` |

No existing test assertion was removed or weakened. Existing production theme, font, density, source/document, syntax, preview, recovery and keyboard implementations are unchanged. The separate source diagnostic is archived evidence, not a new production editing path or routine Maven workload.

## E. Visual and focus-state verification

Six new Swing integration tests cover:

- Both confirmed active/inactive closure failures, immediately after binding and after an EDT drain.
- Single pane, both split orientations and three panes; mouse-press routing; existing command navigation in Plain/Vim/Emacs; actual Vim Ctrl-W/W key-pressed routing; buffer switching and reopening the original file.
- Existing theme colors, density, focus/center and preview/cancel interactions; border thickness at zoom 0.5/1/2; exact painted border pixels and accessible labels.
- A real modal command picker, logical pane ownership, and window activation/deactivation events.
- Twelve split lifecycle cycles and rapid activation. Activation itself leaves listener counts stable; original component focus/mouse/viewport listener counts and source selection, snapshot, revision, dirty state and Undo/Redo availability remain stable.

An early **new** lifecycle test assumed split disposal released all document listeners and attempted Undo/Redo after the cycles. It exposed the pre-existing findings in B. The final focused tests assert the narrower emphasis/activation contract; the unsuccessful observations and separate baseline/candidate source diagnostic are preserved rather than presented as passed lifecycle/Undo guarantees. Two other initial test-harness assumptions were corrected: native Ctrl-W uses the control character, and undoing an edit does not imply Shed's dirty flag becomes false. The final tests assert the existing routing and captured dirty state.

The opt-in native probe additionally used `java.awt.Robot` to click a real source pane. All six final processes verified actual source focus, actual keyboard focus in the common command picker with the main window unfocused, unchanged logical pane ownership, and actual source focus on return. This is real macOS runtime evidence, not only synthetic callbacks.

Representative [light two-pane](performance/appearance-tranche8g/visual/candidate-two-panes-light.png) and [dark three-pane](performance/appearance-tranche8g/visual/candidate-three-panes-dark.png) captures show the existing border treatment. Integration tests sample the painted top-border pixel against its theme role. These observations do not certify all existing source-rendering behavior, every custom palette, visual impairment or assistive-technology announcement.

## F. Appearance compatibility and source integrity

Shed Light, Shed Dark, Dracula and Catppuccin Latte use their existing focus/subtle-border roles. Temporary Dracula preview, pane activation during preview, cancellation back to Shed Light, all three densities, independent centering, and focus entry/exit retain correct indicators. No palette or font is adjusted to improve contrast or measurements.

Tested appearance changes and emphasis preserve source text, document/snapshot identity, revision, dirty state, existing history availability and directed selection. The routes test records zero document events. Shared panes continue using the same authoritative document. Existing save, recovery, BOM/line-ending, navigation, Unicode, preview/media and keymap suites were rerun. [Inference] No LSP text-change notification arises from these tested presentation operations because they produce no document changes; a live external language server was not separately instrumented.

This source-preservation result does **not** claim that the independently failing Undo scenario is corrected. That scenario involves actual source edits and was reproduced separately with scratch files.

## G. Performance and memory

### Method

Host: Mac15,12, Apple M3, 8 CPUs, 16 GiB, macOS 26.5.2; Temurin Java 21.0.13+9; `-Xms64m -Xmx512m`; existing Shed Light/JetBrains Mono defaults in isolated homes. Initial host load was 4.30 / 4.82 / 3.78, free-memory report 54%, existing swap use 1023.5 MiB. Host conditions and artifact/probe identities are retained in the raw report; no system settings or unrelated processes were changed.

Three identical ASCII/mixed-Markdown fixture sizes (4 KiB, 100 KiB, 1 MiB), six fresh processes, alternating pair order. Each performs real mouse/picker focus, 51 source inputs before and after (first separate, 50 warm), first activation, 60 two-pane and 30 three-pane activations, light/dark theme application, focus entry/exit, closure checks and twelve split lifecycle cycles. Final desktop measurements ran without the Maven desktop suite concurrently.

Source typing uses the existing completed-source-paint metric. Other totals are EDT dispatch + explicit validation + forced root paint, not desktop-compositor presentation timestamps. Allocation is EDT thread allocation, not retained memory. GC requests occur only in the isolated diagnostic process. RSS is sampled every 250 ms and represents a sampled workload peak, not settled idle memory or a guaranteed true peak. This is not a new startup or cross-platform qualification.

### Paired results

Milliseconds, p95 unless marked first:

| Size | Warm two-pane activation dispatch, baseline / corrected | Activation including validation/paint, baseline / corrected | Typing before, baseline / corrected | Typing after, baseline / corrected |
|---|---:|---:|---:|---:|
| 4 KiB | 0.73 / 0.91 | 3.48 / 3.26 | 2.40 / 2.55 | 1.55 / 1.68 |
| 100 KiB | 1.32 / 1.09 | 5.41 / 4.57 | 2.31 / 2.26 | 1.70 / 2.12 |
| 1 MiB | 0.80 / 0.75 | 3.37 / 4.32 | 2.15 / 6.94 | 1.95 / 1.18 |

All measured ordinary typing p95 values remain below 16 ms. The 1 MiB corrected **first source input was 18.34 ms**, compared with 7.58 ms baseline; it is reported separately rather than hidden in warmup. No improvement to first-use Unicode or ordinary source typing is claimed. Variation in these few process pairs does not establish universal equivalence or a statistically significant speedup.

First activation plus paint: baseline 5.68–6.90 ms, corrected 5.32–6.24 ms. Three-pane activation plus paint p95: baseline 2.52–3.60 ms, corrected 2.42–3.49 ms. Split-create dispatch p95: baseline 2.18–2.56 ms, corrected 2.10–2.88 ms. Split-close dispatch p95: baseline 1.12–1.59 ms, corrected 1.11–1.27 ms; close including paint p95 was 2.41–3.75 / 2.41–2.78 ms. Close allocation p95 was approximately 231–234 / 231–239 KiB. These explicit operation costs include existing binding/layout/painting, not just the added call.

Existing theme application plus paint took up to 49.19 / 55.16 ms; focus entry plus paint up to 3.29 / 3.30 ms. The fix adds no typing listener or new per-keystroke work: its only new invocation is in the close path. It reuses the existing pane loop and border objects' normal lifetime, with no persistent cache or native graphics resource.

### Heap, RSS and lifecycle limits

Used Java heap after requested-GC controls, MiB:

| Size | Ready baseline / corrected | After cycles baseline / corrected | After window disposal baseline / corrected |
|---|---:|---:|---:|
| 4 KiB | 13.18 / 13.23 | 13.75 / 13.85 | 13.79 / 13.89 |
| 100 KiB | 14.04 / 14.07 | 14.62 / 14.64 | 14.65 / 14.67 |
| 1 MiB | 24.25 / 24.23 | 24.82 / 24.79 | 24.88 / 24.83 |

Committed heap was 64 MiB for small/100 KiB cases; the 1 MiB cases reached 87 MiB after cycles in both artifacts. Sampled peak RSS was 698.33 / 689.61, 704.36 / 691.09 and 749.53 / 755.42 MiB respectively. These pairs show no material additional retained heap attributable to the correction; they do not demonstrate low native footprint or an RSS improvement.

The twelve closed text areas remained reachable while the document stayed open, in both versions. Main editor weak references also remained reachable at the post-disposal observation in all six processes; whole-window cleanup is therefore **not qualified by this probe**. No definitive post-disposal GC-root/native-resource attribution was performed. This does not overwrite earlier buffer-lifecycle findings with different ownership/workload boundaries. No production GC or cleanup workaround was introduced.

## H. Regression tests and commands

Java/Maven were sourced from the previously established `/tmp/shed-tranche2c-run/toolchain.env`; Python ran with `PYTHONDONTWRITEBYTECODE=1`.

```sh
"$MVN" '-Dtest=TexteditorSwingIntegrationTest#activePaneIndicator*' test
"$MVN" '-Dtest=TexteditorSwingIntegrationTest#activePaneIndicator*+density*+focus*+centered*+splitClose*+liveThemePreview*+livePreviewCancellation*+galleryAcceptance*,UiDensityTest,ThemePreviewTest,WorkbenchLayoutTest' test
"$MVN" -q -DskipTests package
python3 scripts/benchmark-active-pane.py --jar /tmp/shed-tranche8g-run/candidate.jar --baseline-jar /tmp/shed-tranche8g-run/baseline.jar --output /tmp/shed-tranche8g-run/matrix-isolated
"$MVN" test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8g-run/candidate.jar --output /tmp/shed-tranche8g-run/packaged-smoke.json
git diff --check
```

- Before production changes: **two targeted failures**, confirming both closure defects. After correction: both pass.
- Final new-test run: **six passed**, zero skips/failures/errors. Focused appearance/layout regressions: **41 passed**, zero skips/failures/errors.
- Final complete Maven run: **1,607 passed, five existing skips, zero failures/errors** (1,612 total).
- Tooling: **46 passed**. Packaged image/math/Mermaid smoke: **PASS**. All six final paired desktop processes completed, with all 18 native focus observations correct.
- The first complete Maven run had **one temporary-directory cleanup error** (`DirectoryNotEmptyException`) in `pendingSemanticEditsCannotSurviveTypingCaretBufferModeOrLifetimeChanges`, not an assertion failure. The subsequent separate full run passed. The intermittent cleanup issue's root cause remains unverified.
- The initial desktop run timed out waiting for native mouse focus and overlapped test execution. It is preserved and excluded from the final paired results; no claim is made that overlap definitively caused it.
- The separate baseline/corrected source diagnostic records the **pre-existing Undo exception**, not a passing source-edit test. Its first attempt had a diagnostic-only null-path startup bug; that child was terminated and the corrected, explicitly timeout-bounded pair reproduced the source issue. No production code was changed for it.

The five existing skips are DOS hidden-attribute support, opt-in AI performance, unavailable Fish, unavailable CMake/CTest/CPack integration, and opt-in native AI acceptance. Exact names/counts, successful and unsuccessful logs, diagnostic source and raw measurements are retained in the [evidence index](performance/appearance-tranche8g/INDEX.md).

## I. UI/UX preservation

The only production change corrects stale state after closure. Existing colors, border geometry, typography, layout, controls, navigation, keymaps, focus/centering, density and preference precedence remain intact. Shed Light and bundled JetBrains Mono defaults remain unchanged. No new UI, setting, dependency, worker, polling mechanism, theme engine or native resource was added to production.

## J. README and repository verification

Before and after SHA-256 of `README.md`:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

Entry HEAD: `d3d9730eef0a80fd8bbc4ea5d774d1e8332aae30`, clean working tree. Hashes of 1,629 existing files were recorded. Only the two listed existing production/test files changed; all prior evidence remains present and unchanged. New files are the opt-in tooling, report and evidence listed in D. The final compiled production classes match the measured candidate JAR; comparison with the frozen baseline changes only `PaneBufferController` and its anonymous class's line metadata. Repository identities/status, hashes and the exact diff are retained. No commits, pushes, resets, cleans, reverts or discards were performed.

## K. Remaining limitations

The source-pane cue is verified for the tested transitions. Full alternate-view/terminal cue coverage, every custom palette, screen-reader behavior, multiple physical display scales and Windows/Linux runtime behavior were not qualified here. An intentionally equal user focus/subtle-border color override can remove the distinction; this tranche does not override saved colors. The Undo exception is a P1 correctness finding; closed-view retention is a P2 lifecycle finding pending fuller growth/impact attribution. Neither is newly caused or repaired by this one-line presentation change.

The new pre-existing closed-view and Undo findings need independent remediation/qualification. Unicode first-edit stalls, 51 Unicode conformance mismatches, stock HTML-preview stalls, native-memory attribution, unsupported rich live editing and previous release-readiness restrictions remain unchanged. No passing test count resolves those product limitations.

## L. Readiness for Tranche 8H

**CONDITIONAL GO for combined qualification as an audit; not a clean reliability signoff.** The approved emphasis needs no cosmetic expansion. The next smallest justified task is a separately scoped source-caret/Undo regression and correction for the reproduced out-of-range caret notification, followed by targeted closed-split source-view listener/lifetime cleanup while preserving active shared documents. These findings should enter 8H's defect register and block a claim that all source/lifecycle gates pass until resolved or explicitly dispositioned.

8H must combine the delivered focus/center, preview, density and emphasis paths, preserve opaque controls, recheck startup/typing/native-resource behavior and document platform/accessibility limits. It must retain the earlier Markdown NO-GO release decisions where their blockers remain. No 8H or additional remediation was begun in this tranche.
