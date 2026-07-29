# Workspace Index

`workspace.index.enabled` is `false` by default. When enabled, `WorkspaceIndexService` indexes one normalized non-symbolic workspace root at a time and writes its index atomically under its caller-provided storage directory.

Every regular file is checked with `git check-ignore --no-index`, which applies Git's ignore and exclude mechanism even to tracked paths. Git metadata directories, symbolic links, unreadable files, and paths outside the root are not indexed. If Git cannot evaluate the root, the build reports `FAILED` and does not write an index.

`status()` exposes `DISABLED`, `BUILDING`, `READY`, or `FAILED` plus exact current counts for visited, indexed, ignored, skipped, unreadable, out-of-boundary, and excluded-directory items. The persisted JSON records only root-relative path, size, and modification time; it contains no file content.
