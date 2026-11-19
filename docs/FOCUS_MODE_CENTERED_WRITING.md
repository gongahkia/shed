# Focus mode and centered writing — Tranche 8D

## A. Summary

8D extends the existing Goyo/Zen focus layout rather than introducing another editor. Focus hides the established header, document strip, Explorer, minimap and tool windows; the existing command/search footer remains available when needed. Exiting restores captured visibility and dock presentation without undoing source edits or navigation. Centered writing can also be enabled independently. The verified subset supports unwrapped sources and wrapped source documents up to 100 Ki UTF-16 units. When an applicable source pane exceeds that wrapped limit, new focus/centering requests reject with existing-style feedback and leave presentation unchanged.

The original restoration defect was reproduced before production edits: a test entered from pre-hidden chrome, toggled Goyo twice, and observed that the header was incorrectly forced visible. The passing regression retains that assertion. An additional resize test exposed stale writing insets during Swing layout; deferred, coalesced geometry updates corrected the reproduced case.

Transparency and native blur remain blocked by [8A.1](DECORATED_WINDOW_TRANSPARENCY.md). This tranche does not change that decision or begin themes, density, pane emphasis or later qualification work.

## B. Existing architecture reused

`FocusModeController` remains the sole focus/centering owner. It uses existing component visibility, `EditorPane`, `WindowLayoutNode`, scroll-pane borders, `ToolWindowHost` hide/restore, theme colors and editor-focus conventions. `FileBuffer`, its `PlainDocument`, `SwingEditorSurface`, source rendering, keymaps and syntax highlighting are retained.

No new panel, view, dialog, overlay, toolbar, renderer, parser, framework, native hook, background worker or persistent cache was added. Existing Zen/Limelight behavior is reused; focus itself adds no paragraph dimming. Goyo remains the existing toggle route.

## C. Implementation inventory

| File | Purpose |
|---|---|
| `src/main/java/shed/FocusModeController.java` | One temporary focus capture; precise restoration; independent centering; bounded padding; coalesced geometry updates; disposal. |
| `src/main/java/shed/ToolWindowHost.java` | Capture/restore prior dock height, automatic-sizing state, selected tab and original session payload; retain existing detached-window ownership. |
| `src/main/java/shed/WindowLayoutNode.java` | Remember actual displayed divider ratios at entry instead of replacing the live layout tree. |
| `src/main/java/shed/EditorUiController.java` | Size splits from actual host dimensions; queue centering after scroll-pane/viewport resize and font changes; retain the normal border-free source scroll pane. |
| `src/main/java/shed/CommandHandler.java` | Register `focus` and `center` through existing dispatch. |
| `src/main/java/shed/PaletteController.java` | Existing-style focus toggle/exit and centered-writing actions. |
| `src/main/java/shed/SessionConfigController.java` | Save the pre-focus dock state; end transient presentation before replacing session panes. |
| `src/main/java/shed/WorkspaceController.java` | End old focus/centering presentation before a workspace-root replacement. |
| `src/main/java/shed/Texteditor.java` | Clear focus presentation state on window disposal. |
| `src/test/java/shed/TexteditorSwingIntegrationTest.java` | Thirteen deterministic focus/centering integration cases, using existing fixture/lifecycle helpers. Prior tests retained. |
| `src/test/java/shed/CenteredWritingLayoutTest.java` | Three pure font-width, narrow-window and numeric-bound tests. |
| `src/benchmark/java/shed/FocusModePerformanceProbe.java` | Opt-in actual production-editor controls, compatible with the frozen pre-8D JAR. |
| `scripts/benchmark-focus-mode.py` | Bounded fixture/JVM driver, paired artifacts, raw source-paint/toggle/memory measurements. |
| `scripts/test_focus_mode_tooling.py` | Three fast admission/fixture/matrix tests; no GUI in routine tooling tests. |
| `docs/FOCUS_MODE_CENTERED_WRITING.md` | This report. |
| `docs/performance/appearance-tranche8d/` | Raw logs, reports, original inventory, verification, measurements and full added/modified-file inventory. |

## D. State ownership

The [minimum state design](performance/appearance-tranche8d/minimum-state.md) was recorded before production implementation. It deliberately captures presentation rather than documents:

