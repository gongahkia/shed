# Workspace Index

`workspace.index.enabled` is `true` by default. `WorkspaceIndexService` indexes one normalized non-symbolic workspace root at a time and writes its index atomically under its caller-provided storage directory.

Every regular file is checked with `git check-ignore --no-index` when Git is available and the root is in a Git repository. Git metadata directories, symbolic links, unreadable files, and paths outside the root are not indexed. Projects without Git are indexed without Git ignore rules. Other Git errors fail the build.

`status()` exposes `DISABLED`, `BUILDING`, `READY`, `CANCELLED`, or `FAILED` plus exact current counts for visited, indexed, ignored, skipped, unreadable, out-of-boundary, and excluded-directory items. The persisted JSON records only root-relative path, size, and modification time; it contains no file content.

`CancellationSource` can stop a build before persistence; its final status is `CANCELLED` and it returns no index path. After a build, `recover()` watches workspace directories and updates changed files in memory and on disk. Directory and ignore-rule changes trigger a full rebuild. After a restart, the persisted index is revalidated by a full scan before reuse; malformed or incomplete JSON is rebuilt without being returned. Disabled indexing bypasses persisted data. The index storage directory is excluded when it lies inside the workspace.

Use `:workspace index status` to open the comparison surface. It reports the selected search source, persisted-index preference, cache status, file count, cache-byte cost, and explicit controls. `:workspace index enable` and `:workspace index disable` persist only the preference; neither command builds an index. `:workspace index benchmark` measures both a cold build and a warm index query and can create or replace the cache. The selected source applies to future workspace searches.

`:grep <text>` and `:rg <text>` run incremental literal text searches in the background. With persistent indexing enabled, search recovers the Git-ignore-aware index before reading its paths; with it disabled, search performs the same Git-ignore-aware scan without creating an index cache. Matches enter quickfix with absolute path, one-based line, and one-based UTF-16 column locations. No-match and cancellation states are reported in the status message; failed jobs are also recorded in the local command log.

Quick Open warms the watched project index when a workspace folder opens and reads it on subsequent requests. When indexing is disabled or unavailable, it uses Git's tracked and visible untracked file list or a directory scan. Work runs in the background; filter results are limited to 200 visible rows and ranked off the UI thread. Local workspace-symbol searches cache parsed symbols by file size and modification time for repeated queries.
