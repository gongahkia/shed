# Tranche 8H — Final integrated appearance qualification

## A. Executive verdict

**CONDITIONAL GO for the delivered appearance subset on the tested macOS/JDK configuration. NO-GO for general public-release readiness.** The supported focus, centering, manual-theme preview, density and active-pane workflows pass their integrated state/source regressions. Split retirement and exact Undo/Redo remain correct in the repeated diagnostic. No new source-corruption or Undo defect was observed.

This is a completed **qualification and decision**, not an unconditional accessibility, platform, memory or release certification. A caught gutter-paint exception, an incomplete startup control, and mixed-script component-capture artifacts remain recorded below. No production correction was made to obtain a passing verdict. The original six-feature appearance proposal is not fully delivered: transparency, blur and automatic theme following remain blocked/deferred.

The [evidence index](performance/appearance-tranche8h/INDEX.md) separates fresh observations, historical evidence, failed checks and unavailable qualification. No production Java, settings, dependencies, fonts, windows or UI behavior changed in 8H. The only new code is an explicitly invoked benchmark/visual inventory.

## B. Feature status matrix

| Feature | Final status | Supported contract / limit |
| --- | --- | --- |
| Focus mode | Delivered with operating limit | `:focus on\|off\|toggle`, existing Goyo/Zen paths, transient per-window presentation snapshot; command exit remains available |
| Centered writing | Delivered with operating limit | `:center on\|off\|toggle`; existing editor/scroll pane, font-metric column width, independent of focus and document source |
| Large wrapped focus/centering | Intentionally restricted | Affected wrapped panes exceeding 102,400 UTF-16 units reject without partial entry; this is not a general editable-file size limit |
| Manual theme preview | Delivered within one-main-window ownership model | Existing gallery, explicit Preview/Cancel/Apply and Save, latest-authority rollback, save-error handling; multiple-main-window live preview rejects |
| UI density | Delivered for enumerated chrome | Compact / Default / Comfortable, Appearance setting and `:density`; no editor line-height/font zoom changes |
| Active-pane emphasis | Existing feature plus verified correction | Theme-derived viewport top border, logical active pane and accessible label survive split closure; no source fading/animation |
| Split lifecycle safety | Delivered correction | Retired callbacks/views detach, surviving history remains owned by its buffer, Vim uses the correct buffer's history |
| Desktop background transparency | Blocked / NO-GO on investigated Java 21 decorated path | Undecorated alpha experiments do not establish decorated production support |
| Native macOS blur | Deferred behind failed transparency/integration gates | No production native material or blur control exists |
| Automatic OS theme following; preferred automatic light/dark themes | Deferred / NO-GO under current native appearance policy | Existing manual themes remain; no polling, pretend event adapter or inactive preference pair |
| Rich live typography; inline source image/Mermaid/math | Deferred, outside appearance scope | Separate preview is not inline source presentation; 4A remains NO-GO |
| Editable 50–100 MiB documents | Deferred / unsupported claim | Existing read-only projections do not qualify full editing |

Authoritative implementation/feasibility contracts remain in [8A](APPEARANCE_FEASIBILITY.md), [8A.1](DECORATED_WINDOW_TRANSPARENCY.md), [8D](FOCUS_MODE_CENTERED_WRITING.md), [8E](THEME_PREVIEW_AND_SYSTEM_APPEARANCE.md), [8E.1](SYSTEM_APPEARANCE_FEASIBILITY.md), [8F](UI_DENSITY_PRESETS.md), [8G](ACTIVE_PANE_EMPHASIS.md) and [8G.1](SPLIT_VIEW_LIFECYCLE_REMEDIATION.md).

## C. Integrated interaction results

Current headful regressions cover these combinations; the packaged controls add bounded performance/visual observations rather than substitute for the assertions.