- One focus session per `Texteditor` window: prior header, tabs, footer, status and command visibility; prior independent-centering flag; prior hidden state of surviving Explorer panes; and minimap visibility.
- The tool host captures its original visibility, selected tab, dock height/automatic-sizing state and existing serialized dock payload. Its existing map owns detached windows; no second native-window registry was introduced.
- The existing layout tree remains authoritative. Actual visible divider ratios are remembered at entry; source panes, buffers and source revisions are not copied into a second layout model.
- Centering retains only base/applied border references and padding per live pane. Weak keys and pruning remove closed-pane entries. The focus capture uses weak component/pane keys and primitive/enum tool data, with no source snapshot or Undo history.

Entry is idempotent and does not replace the capture on a second `focus on`. Exit is also idempotent. Disposal drops the capture and centering map without restoring a dead component hierarchy. A pending layout callback checks the owning controller's closed/current-centering state and cannot enable a mode by itself.

Focus state is per window, not a global saved preference. Source navigation/view state remains per pane and canonical source remains per buffer. Workspace/session layout replacement ends transient focus/centering before the new layout takes over. Ordinary tab/file switching within the current workbench preserves focus mode. No captured tree is restored into a different workspace or session.

## E. Centered writing

`center on` uses each existing **source scroll pane's own width**, not the width of the whole editor host for every split. It subtracts the current base border, gutter/row header, vertical scrollbar and viewport-border widths. Remaining horizontal space beyond the requested writing column becomes equal left/right padding.

The requested column is `zen.mode.width × current font's M advance`. Effective columns are bounded to **20–240** and the multiplication uses a wide integer. This is a comfortable visual-width approximation, not a claim that every Unicode grapheme or proportional-font character has the same width. The existing default width is reused; no new persisted setting was introduced or preference rewritten.

When space is narrower than the requested column, extra padding becomes zero. The source component, scrollbar policies, font size, tab width, wrap flag and wrap style remain unchanged. Unwrapped long lines retain horizontal scrolling. Wrapped text uses the existing Swing wrapping implementation at the narrower viewport width. Preview, terminal and alternate-surface panes retain their existing presentation rather than acquiring source-writing padding.

There is an explicit performance boundary: **focus and centering reject applicable wrapped documents longer than 102,400 UTF-16 units**. A provisional paired run demonstrated ~122 ms single-pane and ~260–268 ms split-pane whole-root forced paints during 1 MiB wrapped transitions. An intermediate width-only fallback still measured ~114–275 ms, so width changes alone do not explain the whole-root cost. That fallback was insufficient and was not accepted. Root-versus-source painting attribution remains separate work; no gutter/view optimization was attempted. The old focused split case clipped its source, so its cheap paint is not a valid working comparison.

The final guard leaves current presentation unchanged for unsupported entry requests, without disabling wrapping, mutating preferences, changing semantic limits or adding a layout cache. If an active presentation encounters such a source during a later presentation/layout update, it restores the captured layout and ends transient centering. The size check occurs at these presentation boundaries, not in a new document-change listener. Explicit `off` remains available. Source editing itself remains available in the ordinary editor.

Borders compose with the existing base border and are updated only when the base or padding changes. Theme/UI border replacement is recognized instead of stacking old focus padding. Turning centering off restores the surviving base border. The normal source scroll pane remains border-free as before; active-pane viewport borders are not repurposed.

A resize initially reproduced stale insets: reading geometry during a Swing resize/layout transaction was insufficient. Scroll-pane resize, viewport resize, font and wrap-property changes now request **one pending EDT update** after layout. This is event-driven, not a timer or worker. It does not listen to document text changes or scroll-position changes, and adds no document-sized state. Tests wait for actual geometry convergence and verify mouse-to-source mapping after the supported changes.

## F. Layout restoration and policy

