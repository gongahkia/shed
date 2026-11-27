# Independent tool docks

Shed now keeps three presentation regions independent of source documents:

- **Left:** the existing toggleable File Explorer.
- **Right:** Source Control and Agent Chat pages in one resizable, toggleable sidebar.
- **Bottom:** Search, Problems, Replace, Tasks, Tests, Debug, Language reports and navigation Results.

Terminal tabs and terminal editor splits remain unchanged, per the user's decision. No terminal process/session, keymap, font or environment code was modified. Native terminal behavior was not newly exercised.

## Use the docks

**View → Source Control** and **View → Agent Chat…** toggle the corresponding right-side page. Switching between Source Control and Chat keeps both surfaces alive. The sidebar close control hides the right region; the bottom close control hides only the bottom region. **Tools → Hide docked tools** hides both. Opening a bottom tool leaves the right sidebar visible and in place.

Dragged right width and bottom height are retained independently within the window, through hide/reopen and focus-mode entry/exit. The existing session payload now records both regions and their selected pages. Legacy single-dock selections/ratios are read as compatible placement hints. Automatic bottom sizing still grows for denser tools until the user manually resizes it. Opening a tool does not start its task, test or debugger. Chat remains subject to the existing explicit control approval and source-input shield; other tool controls remain shielded during an active approved turn.

Existing bottom-tool detach/reattach remains available. Source Control and Chat stay pinned to the right. Separate advanced Git dialogs retain their previous behavior. Restoring a saved Chat placement does not implicitly start an agent or restore control authorization.

## Source Control

The right sidebar now has a Git / Mercurial / Subversion selector. Git retains the controls described below; the other providers have structured changes, diffs, history and native working-copy actions. See [Source Control](SOURCE_CONTROL.md) for current behavior and qualification boundaries. The earlier dock evidence remains historical.

### Git

Normal `:git`, `:git status`, `:git changes` and the View action open the Git sidebar. Changes are listed above a compact patch preview, with existing Open/Stage/Unstage/Revert/Commit controls. Their confirmation, repository and source-file rules remain. **Open Diff** shows the full bounded patch in the center, with **Back to source** and an explicit **Export displayed patch** action. It does not create a source buffer, alter the active file or add Undo entries.

**History** and `:git history` / `:git log [count]` show local commits in the sidebar. **Open commit comparison** requests a bounded patch using the existing process service and background job execution. A newer history request cancels the previous one. Existing history/presentation and diff-disable settings are checked before the new history/commit-patch backend requests. Workspace generations invalidate old snapshots/actions/responses, including switching away and back to the same project. A commit response does not replace the center if its captured source pane, file, revision or human-input context changed while waiting.

Graph, remote actions, worktrees, stashes, branch selection and conflict resolution remain explicit advanced views/dialogs. No automatic fetch, pull, push, stage, commit or conflict application is introduced. Full backend/native Git qualification is separate from the synthetic UI checks described below. Existing Git operations that synchronously execute commands are not optimized in this presentation change.

## Reports and navigation

LSP status/server/log/support and capability reports use **Language** tools. Debug and task/test reports use their matching bottom tools. A read-only report card offers **Back to …** for the tool's primary controls and **Export displayed text** to deliberately create an editable scratch copy. Normal report display leaves the source document, revision, caret, dirty state and Undo history unchanged.

Each tool retains at most one report, limited to 262,144 UTF-16 units with an explicit shortening notice and grapheme-safe cut. Exports contain the displayed portion, rather than silently promising an entire large log. Reports requested during staged startup occupy one bounded pending slot and are installed when the tool host is ready. Later explicit tool navigation/hiding supersedes that pending request. Reports are window-local presentation state, not a durable log archive.

Quickfix/search/reference locations use a **Results** table backed by the existing `QuickfixService`. Enter or Open location navigates to the selected file/range; `:cnext`, `:cprevious` and indexed quickfix commands retain the same service. `:cclose` hides Results without closing a source document. The visible snapshot is capped at 10,000 entries and rechecks entry identity before navigation; larger result sets are explicitly labeled. Previously restored scratch reports are preserved and are not silently deleted.

Rename previews use the central read-only comparison presentation; applying still requires the existing explicit rename-apply command and checked workspace-edit path. No new source/Undo model, agent endpoint, permission bypass or renderer framework is added. Source-related documents, notebooks and previews retain their existing central presentation.

## Run and verify

```sh
make run .
make demo-chat
```

The controlled Chat demo also checks both docks, startup reports, quickfix, source-preserving report/comparison presentation, synthetic Git file selection and stale-project action rejection. It uses a fixed external Java CLI double and disposable files/preferences; it does not run a real coding model, Git backend, debugger, language server or native shell.

See [test commands, exact counts, failures, captures and limitations](performance/tool-docks/INDEX.md). macOS owned-window checks and safe portable tests do not establish Windows/Linux GUI acceptance, real Git/network operation acceptance, native-shell confinement, complete native-memory attribution or full OS keyboard-to-screen latency. Existing Unicode/glyph and HTML-preview performance limitations remain open.
