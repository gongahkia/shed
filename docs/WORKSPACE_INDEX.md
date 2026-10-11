# Workspace Index

`workspace.index.enabled` is `true` by default. `WorkspaceIndexService` indexes one normalized non-symbolic workspace root at a time and writes its index atomically under its caller-provided storage directory.

Every regular file is checked with `git check-ignore --no-index` when Git is available and the root is in a Git repository. Git metadata directories, symbolic links, unreadable files, and paths outside the root are not indexed. Projects without Git are indexed without Git ignore rules. Other Git errors fail the build.

`status()` exposes `DISABLED`, `BUILDING`, `READY`, `CANCELLED`, or `FAILED` plus exact current counts for visited, indexed, ignored, skipped, unreadable, out-of-boundary, and excluded-directory items. The persisted JSON records only root-relative path, size, and modification time; it contains no file content.

`CancellationSource` can stop a build before persistence; its final status is `CANCELLED` and it returns no index path. `recover()` reuses an index built by the same service during the previous three seconds, then rescans to refresh it. After a restart, a changed index is rebuilt as stale, malformed or incomplete JSON is rebuilt without being returned, and disabled indexing bypasses all persisted data.

Use `:workspace index status` to open the comparison surface. It reports the selected search source, persisted-index preference, cache status, file count, cache-byte cost, and explicit controls. `:workspace index enable` and `:workspace index disable` persist only the preference; neither command builds an index. `:workspace index benchmark` starts an explicit cancellable local build measurement and can create or replace the cache. The selected source applies to future workspace searches.

`:grep <text>` and `:rg <text>` run incremental literal text searches in the background. With persistent indexing enabled, search recovers the Git-ignore-aware index before reading its paths; with it disabled, search performs the same Git-ignore-aware scan without creating an index cache. Matches enter quickfix with absolute path, one-based line, and one-based UTF-16 column locations. No-match and cancellation states are reported in the status message; failed jobs are also recorded in the local command log.

Quick Open uses Git's tracked and visible untracked file list when available, and falls back to a directory scan outside Git repositories. The scan runs in the background. A three-second in-memory list avoids a repeat scan when reopening the picker; filter results are limited to 200 visible rows and ranked off the UI thread. Local workspace-symbol searches cache parsed symbols by file size and modification time for repeated queries.