| Situation | Defined behavior |
|---|---|
| Default / customized chrome | Restore captured visibility of surviving components, including regions already hidden before entry. Ordinary mode changes no longer force them all visible. |
| Tabs preference changed during focus | A net change in the existing configuration preference wins on exit; focus does not write configuration. Outside focus, changed tab preferences still propagate. |
| Temporary programmatic visibility | Focus owns temporary chrome visibility; exit restores entry presentation. Explicit tools can still be shown for IDE work, then return to the prior dock state on exit. |
| Explorer already hidden | Preserve its prior hidden flag. A closed pane is not resurrected. |
| Minimap | Hide/show the same existing component. Repeated focus toggles do not destroy it or register new viewport listeners. Explicit removal remains removal. |
| Dock / detached tools | Restore prior selected dock tab and remembered height; preserve the existing automatic-height behavior and existing detached-window restoration path. |
| User navigates or moves caret | Keep the current surviving active editor, document, caret and selection. Exiting does not jump back to the entry document or undo editing work. |
| Splits added/closed while focused | Preserve the live topology and current buffer ownership. Closed panes are pruned from presentation metadata. |
| Window resized | Use current available dimensions and remembered split proportions; existing minimum-size/collapse behavior remains. Fixed original dock height is restored where it fits, subject to existing minimums. Pixel equality is not promised across different window sizes. |
| Independent centering before entry | Restore that prior centering flag. A `center` change during focus is temporary for that focus session. |
| Workspace-root / session replacement | End the old transient presentation before the replacement. Independent centering is also cleared for the replacement context. |
| Large wrapped pane encountered | Reject new focus/centering entry without a UI change; an existing transient presentation ends when the unsupported context is observed at a layout update. |
| Session save / shutdown | Serialize the original dock visibility/selection/ratio while focus is active, not its temporary hidden state. Focus/centering themselves remain transient. |
| Disposal / late callback | Clear transient maps/capture; queued centering work checks disposal and does nothing afterward. |

The layout tree is not cloned or rewound. Existing split commands and new/closed panes remain live. A divider dragged before focus is remembered; the regression verifies its position after toggling at the same window size. This tranche does not introduce a general-purpose layout-history or arbitrary plugin-component restoration framework.

## G. User interaction

Existing command mechanisms expose:

```text
:focus [on|off|toggle]
:center [on|off|toggle]
:goyo toggle
:zen toggle
```

No argument means toggle for the new commands. Invalid arguments return existing-style usage feedback. The palette includes **Toggle Focus Mode**, **Exit Focus Mode**, and **Toggle Centered Writing**. Existing Goyo, Zen, Limelight and minimap actions remain. No competing global shortcut was added.

The existing command/search footer is revealed while the editor is in command/search mode, so hiding chrome does not remove those editing routes. Plain/Vim/Emacs share the established dispatcher and palette. Exit requests focus through `requestActivePaneFocus`, preserving its handling of source, alternate, preview and terminal surfaces.

## H. Correctness evidence

The focused suite covers pre-hidden chrome, idempotent commands across configured keymaps, command-footer exit, minimap identity/listener counts over twelve cycles, shared document identity in splits, per-pane width, source navigation and pane removal, font/wrap/window changes, UTF-16 model/view hit testing with combining characters/emoji/CJK/tabs, independent centering, changed tab preferences, hidden-tree restoration, dock dimensions, workspace changes, disposal, dragged dividers, session serialization/replacement, and edits with one-step Undo/Redo.

The BOM/CRLF/Unicode fixture remains the same authoritative document through toggling; its revision, selection, dirty flag and empty Undo history are checked, followed by an explicit byte-identical save. Edits made while focused remain present after exit, and existing Undo/Redo restores those edits normally. Rendering/centering does not create source edits. No new source listener, LSP text-change path, persistence codec or recovery mechanism was introduced.

These tests are deterministic Swing/programmatic integration checks. They are not physical-device IME, screen-reader, native macOS Spaces/fullscreen or Windows/Linux headful certification. The window-state test verifies existing state flags are not changed by toggling; native fullscreen transitions remain a platform qualification item. Prior recovery, LSP, preview, highlighting, navigation, Unicode and keyboard regressions are run separately in the full suite.

## I. Performance and memory

Final measurements and their limitations are recorded in the evidence directory. The probe measures the actual production editor against the frozen pre-8D packaged JAR, using the same Java 21 runtime, fixture generator and source-paint instrumentation. It separates typed input-to-attributed-paint from explicit Undo/scroll/toggle forced-paint observations. A direct Undo is not falsely described as producing the keyboard input-to-paint marker.

