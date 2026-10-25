# Agent and human workspace implementation

This work implements the eight agent-workflow requirements identified in the repository audit. Startup/performance optimization is outside this implementation goal. README.md must remain unchanged. Verification during the current work stays headless: no tested editor, browser, or GUI fixture is launched.

## Scope and acceptance ledger

An item is complete only when the application exposes the behavior and focused integration evidence verifies it. A model, command stub, or passing parser test alone does not complete a workflow.

| Requirement | Required end state | Status |
| --- | --- | --- |
| 1. Machine interface | Versioned structured requests/results; explicit workspace/document identity; live unsaved snapshots; bounded search, language/diagnostic and job queries; events; cancellation; CLI/stdio access without a window. | In progress: policy and workspace contracts first. |
| 2. Agent integration | ACP negotiation, configured process/auth diagnostics, sessions, streaming activity, input/permission requests, steering and cancellation; capability-aware resumption; one agent fixture and independent interoperability fixture. MCP adapter shares the workspace contract. | Pending. |
| 3. Concurrent edits | One edit service with version/hash preconditions, actor/request provenance, file operations, format preservation, idempotency, preflight, rollback and recoverable partial-operation records; live editor integration. | Pending. |
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
