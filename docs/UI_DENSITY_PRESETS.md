# UI density presets — Tranche 8F

## A. Summary

**Delivered for the verified workbench surfaces.** Compact, Default and Comfortable are live application appearance preferences. Use `:density compact|default|comfortable`, the three existing-style command-palette actions, or **Appearance → Interface density** in the existing settings editor. `:density` reports the current value. No shortcut was added.

Default retains the previous metrics. The paired packaged-editor experiment found identical Default geometry in all six workloads, initially and after restoration, and identical sampled header, editor-strip and status-bar PNGs: 12 geometry comparisons and 36 image comparisons. This is evidence for those sampled surfaces, not a pixel comparison of every application screen.

Source line spacing, typography, colors, editor rendering, and specialized component bodies are outside this implementation. Existing Unicode first-edit, HTML-preview layout and native-memory qualifications remain open. No later appearance tranche was started.

## B. Architecture

`UiDensity` is one small enum containing spacing roles. It does not scale the whole interface or replace `UiZoom`. Each role retains the existing zoom conversion and, where applicable, font/inset minimums. There are no component registries, notification subscriptions, new workers, timers or document-sized caches.

The settings application now supplies the changed key to the existing session/config controller. Density uses a targeted `EditorUiController.applyDensity()` path instead of the general font/theme refresh, which replaces source TextUI/view instances. The targeted path updates existing chrome and marked common picker components, then revalidates/repaints. It skips traversal into text-component children and retains the source TextUI and its actual child View.

At normal UI font size 13 and zoom 1, representative logical-pixel dimensions are:

| Dimension | Compact | Default, previous value | Comfortable |
|---|---:|---:|---:|
| Header/editor-strip content height | 34 | 40 | 48 |
| Editor tab width | 188 | 220 | 252 |
| Tab vertical/horizontal padding | 6 / 10 | 8 / 14 | 10 / 18 |
| File-tree row | 20 | 22 | 28 |
| Common picker row/filter | 32 | 40 | 48 |
| Status vertical padding | 3 | 5 | 7 |
| Command field vertical padding | 2 | 4 | 6 |

The header additionally retains its existing one-pixel bottom border. Control height uses the greater of its density minimum and actual font height plus padding. Nondefault tree/picker dimensions include font/inset floors. Default retains the old formulas, including the existing fixed tool-header minimum. Compact can be as large as, or slightly larger than, legacy Default at unusual font/zoom combinations: clipping avoidance takes precedence over the preset name. For example, the font-24 picker is 44 / 42 / 50 pixels.

## C. Files changed

Changes are listed against the entry checkout, including changes subsequently captured by an external commit during this task.

| File | Purpose |
|---|---|
| `src/main/java/shed/UiDensity.java` | New bounded spacing roles and component-owner resolution |
| `src/main/java/shed/ConfigManager.java` | Default, safe config repair, density persistence/failure handling, application ownership and same-config-window acknowledgement |
| `src/main/java/shed/TypedSettings.java` | Appearance category, choices, validation and apply description |
| `src/main/java/shed/ConfigSettingsApplication.java` | Key-aware application callback; existing Runnable constructor retained |
| `src/main/java/shed/Texteditor.java` | Wire the existing settings boundary to that callback |
| `src/main/java/shed/SessionConfigController.java` | Targeted application, command, shared-window synchronization and session exclusion |
| `src/main/java/shed/CommandHandler.java` | Existing command registration |
| `src/main/java/shed/PaletteController.java` | Three density actions and common picker sizing |
| `src/main/java/shed/SettingsEditorDialog.java` | Friendly label for the existing settings control |
| `src/main/java/shed/EditorUiController.java` | Targeted chrome/picker update and existing theme-refresh compatibility |
| `src/main/java/shed/OpenEditorsBar.java` | Editor-strip/tab sizing |
| `src/main/java/shed/WorkbenchHeader.java` | Header/action sizing and nested preferred-size invalidation |
| `src/main/java/shed/FileTreePanel.java` | Row height and scrolling increment |
| `src/main/java/shed/ToolWindowHost.java` | Existing tool-header/tab/action sizing |
| `src/main/java/shed/WorkbenchStyle.java` | Reuse existing tab delegate with updated insets |
| `src/test/java/shed/UiDensityTest.java` | Seven configuration/metrics/ownership/failure tests |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | Seven density integration tests |
| `src/benchmark/java/shed/DensityPerformanceProbe.java` | Opt-in packaged-editor probe |
| `scripts/benchmark-density.py` | Bounded paired process runner and fixtures |
| `scripts/test_density_tooling.py` | Three fast tooling checks |
| `docs/CONFIG.md` | Existing configuration reference entry and example |
| `docs/UI_DENSITY_PRESETS.md` | This report |
| `docs/performance/appearance-tranche8f/` | Raw measurements, logs, reference images, comparisons and preservation evidence; complete inventory in `INDEX.md` |

