package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class WorkspaceIndexBenchmark {
    private final WorkspaceIndexService indexService;

    WorkspaceIndexBenchmark(WorkspaceIndexService indexService) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
    }

    Report measure(Path workspaceRoot, WorkspaceIndexService.Cancellation cancellation) {
        long heapBefore = usedHeapBytes();
        long startedAtNanos = System.nanoTime();
        WorkspaceIndexService.BuildResult result = indexService.build(true, workspaceRoot, cancellation, WorkspaceIndexService.Observer.NO_OP);
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        long cacheBytes = cacheBytes(result.persistedPath());
        long inputBytes = result.index() == null ? 0L : result.index().entries().stream().mapToLong(WorkspaceIndexService.Entry::size).sum();
        int outputFiles = result.index() == null ? 0 : result.index().entries().size();
        return new Report(result.status().workspaceRoot(), result.status().state(), elapsedNanos, heapBefore, usedHeapBytes(),
            result.status().visited(), result.status().ignored(), inputBytes, outputFiles, cacheBytes, result.status().message());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long cacheBytes(Path target) {
        if (target == null) {
            return 0L;
        }
        try {
            return Files.size(target);
        } catch (IOException error) {
            return 0L;
        }
    }

    record Report(String workspaceRoot, WorkspaceIndexService.State state, long durationNanos, long heapBeforeBytes,
                  long heapAfterBytes, long inputFiles, long ignoredFiles, long inputBytes, int outputFiles,
                  long cacheBytes, String message) {
        Report {
            if (durationNanos < 0 || heapBeforeBytes < 0 || heapAfterBytes < 0 || inputFiles < 0 || ignoredFiles < 0
                || inputBytes < 0 || outputFiles < 0 || cacheBytes < 0) {
                throw new IllegalArgumentException("benchmark counters must be non-negative");
            }
        }

        long heapDeltaBytes() {
            return heapAfterBytes - heapBeforeBytes;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("workspaceRoot", workspaceRoot);
            values.put("state", state.name());
            values.put("durationNanos", durationNanos);
            values.put("heapBeforeBytes", heapBeforeBytes);
            values.put("heapAfterBytes", heapAfterBytes);
            values.put("heapDeltaBytes", heapDeltaBytes());
            values.put("inputFiles", inputFiles);
            values.put("ignoredFiles", ignoredFiles);
            values.put("inputBytes", inputBytes);
            values.put("outputFiles", outputFiles);
            values.put("cacheBytes", cacheBytes);
            values.put("message", message);
            return values;
        }

        String format() {
            StringBuilder report = new StringBuilder();
            for (Map.Entry<String, Object> entry : toMap().entrySet()) {
                report.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            return report.toString();
        }
    }
}
