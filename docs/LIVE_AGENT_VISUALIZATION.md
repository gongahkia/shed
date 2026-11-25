# Live visualization for external coding agents

Tell your CLI agent:

> Use Shed to visualize this project's changes. Run `shed visualize .` once, then continue editing files normally. Leave Shed open for me. Do not turn the filesystem edits into review proposals just to visualize them.

The launcher opens a normal Shed editor window with agent management panels hidden, captures a watch baseline, starts its explicitly authorized local attachment, prints JSON with `ready`, `descriptor`, `credential`, `pid`, `project` and `agent`, and returns. It does not run or connect to any particular agent or model. The human can keep using the CLI, and Shed watches ordinary filesystem writes.

```sh
shed visualize .
```

The watcher is agent-neutral: it watches files in the selected project, not a Codex process. It works with Codex CLI, Claude Code, OpenCode, local agents, scripts or any other tool that writes supported files there. No agent-specific integration is required. Human saves and other tools also appear; the watcher cannot identify the author.

`--agent` is an optional display label, defaulting to `Agent`. For example, `shed visualize . --agent "Claude Code"` changes the name beside the animated change cursor; it does not change what is watched or restrict other writers.

Use the returned descriptor if you want to control the view:

```sh
export SHED_CONTROL=/returned/private/status-ATTACHMENT.json
shed ctl watch-state
shed ctl pause
shed ctl follow
shed ctl watch-stop
shed ctl watch
```

Watch commands and incoming proposals do not open or switch the tool dock. Use `:agent changes` to explicitly open the retained Live changes controls, `:agent proposals` for reviewed proposals, or `:agent` / Tools → Agents for the optional built-in assistant. Agent tool panels are not restored visibly on a fresh launch merely because they were open last time. Existing focus-mode restoration within a session still preserves panels you explicitly opened.

For a window already using an explicitly enabled control attachment, `shed ctl watch` starts watching that window's active project. Inside Shed, `:agent watch` or the play icon in Agents → Live changes starts a watch without launching any coding agent; the square icon stops it. `:agent watch stop` also stops it. Launching again creates another window; there is no implicit choice among existing instances.

## What appears

The normal editor shows the followed source file, change marker and animated viewport tour directly. Explorer badges and the existing Git view remain available without opening agent controls. If explicitly opened, Agents → Live changes contains the retained file list and unified diff. `A`, `M`, `D` and `R` identify added, modified, deleted and observed renamed/moved files. `R?` explicitly identifies a possible move inferred from similar text. A folder move summary groups at least two observed file moves; it does not claim an atomic directory operation. The list shows counts when its snapshot provides exact textual counts. Added and deleted lines use existing theme success/error colors; changed words in adjacent removed/added line pairs receive emphasis. Previous/next arrows move between diff hunks. Enter or double-click opens a surviving file. Deleted contents remain readable in the diff.

The baselines have different meanings:

- **Since watch started:** compares disk contents with the in-memory snapshot taken when the watch became ready. Existing uncommitted changes are part of that starting snapshot. Restarting the watch resets this baseline.
- **Git changes:** reuses Shed's existing Git status/diff services to show repository changes, including staged/working-tree patch sections and untracked textual files. These are repository changes, not proof of agent authorship.

Follow selects the latest observed changed file. When the loaded source matches the observation, it smoothly scrolls through up to 32 changed blocks and moves the distinct, labeled agent marker through each block. Each block pulses while presented. Both baselines use the latest incremental observation for this tour, while their diffs retain the selected baseline. The human caret and selection stay separate. It does not type fake intermediate characters. Human typing, unsaved edits, source clicks, manual scrolling, Explorer interaction or manual interaction with the changed-file list pause automatic following. Interacting with the current tour cancels it immediately. Follow does not replace an active Explorer or Markdown-preview surface with source. Reduced motion disables animated scrolling and pulses; the first changed block is revealed statically. The Follow button resumes it. Explicit file opening remains available while paused. Source loading, external reload/conflict handling and Undo stay with Shed's existing editor infrastructure. This panel never writes source files, applies proposals, saves buffers, stages or commits.

