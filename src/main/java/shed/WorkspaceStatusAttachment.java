package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ephemeral authenticated grant; discovery contains no secret, path, name or document metadata. */
final class WorkspaceStatusAttachment implements AutoCloseable {
    static final Duration LIFETIME = Duration.ofMinutes(10);
    record Descriptor(String instanceId, String windowId, String attachmentId, int port, long expiresAt, String scope, String projectId) {
        Descriptor(String instanceId, String windowId, String attachmentId, int port, long expiresAt) {
            this(instanceId, windowId, attachmentId, port, expiresAt, "status", null);
        }
        Map<String, Object> fields() {
            var fields = new java.util.LinkedHashMap<String, Object>(Map.of("schemaVersion", 2, "instanceId", instanceId, "windowId", windowId,
                "attachmentId", attachmentId, "port", port, "expiresAt", expiresAt, "scope", scope));
            if (projectId != null) fields.put("projectId", projectId);
            return fields;
        }
        static Descriptor read(Path file) throws IOException {
            Map<String, Object> value = object(privateBytes(file));
            if (!Long.valueOf(2).equals(value.get("schemaVersion")))
                throw failure(WorkspaceApiException.Code.UNSUPPORTED_PROTOCOL);
            boolean metadata = "editor-metadata".equals(value.get("scope")) || "editor-navigation".equals(value.get("scope"));
            Set<String> fields = metadata ? Set.of("schemaVersion", "instanceId", "windowId", "attachmentId", "port", "expiresAt", "scope", "projectId")
                : Set.of("schemaVersion", "instanceId", "windowId", "attachmentId", "port", "expiresAt", "scope");
            if (!value.keySet().equals(fields) || !metadata && !"status".equals(value.get("scope"))
                || metadata && (!(value.get("projectId") instanceof String project) || !project.matches("project-[a-f0-9-]{36}"))
                || !(value.get("port") instanceof Long port) || port < 1 || port > 65535
                || !(value.get("expiresAt") instanceof Long expiry) || expiry < 1)
                throw failure(WorkspaceApiException.Code.SESSION_INVALID);
            return new Descriptor(uuid(value.get("instanceId")), uuid(value.get("windowId")), uuid(value.get("attachmentId")), port.intValue(), expiry,
                (String) value.get("scope"), (String) value.get("projectId"));
        }
    }

    final Descriptor descriptor;
    private final Clock clock;
    private final long deadlineNanos;
    private final byte[] key = random();
    private final Path discoveryFile;
    private final Path credentialFile;
    private final byte[] discoveryBytes;
    private final byte[] credentialBytes;
    private boolean closed;

    WorkspaceStatusAttachment(ApplicationStatus application, int port, Path directory, List<Path> roots,
                              Duration lifetime, Clock clock) throws IOException {
        this(application, port, directory, roots, lifetime, clock, null);
    }

