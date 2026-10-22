# Shed editor comparison and improvement plan

Review dates: 2026-10-22–23. Baseline: `d45c51ff`. “LightXL” is interpreted as **Lite XL**.

## What Shed sets out to do

Shed began as an opinionated modal editor. The implementation now offers a nonmodal default, Vim and Emacs profiles, and a substantial local development workbench. Its useful product identity is a keyboard-friendly, customizable desktop editor with explicit local tooling, recovery, and no telemetry. Preserving that identity means making ordinary editing dependable before adding more integrations.

At the baseline revision, the Java 21/Swing application contains 288 production Java files and 176 test files. `Texteditor` is both the composition root and shared state owner; feature controllers receive it directly. Pure services provide useful test seams, while `AsyncJobService` marshals background results to Swing's event thread. This architecture makes incremental improvements feasible, but broad state ownership makes navigation, focus, lifecycle, and asynchronous races especially important to test together.

The review inspected the build and CI configuration, architecture and capability documentation, buffer and document lifecycle, keyboard dispatch, menus, palettes, file discovery, search/replace, navigation, configuration, and associated tests. LSP/DAP, terminal, recovery, remote, and large-file capabilities were assessed from their implementation seams and documentation, not exhaustively exercised against every external tool. Competitors were researched through official documentation; no comparative runtime benchmark was performed.

## Reference products