Visible Explorer trees refresh their loaded directories after watch updates, retaining surviving nodes, selection and expansion where possible. Files show theme-colored A/M/D/R/R? badges, folders aggregate descendant changes, and recently changed rows pulse briefly in Explorer, Live changes and the visible Git summary. Deleted/moved-away rows are struck through; opening them reveals the existing diff rather than recreating the file. The Git summary uses the same colored unified diff and word emphasis; its selected patch refreshes even if the Git status stays Modified. The live changes list is authoritative for this watch's supported file set; a deleted active editor buffer follows Shed's existing external-deletion policy rather than being forcibly closed.

## Scope and limits

The watcher observes disk writes, not model tokens. The agent name is a display label chosen at watch startup. A change marker means “changed here”, not an observed remote caret or authenticated authorship. Human saves and other tools inside the same project also appear.

Snapshots are bounded: 16,384 files, 1 MiB of diffable UTF-8 text per file, 32 MiB total content-read bytes, depth 32 and a five-second capture deadline. Larger/binary/unsupported encodings receive a bounded notice rather than a fabricated textual diff. Known binary asset/archive/font/media extensions use file size, timestamp and identity only; they do not consume the text-read budget. This is not binary content comparison: a same-size in-place change with restored timestamps can be missed, and metadata alone is never used to infer an exact-content rename. Moves use unambiguous file identity with creation time where available, then unique identical contents. Edited delete/add pairs may be labeled R? only when bounded reciprocal text similarity is strong and unambiguous. Completely rewritten or ambiguous moves remain delete/add. Known safe project config files (including .gitignore, .editorconfig and .prettierrc), .github and .changeset are included and visible in Explorer. Other dotfiles/directories, common dependency/build directories, environment/credential file patterns, symlinks and independently configured nested project roots remain excluded. The whitelist is not a secret scanner; do not put credentials in public project configuration. The session does not claim complete .gitignore equivalence. Git may list additional ignored-by-watch repository paths, but content observation remains bounded to the selected project.

A failed refresh retains the last view with an explicit error. Stopping clears the watch snapshot and stops its event worker; the last rendered diff is a bounded display artifact. Closing the window disposes the watch. No project-wide watcher or extra worker runs for this feature until a watch is explicitly started. The existing Git tab retains its refresh lifecycle and additionally refreshes after relevant observed writes when visible. Pulse timers expire after 2.4 seconds or on hiding; tours remove their temporary interaction listeners on completion/cancellation.

The launcher uses the current Java executable and classpath directly, not a shell. It waits at most 45 seconds for its owned child's matching PID/project readiness record. Owner-only private session storage and the existing mutually authenticated bridge remain authoritative. This is a trusted-local workflow, not an OS sandbox against a same-user process that can read the user's files. Launch starts the existing editor-control scope; automatic Apply, Save, arbitrary shell execution and desktop screenshots remain unavailable.

PNG provider/model logos are bundled for OpenAI/Codex, Claude, Gemini, Ollama, DeepSeek, Qwen, Meta/Llama, Mistral, OpenRouter, OpenCode and Hugging Face. Unknown/custom models retain their text labels. Light/dark assets are selected locally without network downloads. Model/provider names remain visible; common action buttons use Shed's outline icon family with tooltips and accessible names. Permission/sign-in/apply decisions keep explicit text.

The icons come from LobeHub's MIT-licensed `@lobehub/icons-static-png` 2.12.0. Exact source URLs, SHA-256 values and license are in `assets/providers/`. Logos remain their owners' trademarks.

## Verification

See [the evidence index](performance/live-agent-visualization/INDEX.md) for safe tests, fixed direct-Java launcher acceptance, real filesystem writers, disposable Git diffs, screenshots and qualifications. Native-shell confinement, whole-IDE native memory, remaining Unicode glyph/layout problems and Windows/Linux GUI qualification are not resolved by this feature.

