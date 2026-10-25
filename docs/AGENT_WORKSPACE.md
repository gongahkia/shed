# Agent and human workspace implementation

This work implements the eight agent-workflow requirements identified in the repository audit. Startup/performance optimization is outside this implementation goal. README.md must remain unchanged. Verification during the current work stays headless: no tested editor, browser, or GUI fixture is launched.

## Scope and acceptance ledger

An item is complete only when the application exposes the behavior and focused integration evidence verifies it. A model, command stub, or passing parser test alone does not complete a workflow.

| Requirement | Required end state | Status |
| --- | --- | --- |
| 1. Machine interface | Versioned structured requests/results; explicit workspace/document identity; live unsaved snapshots; bounded search, language/diagnostic and job queries; events; cancellation; CLI/stdio access without a window. | In progress: scoped snapshot contracts, strict JSON-RPC, read-only headless CLI, Swing buffer adapter and authenticated live connection implemented. Search/intelligence, jobs, events and operation cancellation remain pending. |
| 2. Agent integration | ACP negotiation, configured process/auth diagnostics, sessions, streaming activity, input/permission requests, steering and cancellation; capability-aware resumption; one agent fixture and independent interoperability fixture. MCP adapter shares the workspace contract. | Pending. |
| 3. Concurrent edits | One edit service with version/hash preconditions, actor/request provenance, file operations, format preservation, idempotency, preflight, rollback and recoverable partial-operation records; live editor integration. | In progress: document format preservation and common resource transaction extracted from LSP, with guarded rollback and recovery reports. Versioned text proposals, provenance, private event journals and reviewed writes are implemented. Resource proposals, crash recovery controls and full shared-edit migration remain pending. |
| 4. Review/history | Task/turn/branch views distinguish human changes; selectable hunks and editable proposals; accept/reject, task checkpoints and conflict-aware undo preserve later human edits. | In progress: Agents panel supports per-proposal text/hunk review, actor/task/turn labels, apply/reject/rebase and conservative undo. Full task/branch history and checkpoints remain pending. |
| 5. Validation | Existing task/test execution consumes explicit code snapshots; results record command, environment, workspace, code fingerprints and skips/failures; later changes mark results stale; cancellation stops owned work. | Pending. |
| 6. Context | Bounded, inspectable context from selections, unsaved documents, symbols, diagnostics, tests, changes and instructions; exclusions, source/version provenance and persistent enforced task constraints. | Pending. |
| 7. Durable sessions/workspaces | Goals, constraints, actor, workspace, approvals, edits, validation and lifecycle survive interruption; provider resumption is capability-aware; explicitly seeded owned worktrees have setup, routing, integration and conservative cleanup. | Pending. |
| 8. Background/policy | Task/workspace/action capabilities; protected paths; read-only and background-only are independent; foreground actions defer; notifications and navigation respect quiet operation; external-process enforcement limits are explicit. | In progress: immutable capability/path policy. |

## Architecture

Core workspace queries, edits, context, sessions and validation must remain independent of `Texteditor` and feature controllers. Swing adapters capture or mutate editor state on the EDT. Disk/process work runs off the EDT. Human UI, ACP and CLI/MCP adapters consume the same contracts; machine requests must not call ex commands that implicitly select a buffer or open a dialog.

Workspace identities resolve filesystem aliases. Document versions are opaque preconditions, and every request names its workspace/document rather than depending on the active tab. Disk results and unsaved editor results have different provenance. Wire requests are bounded and strictly decoded; errors carry stable codes, not stack traces or parsed display strings.

The existing LSP workspace-edit transaction, immutable text snapshots, workspace index, process cancellation, tasks/tests and Git services provide reusable foundations. Their migration is incremental; separate agent implementations of file writes and validation are not acceptable completion substitutes.

## Verification requirements

- Malformed/oversized messages fail deterministically; unsupported operations have structured errors.
- A human tab switch cannot retarget a request; a human edit invalidates an older edit proposal.
- Read-only denies mutations/execution; background-only defers foreground operations even when otherwise permitted.
- Protected paths include README.md and Git administration, including alias/path traversal attempts.
- Unsaved contents and their versions are visible without saving; ordinary file queries do not start GUI, network or project processes.
- Reviewed changes preserve encoding/EOL and unrelated human edits; retry/cancellation cannot duplicate changes.
- Tests validate the intended snapshot, and their results become stale after changes.
- Restart, protocol failure, task cancellation and partial application retain enough state to explain and recover work.
- End-to-end fixtures cover session → context → proposal → review → validation → integration and an interrupted/resumed task. They remain headless while the no-app-launch constraint applies.