## D. Defaults and persistence

`ui.density = "default"` is the application-owned preference. Missing values retain Default. Invalid saved values use the existing configuration-repair path while preserving unrelated valid settings and the original file. Invalid runtime requests reject. Projects, including configurations allowing unsafe project keys, and restored session UI overrides cannot own density.

The named command and settings control save through the existing settings use case. `:set ui.density compact` remains a runtime change; `:set! ui.density compact` follows existing persistence conventions. Density saves install runtime state only after a successful write; a failed save/reset restores the prior persisted key and does not apply a visual change. The underlying configuration-file writer is unchanged; this does not claim a new disk-level atomic-save mechanism.

Other displayable main windows sharing the same configuration directory receive the selected density through their existing controllers. Component sizing resolves its owning main window instead of adding global mutable density state. A narrow acknowledgement of a verified density-only config write avoids a second window replaying that write over unrelated unsaved local font settings. A deterministic test also checks that an external font edit is still reloaded rather than swallowed by this acknowledgement.

Existing saved themes, editor/UI fonts, zoom and layout preferences remain separate. Shed Light and bundled JetBrains Mono remain the defaults.

## E. Component coverage

Supported: main header/actions; open-editor strip; existing tab delegates; file-tree rows; common `PaletteController.showPicker` list/filter rows (including command and navigation pickers using that route); tool-window header actions; status and command footer padding. Newly opened instances use the current preference; already displayed supported pickers are updated through their owner.

Unchanged: source line height; editor/font zoom; menus; generic buttons, tables and specialized lists; custom picker implementations outside the common route; tool-body padding; settings-content spacing; divider thickness and scrollbar dimensions. No claim of complete app-wide density coverage is made.

## F. Runtime behavior and integration

Compact → Default → Comfortable and repeated cycles update existing components without rebuilding the workbench or source documents. Focus mode and centering retain their own state; exiting focus mode does not restore an old density. Theme preview/acceptance/cancellation retains the current density instead of rolling it back with the previewed theme. Layout ownership and divider proportions remain with the existing split/focus infrastructure.

Two integration defects were reproduced and corrected during this tranche:

- A density-only saved write initially caused another window's config watcher to restore its persisted font over a runtime-only font change. The write acknowledgement above corrects this path; the regression test retains a local font choice and still permits a later unrelated external edit.
- After Comfortable, document navigation and Default restoration, nested header containers retained a 49-pixel preferred height instead of 41. Explicitly invalidating the existing action/focus containers before revalidation restores the Default geometry. The failing and passing regression logs are retained.

The initial configuration-reference test also failed until `docs/CONFIG.md` included the new typed setting. No assertion was weakened.

## G. Geometry and source correctness

Tests exercise Plain, Vim and Emacs, shared splits, large UI fonts, zoom 0.5/1/2, narrow/wide resizes, wrapping, tabs, long lines, Indic conjuncts, combining marks, emoji and CJK. They check model/view mouse mapping, caret/selection offsets, active source identity, actual source child View identity, font identity, snapshots/revisions, dirty state, zero document events during density changes, and preservation of existing Undo and Redo.