External writes are not synthesized into the human Undo stack. Review/revert those writes through the diff/Git workflow; ordinary human editing retains the existing Undo/Redo behavior. Files above 64 Ki UTF-16 units and binary entries are not automatically opened by Follow; their bounded textual diff or notice stays available in the panel. Diff computation also has a five-second between-file budget and checks interruption; if that budget is exceeded, the last view remains available with an error instead of continuously processing an oversized changeset.

## Human edits and rapid writes

Follow validates the active pane, buffer revision, caret/selection and last input again before a background file load installs. Moving on or typing during that load cancels automatic navigation. Diff formatting uses the existing background job service; outdated selections are discarded and truncated displays are labeled.

Writes are coalesced for 300 ms of quiet, with a one-second maximum batch once Java receives events. The operating system may add its own delivery delay (macOS Java WatchService can poll). Excluded paths are filtered before coalescing, so an ignored temporary path cannot replace the meaningful source event. A refresh that overlaps another event drains that pending event even after a transient snapshot failure.

When an external tool changes a file with an unsaved human draft, Shed's existing External Change Conflict dialog remains authoritative. View Both opens a scratch comparison, ordinary Save rejects an unreviewed disk version, and the human's Undo/Redo history remains attached to the original buffer. Keep Mine explicitly authorizes replacing the reviewed disk version on a later save; it does not automatically save.

Follow-up qualification, real Codex execution, conflict handling, visual checks and remaining change-type gaps are documented in [the editing qualification report](AGENT_VISUALIZATION_QUALIFICATION.md). Automatic conflict acknowledgment suppresses repeated notifications for an unchanged disk observation without granting overwrite permission.

The newest config/move/motion checks are recorded in [the motion evidence index](performance/agent-change-motion/INDEX.md). Scrolling presents completed filesystem observations, not a model's private cursor or token stream. Activity retained for tours is bounded to 32 small before/after pairs; rapid writes may coalesce or supersede an earlier tour. Metadata-only changes, binary comparisons and a Git-operation/session timeline remain outside this presentation.

## Startup failures

The launcher reports watch-baseline or attachment initialization failures as soon as they are known. A failed watch includes a bounded `error` string in `shed ctl watch-state`; the launcher reports `WATCH_START_FAILED` with that reason instead of waiting for the generic timeout. It still validates the owning child PID/project and never returns connection credentials in a failure response. Genuine stalled startup retains bounded timeouts.

If source contents exceed the unchanged 32 MiB read budget or file/directory limits, choose a smaller project/folder. There is no silent partial baseline or automatic increase to the memory limits. Known binary assets remain visible through metadata-only observation rather than forcing an otherwise supported source project over the read limit. See [the startup correction evidence](performance/visualization-startup/INDEX.md).

## Optional built-in agent connection

The assistant's ACP dropdown is not a list of detected external CLI agents. It lists user-configured ACP-compatible programs from `agent.<id>.command` settings; OpenCode is the default preset. The selector now reflects all such configured programs. A normal Codex/Claude/OpenCode/local CLI workflow needs no connection or dropdown selection: edit project files and the watcher presents them. Configuring an arbitrary CLI command does not make it ACP-compatible. Connect is explicit, and review/approval requirements remain unchanged.

## Smooth scrolling

Standard Swing scroll panes share smooth wheel/trackpad motion, including source tabs/splits, Explorer, lists/pickers, Git/diff panes, settings/dialogs and supported previews. Shift-wheel scrolls horizontally; nested scroll panes hand off at their boundary. Manual navigation, view/document replacement, hiding or disposal cancels pending frames. Keyboard/caret reveals, scrollbar dragging and the agent's source tour retain their own immediate or dedicated behavior. Existing reduced-motion overrides disable interpolated wheel motion.

Terminal grids keep JediTerm's own row/protocol scrolling; their terminal input is not intercepted by the Swing scroll-pane delegate. Native-shell execution remains unqualified, so terminal-grid smoothing was not added under this change. See [editor-first qualification](EDITOR_FIRST_AGENT_VISUALIZATION.md) for evidence and exclusions.