ACP reference: <https://agentclientprotocol.com/protocol/v1/initialization>, <https://agentclientprotocol.com/protocol/v1/file-system>, <https://agentclientprotocol.com/protocol/v1/session-setup>. Protocol versions/capabilities must be negotiated. A configured external executable retains its own operating-system permissions; editor policy is not an OS sandbox, and unsupported containment must never be presented as enforced isolation.

## Current workspace query CLI

`shed --workspace-api --root <directory> --stdio` serves one UTF-8 JSON-RPC 2.0 object per line. `--request '<JSON>'` instead serves one request and exits. The entry point forces headless AWT and never constructs the editor. The default disk-only mode creates no workspace files, child processes, network connections or windows and reports `editorConnected=false`. Explicit `--connect <descriptor>` instead authenticates to the selected live editor as described below.

Start with `{"jsonrpc":"2.0","id":1,"method":"workspace/info","params":{"schemaVersion":1}}`. The result identifies the canonical workspace, capabilities and implemented operations. `documents/list` and `documents/read` require that exact `workspaceId`. Read parameters are `document` (ID, local path or file URI), optional `expectedVersion`, zero-based UTF-16 `offset`, and a `limit` of at most 65,536 code units. Reads preserve complete grapheme clusters and return `nextOffset` plus `truncated`. Document metadata distinguishes editor/disk provenance, dirty state, completeness, encoding, byte-order mark and original newline style. Partial large-file editor projections are reported rather than misrepresented as complete documents.

Requests are bounded to 1 MiB and 64 levels of JSON nesting. Duplicate keys, malformed UTF-8/JSON, ambiguous numeric limits and unsupported fields fail explicitly. Git administration, excluded paths, root escapes and unresolved aliases are rejected. The disk-only adapter advertises `workspace/info`, `documents/list` and `documents/read`. Connected adapters additionally advertise permitted proposal operations described below; unsupported operations fail rather than return empty successes. In a disk-only connection there are no open documents to list; files remain directly readable by path.

Stream mode continues after ordinary request errors; its exit status describes the transport. One-shot request failures exit 65. Usage errors exit 64, stream I/O errors exit 74, and oversized stream frames terminate with a structured limit error and exit 65. Responses use stdout and startup/transport diagnostics use stderr.

## Explicit live connection

Select a workspace folder and run `:agent api start` in the editor. `:agent api status` reports the connection descriptor path; `:agent api stop` revokes the listener and clients and removes its owned descriptor. Start/stop filesystem work runs in workers; there is no automatic start or reconnect. Closing the editor also revokes its source and owned connections.

Connect from a terminal with `shed --workspace-api --root <same-directory> --connect <descriptor-path> --stdio`, or use `--request '<JSON>'`. The CLI remains headless. Queries use the live buffer adapter, including unsaved contents and stable document revisions. A tab/Explorer switch cannot retarget the connection. Multiple editor instances have separate descriptors and random instance IDs; connecting requires the matching canonical root, instance and 256-bit token.

The server binds only IPv4 loopback on an ephemeral port. The descriptor contains a credential, is never printed as content, and must remain in its owner-only session directory (POSIX permissions or owner-only ACL). Unsupported permission models, symbolic descriptor links, shared permissions, invalid descriptors and a mismatched root fail closed. Credential storage must be outside the exposed workspace, so an API cannot reveal its own credentials through document reads. Configure a session directory outside the selected root when working on a folder that would contain it.

Eight simultaneous clients are allowed. Authentication has a three-second absolute deadline and a 4 KiB frame limit. Authenticated frames have a sixty-second socket deadline, 1 MiB requests and 16 MiB responses; the CLI has a thirty-second response timeout. Malformed JSON/UTF-8 produces structured errors; oversized frames abort that connection. Valid notifications produce no response and do not desynchronize later requests. This is a local workspace API, not ACP/MCP, a remote service, an external-process sandbox, or a background agent runtime. The default connection is read-only; proposal mode is explicitly enabled as described below. Unsupported operations remain explicit errors.

## Live document adapter and format preservation

`EditorWorkspaceDocuments` captures each buffer's immutable text snapshot and metadata on the Swing event thread. Stable buffer IDs and monotonically advancing opaque revisions distinguish human edits, reloads, path changes and format changes. Materialization and filesystem ownership resolution run on the requesting caller; transport callers must use a worker. Scratch documents bind to their selected workspace when first exposed and retain that ownership when the Explorer selection changes. Closing the editor revokes the source. Creating the adapter has no listener or implicit startup side effects.

