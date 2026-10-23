# Shared interface design

Shed's interface now uses one shared set of Swing control delegates, neutral dark and light palettes, and consistent UI typography. The scope includes editor chrome, menus and popups, search and replacement, docked and detached tools, settings, pickers, ordinary dialogs, and custom notebook and Git graph views. Source editing retains its separately configured font.

New configurations use `shed-dark`; `shed-light` is its matching light palette. Existing saved theme choices remain available. Use the theme gallery to select the new palettes in an existing configuration. Font and zoom settings continue to apply through the existing configuration system. See [CONFIG.md](CONFIG.md).

## Reference direction

The visual direction takes three concrete ideas from the requested references:

| Reference | Relevant behavior | Application in Shed |
| --- | --- | --- |
| [Sublime Text themes](https://www.sublimetext.com/docs/themes.html) | Interface styling is distinct from syntax coloring, with component states represented in the theme. | Shared controls use palette roles for surfaces, borders, focus, selection, and disabled text. Editor and interface fonts stay independently configurable. |
| [Atom One Dark UI](https://github.com/atom/one-dark-ui) | Interface colors adapt to the syntax theme, and interface sizing is configurable. | Existing `ConfigManager` themes supply every shared control color; `UiZoom` scales the control metrics. |
| [Zed appearance](https://zed.dev/docs/appearance) | Appearance settings distinguish editor and UI fonts and cover panels as well as the editing surface. | A plain UI font, quieter document and tool tabs, and consistent dialogs carry the design through the workbench. |

These are design references, not claims of feature or performance parity. The implementation uses Swing and Shed's existing settings; it does not copy product assets or introduce a browser layer.

## Full review scope and coverage

Review mode: **full**. Styling conventions remain `ConfigManager` palette roles, `EditorUiController` theme/font application, and `UiZoom` metrics. `WorkbenchStyle` supplies Swing UI delegates within that system. The application installs these defaults before creating its controls, reapplies them to existing windows when appearance settings change, and prepares newly opened dialogs with the same treatment.

The shared path covers buttons, toggles, checkboxes, radio buttons, fields, combo boxes, lists, tables, trees, tabs, scrollbars, dividers, progress controls, menus, tooltips, and Swing file choosers. This is implementation coverage; it does not imply that every dialog, operating system, or combination of settings received a separate visual audit. OS-owned title bars and external tools remain outside Shed's drawing code.

| Category | Evidence inspected | Result |
| --- | --- | --- |
| Typography | `EditorUiController`, settings descriptions and controls, open-document labels, Git graph and notebook rendering; final captures and their font diagnostics | Shared plain interface typography replaces mixed native control fonts. Separate code typography and relative heading sizes are preserved. Desktop tests verify custom sizes, repeated appearance changes, and new dialogs at 150%. |
| Surfaces | `WorkbenchStyle`, recursive theme application, search/replacement metrics, tool dock, settings, task and debugger layouts, theme gallery; [29 packaged-build captures](images/interface/gallery.json) | Dark/light, enlarged, detached, and narrow layouts passed fixed-control bounds checks. Every dock tab was checked at 760 pixels wide. Both debugger forms passed field/action scroll-reachability checks. |
| Animations | Shared delegates and modified view/event handlers | No custom animation introduced. Hover, press, focus, and selection feedback is immediate; standard Swing loading indicators retain their behavior. No 10% playback check applies to these changes. |
| Icons | `WorkbenchIcons` and disclosure/choice painting in `WorkbenchStyle`; document and dock action metadata | Close and detach use one scalable outline treatment with text alternatives. Selection and disabled states use the active palette. Native/custom icons elsewhere were not redesigned. |
| Performance | Staged-font guards, theme/font update paths, wrapping layout, clipped Git graph painting; [five packaged-build measurements](performance/interface-d990d019.json) | No added UI dependency or custom animation timer. Edit/paint p95 was 6.37 ms; memory passed its budgets. Startup and picker tail latency still miss their budgets. No controlled speed improvement is claimed. |

## Systemic findings and changes

Locations identify the implementation points reviewed at production commit `d990d019`. Severity describes the original problem, not an assertion that a completed fix remains broken.

### Typography and readable state

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM | `src/main/java/shed/ConfigManager.java:49`, `src/main/java/shed/ConfigManager.java:2252` | The default palette gave editor modes and surrounding surfaces a stronger changing tint. | Add neutral Shed Dark and Shed Light palettes, stable mode backgrounds, explicit surface roles, and clearer muted text. Keep saved theme choices. | Visual hierarchy: stable surroundings keep attention on code and intentional selection/focus states. |
| MEDIUM | `src/main/java/shed/EditorUiController.java:882`, `src/main/java/shed/EditorUiController.java:962`, `src/main/java/shed/EditorUiController.java:1262` | Native defaults and local overrides produced mixed font families, weights, and sizes across screens. | Resolve ordinary controls through the same plain UI font and configured size/zoom. Normalize newly opened controls against the active scale to avoid double scaling and preserve relative heading sizes. Preserve explicitly selected code fonts while keeping prose descriptions proportional. Keep editor and terminal font handling separate; avoid reinstalling delegates during font-only recursion. | Typography consistency and scalable controls make secondary screens as readable as the editor. |
| MEDIUM | `src/main/java/shed/SettingsEditorDialog.java:121`, `src/main/java/shed/SettingsEditorDialog.java:252`, `src/main/java/shed/ThemeGalleryDialog.java:178` | Repeated framing, separate boolean labels, long default values, and duplicated theme IDs crowded settings and theme selection. | Give settings rows consistent space, label checkboxes directly, separate descriptions from defaults, and retain full detail in tooltips/accessibility text. Show theme display names and a Current indicator, with IDs in tooltips. | Readable hierarchy: the value being changed stays distinguishable from supporting explanation. |
| MEDIUM | `src/main/java/shed/ShedHelpDialog.java:125`, `src/main/java/shed/MarkdownOutlineDialog.java:80` | Help and Markdown Outline relied on placeholder properties that the shared text-field delegate does not render, leaving unlabeled blank fields. | Add visible Search help and Filter headings labels associated with their fields. Let the Help field height follow its configured font. | Clear labeling: users can identify an empty search field before interacting with it. |

### Surfaces, interaction states, and layout

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| HIGH | `src/main/java/shed/WorkbenchStyle.java:14`, `src/main/java/shed/WorkbenchStyle.java:160`, `src/main/java/shed/WorkbenchStyle.java:185`, `src/main/java/shed/WorkbenchStyle.java:262`, `src/main/java/shed/SearchControlMetrics.java:36` | Native control painting could ignore dark background colors; search and settings had separate partial fixes, leaving inconsistent arrows, fields, and button states. | Install common button, choice, field, combo, and standard control delegates. Paint enabled/disabled, selected, hover, pressed, and focus states from shared roles. Search uses the same implementation. | Readable contrast and consistent feedback: actions remain identifiable in dark and light themes, including disabled and keyboard-focused states. |
| MEDIUM | `src/main/java/shed/EditorUiController.java:992`, `src/main/java/shed/EditorUiController.java:1343`, `src/main/java/shed/SettingsEditorDialog.java:362` | Applying colors to one panel did not reliably replace native painting in later dialogs or preserve one styling path. Settings maintained its own recursive renderer overrides. | Apply shared defaults and theme recursion to the workbench, existing windows, command popup, and newly opened editor-owned dialogs. Fit new dialogs to the screen and remove the window listener when its editor closes. Settings delegates appearance to that same path. | System consistency: opening another screen should preserve the selected appearance. |
| MEDIUM | `src/main/java/shed/WorkbenchStyle.java:286`, `src/main/java/shed/WorkbenchStyle.java:304`, `src/main/java/shed/WorkbenchStyle.java:328`, `src/main/java/shed/EditorUiController.java:1100` | Bright native scrollbar/divider regions, raised tab outlines, and dense table grids added competing structure. | Use quiet scrollbars without invisible focus targets, structural dividers, flat tabs with a selection accent, padded list/table rows, and restrained table headers. Common button and tab targets have a 40-pixel minimum before zoom. | Borders for structure and predictable hit areas: controls remain recognizable while data and selection carry more visual weight. |
| MEDIUM | `src/main/java/shed/OpenEditorsBar.java:22`, `src/main/java/shed/ToolWindowHost.java:32`, `src/main/java/shed/ToolWindowHost.java:59`, `src/main/java/shed/ToolWindowHost.java:114`, `src/main/java/shed/EditorUiController.java:1590` | A separate Tools header, large text actions, boxed document selection, and an empty command row consumed space around the content. | Integrate dock actions with the tab strip; keep both actions in a fixed two-column layout across zoom changes. Distinguish the active document with a subtle accent; hide the command row when it has no content or active input mode. Grow an automatically sized dock when selecting a denser tool; retain manually adjusted height and avoid shrinking it on data refresh or lighter tabs. | Visual hierarchy: frequent document/tool navigation remains visible without redundant chrome. |
| MEDIUM | `src/main/java/shed/WrappingFlowLayout.java:6`, `src/main/java/shed/EditorUiController.java:1069`, `src/main/java/shed/TasksToolPanel.java:53`, `src/main/java/shed/DebugToolPanel.java:230` | A wrapped FlowLayout could report only one row of preferred height. Task labels competed equally with fields; five stacked debugger sections squeezed their contents. | Account for every visible flow row, regroup task fields/actions, and present debugger stack/watch/breakpoint sections as tabs. Make breakpoint configuration sections vertically scrollable and reveal focused controls through their enclosing form. | Responsive layout: resizing or increasing UI zoom should preserve access to controls and leave usable space for results. |
| MEDIUM | `src/main/java/shed/DebugToolPanel.java:104`, `src/main/java/shed/ProjectReplaceToolPanel.java:101`, `src/main/java/shed/TestsToolPanel.java:34`, `src/main/java/shed/ProblemsToolPanel.java:87`, `src/main/java/shed/WorkspaceSearchPanel.java:38` | Dock toolbars placed in unconstrained west regions could extend past the window; equal-height replacement rows and wrapped Search actions could stretch fields unnecessarily. Long workspace paths could make the test-root selector wider than the dock. | Constrain Debug, Tests, and Problems controls to the available center width; put Debug and Tests status on a separate row. Give Search a full-width query and one wrapping options/action row. Let replacement fields, options, and filters use their own required height. Bound the test-root selector and expose its full selected path in a tooltip. | Responsive layout: wrapping only helps when the container constrains width and reports the resulting height; actions remain reachable as tool content and zoom vary. |
| MEDIUM | `src/main/java/shed/WorkbenchStyle.java:115`, `src/main/java/shed/WorkspaceSearchPanel.java:31`, `src/main/java/shed/ProjectReplaceToolPanel.java:57`, `src/main/java/shed/GitChangesToolPanel.java:46`, `src/main/java/shed/TasksToolPanel.java:45` | Empty lists and tables left large unexplained blank areas. | Draw muted empty-state guidance through the common list/table delegates, with task-specific text for search, replacement, Git changes, and tasks. Expose the full guidance as an accessible description. | Clear empty states: users can distinguish an empty collection and understand the next relevant action. |
| MEDIUM | `src/main/java/shed/NotebookPanel.java:92`, `src/main/java/shed/NotebookPanel.java:209`, `src/main/java/shed/NotebookController.java:49`, `src/main/java/shed/SyntaxUiController.java:495`, `src/main/java/shed/GitGraphDialog.java:168` | Notebook Markdown used hardcoded black/white output, mixed output types had different horizontal alignment, new cells could miss appearance updates, and Git graph text/rows used fixed font and sizing. | Refresh notebook source, text output, and Markdown previews from the active palette, align outputs to the same leading edge, retain code typography, and style newly added cells without rebuilding the whole notebook. Scale Git graph lanes and rows with the configured UI font/zoom; use palette colors and explicit focus/selection cues, with Home/End navigation scrolling the selected row into view. | All-screen consistency: custom painted and rendered content must follow the same appearance contract as ordinary controls. |
| LOW | `src/main/java/shed/ThemeGalleryDialog.java:208`, `src/main/java/shed/ThemeGalleryDialog.java:275` | Custom theme previews painted from the component origin, crowding their titled-border labels. | Reserve border insets and use the active font metrics and scaled spacing for code and palette previews. | Optical alignment and text spacing: preview content should remain separate from its section label at every UI scale. |

### Icons and restrained rendering

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| LOW | `src/main/java/shed/WorkbenchIcons.java:7`, `src/main/java/shed/WorkbenchStyle.java:146`, `src/main/java/shed/ToolWindowHost.java:59`, `src/main/java/shed/OpenEditorsBar.java:15` | Document and dock actions used visually heavy text buttons; disclosure controls inherited unrelated native treatment. | Paint small outline actions and disclosure marks at UI scale. Close/detach retain tooltips and accessible names; disabled icons use muted color. | Match icon weight to text and retain nonvisual meaning; small artwork does not require a small interactive target. |
| MEDIUM | `src/main/java/shed/GitGraphDialog.java:242`, `src/main/java/shed/OpenEditorsBar.java:79`, `src/main/java/shed/EditorUiController.java:1079` | Git graph painting traversed every row; appearance changes risked repeating the previous early-font-discovery startup regression. | Limit graph drawing to clipped visible rows and preserve staged-startup font guards while installing shared styles. | Performance and motion restraint: visual polish should avoid unnecessary paint work or new startup blocking. Timings are recorded separately from visual impressions. |

## Considered but rejected

| Location | Candidate | Rejected because |
| --- | --- | --- |
| Shared workbench | Add a separate look-and-feel dependency or a browser-based shell | Existing Swing theme, settings, and zoom paths can support the requested presentation directly. A second system would widen migration and startup risk. |
| Individual tool/dialog classes | Continue adding local button and combo painting fixes | The user requested every screen; local patches would keep producing inconsistent states and duplicate maintenance. |
| Document/tool chrome | Add animated hover, tab, or page transitions | These are frequent editing interactions. Immediate color/focus feedback supplies the needed state without repeated motion or an animation timer. |
| Global controls | Shrink all targets to imitate another editor's density | Cleaner structure can recover space while retaining common 40-pixel button/tab targets before zoom. Existing narrow surfaces need layout fixes, not uniformly smaller controls. |
| Theme selection | Force the new theme over every existing user configuration | A cleaner initial appearance does not require discarding a saved theme preference. New defaults and an explicit gallery choice support both cases. |

## Verification and verdict

Verification used the final production source at `d990d019`. The clean packaged JAR, visual gallery, and performance artifact share SHA-256 `1d661280d4ca8df9b398f105b2864508f7572d0ba1f9cf241c68b1174d145a4b`.

| Check | Observed result |
| --- | --- |
| Source review | Inspected shared style/theme/font paths and every changed screen integration listed above. Compared the previous Search capture with final editor, tool, settings, dialog, and custom-view captures. |
| Final headless suite and package | `mvn -B -q -Djava.awt.headless=true clean package`: 965 tests, 0 failures, 0 errors, 83 skips. Portable JAR packaged successfully. |
| Desktop behavior | `mvn -B -q -Djava.awt.headless=false test`: 965 tests, 0 failures, 0 errors, 3 environment-dependent skips. Includes choice-control keyboard actions, selection preservation, dialog typography, repeated theme/zoom changes, dock sizing, notebook appearance/alignment, Git graph navigation, and existing editing/search workflows. |
| Final visual captures | Standalone probe against the packaged JAR: 29 captures, 0 clipped fixed controls. All seven dock tabs checked at 760 pixels wide; both debugger forms' fields/actions scrolled fully into view. Normal dark, light 150%, Settings 150%, dialogs, detached Search, notebook, explorer, and Git graph are retained below. |
| Interaction states | Enabled, disabled, hover, pressed, selected, loading, and empty states captured. Keyboard actions and focus-painting source were reviewed. The host denied activation in the complete final gallery run (`keyboard_focus_observed=false`), so native focused-state captures and native focus traversal through both forms remain unverified in that run; form scrolling was checked programmatically. No custom animation playback check applies. |
| Startup/edit performance | Five completed fresh-process measurements and 125 warmed edit/picker samples. Edit/paint p95 6.37 ms and memory passed; startup and picker tail latency missed their budgets. The benchmark returned 1 for those misses. See [recorded results](PERFORMANCE_RESULTS.md#interface-refresh-2026-10-23). |
| Cross-platform and assistive technology | Not verified: other operating systems, external screen readers, and every theme/zoom combination require separate runs. Accessible metadata in code is not a substitute for those checks. |
| README constraint | `git diff 8b821980 HEAD -- README.md` is empty; README.md was not edited. |

Verdict: **Verified for the inspected macOS scope, with the native-focus and cross-platform boundaries above.** Functional tests and layout checks pass for the shared presentation and reviewed surfaces. Startup and picker tail latency remain separate performance work; these results do not establish parity with another editor.

## Captured screens

Compare [previous Search](images/workflows-search.png) with [current Search](images/interface/02-tool-search.png). All images below come from the packaged JAR identified above. The [manifest](images/interface/gallery.json) records logical sizes, fonts, bounds checks, focus availability, and the artifact hash.

| Scope | Captures |
| --- | --- |
| Workbench | [Editor](images/interface/01-editor.png), [Search](images/interface/02-tool-search.png), [Explorer](images/interface/09-explorer.png), [Notebook](images/interface/10-notebook.png) |
| Dock tools | [Git](images/interface/03-tool-git.png), [Debug](images/interface/04-tool-debug.png), [Tasks](images/interface/05-tool-tasks.png), [Tests](images/interface/06-tool-tests.png), [Problems](images/interface/07-tool-problems.png), [Replace](images/interface/08-tool-replace.png) |
| Dialogs | [Markdown Outline](images/interface/11-markdown-outline.png), [Git graph](images/interface/12-git-graph.png), [Command Palette](images/interface/13-command-palette.png), [Settings](images/interface/14-settings.png), [Keymaps](images/interface/15-keymaps.png), [Themes](images/interface/16-themes.png), [Help](images/interface/17-help.png), [About](images/interface/18-about.png), [File chooser](images/interface/19-file-chooser.png), [Confirmation](images/interface/20-confirmation.png) |
| States and scaling | [Control states](images/interface/21-control-states.png), [Detached Search](images/interface/22-detached-search.png), [Light 150%](images/interface/23-light-150.png), [Settings light 150%](images/interface/24-settings-light-150.png), [Narrow Search](images/interface/29-narrow.png) |
| Scrollable debugger forms | [Source](images/interface/25-narrow-debug-source.png), [Source scrolled](images/interface/26-narrow-debug-source-scrolled.png), [Functions](images/interface/27-narrow-debug-functions.png), [Functions scrolled](images/interface/28-narrow-debug-functions-scrolled.png) |

## Repeating the visual check

Build the portable JAR first. On a desktop with Java 21, the standalone probe opens an isolated fixture workspace, captures Swing root panes at 2× resolution, checks fixed-control bounds, and writes a JSON manifest. It uses a temporary home rather than the user's configuration. The notebook and Git graph use fixture data; no kernel, debugger, task, or remote service is started.

```sh
ui_java_home=$(scripts/resolve-toolchain.sh home)
mkdir -p /tmp/shed-gallery-classes
"$ui_java_home/bin/javac" -cp target/shed-2.0.0.jar -d /tmp/shed-gallery-classes src/benchmark/java/shed/InterfaceGalleryProbe.java
"$ui_java_home/bin/java" -Djava.awt.headless=false -cp /tmp/shed-gallery-classes:target/shed-2.0.0.jar shed.InterfaceGalleryProbe /tmp/shed-interface-gallery
```

The probe exits unsuccessfully for clipped fixed controls, unreachable debugger fields/actions, or setup/background failures. Scrollable content can extend outside its viewport. When the host grants focus, the debugger form check requests focus on each enabled control; otherwise it verifies ancestor scrolling programmatically. `keyboard_focus_observed` and `debug_form_native_focus_checked` record that boundary separately from `control_bounds_passed`. A false focus result requires a separate focused walkthrough. OS-owned title bars are excluded from the images.
