# Document Lifecycle

This contract defines Shed's authoritative document state. `FileBuffer.modified` is the dirty flag; it is not inferred by comparing text. `savedContent` is the last successfully opened, reloaded, or saved full document snapshot.

## States

| State | Invariant |
| :--- | :--- |
| Clean file buffer | Has a file path; `modified=false`; full content and `savedContent` represent the acknowledged disk version. |
| Dirty file buffer | Has a file path; `modified=true`; in-memory content is authoritative until saved, discarded, or reloaded. |
| Named unsaved buffer | Has a target path that may not exist; starts clean and becomes a clean file buffer only after a successful save. |
| Scratch buffer | Has no file path; may be clean or dirty; `:w <path>` is required to persist it. |
| Recovery snapshot | Serialized dirty editor content; restoring creates or updates a dirty buffer with empty undo/redo history and never writes the recovered content automatically. |

Large-file preview is an overlay on a file-buffer state, not a separate lifecycle state. Its hidden tail remains part of full content and is preserved by save.

## Transitions

| Event | Preconditions | Result | Failure or cancel |
| :--- | :--- | :--- | :--- |
| Open existing file | Read and decode succeed | Clean file buffer; full content becomes `savedContent` | No buffer is added when opening throws. |
| Open absent target | Target does not exist | Empty named unsaved buffer | No disk file is created. |
| Edit | Any editable buffer | Dirty; bounded undo history, versioned backup, and recovery snapshot attempts are best-effort | Backup/snapshot failure is recorded locally and does not clear dirty content. |
| Save / save-as | File path is available or supplied | Forced temporary write, atomic replacement, and target size/digest verification succeed; clean; `savedContent` and timestamp advance; versioned backups remain subject to retention policy | Write failure leaves the buffer dirty and its in-memory content unchanged; post-move verification restores the prior source when possible and reports retry/remediation details. |
| Reload from disk | Explicit reload, or an unmodified externally changed or replaced file | Clean file buffer with new disk content and snapshot | Read/decode failure leaves the current buffer state unchanged. |
| External change while dirty | Filesystem state is changed, deleted, replaced, or unsupported | Keep Mine and View Both retain dirty content; Reload Theirs replaces it only when a regular disk file is available | Dialog dismissal is Keep Mine: content remains dirty and the external state is acknowledged. |
| External deletion or unsupported target while clean | Filesystem state is deleted or unsupported | Buffer content is retained and the external state is acknowledged | No buffer is discarded. |
| Close / quit | Dirty buffer and no force flag | Multiple-buffer close is blocked; final-buffer close and quit request confirmation | Cancel leaves buffer membership, content, and dirty state unchanged. `:bd!`/`:q!` discard deliberately. |
| Recovery restore | Snapshot decodes | Restored content is dirty until an explicit save | Declining restore leaves snapshots in place; restoration never overwrites disk automatically. |

## Ownership

- `FileBuffer` owns content, dirty state, saved snapshot, encoding, line endings, and disk I/O transitions.
- `PaneBufferController` owns opening, active-buffer changes, and buffer deletion.
- `RecoveryController` owns external-change resolution and dirty-buffer snapshot/restore flow.
- `SessionConfigController` owns quit confirmation.
- `DocumentLifecycle` owns the common dirty-discard confirmation rule used by close and quit paths.
