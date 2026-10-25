# Agent and human workspace implementation

This work implements the eight agent-workflow requirements identified in the repository audit. Startup/performance optimization is outside this implementation goal. README.md must remain unchanged. Verification during the current work stays headless: no tested editor, browser, or GUI fixture is launched.

## Scope and acceptance ledger

An item is complete only when the application exposes the behavior and focused integration evidence verifies it. A model, command stub, or passing parser test alone does not complete a workflow.

| Requirement | Required end state | Status |
| --- | --- | --- |
| 1. Machine interface | Versioned structured requests/results; explicit workspace/document identity; live unsaved snapshots; bounded search, language/diagnostic and job queries; events; cancellation; CLI/stdio access without a window. | In progress: scoped snapshot contracts, strict JSON-RPC, read-only headless CLI, Swing buffer adapter and authenticated live connection implemented. Search/intelligence, jobs, events and operation cancellation remain pending. |
| 2. Agent integration | ACP negotiation, configured process/auth diagnostics, sessions, streaming activity, input/permission requests, steering and cancellation; capability-aware resumption; one agent fixture and independent interoperability fixture. MCP adapter shares the workspace contract. | Pending. |
| 3. Concurrent edits | One edit service with version/hash preconditions, actor/request provenance, file operations, format preservation, idempotency, preflight, rollback and recoverable partial-operation records; live editor integration. | In progress: document BOM preservation and asynchronous save format preconditions implemented. Shared edit transactions and agent writes remain pending. |
| 4. Review/history | Task/turn/branch views distinguish human changes; selectable hunks and editable proposals; accept/reject, task checkpoints and conflict-aware undo preserve later human edits. | Pending. |
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

Requests are bounded to 1 MiB and 64 levels of JSON nesting. Duplicate keys, malformed UTF-8/JSON, ambiguous numeric limits and unsupported fields fail explicitly. Git administration, excluded paths, root escapes and unresolved aliases are rejected. The workspace adapter currently advertises only `workspace/info`, `documents/list` and `documents/read`; unsupported writes or other operations fail rather than return empty successes. In a disk-only connection there are no open documents to list; files remain directly readable by path.

Stream mode continues after ordinary request errors; its exit status describes the transport. One-shot request failures exit 65. Usage errors exit 64, stream I/O errors exit 74, and oversized stream frames terminate with a structured limit error and exit 65. Responses use stdout and startup/transport diagnostics use stderr.

## Explicit live connection

Select a workspace folder and run `:agent api start` in the editor. `:agent api status` reports the connection descriptor path; `:agent api stop` revokes the listener and clients and removes its owned descriptor. Start/stop filesystem work runs in workers; there is no automatic start or reconnect. Closing the editor also revokes its source and owned connections.

Connect from a terminal with `shed --workspace-api --root <same-directory> --connect <descriptor-path> --stdio`, or use `--request '<JSON>'`. The CLI remains headless. Queries use the live buffer adapter, including unsaved contents and stable document revisions. A tab/Explorer switch cannot retarget the connection. Multiple editor instances have separate descriptors and random instance IDs; connecting requires the matching canonical root, instance and 256-bit token.

The server binds only IPv4 loopback on an ephemeral port. The descriptor contains a credential, is never printed as content, and must remain in its owner-only session directory (POSIX permissions or owner-only ACL). Unsupported permission models, symbolic descriptor links, shared permissions, invalid descriptors and a mismatched root fail closed. Credential storage must be outside the exposed workspace, so an API cannot reveal its own credentials through document reads. Configure a session directory outside the selected root when working on a folder that would contain it.

Eight simultaneous clients are allowed. Authentication has a three-second absolute deadline and a 4 KiB frame limit. Authenticated frames have a sixty-second socket deadline, 1 MiB requests and 16 MiB responses; the CLI has a thirty-second response timeout. Malformed JSON/UTF-8 produces structured errors; oversized frames abort that connection. Valid notifications produce no response and do not desynchronize later requests. This is a read-only workspace API, not ACP/MCP, a remote service, an external-process sandbox, or a background agent runtime. Unsupported operations remain explicit errors.

## Live document adapter and format preservation

`EditorWorkspaceDocuments` captures each buffer's immutable text snapshot and metadata on the Swing event thread. Stable buffer IDs and monotonically advancing opaque revisions distinguish human edits, reloads, path changes and format changes. Materialization and filesystem ownership resolution run on the requesting caller; transport callers must use a worker. Scratch documents bind to their selected workspace when first exposed and retain that ownership when the Explorer selection changes. Closing the editor revokes the source. Creating the adapter has no listener or implicit startup side effects.

Ordinary document saves, asynchronous saves and backups preserve UTF-8/UTF-16 byte-order marks. Marked malformed Unicode files fail to load rather than silently substituting replacement characters. A save finishing after an encoding/EOL change leaves the newer buffer dirty. Incomplete large-file projections remain explicitly incomplete and are not exposed as full documents.

Verification: `mvn -B -q -Djava.awt.headless=true clean package` passed 994 tests with zero failures/errors and 83 headless skips. The new format and adapter checks use model objects and the Swing event thread without a frame; the CLI subprocess explicitly forces headless AWT. Desktop behavior remains unverified during the no-app-launch restriction. This evidence does not complete any pending end-to-end agent workflow.

After live-connection integration, the same clean headless package passed 1,002 tests with zero failures/errors and 83 skips. Six bridge tests cover actual unsaved buffers through the connected CLI and a headless application subprocess, stale versions, authentication before document capture, mismatched workspaces/tokens, notifications and malformed messages, size limits, absolute authentication deadlines, multiple instances, revocation, and separation of credentials from workspace reads. Private-state tests cover permission/link rejection, bounded reads and conservative descriptor cleanup. The network-source audit and architecture guards pass. The editor command/window lifecycle is composed and compiled but its native GUI interactions have not been exercised under the current restriction; Windows ACL behavior also remains unverified locally.