| Interaction | Evidence / result |
| --- | --- |
| Default/custom/pre-hidden layout → focus → restore | Focus tests pass: visibility, dock size, dragged divider, independent centering, live tab preference and editor identity restore according to 8D policy |
| File/tab/workspace changes, closing splits | Current live pane tree remains authoritative; closed panes are not recreated. Workspace/session replacement ends old transient presentation; navigation and user edits are retained |
| Resizing, centering, wrapping, narrow windows | Font-based padding/hit testing tests pass; small wrapped Unicode, font and border tests pass. Unwrapped 10 MiB and wrapped 100 KiB packaged focus controls complete |
| Fullscreen and Goyo/Zen | Existing window-state preservation and command tests pass; this does **not** certify physical macOS Space/fullscreen transitions |
| Unsupported wrapped sources | Existing large-wrapped regression verifies conservative rejection and unchanged source/wrap. No guard was removed or limit raised |
| Preview A → B → cancel, accept, Close/Escape, save error | Lease/configuration/gallery tests pass; no temporary ID promoted by unrelated save; errors leave preview cancellable |
| Density changed during preview; cancel/accept afterward | Density remains an independent application preference; rollback resolves current theme authority, not old full configuration |
| Font changes during preview; focus/center during preview | Existing font/current-authority and combined-state assertions pass; source selection and independent presentation state remain |
| Second main window during preview | Preview is cancelled before constructing the new workbench; multiple existing mains reject live preview. Sample browsing/manual selection remain available |
| Density restart/custom fonts/splits/themes | Persistence, invalid fallback, targeted refresh, two-window preference synchronization and custom-font clipping-floor tests pass |
| Active pane across mouse/keyboard/buffer/picker/window transitions | Indicator, current theme roles and accessible labels pass the existing actual component/event tests; logical editor owner remains identified while a picker holds focus |
| Active/inactive closure, recreation and buffer switching | 8G.1 source/history/lifecycle tests pass across Plain/Vim/Emacs, including actual Vim Undo routing |

Exact restoration concerns presentation, not historical editing state. Explicit live UI actions retain 8D's conservative policy; shutdown/session replacement does not install an old layout into a new owner. Some test events are programmatically dispatched. Native keyboard, IME and window-manager coverage remains qualified in G.

## D. Source and editing integrity

The full suite passes checked Markdown edits, heading/subtree/list/task/table/link operations, navigation/index isolation, stale-result rejection, read-only protections, codecs/BOM/newlines/EOF, atomic-save and recovery contracts. Appearance integration assertions retain `FileBuffer`, `PlainDocument`, snapshot identity/revision, dirty state, history capability and caret/selection. No new text-change or LSP synchronization path was added. This is evidence for the tested contracts, not live external language-server certification.

Fresh rooted split diagnostic: one document, three batches of eight split closures, observations at 0/8/16/24 cycles. **Retired views = 0 and document-to-retired-caret edges = 0 at every batch**. The open buffer remains legitimately owned. Document listeners remain seven. It then completes **51 exact Redo/Undo pairs**, with the same document/history identities, final source and snapshot both `body\n`, final revision 105 and caret 0. The two 1 MiB lifecycle controls also report zero reachable closed views and seven listeners before/after twelve cycles.

The new visual inventory retains the same mixed-script snapshot/revision, dirty/history state and directed selection through all theme/density/focus/center operations. Its source assertions pass, but its caught paint exception means exit zero is **not** an all-clear visual result. Existing split tests additionally cover bounded history, Markdown/Unicode, BOM/CRLF persistence/recovery, late callback retirement, private empty documents on retired widgets, final-owner shutdown and IME focus-lost reset. No history is cleared to pass lifecycle checks.

## E. Accessibility and visual qualification

Eight component-root captures cover Shed Light and Dracula at all three densities, combined focus/centering, and an 800×600 narrow split window. Captured device transform is **2×**; fonts remain JetBrains Mono 16 and measured source line height remains **22 logical pixels** in all sampled states. These are Swing component paints, not native-title-bar or composited desktop screenshots.