Other tests cover settings/restart persistence, read/validation failures, application versus project/session ownership, two main windows, live modal picker sizing, tool/tree rows, focus/centering and theme-preview cancellation. The full suite reruns Unicode 15.1 GB9c, buffer lifecycle, save/recovery, Markdown commands/navigation/media and editor/keymap regressions. There is no new source-editing path or LSP text mutation; an external language server was not separately instrumented for this appearance change.

Default geometry/image results are in [default-comparison.json](performance/appearance-tranche8f/default-comparison.json). Representative [Default](performance/appearance-tranche8f/visual/candidate-default_initial-editor_strip.png), [Compact](performance/appearance-tranche8f/visual/candidate-compact-editor_strip.png) and [Comfortable](performance/appearance-tranche8f/visual/candidate-comfortable-editor_strip.png) editor strips retain the existing visual style. Only transient hover state and benchmark label text were normalized for deterministic image capture; fonts, colors and layout were not normalized.

## H. Performance and memory

### Method

Measured 19 November 2026 on Mac15,12 / Apple M3 / 8 CPUs / 16 GiB, macOS 26.5.2, Temurin Java 21.0.13+9, `-Xms64m -Xmx512m`. Displays: built-in 2560×1664 Retina and external 1920×1080; alternate display-scale configurations were not independently qualified. Initial load averages were 2.18 / 2.65 / 2.70; reported system memory free percentage 56%, existing swap use 1063.5 MiB. Neither system settings nor other processes were changed.

The frozen entry JAR and final candidate were launched in 12 isolated processes, alternating pair order, with disposable homes and explicit Shed Light/Plain mode. Six cases: 4 KiB with sidebar; 100 KiB wrapped with sidebar; 1 MiB unwrapped; 1 MiB with shared source splits; 1 MiB wrapped; 4 KiB with UI font 24. Each has four phases, 51 input/paint samples per phase (first separate, 50 warm), source/sidebar scrolling, tab navigation, 12 complete density cycles, resizing, and picker/focus/preview operations where applicable. Source fixture generation, all samples, host controls and artifact/probe hashes are retained.

The baseline JAR has no density feature: its differently named phase requests are **no-ops**, followed by the same paint/input workload. They are controls, not historical Compact/Comfortable support. The first real Compact switch was not hidden behind an earlier density command.

Source input-to-paint uses the established production completed-source-paint metric. Switch measurements separate dispatch (including preference I/O), validation and forced root painting on the EDT. Forced painting is not a desktop-compositor presentation timestamp. Allocation observations are thread-allocated bytes, not retained heap. Startup is Java-main-to-loaded-editor readiness, not cold-cache startup or launch-to-visible-window certification.

### Typing and switching

Warm source input-to-completed-paint p95, milliseconds, 50 samples per cell:

| Workload | Baseline Default | Candidate Default | Compact | Comfortable | Restored Default |
|---|---:|---:|---:|---:|---:|
| 4 KiB + sidebar | 2.38 | 2.37 | 1.23 | 1.04 | 1.03 |
| 100 KiB wrapped + sidebar | 1.76 | 1.83 | 0.77 | 0.72 | 0.68 |
| 1 MiB unwrapped | 2.21 | 2.49 | 1.08 | 1.72 | 1.20 |
| 1 MiB, shared splits | 3.71 | 3.62 | 1.60 | 1.89 | 1.34 |
| 1 MiB wrapped | 1.83 | 1.81 | 6.62 | 0.80 | 0.83 |
| 4 KiB, UI font 24 | 2.98 | 2.87 | 1.42 | 1.15 | 2.09 |

All measured p95 values satisfy 16 ms. This does not mean every sample did: the 1 MiB wrapped Compact sample set had p50 1.65 ms, p99/max 19.44 ms. A reversed-order repeat of that existing workload measured baseline/candidate Compact-labelled p95 0.79 / 0.93 ms. The main-run excursion is retained; its cause was not profiled. These ASCII-source typing controls do not requalify the known first-complex-Unicode insertion stall.

