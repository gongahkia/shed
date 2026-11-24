# Live visualization for external coding agents

Tell your CLI agent:

> Use Shed to visualize this project's changes. Run `shed visualize . --agent Codex` once, then continue editing files normally. Leave Shed open for me. Do not turn the filesystem edits into review proposals just to visualize them.

The launcher opens a new Shed window, captures a watch baseline, starts its explicitly authorized local attachment, prints JSON with `ready`, `descriptor`, `credential`, `pid`, `project` and `agent`, and returns. It does not run Codex or any model. The human can keep using the CLI, and Shed watches ordinary filesystem writes.

```sh
shed visualize . --agent Codex
```

Use the returned descriptor if you want to control the view:

```sh
export SHED_CONTROL=/returned/private/status-ATTACHMENT.json
shed ctl watch-state
shed ctl pause
shed ctl follow
shed ctl watch-stop
shed ctl watch --agent Codex
```

For a window already using an explicitly enabled control attachment, `shed ctl watch` starts watching that window's active project. Inside Shed, `:agent watch` or the play icon in Agents → Live changes starts a watch without launching any coding agent; the square icon stops it. `:agent watch stop` also stops it. Launching again creates another window; there is no implicit choice among existing instances.

## What appears

Agents → Live changes contains a persistent file list and a unified diff. `A`, `M`, `D` and `R` identify added, modified, deleted and exact-content renamed files. The list shows counts when its snapshot provides exact textual counts. Added and deleted lines use existing theme success/error colors; changed words in adjacent removed/added line pairs receive emphasis. Previous/next arrows move between diff hunks. Enter or double-click opens a surviving file. Deleted contents remain readable in the diff.

The baselines have different meanings:

- **Since watch started:** compares disk contents with the in-memory snapshot taken when the watch became ready. Existing uncommitted changes are part of that starting snapshot. Restarting the watch resets this baseline.
- **Git changes:** reuses Shed's existing Git status/diff services to show repository changes, including staged/working-tree patch sections and untracked textual files. These are repository changes, not proof of agent authorship.

Follow selects the latest observed changed file. In the watch snapshot view it opens surviving source files, scrolls to a validated changed range and uses the existing distinct agent marker/pulse when the loaded source matches the observed text. In Git mode it follows the diff selection. It does not type fake intermediate characters. Human typing, unsaved edits, or manual interaction with the changed-file list pause automatic following. The Follow button resumes it. Explicit file opening remains available while paused. Source loading, external reload/conflict handling and Undo stay with Shed's existing editor infrastructure. This panel never writes source files, applies proposals, saves buffers, stages or commits.

Existing visible explorer trees refresh their loaded directories after watch updates, retaining surviving nodes, selection and expansion where possible. The live changes list is authoritative for this watch's supported file set; a deleted active editor buffer follows Shed's existing external-deletion policy rather than being forcibly closed.

## Scope and limits

The watcher observes disk writes, not model tokens. The agent name is a display label chosen at watch startup. A change marker means “changed here”, not an observed remote caret or authenticated authorship. Human saves and other tools inside the same project also appear.

Snapshots are bounded: 4,096 files, 64 KiB of diffable UTF-8 text per file, 16 MiB total read bytes, depth 32 and a five-second capture deadline. Larger/binary/unsupported encodings receive a bounded notice rather than a fabricated textual diff. Exact renames require one unambiguous unchanged-content source/destination pair; edited renames may appear as delete/add. Dot directories/files, common dependency/build directories, environment/credential file patterns, symlinks and independently configured nested project roots are excluded. The session does not claim complete .gitignore equivalence. Git may list additional ignored-by-watch repository paths, but content observation remains bounded to the selected project.

A failed refresh retains the last view with an explicit error. Stopping clears the watch snapshot and stops its event worker; the last rendered diff is a bounded display artifact. Closing the window disposes the watch. No project-wide watcher or extra worker runs for this feature until a watch is explicitly started. The existing Git tab has its own unchanged refresh lifecycle.

The launcher uses the current Java executable and classpath directly, not a shell. It waits at most 45 seconds for its owned child's matching PID/project readiness record. Owner-only private session storage and the existing mutually authenticated bridge remain authoritative. This is a trusted-local workflow, not an OS sandbox against a same-user process that can read the user's files. Launch starts the existing editor-control scope; automatic Apply, Save, arbitrary shell execution and desktop screenshots remain unavailable.

PNG provider/model logos are bundled for OpenAI/Codex, Claude, Gemini, Ollama, DeepSeek, Qwen, Meta/Llama, Mistral, OpenRouter, OpenCode and Hugging Face. Unknown/custom models retain their text labels. Light/dark assets are selected locally without network downloads. Model/provider names remain visible; common action buttons use Shed's outline icon family with tooltips and accessible names. Permission/sign-in/apply decisions keep explicit text.

The icons come from LobeHub's MIT-licensed `@lobehub/icons-static-png` 2.12.0. Exact source URLs, SHA-256 values and license are in `assets/providers/`. Logos remain their owners' trademarks.

## Verification

See [the evidence index](performance/live-agent-visualization/INDEX.md) for safe tests, fixed direct-Java launcher acceptance, real filesystem writers, disposable Git diffs, screenshots and qualifications. Native-shell confinement, whole-IDE native memory, remaining Unicode glyph/layout problems and Windows/Linux GUI qualification are not resolved by this feature.

External writes are not synthesized into the human Undo stack. Review/revert those writes through the diff/Git workflow; ordinary human editing retains the existing Undo/Redo behavior. Large or binary entries are not automatically opened by Follow. Diff computation also has a five-second between-file budget and checks interruption; if that budget is exceeded, the last view remains available with an error instead of continuously processing an oversized changeset.