Measured existing foreground/background ratios: source text **11.49:1 light / 12.05:1 dark**, selected text **9.55:1 / 7.17:1**. These exceed the repository helper's 4.5 threshold. They do not certify every syntax color, arbitrary custom palette, antialiased edge, disabled control or accessibility state. Source offset 4 round-trips through model/view geometry in both splits in every capture; broader Unicode geometry is covered by existing tests rather than inferred from that one offset.

The active scroll pane exposes `Active editor pane 2`, the other `Editor pane 1`; no source text is dimmed. Gallery theme list exposes `Built-in themes`; visible Preview in App / Apply and Save / Close buttons have matching accessible names, keyboard focusability and 40-pixel height in the sampled compact state. Zero-size nonfocusable scroll-delegate buttons are not counted as visible interaction targets. Custom-font/density regressions verify metric floors rather than imposing a mobile touch-size rule on Swing.

Default evidence is bounded and explicit: the 8F frozen pre-density comparison established 12 geometry and 36 sampled PNG equalities. Fresh 8H controls reproduce **10/10 complete-pair Default geometry/PNG-hash maps**, initial/restored across five workloads, including custom UI font and split/wrapped cases. The sixth pair is incomplete due to startup timeout. This does not establish full-application pixel equivalence.

**Visual findings prevent an unconditional visual GO:**

- Between combined focus/center and narrow capture, `LineNumberPanel.paintComponent:106` logs a caught `BadLocationException: Can't translate offset to line` during a queued repaint. Source/selection invariants subsequently pass. The code maps viewport points to offsets before validating them. [Inference] A temporarily unavailable/out-of-range view mapping during layout is plausible; the actual rejected offset was not captured. A transient missing gutter is possible, not proven from the final image.
- Inspected root images show overprinted heading text in the highlighted split and missing-glyph boxes for portions of mixed-script content. **[Unverified] Their equivalence to actual displayed window pixels, their cause, and whether appearance transitions induce them are not established.** Keep the images as a failed/unqualified rendering observation; do not claim Unicode typography or font fallback visually certified. No font or renderer was changed to conceal this.

No design change, contrast adjustment or glyph workaround is authorized here. VoiceOver announcements, high-contrast OS configurations, physical focus-ring navigation, monitor migration and native fullscreen remain **unverified**. Density/common chrome readability is supported by the sampled geometry/images, not all locales or every specialized panel.

## F. Performance and memory

### Environment and measurement boundaries

Mac15,12 / Apple M3, 8 CPUs, 16 GiB RAM, macOS 26.5.2 arm64; Temurin **21.0.13+9**, Maven **3.9.11**. Retina and external display connected; no monitor migration test. Load around 3.99/3.93/3.66, approximately 999.5 MiB pre-existing swap use and reported free-memory percentage 51. Raw per-run metadata is archived. No system settings, personal documents or unrelated applications were changed.

Frozen JAR SHA-256 **`95f27180c5d955217a8dd9e531d81fc4c1f25d6dc873d77fc0c51dca5ae67f4a`**. All **1,426** compiled production classes match the measured JAR byte-for-byte. Children use isolated homes, `-Xms64m -Xmx512m`, Plain mode, Shed Light unless intentionally switching themes, and bounded timeouts. Fresh JVM is not cold filesystem/font cache.

Existing tools were reused. Density: six planned fixture pairs, 4 KiB, 100 KiB wrapped, 1 MiB unwrapped/split/wrapped and 4 KiB with UI font 24. Baseline labels mean **same current JAR with density commands skipped**; candidate labels mean density commands executed. They are not older/newer releases. Theme references use existing manual theme selection versus temporary preview on the same JAR. Pane labels are two same-artifact lifecycle controls, not different styling implementations. Focus compares normal/focus phases within one current process.

