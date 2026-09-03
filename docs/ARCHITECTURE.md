# Controller architecture

This is the dependency inventory for `src/main/java/shed` as of the Java 21 baseline. It records the current shape; it is not a claim that the current shape is fully decoupled.

## Composition and seams

`Texteditor` is the Swing application, composition root, and shared editor-state owner. It constructs the services, feature controllers, `CommandHandler`, and `AsyncJobService`.

```
Swing input and timers
        |
InputController / CommandHandler
        |
Texteditor shared UI, state, and service references
        |
feature controllers ----> stateless and stateful services
        |
AsyncJobService workers ----> EDT completion handlers
```

The supported entry seams are:

- `Texteditor.main` and `Texteditor(String[])` start and compose the application.
- `CommandHandler.execute` is the ex-command dispatch seam.
- `AsyncJobService.submit` is the asynchronous work seam; its completion runs on the EDT under the [threading policy](THREADING.md).
- Public service classes (`ConfigManager`, `FileBuffer`, `GitService`, `LspService`, `QuickfixService`, `SearchManager`, and `TreeService`) are reusable feature/data seams. Their callers must preserve the EDT policy when the result reaches UI state.

All current `*Controller` classes are package-private. Each is constructed with exactly one `Texteditor` reference; no controller directly holds another controller. `Texteditor` owns controllers while they retain their owner reference, so that composition cycle already exists and is a migration boundary rather than a pattern to extend.

## Controller inventory

| Controller | Ownership | Primary collaborators through `Texteditor` |
| --- | --- | --- |
| `InputController` | key dispatch, modes, command/search input | `ModeEngine`, `SearchManager`, command routing |
| `EditActionController` | editing and motion actions | editor state, buffers, text area |
| `PaneBufferController` | panes, splits, buffers, layouts | `FileBuffer`, `EditorPane` |
| `EditorUiController` | UI assembly, painting, diagnostics | `QuickfixService`, `PerfService` |
| `SyntaxUiController` | syntax and breadcrumb presentation | `SyntaxHighlightService`, `SymbolService`, `PerfService` |
| `SearchReplaceController` | search and substitution UI effects | `SearchManager`, `SubstituteService` |
| `JobQuickfixController` | tasks, shell jobs, quickfix navigation | `AsyncJobService`, `TaskService`, `QuickfixService` |
| `ProblemsController` | live diagnostic and retained quickfix aggregation | `ProblemsService`, `LspClient`, tool-window host |
| `TestController` | explicit test discovery/run session state and Problems projection | `TestService`, `TestAdapterRegistry`, `AsyncJobService` |
| `TerminalController` | PTY terminal panes and lifecycle | `PtyTerminalPane`, pane/buffer state |
| `NotebookController` | local Jupyter notebook presentation/save/explicit execution | `NotebookDocument`, `NotebookPanel`, `AsyncJobService` |
| `CustomEditorController` | extension text/binary custom-editor resource bridge | `CustomEditorDocument`, `AtomicFileWriter` |
| `ScmController` | extension and built-in SCM command routing | `ScmContributionService` |
| `RemoteWorkspaceController` | explicit local-mirror remote workspace lifecycle | `RemoteWorkspaceProvider`, `AsyncJobService`, workspace roots |
| `DevContainerController` | explicit local Dev Container CLI bridge | `AsyncJobService`, `TerminalController`, remote workspace mirrors |
| `WorkspaceToolController` | declared workspace integration actions | `WorkspaceToolContribution`, extension registry, workspace roots |
| `ExtensionManager` / `ExtensionRegistry` | checksum-verified Java extension lifecycle and typed contributions | `shed.api`, isolated JAR class loaders, workbench controllers |
| `TreeGitController` | file tree and Git actions | `TreeService`, `GitService` |
| `LspController` | language-server requests, diagnostics, edits, symbols | `LspService`, `LspClient`, `QuickfixService` |
| `MarkdownController` | Markdown tables, folds, snippets, brackets | `MarkdownService`, `SnippetService`, `BracketColorService` |
| `PaletteController` | file, buffer, symbol, and command palettes | `FuzzyMatchService`, `SymbolService` |
| `SessionConfigController` | sessions, workspaces, config and help | `ConfigManager`, `HelpService` |
| `RecoveryController` | file watching, recovery snapshots, conflicts | `FileWatcherService`, `FileBuffer` |
| `FocusModeController` | Goyo layout, Limelight, minimap | Swing UI state |

## Dependency rules

- Do not add a controller-to-controller field, constructor parameter, static lookup, or callback registration. Coordinate through a narrow `Texteditor` operation or extract shared non-UI behavior into a service.
- Do not add `Texteditor` or any controller dependency to a service. Services remain usable without Swing composition.
- Do not make a controller public solely to let another controller call it. Extend an existing service seam or introduce a focused interface owned by the composition root.
- Do not add cross-feature mutation of another controller's state. Pass immutable data or a narrow callback at the owning boundary.
- Keep Swing mutation in controllers or `Texteditor` on the EDT; keep I/O and process work behind `AsyncJobService` or a service boundary.

## Approved reduction direction

New work should first extract deterministic, non-UI logic into a service with explicit constructor dependencies and focused tests. When a controller needs only part of `Texteditor`, introduce a narrow interface at the composition root and migrate that controller without changing unrelated controllers. This reduces the existing owner-reference surface incrementally while preserving Java, Swing, and Maven.