Ordinary document saves, asynchronous saves and backups preserve UTF-8/UTF-16 byte-order marks. Marked malformed Unicode files fail to load rather than silently substituting replacement characters. A save finishing after an encoding/EOL change leaves the newer buffer dirty. Incomplete large-file projections remain explicitly incomplete and are not exposed as full documents.

Verification: `mvn -B -q -Djava.awt.headless=true clean package` passed 994 tests with zero failures/errors and 83 headless skips. The new format and adapter checks use model objects and the Swing event thread without a frame; the CLI subprocess explicitly forces headless AWT. Desktop behavior remains unverified during the no-app-launch restriction. This evidence does not complete any pending end-to-end agent workflow.

After live-connection integration, the same clean headless package passed 1,002 tests with zero failures/errors and 83 skips. Six bridge tests cover actual unsaved buffers through the connected CLI and a headless application subprocess, stale versions, authentication before document capture, mismatched workspaces/tokens, notifications and malformed messages, size limits, absolute authentication deadlines, multiple instances, revocation, and separation of credentials from workspace reads. Private-state tests cover permission/link rejection, bounded reads and conservative descriptor cleanup. The network-source audit and architecture guards pass. The editor command/window lifecycle is composed and compiled but its native GUI interactions have not been exercised under the current restriction; Windows ACL behavior also remains unverified locally.

## Common resource transaction

LSP workspace edits now use the controller-free `WorkspaceEditTransaction` for staging, replacement, rename, deletion, commit and rollback. Staged files are forced to disk and replacements reuse the ordinary save path's metadata-copy policy. The former duplicate controller transaction and unused direct-write helpers are removed. Version/workspace preflight and the thread affinity of editor callbacks remain responsibilities of the caller; this extraction is not a complete agent edit service.

Transactions fingerprint resources, including directory trees bounded to 10,000 descendants, without following symbolic links. Rollback checks for newer content before removing a replacement or reversing a rename. An external writer holding a backed-up original invalidates commit; rollback preserves that writer's newer data. Undo callbacks continue after an I/O/runtime failure and return `RECOVERY_REQUIRED`, failure details and retained recovery paths. Cleanup removes only owned staged resources and known backup entries, preserves foreign additions, and reports failures. LSP result messages include those recovery notices instead of silently swallowing rollback failures. Filesystem identity/hash checks are preconditions and recovery safeguards, not operating-system locking or a sandbox against independent writers.

Nine headless resource workflows cover mixed operations with metadata, multi-file rollback, newer-human-edit conflicts, changed originals, directory/link cleanup, foreign additions, failing editor callbacks, replaced created directories, and originals moved back into recovery storage. The final clean headless package passed 1,011 tests with zero failures/errors and 83 skips. Language-service, completion, save-model and architecture checks pass within that suite. Native LSP interaction remains unverified while app launches are prohibited. Crash-durable task journals, request idempotency, actor provenance, shared replacement integration, full editor-registry restoration and end-to-end agent review remain required work.

## Shared text changes and encoding

`WorkspaceTextEdits` supplies bounded line-based hunks, selected-hunk application and conservative three-way rebasing. Hunk offsets stay on grapheme boundaries. Rebasing refuses overlapping intervening changes; unrelated later human edits survive an inverse patch. The diff bounds its line list and dynamic-programming matrix, merging large/complex regions into a coarse hunk rather than allocating an unbounded matrix. Review UI and durable task undo still need to consume these primitives.

`TextFileCodec` now owns strict Unicode/BOM encoding and the explicit Latin-1 compatibility path used by ordinary editor loads. Workspace reads retain their stricter UTF-8/BOM-marked UTF-16 boundary. Editor saves, asynchronous saves and backups preserve mixed newline bytes in unchanged spans; changed/inserted spans use an adjacent newline style. An explicit newline setting normalizes the file and remains dirty if selected while an older asynchronous save is in flight. Save images retain their selected encoding instead of guessing it from written bytes.

Closed-file LSP edits use this codec and normalized offsets, retain source format through rename-and-edit, and check captured disk versions before staging. Resource-only binary renames do not require text decoding. These paths are tested directly through a disk-only LSP controller without constructing a frame. The clean headless package at this step passed 1,020 tests, zero failures/errors and 83 skips, including randomized Unicode edit round trips, selected hunks, conservative undo, mixed newlines, charset changes and the actual LSP application path. This does not complete the pending review/history or shared agent-edit service.

## Durable text proposals and transport

