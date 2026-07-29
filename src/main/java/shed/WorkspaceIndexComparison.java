package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

final class WorkspaceIndexComparison {
    private final WorkspaceIndexService indexService;

    WorkspaceIndexComparison(WorkspaceIndexService indexService) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
    }

    Report inspect(boolean persistentIndexEnabled, Path workspaceRoot) {
        if (workspaceRoot == null) {
            return new Report(persistentIndexEnabled, null, "unavailable", 0, 0, "no workspace root", null);
        }
        String normalizedRoot = workspaceRoot.toAbsolutePath().normalize().toString();
        Path cachePath = null;
        try {
            cachePath = indexService.indexPath(workspaceRoot);
            if (!Files.isRegularFile(cachePath)) {
                return new Report(persistentIndexEnabled, normalizedRoot, "absent", 0, 0,
                    "no persisted index", cachePath);
            }
            long cacheBytes = Files.size(cachePath);
            WorkspaceIndexService.WorkspaceIndex index = indexService.load(workspaceRoot);
            return new Report(persistentIndexEnabled, normalizedRoot, "ready", index.entries().size(),
                cacheBytes, "persisted index is available", cachePath);
        } catch (IOException | SecurityException error) {
            return new Report(persistentIndexEnabled, normalizedRoot, "invalid", 0, cacheBytes(cachePath), message(error), cachePath);
        }
    }

    private static long cacheBytes(Path cachePath) {
        if (cachePath == null) {
            return 0;
        }
        try {
            return Files.isRegularFile(cachePath) ? Files.size(cachePath) : 0;
        } catch (IOException | SecurityException error) {
            return 0;
        }
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record Report(boolean persistentIndexEnabled, String workspaceRoot, String indexStatus, int indexedFiles, long cacheBytes,
                  String detail, Path cachePath) {
        Report {
            if (indexedFiles < 0 || cacheBytes < 0) {
                throw new IllegalArgumentException("workspace index comparison counters must be non-negative");
            }
        }

        String format() {
            StringBuilder result = new StringBuilder();
            result.append("Current search source: ad-hoc project scan\n");
            result.append("Persistent-index preference: ").append(persistentIndexEnabled ? "enabled" : "disabled (default)").append('\n');
            result.append("Indexed project search: unavailable; ad-hoc scanning remains active\n");
            result.append("Workspace: ").append(workspaceRoot == null ? "unavailable" : workspaceRoot).append('\n');
            result.append("Index status: ").append(indexStatus).append(" (").append(detail).append(")\n");
            result.append("Index entries: ").append(indexedFiles).append('\n');
            result.append("Cache cost: ").append(cacheBytes).append(" bytes\n");
            if (cachePath != null) {
                result.append("Cache path: ").append(cachePath).append('\n');
            }
            result.append("Build cost: not measured; run :workspace index benchmark explicitly\n\n");
            result.append("Controls:\n");
            result.append("  :workspace index status\n");
            result.append("  :workspace index enable\n");
            result.append("  :workspace index disable\n");
            result.append("  :workspace index benchmark\n");
            return result.toString();
        }
    }
}