First source-input latency was 5.69–9.54 ms baseline and 7.87–10.42 ms candidate. First actual Compact application plus forced paint was 4.66–8.13 ms across the six cases: dispatch 1.98–3.09 ms, remaining work principally paint. EDT allocation was 0.24–0.58 MiB. Repeated Compact/Comfortable/Default switches had worst per-case p95 totals 11.99 / 11.89 / 10.72 ms, with maximum measured allocation per switch below 0.53 MiB. No document-sized allocation is introduced by the density calculation.

Source scroll plus forced paint ranged up to 9.63 ms initially, 5.36 ms Compact and 3.25 ms Comfortable. Common picker interaction/paint was 0.82–6.44 ms; custom-font picker dimensions remained readable. Density changes during focus and theme preview took up to 2.94 / 3.57 ms. Existing theme preview/cancel still cost up to 53.67 / 29.86 ms in these small-document probes.

Large wrapped layouts remain expensive: 1 MiB resize maxima were 118.40 ms baseline / 126.10 ms candidate; tab-return maxima 263.63 / 448.03 ms. A reversed-order repeat measured resize 106.67 / 119.55 ms and tab return 221.65 / 254.86 ms. Existing wrapped tab/resize paths allocate hundreds of MiB (up to approximately 518 MiB thread allocation on tab return), in both artifacts. Density switches themselves did not rebuild source Views. These few samples establish neither equivalence nor a causal density regression for large navigation/layout operations; that performance boundary remains incompletely qualified. They are not ordinary typing results and were not hidden in warmup.

Main-to-loaded-editor measurements were 1.87–3.53 s baseline / 1.88–2.08 s candidate, one launch per workload. This small, non-cold paired set shows no consistent startup penalty; it is not a statistical startup qualification. No eager worker or density startup scan was added.

### Heap, RSS and lifecycle

The runner records used and committed heap separately. GC requests are diagnostic controls only in the isolated probe, never production cleanup. After repeated cycles, short-settle used heap baseline/candidate in MiB was:

| Workload | Baseline | Candidate | After disposal baseline / candidate |
|---|---:|---:|---:|
| 4 KiB + sidebar | 13.68 | 13.64 | 13.65 / 11.60 |
| 100 KiB wrapped + sidebar | 14.75 | 14.48 | 11.37 / 11.44 |
| 1 MiB unwrapped | 24.25 | 25.30 | 11.23 / 11.36 |
| 1 MiB splits | 24.19 | 36.21 | 11.23 / 11.26 |
| 1 MiB wrapped | 36.48 | 35.54 | 11.34 / 11.37 |
| 4 KiB, UI font 24 | 13.56 | 13.56 | 13.57 / 11.53 |

The split difference reproduced at short settle (24.20 / 36.24 MiB). A separate paired `jcmd GC.class_histogram` diagnostic then found similar live object counts/sizes; after a further two-second settling control used heap was 25.37 / 25.35 MiB, and after disposal 11.273 / 11.271 MiB. Immediate histograms had identical 18,799 document LeafElements and 18,913 GapContent MarkData objects, and nearly identical byte/char-array totals. This supports transient activity/accounting rather than a persistent additional density-owned document graph; it does not fully attribute the temporary increase. No new density listener/cache retaining path exists in the implementation.

All six candidate editor weak references cleared within the bounded disposal control; two baseline controls remained reachable at that observation. This is not proof that all native graphics resources were released. Post-disposal committed heap was 64 MiB. The active split diagnostic committed 90 MiB in both processes after settling.

Sampled process peak RSS (250 ms samples) was 665–813 MiB baseline / 673–805 MiB candidate. Individual pairs moved in both directions; the repeated split pair peaked at 671 / 811 MiB despite equivalent settled live heap. These are sampled workload peaks, **not settled idle RSS**, and do not establish a whole-process memory improvement or complete native attribution. No universal low-memory claim is made.

The [evidence index](performance/appearance-tranche8f/INDEX.md) identifies the raw main/repeat results, live histograms and failed pilot controls. Early pilots incorrectly attached a tree to a source-owning split and timed out waiting for paint; the final runner uses the actual file-tree route. Those failed harness runs are excluded from product measurements and preserved.

## I. Regression verification

