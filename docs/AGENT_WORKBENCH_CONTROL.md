# Agent workbench control

Shed's existing authenticated workspace bridge now exposes bounded semantic interactions with the real workbench. The same controllers, documents, panes and Undo history serve humans and agents. This extends the Find-only interface documented in [AGENT_UI_ACTIONS.md](AGENT_UI_ACTIONS.md).

## Use and demonstrations

```sh
make demo-workbench   # project consent, controls, pickers, source edits, files, settings and handback
make demo-ui          # alias for the whole workbench tour
make demo-find        # the smaller original Find/pointer tour
make demo             # original source animation tour, followed by the workbench tour
```

These use disposable projects/preferences and fixed Java clients, not a cloud model. The workbench demo labels its simulated VCS client explicitly; it does not execute native VCS mutations. The default source tour does not include the separate native terminal demo. Native-shell benchmarking remains NO-GO under B0.1; it was not run for this change.

Existing Chat launchers inject the common `AgentTaskInstructions` into each agent turn. These instructions describe discovery, real reads, synchronization and workbench commands for any cooperating CLI agent. Ordinary disk writes remain observable through the watcher; an ordinary filesystem read cannot be inferred reliably and still needs `ctl read` or an explicit activity request. No model-specific log parser or new daemon is required.

## Consent and ownership

The existing project delegation dialog now lets the human select navigation, source edits, file changes, VCS actions, tools, appearance, and system clipboard permissions. Navigation is required; the system clipboard is unchecked by default. The approved lease supplies the displayed agent identity, overriding a client-supplied label for workbench requests.

Agent mode keeps the existing human-input shield and Take Back Control. No additional human-interruption arbitration or locking framework was added. After handback, the workbench session/private clipboard is closed, the pointer is removed and delegated terminal requests are cancelled through the existing cancellation path. Chat can create a fresh workbench session on its next approved turn. A bounded count of successful UI operations appears alongside the existing agent-reported handback summary; source/query/clipboard contents are not included in those counts.

VCS mutations require the approved repository root to equal the active project root and retain existing unsaved-buffer checks. The Mercurial/Subversion owner check now permits the approved agent rather than silently ignoring its click. Existing human confirmations remain when no agent owns the editor. Terminal requests can use explicit project Tools consent; requests outside that delegation retain their existing individual review. Tools run with normal operating-system permissions, not in a filesystem sandbox. Application-managed tasks retain their normal job lifecycle; UI `painted` does not assert that a build finished.

## Commands and schemas

Use the existing authenticated descriptor/credential and delegation environment. No additional listener, secret, OS mouse driver, dependency or permanent worker pool was introduced.

```sh
shed ctl ui-targets --offset 0
shed ctl ui-inspect <target> --offset 0
shed ctl ui-run find.open
shed ctl ui-type find/find.query --text-file -
shed ctl ui-key editor.active --key 'mod F'
shed ctl ui-scroll editor.active --axis vertical --unit pages --amount 2
shed ctl ui-select <target> --index 0
shed ctl ui-click <target>
shed ctl ui-double-click <target> --index 0
shed ctl ui-context <target> --index 0
shed ctl ui-hover <target>
shed ctl ui-drag layout/right --start 0 --end 700
shed ctl ui-copy editor.active
shed ctl ui-paste <target>
shed ctl ui-read editor.active --start 0 --end 120
shed ctl ui-wait --timeout-ms 2500
shed ctl ui-sequence --steps-file steps.json
```

`ui-type` defaults to `set`. Other modes are `insert`, `delete`, `select`, and `read`, with explicit `--start/--end`; non-writing modes require an empty text input. `ui-do <target> --kind <kind> --args-file <json>` exposes the same validated contract directly. `ui-run` supplies a bounded value with `--value-file`; file changes and folder operations use this parameter.

Discovery returns 48 targets/page, inspecting a list/table/tree/picker returns 24 items/page. Use returned semantic IDs, not OS coordinates or guessed labels. IDs use explicit control names where available; ambiguous unnamed controls are omitted. Existing tabs, fields, buttons, checkboxes, dropdowns, tables, trees, split dividers, viewports and scrollbars are admitted only under known workbench surfaces or owned dialogs. Passwords, consent dialogs, file choosers and Chat's agent/process-management controls are excluded.

RPC methods are `editor/ui/workbench-state`, `editor/ui/workbench-inspect` and `editor/ui/workbench-perform`. The outer authenticated identity remains schema version 1: instance, window and project IDs. The nested request is version 2:

```json
{"schemaVersion":2,"kind":"scroll","target":"editor.active","agent":"Codex","expectedUiRevision":42,"args":{"axis":"vertical","unit":"pages","amount":2}}
```

A successful result is nested under the existing JSON-RPC `result.observation`:

```json
{"kind":"ui-result","schemaVersion":2,"revision":43,"id":7,"stage":"painted","agent":"Codex","operation":"scroll","target":"editor.active","viewport":{"x":0,"y":600,"width":800,"height":500,"contentWidth":800,"contentHeight":4000}}
```

The CLI captures a fresh UI revision unless `--ui-revision` is supplied explicitly. State also reports `busy` and the most recent operation's stage. Revisions include document revisions and pane identities, control identity/visibility/enabled state and bounded field state. Pending operations reject changed document context; row operations retain the selected model item's identity. An older modal callback cannot replace the completion record of a newer operation.

Stages mean:

- `requested`: accepted and scheduled; an existing modal controller may now be awaiting input. Discover and operate the dialog rather than waiting for it to close first.
- `ui-updated`: the controller/interaction has updated UI state.
- `painted`: the affected, still-attached showing root completed a synchronous paint. This is not a claim that every asynchronous tool/search/preview job or animation has settled.
- `failed`: the deferred action failed. `ui-wait` returns an error, not a success-shaped result.

`ui-wait` observes the latest operation, or a specified still-current request ID. It does not queue commands or maintain an unbounded event history. `ui-sequence` accepts up to 32 `{kind,target,args}` steps. Use discovery between UI transitions; do not assume a picker result is already available just because its query field accepted text.

Errors include `INVALID_REQUEST`, `INVALID_OFFSET`, `PERMISSION_DENIED`, `PROJECT_UNAUTHORIZED`, `CONTEXT_CHANGED`, `BOUNDARY_UNAVAILABLE`, `UNSUPPORTED`, `BUSY`, `LIMIT_EXCEEDED`, `AUTHORIZATION_REVOKED` and `TIMEOUT`. Transport failure after a mutating request remains an uncertain outcome under the existing CLI rules; inspect state instead of replaying a source/file operation blindly.

## Surfaces and workflows

| Area | Existing mechanism reused | Agent operations |
|---|---|---|
| Source | `EditorPane`, `FileBuffer.TextTransaction`, `AgentChangeTour`/`AgentChangePlayback` | Bounded read, selection, checked text replacement, Undo/Redo, save; one source transaction with paint-only replay |
| Find/replace | `InlineFindBar`, existing actions | Open/type/toggle/navigate/replace through real controls; same Plain shortcut action path |
| Pickers | `PaletteController`, `PaletteFilterSession`, background project scanner | Project files, buffers and the admitted command palette; visible query, results and acceptance |
| Explorer | `FileTreePanel`, `FileTreeModel` | Select, expand/collapse with arrows or double-click, open real files; existing change badges |
| Panes/tabs | Existing pane/buffer controllers and `OpenEditorsBar` | Select/close tabs, focus panes, create/close splits and drag real dividers |
| Search/reports | Existing Search, Replace, Outline, Problems, Tasks, Tests and Debug surfaces | Discover and use their actual fields, result rows, buttons and scroll containers; backend jobs retain existing behavior |
| Source control | Existing Git workbench and `ScmChangesPanel` | Provider selection, status/diff/history controls and approved actions; native backend qualification remains separate |
| Terminal | Existing terminal session controllers and reviewed command request handler | Session creation/closure/focus, existing typed-command presentation, and native scrollbar models where present |
| Preview | Existing `openPreviewForAgent`, real preview surfaces | Open and scroll supported Markdown/media previews; remote images remain disabled by the existing agent preview boundary |
| Settings/themes | Existing typed Settings editor/gallery/controllers | Filter, select, edit typed settings fields and dropdowns, choose controls, change density/theme/focus under Appearance consent |
| Files | Existing `WorkspaceProposalService` | `files.change` accepts the existing checked create/text/delete/rename records; versions remain required for existing-file operations |
| Empty folders | `AgentProjectFolders`, portable Java filesystem operations | `files.folder` supports one create, empty-folder rename or empty-folder delete; no overwrite, recursive deletion or populated-folder move |

`files.change` example:

```json
[{"kind":"create","path":"NewFile.txt","text":"Hello\n"}]
```

`files.folder` example:

```json
{"kind":"rename","path":"EmptyFolder","destination":"RenamedFolder"}
```