Input timing is controller/action dispatch to the attributed completed source Swing paint, **not hardware key-to-photon**. Operation timings separately record dispatch/application, validation and synchronous forced paint. The latter do not prove that all queued EDT work or physical presentation has finished. Percentiles use nearest rank; first inputs/switches are retained separately. Do not sum independent percentiles. Raw per-sample EDT allocations remain available; no new production allocation optimization is claimed.

Appearance-preference initialization, standalone first-center cost and whole-process native graphics allocation were not separately instrumented. Startup includes current defaults and initialization; resize/scroll samples are in the raw controls. These boundaries cannot be inferred from warm typing or a requested GC.

### Current observations

| Workload / boundary | Result |
| --- | --- |
| Three fresh desktop JVMs: visible / source forced paint / full UI, observed p95=max | **1,646.56 / 1,985.60 / 1,995.60 ms**, within existing 2/3/10 s gates for this set |
| Desktop edit + synchronous paint, 75 samples | p95 **3.17 ms**; picker ranking p95 **8.44 ms**, not whole picker latency |
| Density 4 KiB, Default / Compact / Comfortable warm typing p95 | **2.38 / 1.20 / 0.85 ms** |
| Density 100 KiB wrapped, enabled arm only | **1.90 / 0.58 / 0.52 ms**; reference failed before workload |
| Density 1 MiB unwrapped | **2.15 / 1.34 / 0.97 ms** |
| Density 1 MiB unwrapped, two splits | **3.33 / 1.50 / 1.93 ms** |
| Density 1 MiB wrapped | **1.79 / 0.79 / 0.69 ms**; tab-return work remains much slower |
| Density custom UI font 24 | **2.11 / 1.03 / 1.14 ms** |
| Focus 10 MiB unwrapped, normal / focus warm typing | p95 **2.44 / 1.54 ms**, n=50 each; first inputs **10.58 / 1.81 ms** |
| Focus 100 KiB wrapped, normal / focus warm typing | p95 **2.14 / 1.04 ms**, n=50 each; first inputs **9.20 / 1.44 ms** |
| First density Compact switch including forced root paint | **3.08–5.32 ms** across enabled cases; twelve three-preset repeat cycles per process |
| First focus enable / disable including forced root paint | **2.85 / 3.95 ms** at 10 MiB; **15.15 / 15.75 ms** at 100 KiB wrapped |
| Preview first switch / cancel, small HTML preview open | **77.33 / 53.02 ms**; manual reference **79.76 / 63.91 ms** |
| Preview repeated switch / cancel p95, n=9 | **48.02 / 41.92 ms** with small HTML; **27.08 / 27.36 ms** at 100 KiB with split/focus |
| Preview accept/save | **36.05 ms** small HTML; **18.37 ms** split/focus; existing I/O included |
| Typing after preview cycles | p95 **2.09 ms** small HTML / **2.82 ms** split/focus, n=50 each |
| 1 MiB pane warm activation, n=60, two controls | Dispatch p95 **0.78–0.99 ms**; forced paint p95 **1.97–2.70 ms**, separate boundaries |
| Repeated split creation / closure, n=12 each per control | Dispatch p95 **2.39–2.55 / 1.04–1.06 ms**; validation/paint separately archived |

All completed warm source-typing sets meet 16 ms. That does not imply every input or explicit appearance operation is below 16 ms. Historical 8E first switching reached 148 ms; the newer small sample does not erase that bound or prove an optimization. First complex-Unicode insertion is **not** represented by the ASCII typed-action trials.

**Failures/slow work retained:** the fourth density child (100 KiB reference) failed the 30-second loaded-editor wait before emitting any workload event; RSS sampling peaked at 641 MiB. It was not retried or counted as passing. Only unexecuted cases continued in a second driver. Cause is **unattributed**, not established as density-induced. The remaining 11/12 density children complete. Large wrapped 1 MiB tab-return forced EDT totals reach **238.26 ms reference / 237.40 ms density-enabled**. Both have the same JAR; this supports recording an existing layout cost, not appearance speedup or universal responsiveness.