`:agent api start propose` explicitly starts a connection with READ and PROPOSE capabilities. The default start remains read-only. Changing mode requires stopping the existing connection. Neither connection grants APPLY. Trusted actor identity comes from the adapter, never a request field. Current local API connections share the `workspace-api` actor within a workspace; request IDs should therefore be globally unique for those clients. These tokens authenticate a local connection, not separate provider identities.

Connected `workspace/info` advertises permitted proposal operations. `proposals/propose` requires workspaceId, requestId, taskId, turnId and changes containing document, expectedVersion and normalized-LF text. Existing complete text documents, including unsaved editor buffers, are supported. Empty replacement text is valid. Creating a proposal does not modify the workspace. Repeated identical actor/request IDs return the persisted outcome; different payloads with the same ID conflict. Task/turn strings are provenance labels at this stage, not implemented task/session lifecycle objects.

`proposals/list` provides bounded pages with nextOffset/truncated. `proposals/read` accepts id, document index, optional expectedRevision, text variant (before/proposed/selected), UTF-16 offset/limit and hunkOffset/hunkLimit. Text pages preserve graphemes; hunk pages contain ranges, replacement lengths and selection state. `proposals/revise` requires the reviewed revision and can edit text or select hunk indices; `proposals/reject` requires the reviewed revision. Apply/undo/rebase are implemented by the shared reviewer service and permission-gated in the transport; proposal-only clients cannot invoke them.

Private history lives outside the exposed workspace under the owner-only session directory at agent-workspace/<workspaceId>. Immutable chained events and exact UTF-16/raw-byte blobs preserve original contents, actor, constraints, review revision and application outcomes. An OS file lease and in-process lease guard prevent concurrent journal mutations. Interrupted applying/undoing records require inspection; they never silently retry. Full recovery UI, resource-operation journals and durable task/session lifecycles remain pending.

Application validates all source versions/formats before effects, uses the shared filesystem transaction, and applies live text as an atomic EDT batch with native undo grouping. Rejected/rolled-back batches do not pollute native undo history. Raw document edits invalidate versions even if the text later returns to its prior value. Cancelling a queued EDT edit prevents it running later; once it starts, its actual result is recorded. Open buffers remain unsaved and the result explicitly records pendingSaveAtApplication. Closed files retain their encoding/BOM and mixed newline spans. Explicit rebasing and inverse changes preserve disjoint human edits; overlapping changes conflict. The broader LSP/project-replace migration, file create/rename/delete proposal support and task-level review are still required.

Focused headless checks pass for proposal persistence, format-preserving apply/undo, stale inputs, hunk selection, unsaved buffers, rollback conflicts and actual authenticated bridge transport. Four new transport workflows cover proposal-only permissions, persisted human review, idempotent retries, strict request fields, actor/workspace spoofing, bounded grapheme pages, stale review pages, empty text and filtered list cursors. Native desktop interaction has not been exercised.

## Human review surface

Open View > Agent Review or `:agent` / `:agent proposals`. The docked Agents surface uses the existing Swing workbench style, zoom-aware control heights and accessible labels. It lists persisted proposals for the selected workspace, labels actor/task/turn/review revision, and shows original and proposed text alongside selectable hunks. A document selector makes every file in a proposal inspectable. Narrow layouts keep actions reachable in a horizontal scroller.

Edit proposed text or choose hunks, then Save review. Editing text rebuilds the hunks and selects all of the resulting changes; inspect those before accepting. Apply proposal accepts the saved selections for the entire proposal, with the document count in its label. Local unsaved review edits disable apply/navigation/refresh and survive automatic refreshes; an explicit discard restores the persisted review. Rebase for review preserves disjoint intervening edits and returns an updated proposal for inspection. Undo proposal preserves later disjoint human changes and refuses overlaps. Open buffers stay unsaved after acceptance; the panel states that saving is separate.

Filesystem/journal work runs in workers. Review requests capture their workspace and revision, and delayed results from a prior workspace are discarded. Incoming proposals never open the panel, select a document, launch a process or take focus. Refresh is explicit while already viewing a review. Interrupted operations are labeled and cannot be automatically reapplied; recovery controls remain pending.

Four headless component workflows exercise real Swing controls and the durable service without a frame: multiple-document text/hunk review through apply/undo, stale human changes followed by explicit rebase, preservation of unsaved review edits across refresh/workspace changes, and delayed old-workspace results. Native window layout, keyboard traversal and desktop rendering remain unverified under the no-app-launch restriction.

Verification after API and review-surface integration: clean headless package passed 1,041 tests with zero failures/errors and 83 headless skips. This verifies the implemented proposal slice; the remaining numbered requirements are still active work.
