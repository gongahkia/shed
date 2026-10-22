# Shed editor comparison and improvement plan

Review date: 2026-10-22. Baseline: `d45c51ff`. “LightXL” is interpreted as **Lite XL**.

## What Shed sets out to do

Shed began as an opinionated modal editor. The implementation now offers a nonmodal default, Vim and Emacs profiles, and a substantial local development workbench. Its useful product identity is a keyboard-friendly, customizable desktop editor with explicit local tooling, recovery, and no telemetry. Preserving that identity means making ordinary editing dependable before adding more integrations.

The Java 21/Swing application contains 288 production Java files and 176 test files at this revision. `Texteditor` is both the composition root and shared state owner; feature controllers receive it directly. Pure services provide useful test seams, while `AsyncJobService` marshals background results to Swing's event thread. This architecture makes incremental improvements feasible, but broad state ownership makes navigation, focus, lifecycle, and asynchronous races especially important to test together.

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

## Capability and gap matrix

“Present” means the implementation exists, not that all workflows have competitor-equivalent polish.

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

## Interface review

Mode: full. Scope: navigation menus, file/buffer/recent pickers and buffer transitions. Framework: Swing. Styling: existing `ConfigManager` colors/fonts and `EditorUiController` dialog sizing/theme helpers. The wider workbench was inventoried but not visually audited in every state.

| Category | Evidence inspected | Result |
| --- | --- | --- |
| Typography | Picker filter, list, preview and menu labels | Reuse existing UI font and theme contrast colors; add clear pending/empty feedback. |
| Surfaces | Dialog focus, list selection, buffer transitions | Findings below; preserve the existing floating-picker layout. |
| Animations | Picker filtering and navigation | No decorative animation needed; immediate state feedback is appropriate. |
| Icons | `FileFinderIcons` and text menu actions | Reuse established renderer; new actions remain understandable through text. |
| Performance | 75 ms debounce, background matching, 200-result limit, project scanning | Keep matching off the event thread and invalidate superseded work. Streaming discovery remains unimplemented. |

| Severity | Location | Before | After / acceptance criterion | Why |
| --- | --- | --- | --- | --- |
| HIGH | `PaneBufferController.loadBufferIntoEditor` | File switching resets the caret. | Restore each split's saved view; explicit jumps still win. | Context preservation prevents repeated disruptive re-navigation. |
| HIGH | `EditActionController.jumpBack/jumpForward` | History has offsets without documents. | Navigate to the correct document and position from menus and keyboard. | A navigation control must take users to the location it represents. |
| HIGH | `PaletteController.showPaletteDialog` | Enter can accept stale results; Escape only works in filter focus. | Accept only current-query results; Enter/Escape work from the list; pending and empty states are visible. | Correct feedback and keyboard access are more important than decorative polish. |
| MEDIUM | `SessionConfigController.showRecentFiles` | A passive scratch list interrupts editing. | A searchable picker opens the selected file; cancellation preserves the document. | Reduce unnecessary mode changes and transcription. |
| MEDIUM | `PlainKeymap` / `ShedMenuBar` | Common project-search and history actions are missing. | Expose them through consistent shortcuts, menus and palette labels. | Discoverability should not depend on knowing Ex commands. |

Considered but rejected for this pass: a new icon/style system (would fragment the existing theme), animated picker transitions (repeated attention cost during typing), a permanent tab strip before repairing view state (would hide the underlying lifecycle bug), and a remote extension host (too broad for an editor-usability change).

Verification results and remaining boundaries are recorded in the implementation notes below as changes land. Until those checks complete, the review verdict is **Needs changes**; visual and cross-platform verification are not implied by source inspection.