The matrix includes small, 100 KiB, 1 MiB and supported 10 MiB sources; 1 MiB wrapped/unwrapped and single/split controls; source typing, toggle dispatch, validation, forced root paint, scrolling, twelve repeated toggle cycles, resizing, heap and process RSS. Both artifact hashes, source hashes, host load and raw measurements are retained. Unsupported or missing paint observations remain explicit; they do not become zero-latency samples.

Production changes add no semantic parse, full-document copy, source model, background worker, persistent cache or eager renderer/font initialization. Geometry metadata is proportional to live panes/components. Existing source-width and lexical-highlighting implementations are unchanged. The pre-existing Unicode first-edit and stock HTML-preview layout failures remain open.

## J. Regression verification

Exact commands, final counts, five existing skips, packaged-smoke output and any failed exploratory runs are retained in the verification record. The initial restoration test failed before implementation and subsequently passed. Early diagnostic tests and benchmark admission/paint-attribution corrections are not discarded or presented as passing qualification data.

```sh
source /tmp/shed-tranche2c-run/toolchain.env
export PYTHONDONTWRITEBYTECODE=1
"$MVN" '-Dtest=CenteredWritingLayoutTest,WindowLayoutNodeResponsiveTest,CommandParsingTest,TexteditorSwingIntegrationTest#focus*+centeredWriting*+workspaceChangeAndDisposal*' test
"$MVN" test
"$MVN" -q -DskipTests package
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/test-packaged-markdown.py --jar /tmp/shed-tranche8d-run/production.jar --output /tmp/shed-tranche8d-run/packaged-smoke.json
git diff --check
shasum -a 256 README.md
```

Heavy focus benchmarks remain opt-in and are not added to routine Maven tests.

## K. UI/UX compliance

The only intended visual changes are temporary chrome hiding, padding/centering of the existing source viewports, and restoration. Existing controls, pane styles, source rendering, syntax colors, fonts, default theme, menu/palette design and IDE interactions are retained. No transparency/blur, theme preview, OS theme following, density, new pane-emphasis treatment, live Markdown presentation or custom editor was introduced.

## L. README and repository compliance

README SHA-256 before/after is **`48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`**. README is not modified. Shed Light, bundled JetBrains Mono, configuration defaults and saved-preference files are unchanged by focus/centering commands.

The starting inventory records 1,476 pre-existing files and HEAD `2e3cfd8e7b5618d0cc330a77e1967d47a699651b`, including pre-existing uncommitted work. During the task HEAD advanced externally to `807db20af0c08cb42dea84777dcb69b7ee1318ce`, capturing prior and partially completed work. The agent issued no commit/push/reset/clean/revert command and preserved that commit. Repository verification compares content with the start inventory rather than claiming a clean checkout or unchanged HEAD.

## M. Remaining limitations

- Opaque presentation remains the default. Transparency/blur have not been reopened.
- Centered writing applies to source scroll panes; terminal, preview and alternate workbench surfaces retain their existing geometry.
- Focus/centering are unavailable when an applicable wrapped source exceeds 100 Ki UTF-16 units. Window resizing, enabling wrapping itself, and ordinary root repaint remain core editor operations with outstanding wrapped-layout costs; no universal wrapping/resize latency guarantee is made.
- Exact restoration applies to surviving components and unchanged-size layout where meaningful. New/closed panes and source navigation are not rolled back; resizing uses existing layout minimums and proportions.
- Workspace/session replacement intentionally ends transient modes instead of restoring a stale capture later.
- Stock wrapped text and HTML-preview reflow, Unicode first-use stalls, remaining Unicode corpus mismatches, native-memory attribution and cross-platform performance remain existing qualification concerns. Focus mode does not solve them.
- Native fullscreen/IME/accessibility and other operating systems need platform testing; passing programmatic Swing tests is not a claim of full platform certification.

## N. Next recommendation

8E can be considered separately after reviewing these results; it is not started. Its minimum prerequisite is a precise **runtime versus persisted theme transaction**: manual saved preferences keep precedence, OS events do not save themes, temporary preview captures one prior runtime theme, cancel/disposal restores it, and explicit commit alone persists. Reuse the current theme registry/gallery and serialize/coalesce EDT application. Verify theme changes while focus/centering are active and test costly Unicode/preview reflows before adding an OS appearance observer. No new theme ownership or persistence policy is implemented in 8D.
