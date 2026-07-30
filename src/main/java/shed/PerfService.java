package shed;

import java.util.LinkedHashMap;
import java.util.Map;

final class PerfService {
    private final Map<String, Metric> metrics = new LinkedHashMap<>();

    synchronized void recordDuration(String name, long startedAtNanos, String detail) {
        long elapsed = Math.max(0L, System.nanoTime() - startedAtNanos);
        Metric metric = metrics.computeIfAbsent(name == null ? "unknown" : name, key -> new Metric());
        metric.count++;
        metric.totalNanos += elapsed;
        metric.maxNanos = Math.max(metric.maxNanos, elapsed);
        metric.lastNanos = elapsed;
        metric.lastDetail = detail == null ? "" : detail;
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
                .append(" max=").append(metric.maxNanos / 1000L).append("us");
            if (metric.lastDetail != null && !metric.lastDetail.isBlank()) {
                sb.append(" ").append(metric.lastDetail);
            }
            sb.append("\n");
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
    }
}
