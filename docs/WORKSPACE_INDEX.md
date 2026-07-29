# Workspace Index

`workspace.index.enabled` is `false` by default. When enabled, `WorkspaceIndexService` indexes one normalized non-symbolic workspace root at a time and writes its index atomically under its caller-provided storage directory.

Every regular file is checked with `git check-ignore --no-index`, which applies Git's ignore and exclude mechanism even to tracked paths. Git metadata directories, symbolic links, unreadable files, and paths outside the root are not indexed. If Git cannot evaluate the root, the build reports `FAILED` and does not write an index.

`status()` exposes `DISABLED`, `BUILDING`, `READY`, `CANCELLED`, or `FAILED` plus exact current counts for visited, indexed, ignored, skipped, unreadable, out-of-boundary, and excluded-directory items. The persisted JSON records only root-relative path, size, and modification time; it contains no file content.

`CancellationSource` can stop a build before persistence; its final status is `CANCELLED` and it returns no index path. `recover()` rescans before use after a restart: a changed index is rebuilt as stale, malformed or incomplete JSON is rebuilt without being returned, and disabled indexing bypasses all persisted data.

Use `:workspace index status` to open the comparison surface. It reports the active ad-hoc project scan, the persisted-index preference, cache status, file count, cache-byte cost, and explicit controls. `:workspace index enable` and `:workspace index disable` persist only the preference; neither command builds an index. `:workspace index benchmark` starts an explicit cancellable local build measurement and can create or replace the cache. Indexed project search remains unavailable until its separate implementation is enabled.
