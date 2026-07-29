package shed;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecoveryJournal {
    static final int VERSION = 1;
    static final int MAX_ENTRIES = 32;
    static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;
    static final String FILE_NAME = "journal-v1.json";

    private RecoveryJournal() {
    }

    static void write(Path directory, Workspace workspace, List<Entry> entries) throws IOException {
        if (directory == null) {
            throw new IOException("recovery directory required");
        }
        Files.createDirectories(directory);
        Journal journal = bounded(workspace, entries);
        Map<String, Object> payload = payload(journal);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("payload", payload);
        envelope.put("integrity", "sha256:" + sha256(MiniJson.stringify(payload)));
        atomicWrite(directory.resolve(FILE_NAME), MiniJson.stringify(envelope));
    }

    static Journal read(Path directory) throws IOException {
        if (directory == null) {
            return null;
        }
        Path journalPath = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(journalPath)) {
            return null;
        }
        try {
            Map<String, Object> envelope = requireObject(MiniJson.parse(Files.readString(journalPath, StandardCharsets.UTF_8)), "journal envelope");
            Map<String, Object> payload = requireObject(envelope.get("payload"), "journal payload");
            String integrity = requireString(envelope.get("integrity"), "journal integrity");
            String expected = "sha256:" + sha256(MiniJson.stringify(payload));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), integrity.getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("recovery journal integrity check failed");
            }
            return parse(payload);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid recovery journal: " + error.getMessage(), error);
        }
    }

    static void clear(Path directory) throws IOException {
        if (directory != null) {
            Files.deleteIfExists(directory.resolve(FILE_NAME));
        }
    }

    private static Journal bounded(Workspace workspace, List<Entry> source) {
        Workspace effectiveWorkspace = workspace == null ? new Workspace("", null, 0) : workspace;
        List<Entry> retained = new ArrayList<>();
        int bytes = 0;
        int dropped = 0;
        for (Entry entry : source == null ? List.<Entry>of() : source) {
            if (entry == null) {
                continue;
            }
            int entryBytes = entry.content().getBytes(StandardCharsets.UTF_8).length;
            if (retained.size() >= MAX_ENTRIES || entryBytes > MAX_CONTENT_BYTES - bytes) {
                dropped++;
                continue;
            }
            retained.add(entry);
            bytes += entryBytes;
        }
        return new Journal(Instant.now().toString(), effectiveWorkspace,
            List.copyOf(retained), new Retention(MAX_ENTRIES, MAX_CONTENT_BYTES, retained.size(), bytes, dropped));
    }

    private static Map<String, Object> payload(Journal journal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", VERSION);
        payload.put("writtenAt", journal.writtenAt());
        payload.put("retention", retention(journal.retention()));
        payload.put("workspace", workspace(journal.workspace()));
        List<Object> entries = new ArrayList<>();
        for (Entry entry : journal.entries()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("id", entry.id());
            encoded.put("name", entry.name());
            encoded.put("path", entry.path());
            encoded.put("content", entry.content());
            entries.add(encoded);
        }
        payload.put("entries", entries);
        return payload;
    }

    private static Map<String, Object> retention(Retention retention) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("maxEntries", retention.maxEntries());
        values.put("maxContentBytes", retention.maxContentBytes());
        values.put("retainedEntries", retention.retainedEntries());
        values.put("retainedContentBytes", retention.retainedContentBytes());
        values.put("droppedEntries", retention.droppedEntries());
        return values;
    }

    private static Map<String, Object> workspace(Workspace workspace) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workingDirectory", workspace.workingDirectory());
        values.put("activePath", workspace.activePath());
        values.put("activeCaretPosition", workspace.activeCaretPosition());
        return values;
    }

    private static Journal parse(Map<String, Object> payload) {
        int version = requireInt(payload.get("version"), "journal version");
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported recovery journal version " + version);
        }
        String writtenAt = requireString(payload.get("writtenAt"), "journal timestamp");
        Map<String, Object> workspace = requireObject(payload.get("workspace"), "workspace");
        Workspace parsedWorkspace = new Workspace(
            requireString(workspace.get("workingDirectory"), "workspace working directory"),
            optionalString(workspace.get("activePath"), "workspace active path"),
            requireNonNegativeInt(workspace.get("activeCaretPosition"), "workspace active caret position")
        );
        List<Object> encodedEntries = requireArray(payload.get("entries"), "journal entries");
        List<Entry> entries = new ArrayList<>();
        for (Object encoded : encodedEntries) {
            Map<String, Object> entry = requireObject(encoded, "journal entry");
            entries.add(new Entry(
                requireString(entry.get("id"), "entry id"),
                requireString(entry.get("name"), "entry name"),
                optionalString(entry.get("path"), "entry path"),
                requireString(entry.get("content"), "entry content")
            ));
        }
        Map<String, Object> encodedRetention = requireObject(payload.get("retention"), "journal retention");
        Retention retention = new Retention(
            requirePositiveInt(encodedRetention.get("maxEntries"), "retention max entries"),
            requirePositiveInt(encodedRetention.get("maxContentBytes"), "retention max content bytes"),
            requireNonNegativeInt(encodedRetention.get("retainedEntries"), "retention retained entries"),
            requireNonNegativeInt(encodedRetention.get("retainedContentBytes"), "retention retained content bytes"),
            requireNonNegativeInt(encodedRetention.get("droppedEntries"), "retention dropped entries")
        );
        validateRetention(entries, retention);
        return new Journal(writtenAt, parsedWorkspace, List.copyOf(entries), retention);
    }

    private static void validateRetention(List<Entry> entries, Retention retention) {
        int bytes = 0;
        for (Entry entry : entries) {
            bytes += entry.content().getBytes(StandardCharsets.UTF_8).length;
        }
        if (retention.maxEntries() != MAX_ENTRIES || retention.maxContentBytes() != MAX_CONTENT_BYTES
            || retention.retainedEntries() != entries.size() || retention.retainedContentBytes() != bytes
            || entries.size() > retention.maxEntries() || bytes > retention.maxContentBytes()) {
            throw new IllegalArgumentException("journal retention metadata does not match entries");
        }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, ".recovery-journal-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("atomic recovery journal move is unavailable", error);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(String content) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static Map<String, Object> requireObject(Object value, String field) {
        Map<String, Object> result = MiniJson.asObject(value);
        if (result == null) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return result;
    }

    private static List<Object> requireArray(Object value, String field) {
        List<Object> result = MiniJson.asArray(value);
        if (result == null) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return result;
    }

    private static String requireString(Object value, String field) {
        String result = MiniJson.asString(value);
        if (result == null) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return result;
    }

    private static String optionalString(Object value, String field) {
        if (value == null) {
            return null;
        }
        return requireString(value, field);
    }

    private static int requirePositiveInt(Object value, String field) {
        int result = requireInt(value, field);
        if (result < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return result;
    }

    private static int requireNonNegativeInt(Object value, String field) {
        int result = requireInt(value, field);
        if (result < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return result;
    }

    private static int requireInt(Object value, String field) {
        if (!(value instanceof Long) || (Long) value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return ((Long) value).intValue();
    }

    record Workspace(String workingDirectory, String activePath, int activeCaretPosition) {
        Workspace {
            if (workingDirectory == null || activeCaretPosition < 0) {
                throw new IllegalArgumentException("invalid workspace state");
            }
        }
    }

    record Entry(String id, String name, String path, String content) {
        Entry {
            if (id == null || id.isBlank() || name == null || content == null) {
                throw new IllegalArgumentException("invalid recovery entry");
            }
        }
    }

    record Retention(int maxEntries, int maxContentBytes, int retainedEntries, int retainedContentBytes, int droppedEntries) {
    }

    record Journal(String writtenAt, Workspace workspace, List<Entry> entries, Retention retention) {
    }
}