The command palette uses admitted existing workbench actions during agent ownership, rather than exposing Quit, OS folder selection, user configuration-file buffers or arbitrary Ex execution. Its ordinary human catalog is unchanged. Open/Save use project-relative commands and Shed's existing owned picker; OS desktop dialogs are not targets. Unknown controls/keys/actions fail explicitly rather than falling back to desktop automation.

## Coordinates, animation and integrity

Text ranges are zero-based authoritative UTF-16 code-unit offsets, half-open `[start,end)`, with `length` representing EOF. They are not code-point or grapheme indices. Range endpoints must lie on existing grapheme boundaries; an implied Swing EOF beyond source length is never accepted. Per-paragraph boundary checks are bounded to 65,536 units and source requests to 1 MiB. Text payloads/excerpts are limited to 4,096 units. Complex text is not normalized or split into invalid intermediate prefixes.

Viewport positions are pixel coordinates for `JScrollPane` surfaces. A standalone scrollbar, including a terminal history model, reports `units: "scrollbar-model"`; its value can be negative and must not be confused with source offsets or pixels. Scroll requests support signed lines/pages/pixels (native model increments for standalone bars), clamp to current bounds, and leave caret, selection and source unchanged. Drag fractions for dividers/scrollbars are 0–1000, starting from the real current position; text drags use UTF-16 endpoints.

One lazy window-owned presence now joins semantic UI, source and terminal presentation. It keeps position between commands, glides to targets, morphs between outlined arrow and I-beam, and carries the approved provider logo/name. Old source/terminal follow tokens cannot steal the pointer from a newer UI action. Root changes preserve owned-window screen position, including returning from a closed dialog; no OS mouse movement or global overlay is used. Reduced motion applies updates without the animated travel/morph. No timer runs merely to maintain a parked pointer. Pointer, badge and text pulses share action colors: writing green, deletion/failure red, reading/checking yellow/amber, search blue and navigation purple. Shades adapt for contrast without changing the action hue. Large inserted chunks keep the provider/activity badge near the block; the tracking pointer fades out and no thin replay caret is drawn. Finishing a block does not bring the cursor back at EOF. The next small edit or control interaction restores it from the retained position.

Source changes remain one checked Undo transaction. Existing paint-only playback handles quick small typing, deletions and phased blocks without creating a second source buffer or synthetic per-character Undo history. The existing 64 KiB change-tour bound remains: larger supported source buffers get checked edits/highlights, not the full animated tour. Read highlights keep their distinct sweep; pointer badges are not duplicated inside source/terminal decorations when shared presence is available. Terminal decoration uses its existing repaint pulses. Source and admitted text fields share `AgentTextPulse` color, alpha and directional-sweep painting. Small ASCII field entries keep fast progressive insertion; complex text and larger field values install as one shaped value with a shared pulse. Field reading sweeps top-to-bottom and deletion sweeps right-to-left. Both the legacy Find API and the semantic workbench API use this treatment. Source glyph replay remains paint-only over one checked transaction, preserving one-step Undo.

## Security and qualification boundaries

Paths for open source buffers and Explorer actions are canonicalized on the existing bridge worker before EDT application. Existing protected/excluded path policy is retained, including README protection and VCS administration resources. Folder operations reject aliases, project-root operations, occupied destinations and nonempty removal/move. Password controls and unapproved capabilities fail closed. There is one active finite semantic interaction, bounded messages, finite tooltip lifetime, and no general command-evaluation endpoint.

This is an application permission boundary, not protection against a fully privileged same-user process or hostile filesystem races. Tools retain their ordinary filesystem/network access. VCS operations affecting a containing parent repository are not authorized by approval for a narrower subproject. Native system clipboard access is explicit and remains subject to native-platform qualification. Some existing tool callbacks, notably legacy Git write/branch handlers, can synchronously wait for their backend; this work does not qualify their native execution latency. The 16 ms source-typing results must not be generalized to every tool callback.

The implementation does not promise that every Swing widget, arbitrary custom keybinding/Ex command, OS dialog, drag-and-drop gesture or populated-folder operation is exported. Custom keybindings resolve through existing configuration only when they map to a supported action; unavailable cases are reported, not simulated. Native PTY execution, real LSP/server startup, Windows/Linux desktops and alternate HiDPI configurations remain unqualified here. Existing multilingual glyph, first-use Unicode layout and HTML-preview EDT limitations remain open.

See [the evidence index](performance/agent-workbench/INDEX.md) for exact tests, live captures, failed intermediate attempts, paired measurements and repository compliance.

The [text-animation unification evidence](performance/agent-text-unification/INDEX.md) records the later badge-only block treatment and shared field pulses.
