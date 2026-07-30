package shed;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DiagnosticLog {
    public enum Severity {
        ERROR
    }

    private static final int DEFAULT_MAX_BYTES = 1_048_576;
    private static final int MAX_CAUSE_MESSAGE_LENGTH = 4_096;
    private static final int MAX_STACK_TRACE_LENGTH = 16_384;
    private final Path path;
    private final int maxBytes;

    public DiagnosticLog(Path path) {
        this(path, DEFAULT_MAX_BYTES);
    }

    DiagnosticLog(Path path, int maxBytes) {
        this.path = Objects.requireNonNull(path, "path");
        this.maxBytes = Math.max(1_024, maxBytes);
    }

    public Path getPath() {
        return path;
    }

    public synchronized boolean record(
        Severity severity,
        String subsystem,
        String context,
        Throwable cause,
        String remediationReference
    ) {
        if (severity == null || cause == null) {
            return false;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                path,
                entry(severity, subsystem, context, cause, remediationReference) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            );
            trimToLimit();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    synchronized Snapshot inspect(int maximumEntries) {
        if (!Files.exists(path)) {
            return new Snapshot(path, Status.MISSING, 0, 0, 0, List.of());
        }
        try {
            long bytes = Files.size(path);
            List<Entry> latestEntries = new ArrayList<>();
            int entries = 0;
            int malformedEntries = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Entry entry = parseEntry(line);
                if (entry == null) {
                    malformedEntries++;
                    continue;
                }
                entries++;
                latestEntries.add(entry);
            }
            int visibleEntries = Math.max(0, maximumEntries);
            int start = Math.max(0, latestEntries.size() - visibleEntries);
            List<Entry> newestFirst = new ArrayList<>();
            for (int index = latestEntries.size() - 1; index >= start; index--) {
                newestFirst.add(latestEntries.get(index));
            }
            return new Snapshot(path, Status.AVAILABLE, bytes, entries, malformedEntries, List.copyOf(newestFirst));
        } catch (Exception ignored) {
            return new Snapshot(path, Status.UNREADABLE, 0, 0, 0, List.of());
        }
    }

    private static Entry parseEntry(String line) {
        try {
            Map<String, Object> values = MiniJson.asObject(MiniJson.parse(line));
            if (values == null) {
                return null;
            }
            Map<String, Object> cause = MiniJson.asObject(values.get("cause"));
            return new Entry(
                MiniJson.asString(values.get("timestamp")),
                MiniJson.asString(values.get("severity")),
                MiniJson.asString(values.get("subsystem")),
                MiniJson.asString(values.get("context")),
                cause == null ? "" : MiniJson.asString(cause.get("type")),
                cause == null ? "" : MiniJson.asString(cause.get("message")),
                MiniJson.asString(values.get("remediation"))
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void trimToLimit() throws java.io.IOException {
        if (Files.size(path) <= maxBytes) {
            return;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> retained = new ArrayList<>();
        int retainedBytes = 0;
        int separatorBytes = System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index);
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + separatorBytes;
            if (retainedBytes + lineBytes > maxBytes) {
                continue;
            }
            retained.add(0, line);
            retainedBytes += lineBytes;
        }
        Files.write(path, retained, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String entry(
        Severity severity,
        String subsystem,
        String context,
        Throwable cause,
        String remediationReference
    ) {
        StringWriter stackTrace = new StringWriter();
        cause.printStackTrace(new PrintWriter(stackTrace));
        return "{"
            + field("timestamp", Instant.now().toString()) + ","
            + field("severity", severity.name()) + ","
            + field("subsystem", subsystem) + ","
            + field("context", context) + ","
            + "\"cause\":{" + field("type", cause.getClass().getName()) + ","
            + field("message", truncate(cause.getMessage(), MAX_CAUSE_MESSAGE_LENGTH)) + ","
            + field("stackTrace", truncate(stackTrace.toString(), MAX_STACK_TRACE_LENGTH)) + "},"
            + field("remediation", remediationReference)
            + "}";
    }

    private static String field(String name, String value) {
        return "\"" + escape(name) + "\":\"" + escape(value) + "\"";
    }

    private static String truncate(String value, int maximumLength) {
        String normalized = value == null ? "" : value;
        if (normalized.length() <= maximumLength) {
            return normalized;
        }
        return normalized.substring(0, maximumLength) + "…";
    }

    private static String escape(String value) {
        String source = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(source.length() + 16);
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            switch (current) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (current < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
            }
        }
        return escaped.toString();
    }

    enum Status {
        MISSING,
        AVAILABLE,
        UNREADABLE
    }

    record Entry(String timestamp, String severity, String subsystem, String context, String causeType, String causeMessage,
                 String remediation) {
        Entry {
            timestamp = timestamp == null ? "" : timestamp;
            severity = severity == null ? "" : severity;
            subsystem = subsystem == null ? "" : subsystem;
            context = context == null ? "" : context;
            causeType = causeType == null ? "" : causeType;
            causeMessage = causeMessage == null ? "" : causeMessage;
            remediation = remediation == null ? "" : remediation;
        }
    }

    record Snapshot(Path path, Status status, long bytes, int entries, int malformedEntries, List<Entry> latestEntries) {
        Snapshot {
            latestEntries = latestEntries == null ? List.of() : List.copyOf(latestEntries);
        }
    }
}