The three dedicated startup launches pass their sampled budgets, but neither override the timeout nor 7C's earlier first launch at 3.60 s visible / 4.12 s source. Startup reliability is **not fully qualified**. A driver setup attempt also failed toolchain resolution before launching any GUI child; supplying the already established toolchain environment corrected the setup. Both logs are retained, not represented as product success.

### Memory and lifecycle boundaries

- Small density live heap after requested diagnostic GC: approximately **13.1→13.6 MiB**, then **11.6 MiB** after the harness's explicit service cleanup. Candidate 1 MiB split: **26.0→35.2 MiB**, then **11.3 MiB**; this is a bounded endpoint, not a cumulative-growth slope or proof of zero temporary allocation.
- Theme/HTML control: manual **48.7→49.3 MiB**, preview **48.8→49.4 MiB** after cycles; split/focus approximately **13.9→14.4 MiB**. Nine closed galleries per child, **zero reachable** in every theme control; source/listener checks pass.
- Pane controls: approximately **24.0→24.2 / 24.1→24.3 MiB**, zero closed view references, seven document listeners throughout. Main editor weak references remain reachable after short disposal controls, as already observed in 8G.1. This does not identify a new root or prove a native leak. The focus harness's 10 MiB disposed used heap remains **88.4 MiB**; do not substitute the density harness's deeper service cleanup as an equivalent disposal boundary.
- Desktop readiness used heap **39.17 MiB p50 / 41.26 MiB max**; RSS **661.61 / 668.08 MiB**, sampled at readiness rather than a settled/peak pair. Density process sampled peaks **668–751 MiB** for completed cases; small HTML peaks **991.9 MiB manual / 939.8 MiB preview**, not a general memory-reduction result. Focus 10 MiB peak **892.8 MiB**. Committed heap and all RSS time series are archived separately.

Explicit GC is confined to opt-in diagnostics. No GC, polling, cleanup worker, listener or layout cache was added to production. These observations support bounded tested Java view/gallery ownership and ordinary editing, **not low native/process memory certification**. No NMT/native-resource attribution or genuine system-RAM-pressure experiment was repeated here. Original native-memory uncertainty remains.

## G. Cross-platform and unavailable verification

Actual headful execution is **macOS 26.5.2 / arm64 / JDK 21.0.13** only. Portable state/configuration/service tests run on that host. Windows/Linux GUI execution is **No access**; their visual/performance support is unqualified, not claimed from portable APIs.

Next qualification matrix: Windows supported JDK 21 and Linux X11/Wayland where supported; 100/150/200% scaling, default/custom fonts, narrow/wide windows, all densities, light/dark/manual preview, focus/center bounds, shared splits, actual mouse/keyboard pane navigation, picker focus, save/recovery, disposal and packaged media. Include macOS Intel/other supported OS/JDK/display combinations before expanding claims. Native IME and VoiceOver/screen-reader interaction require separate actual-runtime sessions. Use safe opaque/manual fallbacks; native blur/transparency/automatic following remain unavailable.

The inherited Robot focus workload timed out in 8G.1 on its starting artifact. 8H explicitly uses `--lifecycle-only`, records native focus as NOT_REQUESTED, and relies on current deterministic routing/pixel tests for their stated scope. No fresh native-focus success is claimed.

## H. Regression results and commands