    WorkspaceStatusAttachment(ApplicationStatus application, int port, Path directory, List<Path> roots,
                              Duration lifetime, Clock clock, EditorMetadata metadata) throws IOException {
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(LIFETIME) > 0) throw new IllegalArgumentException("Invalid attachment lifetime");
        for (Path root : roots) if (directory.startsWith(WorkspacePaths.root(root)))
            throw new IOException("Private attachment storage must be outside configured roots");
        this.clock = clock;
        descriptor = new Descriptor(application.instanceId, application.windowId, UUID.randomUUID().toString(), port,
            Math.addExact(clock.millis(), lifetime.toMillis()), metadata == null ? "status" : metadata.scope(), metadata == null ? null : metadata.projectId);
        deadlineNanos = System.nanoTime() + lifetime.toNanos();
        discoveryBytes = MiniJson.stringify(descriptor.fields()).getBytes(StandardCharsets.UTF_8);
        credentialBytes = MiniJson.stringify(Map.of("schemaVersion", 2, "attachmentId", descriptor.attachmentId(),
            "key", HexFormat.of().formatHex(key))).getBytes(StandardCharsets.UTF_8);
        Path credential = null;
        try {
            credential = PrivateWorkspaceFiles.create(directory, "status-" + descriptor.attachmentId() + ".key", credentialBytes);
            discoveryFile = PrivateWorkspaceFiles.create(directory, "status-" + descriptor.attachmentId() + ".json", discoveryBytes);
            credentialFile = credential;
        } catch (IOException | RuntimeException failed) {
            if (credential != null) try { PrivateWorkspaceFiles.deleteIfMatching(credential, credentialBytes); }
            catch (IOException cleanup) { failed.addSuppressed(cleanup); }
            Arrays.fill(key, (byte) 0); Arrays.fill(credentialBytes, (byte) 0);
            throw failed;
        }
    }

    Path discoveryFile() { return discoveryFile; }
    Path credentialFile() { return credentialFile; }
    synchronized boolean expired() { return closed || clock.millis() >= descriptor.expiresAt() || System.nanoTime() - deadlineNanos >= 0; }
    synchronized byte[] key() {
        if (expired()) throw failure(WorkspaceApiException.Code.SESSION_EXPIRED);
        return key.clone();
    }

    static byte[] readKey(Path file, Descriptor descriptor) throws IOException {
        byte[] bytes;
        try { bytes = privateBytes(file); }
        catch (java.nio.file.NoSuchFileException missing) { throw failure(WorkspaceApiException.Code.UNAUTHORIZED); }
        try {
            Map<String, Object> value = object(bytes);
            if (!value.keySet().equals(Set.of("schemaVersion", "attachmentId", "key")) || !Long.valueOf(2).equals(value.get("schemaVersion"))
                || !descriptor.attachmentId().equals(value.get("attachmentId")) || !(value.get("key") instanceof String key) || !key.matches("[a-f0-9]{64}"))
                throw failure(WorkspaceApiException.Code.UNAUTHORIZED);
            return HexFormat.of().parseHex((String) value.get("key"));
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            IOException failure = null;
            try { PrivateWorkspaceFiles.deleteIfMatching(discoveryFile, discoveryBytes); } catch (IOException error) { failure = error; }
            try { PrivateWorkspaceFiles.deleteIfMatching(credentialFile, credentialBytes); } catch (IOException error) { if (failure == null) failure = error; }
            if (failure != null) throw failure;
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(credentialBytes, (byte) 0); }
    }

    static byte[] random() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return value; }
    static Path privateDirectory(Path path) throws IOException {
        checkAncestors(path);
        Path directory = PrivateWorkspaceFiles.directory(path);
        checkAncestors(directory);
        return directory;
    }
    private static void checkAncestors(Path requested) throws IOException {
        Path path = requested.toAbsolutePath();
        if (!path.equals(path.normalize())) throw new IOException("Noncanonical attachment path");
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic link in attachment path");
    }
    private static byte[] privateBytes(Path file) throws IOException {
        try { checkAncestors(file); return PrivateWorkspaceFiles.read(file, 4096); }
        catch (java.nio.file.NoSuchFileException missing) { throw missing; }
        catch (IOException invalid) { throw failure(WorkspaceApiException.Code.UNAUTHORIZED); }
    }
    static Map<String, Object> object(byte[] bytes) throws IOException {
        try {
            Map<String, Object> value = MiniJson.asObject(MiniJson.parseStrict(WorkspaceFrames.decode(bytes)));
            if (value == null) throw failure(WorkspaceApiException.Code.SESSION_INVALID);
            return value;
        } catch (IllegalArgumentException invalid) { throw failure(WorkspaceApiException.Code.SESSION_INVALID); }
    }
    private static String uuid(Object value) {
        if (!(value instanceof String text)) throw failure(WorkspaceApiException.Code.SESSION_INVALID);
        try { if (!UUID.fromString(text).toString().equals(text)) throw failure(WorkspaceApiException.Code.SESSION_INVALID); }
        catch (IllegalArgumentException invalid) { throw failure(WorkspaceApiException.Code.SESSION_INVALID); }
        return text;
    }
    static WorkspaceApiException failure(WorkspaceApiException.Code code) {
        return new WorkspaceApiException(code, switch (code) {
            case UNAUTHORIZED -> "Attachment authentication failed";
            case UNSUPPORTED_PROTOCOL -> "Unsupported attachment protocol";
            case SESSION_EXPIRED -> "Attachment has expired";
            default -> "Invalid attachment session";
        });
    }
}
