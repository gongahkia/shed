# Workspace State Format

Workspace state uses canonical JSON with `version: 1` and exactly these top-level fields: `roots`, `buffers`, `panes`, `activeSelection`, and `tools`.

`roots` contains zero or more unique absolute paths. A buffer is either a file buffer with an absolute path or a scratch buffer with a name and content. Dirty file buffers include content; clean file buffers do not. Panes name their buffer and caret. `activeSelection` names the active pane, its buffer, and caret; it is absent only when the workspace has no buffers. Tool states use a unique identifier and string key/value metadata.

The decoder rejects unsupported versions, unknown or missing fields, wrong types, duplicate IDs, invalid paths, dangling pane references, and inconsistent active selection. It retains its last successfully decoded workspace when a later decode fails; persistence and restore owners consume that result in later workspace stages.

## Persistence

`WorkspaceStatePersistenceService` is the single asynchronous writer for accepted `WorkspaceState` snapshots. Its save request accepts only an already validated immutable state, coalesces queued requests to the latest snapshot, creates the target parent directory, and writes canonical JSON through `AtomicFileWriter`. Normal saves and observer callbacks run on its daemon worker, never on the Swing event-dispatch thread. Closing the service stops that worker and flushes the last accepted snapshot before shutdown completes.
