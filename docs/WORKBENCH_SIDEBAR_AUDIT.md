# Workbench sidebar audit

The File Explorer is now a dedicated left sidebar. It no longer creates a scratch buffer, editor tab or editor pane. **View → File Explorer** and `:tree toggle` show/hide the same explorer, preserving its expansion/selection and dragged width within the window. Closing/switching documents does not close it. Focus/Goyo hides it temporarily and restores its prior open state. Existing project switching, density/theme styling, file-change badges/pulses and file-opening actions use the same explorer/controller.

The workbench header remains global; document tabs and editor/tool content sit to the right of Explorer. This change does not relocate other tools. No preference schema, source/Undo model, font/theme default or native dependency changes. Existing saved scratch documents are preserved; old manually restored session files can still contain historical tree scratch buffers, and are not silently rewritten or discarded. Sidebar width/visibility are retained for this window, not newly persisted across application restarts.

## Placement findings

These are repository-confirmed presentation inconsistencies, not claims that each feature is broken. Textual exports/reference snapshots can remain explicit actions. Normal tool navigation should not create editable pseudo-documents.

| Surface | Current implementation | Recommended treatment |
| --- | --- | --- |
| File Explorer | Previously a `[tree …]` scratch buffer plus editor split (`TreeGitController.showFileTree`). Now a dedicated `FileExplorerSidebar`. | Left, resizable and toggleable; implemented here. |
| Git Changes | `ToolWindowHost` puts Git in its bottom `JTabbedPane`; only AGENT selects horizontal/right docking. `TreeGitController.showGitDiff` and interactive Git text views still create scratch buffers. History, conflicts and worktree/stash tools use separate dialogs. | One right-pinned Git sidebar for repository/files/staging/history. Keep substantial diff/merge content in a dedicated read-only comparison view in the center; preserve explicit text export. Unify entry points before retiring old views. |
| Agent Chat | Already a single right-side surface. `ToolWindowHost.setDockMode` reuses the same split/host for bottom tools, changing orientation when switching from Chat to Git/Search/etc. Review/legacy tools are explicit subviews. | Keep Chat right. Give the right region independent ownership from the bottom region; switching bottom tools should not move/hide Chat. Use a small Git/Chat selector inside that tool region, separate from document tabs. |
| Terminal | `TerminalController.openTerminal` creates a scratch `FileBuffer`, allocates an `EditorPane` and splits the source layout. Session restoration and process ownership use that association. | Bottom terminal dock with session tabs inside the dock. Preserve output, focus, links, cancellation and process ownership; hide must not terminate a shell. This needs a separate lifecycle change and qualified process tests. |
| Search / Problems / Replace | Normal UI already uses bottom tool surfaces. `ProblemsController` retains explicit `:problems text`; project-replace preview/result/settings can create scratch buffers. | Keep bottom. Use existing review/preview surfaces for normal actions; explicit copyable text reports can remain optional. |
| Tasks / Tests / Debug | Bottom tools exist, but list/configuration/output/detail commands still call `showScratchBuffer` (`JobQuickfixController`, `TestController`, `DebugSessionController`). | Keep execution output/console in the bottom dock; route normal commands to the existing panels. Preserve actual editable configuration files as source documents. |
| LSP / quickfix / symbol and diagnostics lists | `LspController` creates scratch tabs for status/servers/logs/code lenses/links/folding/etc.; quickfix has its own scratch buffer. | Diagnostics and logs belong below, navigation results in a picker or outline/navigation tool. Keep source edits and a real rename preview in a dedicated checked review surface. Do not convert all lists blindly. |
| Workspace/configuration/container reports | `WorkspaceController`, `SessionConfigController`, Docker/Compose controllers create some scratch reports while dialogs also exist. | Workspace management via Explorer/workspace UI, settings via the existing settings surface, transient output below. Keep deliberate reference/help exports available. |
| Source files / notebooks / Markdown or image previews / substantial comparisons | Real documents or document-related views. | Keep in the center. A preview or full diff is a useful document-like view; it should not be forced into a narrow sidebar. |

## Proposed stable layout

- **Left:** persistent Explorer, optionally Outline later.
- **Center:** source documents, notebooks, previews and substantial comparisons.
- **Right:** toggleable Git/Chat tool surfaces, each retaining its state and width.
- **Bottom:** Terminal, Search/Replace, Problems, Tasks/Tests and Debug output.

Tool-local tabs are appropriate for multiple terminal sessions or Git/Chat selection. They should not enter the source-document list or source-pane count. Closing a dock means hiding it; stopping a process, clearing a conversation or closing a document remains explicit. Reopening restores state without creating duplicates.

Recommended order: (1) independent right and bottom regions, reusing existing controllers; (2) move Git's normal entry points right while preserving staging and read-only diff behavior; (3) move terminal presentation with separately verified process/session lifecycle; (4) replace the most visible scratch-report entry points with existing tool surfaces. No other relocations were implemented in this task.

## Verification

See [commands, counts, live checks and limitations](performance/explorer-sidebar/INDEX.md). Unqualified native-shell fixtures and real language servers remain excluded. Atomic local commits are authorized; no push. README remains byte-for-byte unchanged.
