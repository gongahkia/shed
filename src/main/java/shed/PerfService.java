package shed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Deque;

final class PerfService {
    private final Map<String, Metric> metrics = new LinkedHashMap<>();
    private final Deque<SlowEdit> slowEdits = new ArrayDeque<>();

    synchronized void recordDuration(String name, long startedAtNanos, String detail) {
        recordNanos(name, Math.max(0L, System.nanoTime() - startedAtNanos), detail);
    }

    synchronized void recordNanos(String name, long elapsed, String detail) {
        Metric metric = metrics.computeIfAbsent(name == null ? "unknown" : name, key -> new Metric());
        metric.count++;
        metric.totalNanos += elapsed;
        metric.maxNanos = Math.max(metric.maxNanos, elapsed);
        metric.lastNanos = elapsed;
        metric.lastDetail = detail == null ? "" : detail;
        metric.recent[(int) ((metric.count - 1) % metric.recent.length)] = elapsed;
    }

    synchronized void recordSlowEdit(String phase, long total, long inputToDocument, long documentToPaintStart,
                                     long paint, int documentChars, String activeJobs) {
        slowEdits.addFirst(new SlowEdit(phase, total, inputToDocument, documentToPaintStart, paint,
            documentChars, activeJobs == null ? "none" : activeJobs));
        while (slowEdits.size() > 8) slowEdits.removeLast();
    }

    synchronized String report() {
        if (metrics.isEmpty()) {
            return "No perf samples.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Perf\n");
        sb.append("=".repeat(40)).append("\n\n");
        for (Map.Entry<String, Metric> entry : metrics.entrySet()) {
            Metric metric = entry.getValue();
            long avgMicros = metric.count == 0 ? 0 : (metric.totalNanos / metric.count) / 1000L;
            sb.append(entry.getKey())
                .append(" count=").append(metric.count)
                .append(" last=").append(metric.lastNanos / 1000L).append("us")
                .append(" avg=").append(avgMicros).append("us")
                .append(" p95=").append(metric.p95Nanos() / 1000L).append("us")
                .append(" max=").append(metric.maxNanos / 1000L).append("us");
            if (metric.lastDetail != null && !metric.lastDetail.isBlank()) {
                sb.append(" ").append(metric.lastDetail);
            }
            sb.append("\n");
        }
        sb.append("\nSlow edits (at least 16ms, newest first):\n");
        if (slowEdits.isEmpty()) sb.append("none\n");
        for (SlowEdit edit : slowEdits) {
            sb.append("phase=").append(edit.phase())
                .append(" total=").append(edit.total() / 1000L).append("us")
                .append(" input-to-document=").append(edit.inputToDocument() / 1000L).append("us")
                .append(" document-to-paint-start=").append(edit.documentToPaintStart() / 1000L).append("us")
                .append(" paint=").append(edit.paint() / 1000L).append("us")
                .append(" chars=").append(edit.documentChars())
                .append(" active-jobs-at-paint=").append(edit.activeJobs()).append('\n');
        }
        return sb.toString();
    }

    synchronized int metricCount() {
        return metrics.size();
    }

    synchronized long sampleCount() {
        long samples = 0;
        for (Metric metric : metrics.values()) {
            samples += metric.count;
        }
        return samples;
    }

    private static final class Metric {
        long count;
        long totalNanos;
        long maxNanos;
        long lastNanos;
        String lastDetail;
        final long[] recent = new long[256];

        long p95Nanos() {
            int size = (int) Math.min(count, recent.length);
            if (size == 0) return 0L;
            long[] sorted = Arrays.copyOf(recent, size);
            Arrays.sort(sorted);
            return sorted[Math.max(0, (int) Math.ceil(size * 0.95d) - 1)];
        }
    }

    private record SlowEdit(String phase, long total, long inputToDocument, long documentToPaintStart,
                            long paint, int documentChars, String activeJobs) { }
}