All commands use the existing toolchain environment `/tmp/shed-tranche2c-run/toolchain.env`, `PYTHONDONTWRITEBYTECODE=1`, and a copied frozen artifact; no package rebuild or production dependency change is needed.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" '-Dtest=UiDensityTest,ThemePreviewTest,AccessibilitySupportTest,WorkbenchLayoutTest,BoundedUndoManagerTest,FileBufferTest,TexteditorSwingIntegrationTest#focus*+centered*+goyo*+density*+activePaneIndicator*+splitLifecycle*+liveThemePreview*+livePreviewCancellation*+galleryAcceptance*+gallerySaveFailure*+openingAnotherWorkbench*+savingSettingsDuringPreview*' test
"$MVN" test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8h-run/qualification.jar --output /tmp/shed-tranche8h-run/packaged-smoke.json
python3 scripts/benchmark-density.py --jar /tmp/shed-tranche8h-run/qualification.jar --baseline-jar /tmp/shed-tranche8h-run/qualification.jar --output /tmp/shed-tranche8h-run/density-matrix
python3 /tmp/shed-tranche8h-run/remaining-density.py
python3 /tmp/shed-tranche8h-run/final-controls.py
# exact source inputs, JVM argv, finite matrices and paths are retained in the archived drivers/reports
# independently compile AppearanceQualificationProbe, SplitLifecycleDiagnostic, MarkdownPerformanceProbe and MarkdownLexicalProbe against the JAR
# invoke those probes with scratch homes; exact commands are in final-controls.json