Toolchain was sourced from `/tmp/shed-tranche2c-run/toolchain.env` (`JAVA_HOME` and `MVN`); Python used `PYTHONDONTWRITEBYTECODE=1`.

```sh
"$MVN" -q '-Dtest=UiDensityTest,ConfigManagerTest,ApplicationBoundaryTest,ThemePreviewTest,TexteditorSwingIntegrationTest#density*+liveThemePreview*+livePreviewCancellation*+galleryAcceptance*+openingAnotherWorkbench*+savingSettingsDuringPreview*+gallerySaveFailure*+themeGallerySaves*+focus*+centered*+goyo*' test
"$MVN" test
python3 -m unittest discover -s scripts -p 'test_*.py'
"$MVN" -q -DskipTests package
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8f-run/candidate.jar --output /tmp/shed-tranche8f-run/packaged-smoke.json
python3 scripts/benchmark-density.py --jar /tmp/shed-tranche8f-run/candidate.jar --baseline-jar /tmp/shed-tranche8f-run/baseline.jar --output /tmp/shed-tranche8f-run/matrix
git diff --check
```

Final focused run: **103 passed**, zero failures/errors/skips. Complete Maven suite: **1,601 passed, five existing skips, zero failures/errors** (1,606 total). This adds 14 tests to the reported baseline. Tooling: **44 passed**. Packaged Mermaid/math/image smoke: **PASS**. All 12 matrix processes, four bounded repeat processes and two histogram controls completed. Frozen candidate classes match current compiled production classes.

The five skips remain: unsupported DOS hidden-attribute test; opt-in AI performance; unavailable Fish; unavailable CMake/CTest/CPack integration; opt-in native AI acceptance. Details and exact totals are retained in `full-results.json.gz`. Test success does not remove the previously reproduced Unicode/layout limitations.

## J. UI/UX compliance

Changes are confined to the approved density spacing on existing components and one typed appearance preference with existing commands/palette/settings integration. Themes, default fonts, navigation, source rendering, split infrastructure, focus/centering and keyboard conventions remain intact. No dependency, worker, startup hook, native bridge or new UI framework was added. Windows/Linux runtime and alternate physical display scales were not tested here; arithmetic zoom/font floors and Swing geometry are covered locally.

## K. README and repository compliance

Before and after SHA-256 of `README.md`:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

No README modification occurred. Entry HEAD/status and hashes of 1,569 pre-existing files were recorded before edits. All starting files remain present; changes to existing files are limited to the 16 listed source/test/config-documentation paths. The pre-existing 8E.1 report, native probe/tooling and evidence remain unchanged.

An external commit advanced HEAD during the task, including capturing intermediate 8F work. It was preserved; the final working-tree corrections remain in place. The agent created no commits or pushes and performed no reset, clean, revert or discard. Entry/final identities, status, hashes, class/artifact comparisons and an entry-to-final patch for existing files are retained in the evidence directory so review is not limited to the remaining Git diff.

## L. Remaining limitations

This is a supported-component subset, not a global spacing rewrite. Editor line-height changes and bespoke component bodies are deferred. Large/custom fonts can constrain Compact savings. Large wrapped resize/navigation remains costly and lacks a definitive performance equivalence result. Native/whole-process memory and cross-platform performance remain unqualified beyond the stated observations. The 51 pre-existing Unicode corpus mismatches, complex-Unicode first-edit stalls, stock HTML-preview stalls, rejected live-presentation architecture and deferred transparency/blur/automatic system themes are unchanged.

## M. Next recommendation

**8G should be test-only qualification of existing active-pane emphasis.** `EditorUiController.refreshPaneFocus()` already uses a theme-derived two-scaled-pixel top border for active/inactive splits, removes that cue for a single pane, sets `shed.activePane`, and supplies accessible pane names. No new cue/control is justified by this audit. A search found no direct tests asserting these particular border/property/accessibility contracts. The smallest next task is to test them across theme preview, density, focus mode, split activation and disposal, then skip styling work unless a concrete defect emerges. No 8G implementation or test work was begun here.