| Reference | What to learn from it | Implication for Shed |
| --- | --- | --- |
| [Lite XL](https://lite-xl.com/user-guide/introduction/) | A compact project editor with fuzzy file/command pickers, multiple cursors, project search, and directly accessible indentation/line endings. | Make daily file navigation and editing work with little setup. Its documentation describes project search but no project-wide replacement; Shed already has reviewed project replacement. |
| [Lite XL IDE setup](https://lite-xl.com/user-guide/ide-setup/) | Language services, terminals, source control, formatting, and debugging are supplied through plugins. | Shed already bundles several analogous surfaces. More built-in integrations do not by themselves improve the basic editor. |
| [Sublime Text](https://www.sublimetext.com/) | Fast navigation, multiple selections, split-oriented tabs, contextual completion, and focused UI. | Prioritize preserving context and efficient keyboard use. GPU rendering claims are not evidence that Swing is slower for Shed's workloads. |
| [Sublime documentation](https://www.sublimetext.com/docs/) | Mature settings, key bindings, project/build systems, packages, syntax definitions, and Python APIs. | Shed has settings and project tooling, but no equivalent extension ecosystem or grammar contribution API. |
| [VS Code navigation](https://code.visualstudio.com/docs/editing/editingevolved) and [workbench](https://code.visualstudio.com/docs/editing/getting-started/userinterface) | File and symbol navigation, Back/Forward, explicit open-editor state, preview tabs, and discoverable workbench surfaces. | Borrow navigation semantics and discoverability. Full extension-host and remote-server equivalence is a separate architectural undertaking. |
| [Atom's retirement announcement](https://github.blog/news-insights/product-news/sunsetting-atom/) | Atom demonstrated the value of a customizable editor and package community, but was sunset on December 15, 2022. Package management and security updates ended. | Use it as a historical product reference, not a maintained dependency or compatibility target. |

### Direct workflow comparison

| Dimension | Lite XL | Sublime Text | VS Code | Atom, historical | Shed after this pass |
| --- | --- | --- | --- | --- | --- |
| Navigation | Fuzzy project files and commands | Goto Anything, symbols, split-oriented tabs | Quick Open, symbols, Back/Forward, preview editors | Fuzzy symbol picker, line/column jumps, ctags project symbols | File/line/column picker, recent files, symbols, Back/Forward across open buffers; no preview-tab model |
| Search experience | File and project search; documented project replacement gap | Editor-oriented workflow with command palette and configurable commands | Workbench-oriented search/navigation surfaces | File/project replace panel with case, regex, whole-word, selection and path filters | Live inline find/replace; workspace prompt plus quickfix; separate reviewed replacement panel; options remain less unified |
| Tooling model | IDE capabilities assembled from plugins | Packages and Python API supplement the editor | Workbench plus extension ecosystem | Electron-based editor with packages | Built-in Swing workbench and explicit external-tool bridges; no general extension host |
| Customization | Lua configuration and key mappings | Settings, key bindings, themes, packages | Configurable workbench and extensions | Keymap CSON and package API | Typed TOML, theme gallery, snippets, Vim overlays; fixed Plain/Emacs bindings remain a gap |
| Best reference for Shed | Keep common operations small and easy to reach | Preserve editing context and navigation speed | Make state and commands discoverable | Make customization approachable and search controls coherent | Prioritize reliable editor workflows before expanding integrations |

The Atom details above come from its archived [navigation manual](https://github.com/atom/flight-manual.atom.io/blob/master/content/using-atom/sections/moving-in-atom.md), [find/replace manual](https://github.com/atom/flight-manual.atom.io/blob/master/content/using-atom/sections/find-and-replace.md), and [project description](https://github.com/atom/atom/blob/master/README.md). The other columns use the official references linked above. These are workflow and architecture comparisons, not speed, memory, security, or feature-count rankings.

## Baseline capability and gap matrix

This table records the starting point; the implemented changes are summarized below. “Present” means the implementation exists, not that all workflows have competitor-equivalent polish.

| Area | Shed evidence and current boundary | Gap / decision |
| --- | --- | --- |
| Editing | `PlainKeymap`, `EmacsKeymap`, `ModeEngine`, multi-selection services, snippets, grapheme support, bounded undo. | Strong foundation. Plain/Emacs bindings remain fixed while Vim has overlays; keyboard customization is inconsistent. |
| Buffer switching | `PaneBufferController.loadBufferIntoEditor` loads at offset zero; `persistCurrentBufferState` does not save caret/selection/scroll. | **High:** switching files loses editing context. Preserve view state independently for each split. |
| Navigation history | `EditActionController` stores global integer offsets and decrements before returning to a recorded origin. LSP records an origin before opening another file. | **High:** first Back can do nothing, and subsequent Back can target an unrelated file at the same offset. Track document identity and bounded history; expose actions outside Vim. |
| Quick Open | `TreeGitController` asynchronously discovers ignored-filtered project files, then uses `PaletteController`. Results are bounded to 200. | No `file:line:column` targeting; no recent-file preference; scanning finishes before the picker appears. Implement position targeting and ranking now; streaming discovery remains future work. |
| Picker correctness | Filtering is debounced/background, but Enter and double-click read the previous list immediately. Escape is bound only on the filter field. | **High:** fast input can open the wrong file or execute the wrong command. Tie acceptance to the current query; support keyboard focus throughout the dialog. |
| Recent files | `SessionConfigController.showRecentFiles` replaces the editor with a scratch list and tells users to type `:e <path>`. | **Medium:** make this a searchable, actionable picker that leaves the source document intact on cancellation. |
| Search | Inline Find/Replace has live counts and atomic replacement. Workspace search uses ignore-aware scanning and quickfix results; project replacement has preview/apply safeguards. | Workspace text search is not exposed through the familiar Ctrl/Cmd+Shift+F path. Regex/case/scope parity needs a separate search design, not more command aliases. |
| Open-file orientation | Buffer picker, pane headers, splits, and status cues exist; no persistent tab strip or preview/pinned tab model. | Consider an optional open-editors surface after fixing buffer state. A tab strip alone does not solve lost positions or unsafe switching. |
| Language services | LSP bridges, completion, symbols, diagnostics, refactoring and managed language support exist; local lexical/Java outlines supplement servers. | Language quality depends on installed/configured servers. No third-party grammar ecosystem. Validate representative languages end to end before expanding the list. |
| Git and development tools | Git changes/history/conflicts, PTY terminals, tasks/tests, DAP, notebooks, Docker and Dev Container bridges are present. | Existing breadth exceeds a minimal editor. External CLI/adapter coverage and actionable failures matter more than another surface. |
| Remote work | Explicit Git/SSH/Docker/WSL mirrors and selected execution bridges. | Not VS Code's remote extension host. Preserve explicit synchronization and document that boundary. |
| Safety and recovery | Atomic writes, conflict handling, backups, recovery journal, undo policy, and data-loss regressions. | Preserve these paths when adding navigation or preview behavior. Never replace a dirty buffer to imitate a preview tab. |
| Large files | Editable size thresholds, immutable text snapshots, viewport-oriented syntax, and bounded read-only projections. | Do not claim unlimited editing. Navigation must respect projection coordinates; benchmarks need representative files and cold/warm measurements. |
| Extensibility | Typed config, themes, snippets and tool declarations; the capability audit explicitly excludes a plugin ecosystem. | Example plugin files are not evidence of a supported marketplace/runtime API. Decide on a maintained contribution API before promising one. |
| Distribution | Java 21 enforcement, bundled-runtime packaging, three-OS CI, separate display-backed Swing tests. | Headless tests skip real window interactions. Local passing tests do not establish Windows/Linux installer quality. |
| Performance | Startup and editing instrumentation plus workload benchmarks already exist. | Establish reproducible startup, memory, typing, picker and large-file budgets; no defensible speed/memory ranking against competitors yet. |

## Implementation priorities

1. **Preserve editing context:** caret, selection direction and viewport when switching buffers; split isolation; retain explicit destination positions.
2. **Repair navigation:** a bounded document-aware Back/Forward history; return from the first jump; no history pollution while traversing it; keyboard/menu/palette access.
3. **Make pickers dependable:** current-query acceptance, keyboard completion/cancellation from the results list, visible pending/empty state, scrolling selection into view, location-aware Quick Open, and actionable recent files.
4. **Expose project search:** a standard shortcut and menu/palette entry using the existing search service.
5. **Follow-up product work:** consistent keymap customization, optional open-editor/preview UI, EditorConfig support, search options, and a measured startup/memory budget. Each needs a focused design and regression coverage.

These are incremental changes, not a claim to close every ecosystem, rendering, language or remote-development gap in one pass.

### Remaining milestones and acceptance criteria

| Priority | Work | Evidence needed before calling it complete |
| --- | --- | --- |
| Next | Consistent Plain/Emacs/Vim keymap customization | Inspector shows effective bindings and conflicts; overlays work in the selected profile without stealing native text input, menu shortcuts, or IME composition. |
| Next | Persistent workspace Search surface | Case/regex/whole-word and include/exclude controls; explicit progress, cancellation and result-limit state; source locations survive switching results; tests cover ignored files and multiple roots. |
| Next | Reproducible lightweight-editor budget | Same machine, project and fixture for all compared editors; separate cold/warm startup, idle memory and typing/picker p95; report loaded plugins and language servers. Use existing Shed metrics instead of inferring speed from implementation language. |
| Later | Optional open-editors / preview-tab surface | Dirty state is visible, all files remain reachable with keyboard and screen reader, edits promote previews to retained buffers, and cancellation/closing cannot discard unsaved content. |
| Later | EditorConfig interoperability | Nested precedence, `root`, glob patterns, `unset`, indentation and newline behavior; explicit precedence against workspace/TOML settings; no unsolicited file rewriting. |
| Later | Navigation refinements | Reopen closed file-backed locations asynchronously, track edits before saved locations, define large-file logical coordinates, and make Vim change-list locations document-aware. |
| Separate product decision | Third-party contribution API | A documented versioned lifecycle and compatibility contract, plus representative independently maintained extensions. Example scripts or a manifest importer alone do not establish an ecosystem. |

## Interface review

Mode: full. Scope: navigation menus, file/buffer/recent pickers and buffer transitions. Framework: Swing. Styling: existing `ConfigManager` colors/fonts and `EditorUiController` dialog sizing/theme helpers. The wider workbench was inventoried but not visually audited in every state.

| Category | Evidence inspected | Result |
| --- | --- | --- |
| Typography | Picker filter, list, preview and menu labels; rendered file results | Relative labels and filename-first recent labels keep filenames visible; existing UI fonts and colors retained. |
| Surfaces | Dialog focus, list selection, buffer transitions and rendered picker rows | Findings below; preserve the floating-picker layout and use scaled 40 px minimum row/filter hit areas. |
| Animations | Picker filtering and navigation | No decorative animation needed; immediate state feedback is appropriate. |
| Icons | `FileFinderIcons` and text menu actions | Reuse established renderer; new actions remain understandable through text. |
| Performance | 75 ms debounce, background matching, 200-result limit, project scanning | Keep matching off the event thread and invalidate superseded work. Streaming discovery remains unimplemented. |

| Severity | Location | Before | After / acceptance criterion | Why |
| --- | --- | --- | --- | --- |
| HIGH | `src/main/java/shed/PaneBufferController.java:76` | File switching resets the caret. | Restore each split's saved view; explicit jumps still win. | Context preservation prevents repeated disruptive re-navigation. |
| HIGH | `src/main/java/shed/EditActionController.java:1500` | History has offsets without documents. | Navigate to the correct document and position from menus and keyboard. | A navigation control must take users to the location it represents. |
| HIGH | `src/main/java/shed/PaletteController.java:832`, `src/main/java/shed/PaletteFilterSession.java:15` | Enter can accept stale results; Escape only works in filter focus. | Accept only current-query results; Enter/Escape work from the list; pending and empty states are visible. | Correct feedback and keyboard access are more important than decorative polish. |
| MEDIUM | `src/main/java/shed/SessionConfigController.java:1386` | A passive scratch list interrupts editing. | A searchable picker opens the selected file; cancellation preserves the document. | Reduce unnecessary mode changes and transcription. |
| MEDIUM | `src/main/java/shed/PlainKeymap.java:86`, `src/main/java/shed/ShedMenuBar.java:51` | Common project-search and history actions are missing. | Expose them through consistent shortcuts, menus and palette labels. | Discoverability should not depend on knowing Ex commands. |
| HIGH | `src/main/java/shed/FileFinderIcons.java:96` | Icon-enabled rows display absolute paths; long prefixes push filenames outside the picker. | Show project-relative labels or filename-first recent labels; retain full paths in tooltips and accessible names, including the text-only fallback. | Typography and information hierarchy: the file being chosen must remain identifiable. |
| MEDIUM | `src/main/java/shed/PaletteController.java:879` | Rows and the filter field have very small native preferred heights. | Use at least 40 scaled pixels for picker rows and input height, increasing for larger fonts. | Minimum hit area: selection should be easy without sacrificing keyboard operation. |
| MEDIUM | `src/main/java/shed/QuickOpenQuery.java:9`, `src/main/java/shed/FuzzyMatchService.java:64`, `src/main/java/shed/PaletteController.java:839` | File picking requires a separate line jump and does not favor basenames or recently used files. | Parse `file:line:column` and `:line`, rank basename matches, and seed known recent candidates. | Efficient navigation: reach the intended location with fewer actions. |

Considered but rejected for this pass: a new icon/style system (would fragment the existing theme), animated picker transitions (repeated attention cost during typing), a permanent tab strip before repairing view state (would hide the underlying lifecycle bug), and a remote extension host (too broad for an editor-usability change).

## Implemented changes and validation

The first implementation pass addresses priorities 1–4: ordinary buffer view state is retained per split; bounded navigation history identifies open documents; file pickers support recent ordering and line/column targeting; pending queries cannot accept stale rows; list-focused Enter and dialog-wide Escape work; empty and pending states are visible; Recent Files is actionable; and Find in Files has a Plain shortcut plus menu/palette access. Help and command documentation were updated. No dependencies were added and `README.md` was not edited. A subsequent regression pass also repairs path-alias handling in remote/container session routing, debugger launch plans and breakpoints, Explorer roots, and imported workspace labels.

History skips closed buffers and projected large files rather than retaining their documents. Location-aware Quick Open explicitly reports that large-file location navigation is unavailable. File discovery still completes before the picker appears. Find in Files uses the existing prompt/quickfix flow, not a new persistent search panel.

| Verification | Observed result |
| --- | --- |
| Baseline `make test` on `d45c51ff` | 922 tests, zero failures/errors, 71 headless skips. |
| `make clean` followed by `make check` with Java 21 | 945 tests, zero failures/errors, 76 headless skips; portable JAR packaged successfully. |
| Five new display-backed workflow tests | All pass: buffer selection/viewport, split independence and explicit jumps, navigation history/branching, pending picker acceptance and Escape, location-aware Quick Open and Recent Files cancellation/missing-file behavior. |
| Final picker polish | Two renderer tests and two display-backed picker workflows pass; JAR packaging succeeds. |
| Rendered interface review | Inspected actual Swing dialog captures in the default dark theme: initial results, `Renderer:2:6`, and no matches. Filenames remain visible, input/rows have usable hit areas, and result counts and empty-state cues are readable. Pending state, keyboard acceptance, selection scrolling, cancellation and superseded queries are covered by automated checks. No animation was added. |
| Parser, ranking, history, view-state and filter-session tests | Pass, including Windows drive letters, colon-containing input, invalid coordinates, locale independence, bounded history, changing shared documents, slow superseded queries and cancellation. |
| Initial full `TexteditorSwingIntegrationTest` with a display | 73 tests: nine failures, zero errors, one skip. All nine failures reproduced on the original `d45c51ff` checkout. After the path fixes, all nine selected methods pass. Final full-suite results are recorded below. |
| Complete test suite with a display | 945 tests, zero failures/errors, three environment-dependent skips (DOS file attributes, Fish, and CMake/CTest/CPack unavailable). This includes all 73 Swing integration tests: zero failures/errors, one skip; the five new workflows and all nine original regressions pass. |
| Architecture checks | Pass. The picker helper is a UI session taking widgets and callbacks, with no dependency on the application or feature controllers. The guard was retained unchanged. |
| Packaged artifact | `target/shed-2.0.0.jar`, Java 21 manifest, new picker session included, obsolete helper class absent. |
| Cross-platform and comparative runtime checks | **Not verified:** Windows/Linux desktop behavior and native installers, competitor startup/memory/latency measurements, assistive-technology testing with an actual screen reader. |

The final complete display run used `JAVA_HOME="$(scripts/resolve-toolchain.sh home)" "$(scripts/resolve-toolchain.sh maven)" -q -Djava.awt.headless=false test`. The earlier focused full-Swing command was `JAVA_HOME="$(scripts/resolve-toolchain.sh home)" "$(scripts/resolve-toolchain.sh maven)" -q -Djava.awt.headless=false -Dtest=TexteditorSwingIntegrationTest test`. Baseline reproduction used an isolated detached worktree at `d45c51ff` and the same command with only these nine methods selected:

- `activeRemoteSessionRoutesNormalTaskDryRunsWithoutStartingAProcess`
- `activeRemoteSessionTakesPrecedenceOverOverlappingDevContainerTaskRouting`
- `connectedDevContainerRoutesNormalTaskDryRunsWithoutStartingAnotherCliProcess`
- `debugBreakpointCommandsConfigureTheActiveSourceBreakpoint`
- `debugPreLaunchTaskRunsBeforeAnAdapterProcessIsOpened`
- `vscodeLaunchProfileCanRunOnlyItsAcceptedProcessTaskLabelBeforeOpeningAnAdapter`
- `importedCodeWorkspaceCanRunItsAcceptedTaskBeforeItsAcceptedLaunchProfile`
- `importedWorkspaceSearchExclusionsFilterWorkspaceSearch`
- `startupFolderCreatesAnActiveWorkspaceAndExplorer`

The path fixes resolve existing parents before testing workspace ownership, retain the configured root spelling when producing relative paths, and keep symlinks that lead outside a workspace excluded. Session lookup uses the same identity for registration and selection. Debug launch planning and breakpoint lookup now agree with the canonical workspace root; Explorer roots and imported label lookup do too. The search-exclusion test already returned the correct file and now compares real file identity rather than spelling. Five new headless symlink tests cover aliases, not-yet-created descendants, terminal mapping, session replacement, task targeting, launch placeholders, persistent breakpoints and links outside the root.

Verdict: **Approve for the reviewed navigation/picker scope**. All identified issues in that scope are addressed, the clean headless build and full display-backed suite pass, and the nine pre-existing Swing failures are resolved. **Not verified:** Windows/Linux desktop behavior and native installers, comparative runtime measurements, and actual screen-reader operation. The remaining product milestones above are separate from this scoped verdict; this review does not establish full parity with the reference editors.