git diff --check
```

**Focused: 72 passed, zero failures/errors/skips. Full Maven: 1,616 passed, five skipped, zero failures/errors (1,621 total across 262 suites). Tooling: 47 passed. Packaged image/math/Mermaid smoke: PASS.** No uncaught EDT exceptions in focused/full Maven logs. The separate visual probe's caught exception is not hidden by those successful totals.

Skips: DOS hidden attributes unavailable; Fish unavailable; CMake/CTest/CPack unavailable; opt-in AI performance unset; opt-in native AI acceptance unset. Exact test identities/reasons are archived. Live hosted AI, external-language-server, debugger/remote infrastructure and native acceptance are not certified merely because their deterministic regressions pass.

Benchmark outcomes: density **11 completed / one load timeout**; focus **2 completed**; theme **4 completed**; pane lifecycle **2 completed**; desktop **3 completed**; rooted history **1 completed**; visual inventory **completed with warning and unqualified mixed-script rendering**. No assertions were weakened, delayed to mask races or skipped to make counts pass.

## I. Known limitations, kept separate

**Appearance:** blocked decorated transparency/native blur/OS-following; 100 Ki wrapped focus/center guard; single-main-window preview ownership; selected density surfaces only; first theme switches occupy the EDT for tens to historically ~148 ms; actual accessibility/platform interactions remain incomplete. Newly observed gutter-paint warning and component-capture uncertainty remain open.

**General Shed:** confirmed multisecond production Unicode first-edit stalls (1 MiB ASCII→complex **4.85 s**, mixed-script first edit **6.70 s**); **51 remaining Unicode 15.1 conformance mismatches**; stock HTML-preview EDT stalls; native-memory/low-system-RAM uncertainty; incomplete cross-platform performance. Retained 7C evidence includes preview open/input delay ~826 ms at 1 MiB and small rich first-use ~2.08 s. These were not rerun to manufacture another result. GB9c correctness and split lifecycle fixes do not solve layout performance. The 7B.4 paragraph-range prototype remained 2.25–5.19 s and NO-GO; it is not a production improvement. Live source typography/inline media and editable 50–100 MiB remain deferred.

## J. Appearance readiness decision

| Readiness level | Verdict | Basis / requirement |
| --- | --- | --- |
| Supported personal/development use | **CONDITIONAL GO** | Useful tested features, exact source/history controls; accept stated text/layout/platform limits and investigate the retained visual warning |
| Delivered appearance functionality | **CONDITIONAL GO** | State/persistence/lifecycle subset qualified together; no claim that all six originally approved features ship. Native accessibility/rendering and incomplete startup control preclude an unconditional stable-everywhere verdict |
| General public release | **NO-GO** | Unicode first-edit and HTML-preview P1 blockers persist; native-memory, Unicode/platform and accessibility qualifications incomplete |

Passing tests do not override separately reproduced performance failures or visual warnings. Original performance-first ambition remains NO-GO as documented in 7C.

## K. Remaining issue register

| Priority | Evidence and classification | Smallest separately reviewable next action / gate |
| --- | --- | --- |
| P0 | No new data loss, corruption or broken history observed; not a universal guarantee | Preserve all source/history/codec/recovery/stale-target regressions in future changes |
| P1, existing release blocker | Production complex-text first edit takes seconds; 7B.4 local prototype rejected | Separate maintained-runtime compatibility screen and source-layout architecture decision; no revival of live Markdown prototype or speculative local patch. Require real completed-paint, geometry/Unicode, memory and lifecycle gates |
| P1, existing release blocker | Synchronous stock HTML-preview EDT stalls | Separate bounded preview operating-limit/product decision and targeted architecture plan; no renderer changes in appearance work |
| P2, new confirmed symptom | Caught gutter `BadLocationException` during split focus/center exit + narrow resize | Capture rejected model offset/geometry, establish deterministic regression including queued paint, then propose a local valid-range/lifecycle correction if justified. No exception suppression or design change as a substitute; gate exact source/selection, gutter paint and resize behavior |
| P2, new qualification gap | One loaded-editor timeout before density operations; three dedicated startup launches subsequently pass | Instrument the bounded startup/load wait and compare artifact-identical independent controls. Do not attribute density or silently retry until green |
| P2, rendering observation, production attribution unverified | Root images contain mixed-script missing glyph/heading overprint | Compare component capture and owned native-window pixels on identical fonts/scale, then trace source highlighting/font fallback only if actual display failure is confirmed. No font substitution to pass |
| P2, existing qualification debt | 51 corpus mismatches; native memory/main-window collection, accessibility and cross-platform execution incomplete | Triage actual editing effects separately from corpus mismatch; root native/window ownership before claiming leaks; execute stated OS/scaling/assistive-technology matrix |
| P3, deferred capability | Transparency, blur, automatic theme following; broader density/specialized panels and independent multi-window preview | Defer until new evidence/product need. Do not start another speculative native bridge or redesign to complete a checklist |

No new appearance-specific P0/P1 is established by these observations. The caught painting symptom and incomplete tests still limit the appearance verdict. Stop at qualification; none of these repairs is implemented here.

## L. Repository compliance

Entry and final verification HEAD: **`7469f4188c1c30675b2549e6a7ecdfbb1f1788f4`**. No commits or pushes were made. Entry changes are preserved: modified `docs/SPLIT_VIEW_LIFECYCLE_REMEDIATION.md` and `docs/performance/appearance-tranche8g1/repository-verification.json`, plus untracked `appearance-tranche8g1/INDEX.md` and `checksums.sha256`.

8H adds only:

- `src/benchmark/java/shed/AppearanceQualificationProbe.java`: opt-in root-image/accessibility/contrast/source inventory; no normal build/startup hook.
- `docs/APPEARANCE_FINAL_QUALIFICATION.md`: this report.
- `docs/performance/appearance-tranche8h/`: indexed raw/summary/test evidence, drivers, images, preservation hashes and file manifest.

All **1,716 starting tracked/untracked source/script/doc/asset/build-input files** retain their entry hashes, including prior uncommitted evidence. No production files, normal tests, defaults, source ownership, dependency manifests or saved user preferences changed. New files and exact Git status are enumerated in `repository-verification.json` and `manifest.json`. `git diff --check` passes; new authored text receives a separate whitespace check. Default theme/font remain Shed Light / bundled JetBrains Mono, and saved preferences remain authoritative.

README before and after SHA-256, identical:

```text
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e
```

## M. Next engineering roadmap — audit handoff only

**First small task:** diagnose the retained gutter geometry warning and native/component capture discrepancy without altering fonts or source architecture; turn a confirmed production failure into its own narrow regression/remediation proposal. This is not automatic authorization to implement it. Keep the existing P1 release blockers at higher product severity, even if this smaller investigation is cheaper.

### Track M — Remaining Markdown work

Audit baseline: 7C, Unicode correctness/layout/feasibility reports, source-view/highlighting, preview, semantic consumers/index and media lifecycle. Separate working Markdown commands/navigation from missing presentation ambitions. Prioritize production Unicode responsiveness and preview-input interference, then confirmed editing effects of conformance gaps. Revisit native-memory attribution and supported low-heap/system-memory evidence with bounded controls. Decide architecture funding/operating claims before new renderer experiments. Require exact UTF-16/grapheme/caret/selection geometry, checked edits/history/persistence/recovery and representative first-use/warm completed-paint gates; preserve 1B/1D bounds. Deferred rich live editing and 50–100 MiB editing are separate investments, not fixes hidden in appearance.

### Track B — Whole-IDE benchmarking

Begin with an inventory-to-test/metric map against current routes, not a claim that every feature is complete. Actual code includes local editing/open/save/recovery/search/replace, LSP/completion/diagnostics, PTY terminals, Git/SCM, tasks/build and test runners, DAP debugging, file trees/watch/index, notebooks, remote/Dev Container sessions and existing AI workflows. Use `ARCHITECTURE.md`, command registrations and controllers/services to distinguish shipped, partial, explicitly external and unsupported operations.

Define bounded local fixtures and matched controls for startup, source input, multi-document/split lifecycle, file I/O, ranking/search/index, terminal output, Git refresh, LSP request/cancellation and task/test/debug process cleanup. External tools require explicit opt-in isolated peers/projects, timeouts and reproducible skips; never run arbitrary project tasks or credentials by discovery. Measure EDT input/paint separately from worker completion/queues and process RSS/heap. Retain first-use samples, source hashes, false/partial results and cleanup ownership. Establish regression budgets from evidence before optimizing. Track B is a benchmarking audit, not an IDE rewrite or assurance inferred from Markdown tests.

### Track A — Agent-controllable Shed

**Repository evidence changes the starting assumption:** `WorkspaceCli`, `WorkspaceRpc`, `WorkspaceQueryService`, `WorkspaceBridgeClient/Server`, proposal transactions and `AGENT_WORKSPACE.md` already exist. The CLI supports explicit `--workspace-api --root ...`, disk-only queries by default and opt-in authenticated loopback attachment via `--connect`. Query routes include workspace info, documents, bounded reads, file listing and text search; bridge-backed proposal routes include propose/revise/apply/undo/rebase and recovery operations under policy. Existing Assistant/OpenCode ACP integration is a separate current workflow. Do not propose implementing these foundations a second time or treat them as fully audited remote GUI control.

Initial audit deliverable: a capability/ownership/security matrix for launch/connect, enumerate/current pane-document identity, versioned source reads/transactions, cursor/selection movement, explicit file opening, diagnostics, rendered-file display and bounded visual capture. Compare existing `DocumentWorkspace`, `EditorSurface` (EDT/UTF-16), checked workspace proposals and lifecycle/request-generation boundaries with what RPC actually exposes. Cursor control, diagnostics and visual capture are **not advertised by the inspected query dispatcher**; inventory other existing routes before proposing additions.

Security gates: local-only explicit capability/authentication, owner-only descriptors, root/symlink/exclusion/protected-path policy, request/output/time/resource limits, revocation and shutdown, captured document/version/selection preconditions, human edit conflict/review, idempotent bounded transactions and Undo. Never translate machine requests blindly into arbitrary ex/shell commands or persist previews/capture unrelated applications. Use local deterministic peers and synthetic fixtures first. ACP/MCP is not a prerequisite or first-design assumption; reuse current transport only after its contracts meet the required audit.

**Dependency order:** record M's release blockers and B's current behavioral/performance/security baseline first; A's read-only capability/security audit may proceed independently, but new mutation/navigation/capture APIs depend on tested document ownership, revision coordination, cancellation and B's lifecycle coverage. Architectural M work and A implementation require separate approval/scoping; none begins in 8H. Appearance research does not conceal the unresolved release blockers. No 8I, Track M/B/A implementation, agent integration or deferred appearance/native work is started.
