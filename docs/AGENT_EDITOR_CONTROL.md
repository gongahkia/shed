# Authenticated editor control and visible agent changes

Shed now supports an opt-in external-agent workflow: open an existing project file, read a bounded source excerpt, show agent activity, submit a checked edit proposal, review its diff, and apply it through the existing human controls. Source application uses the ordinary authoritative buffer and Undo/Redo infrastructure. It does not simulate keystrokes or grant an agent an unrestricted write API.

## Using it

1. Open the intended project and a named source file in Shed. The currently qualified machine-navigation configuration is a visible source pane in Insert mode, without IME composition or modal/picker interaction.
2. Run `:agent api start control`, or use **Agents: Enable Reviewed Editor Control** in the existing command palette. This is an explicit project grant; normal startup remains disconnected. Existing status, metadata and navigation grants do not gain new permissions.
3. Use the descriptor and credential **file paths** reported by Shed. The key contents are never command arguments. For example:

   ```sh
   java -cp target/shed-2.0.0.jar shed.Texteditor --workspace-api --control \
     --connect "$descriptor" --credential "$credential_file"
   ```

   Without a request, this returns a versioned JSON navigation context. Each operation supplies the freshly observed `schemaVersion`, `instanceId`, `windowId`, `projectId`, `paneId`, `documentId`, `documentRevision`, `contextRevision`, `caretOffset`, `selectionStart` and `selectionEnd`. Submit a JSON-RPC request using the existing `--request` argument. Refresh context after human activity or a completed open.
4. Review incoming proposals in **Agents → Review changes**. Original/proposed text, selected hunks, revision, Apply, Reject, Rebase and Undo controls are reused. An external proposal automatically reveals that existing review surface. You press Apply; saving is separate.
5. Run `:agent api stop` to revoke the attachment and remove its private discovery/credential files.

| Operation | Extra parameters | Result and effects |
| --- | --- | --- |
| `editor/file/open` | `path`: project-relative existing regular file | Asynchronous checked acquisition; installs into the captured pane and returns fresh context with `outcome: applied`. No missing-file scratch fallback. |
| `editor/source/read` | `offset`, `limit` (1–2,048) | Exact bounded source excerpt, UTF-16 offsets, `nextOffset` and `truncated`; no source/selection changes. |
| `editor/agent/activity` | `start`, `end` | Shows a transient agent marker and pending-range pulse without changing source or replacing the human caret. |
| `editor/edits/propose` | `start`, `end`, `text`, `requestId` | Replaces the specified range in a durable proposal, returns `proposalId` and `reviewRequired: true`, and opens the existing diff review. Source is unchanged until human application. |

The control connection also supports the established metadata and context-checked navigation operations. When navigation is explicitly requested, the normal navigation semantics remain intact and the control grant adds an agent marker at its destination. It does not recolor the human caret. Only implemented operations are accepted: remote Apply, save, process execution, screenshots and resource creation/deletion/rename are not enabled by this grant.

## Safety and bounds

The existing nonce/HMAC mutual authentication, intended instance/window identity, attachment lifetime and sequenced request/reply validation are reused. `editor-control` is a distinct authenticated scope. It permits at most 32 KiB of UTF-8 payload within a 64 KiB frame; older scopes keep their original 2 KiB/4 KiB limits. Keys remain in ephemeral owner-only files. This provides authentication/integrity, not an OS sandbox or protection against a fully compromised same-user process.

Paths are validated against the approved project and existing path policy, including excluded sensitive/internal resources. The portable trusted-local acquisition contract from [A2.2.2c.1](PORTABLE_PROJECT_FILE_OPEN_INTEGRATION.md) remains explicit: no claim of confinement against hostile concurrent ancestor replacement. No project trust dialog/configuration application or alternate notebook view is introduced by this open path. Normal post-commit watcher, LSP, history and view integration is retained.

Requests are serialized per grant, transport waits occur off the EDT, and acquisition uses the existing job service. Current pane/source/interaction checks precede presentation changes. Prepared opens retain typed cancellation until commit and report unknown outcomes conservatively after possible commit. A proposal uses the actual immutable source snapshot and existing version checks; subsequent human edits invalidate application rather than being overwritten. There is no new source/Undo model or persistent worker pool.

Read/propose operations currently support complete documents up to 1 Mi UTF-16 units and replacements up to 8,192 units, subject to the wire byte limit. Ranges must be valid grapheme boundaries under Shed's existing implementation. Known Unicode conformance qualifications remain. Large wrapped/complex documents beyond the existing 16 Ki source-navigation boundary omit automatic decorations and reject explicit unsafe activity, rather than entering the expensive layout path. Partial large-file projections are unsupported.

## Agent presentation

Agent activity uses a separate labeled colored marker. The human caret object, color, selection and source remain authoritative. Pending changed chunks pulse; applied chunks briefly fade. Up to 32 changed ranges are decorated, with a coalesced 50 ms Swing timer bounded to 48 ticks. Repaints cover the affected visible ranges. Document edits/replacement, pane disposal and hiding clear decorations; `shed.caretAnimation=false` and the corresponding component property disable animation. No per-character source insertion, font change, theme change or new visual framework was added.

## Qualification

[Evidence and reproduction](performance/agent-editor-control/INDEX.md) record **360 safe Maven passes, six existing opt-in measurement skips, zero failures/errors; 91 Python tooling passes; successful packaging and packaged Mermaid/math/local-image smoke**. The full native Maven suite remains excluded under the established B0.1 restrictions.

An actual `gpt-6.1-sol` Codex delegate configured with **low** reasoning used separate Java CLI processes to open/read/mark/propose in a visible Shed window. The proposal made names null/blank-safe and added a farewell method. Human Apply was then exercised through the actual review button by the test harness, followed by exact Undo/Redo. A subsequent replay of that exact model-generated payload completed the full app workflow, asynchronous save, endpoint cleanup and compilation of the saved Java file. This is not a hosted-model benchmark or general public-release verdict.

Live captures are app-owned Swing root-pane captures while the native window is showing; they are not unrestricted desktop screenshots or display-scanout measurements. Synthetic Swing key actions are measured through actual workbench completed paint. The latest raw result retains first-operation latency and every sample. No Windows/Linux GUI, broad workload, native keyboard delivery, whole-process memory or multilingual-rendering acceptance is claimed.

The captures show source-text artifacts even before agent decorations are installed; existing glyph/layout/capture limitations remain open. The original/proposed review text, exact source snapshots, Undo/Redo and saved bytes are separately verified. Native-shell benchmark isolation remains NO-GO and was not bypassed. All test homes, shell startup/history, preferences and working files were disposable; no personal project or shell configuration was used.

README.md and existing UI identity, defaults and saved preferences are preserved. See the evidence index for the protected hash, exact commands, failed fixture/probe attempts and final results.
