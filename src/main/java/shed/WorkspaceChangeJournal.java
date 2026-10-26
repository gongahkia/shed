package shed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Owner-only immutable events and content-addressed preimages, shared across editor instances. */
final class WorkspaceChangeJournal {
    static final int MAX_EVENT_BYTES = 512 * 1024;
    static final int MAX_EVENTS = 1024;
    static final long MAX_RECORD_BYTES = 32L * 1024 * 1024;
    static final int MAX_BLOB_BYTES = 32 * 1024 * 1024;
    static final int MAX_RECORDS = 1024;
    private static final Set<Path> ACTIVE_LEASES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path directory;

    WorkspaceChangeJournal(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    /** A read of absent state has no filesystem side effect. Callers hold a lease across a mutation. */
    Lease open(boolean create) throws IOException {
        if (!create && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        Path root = PrivateWorkspaceFiles.directory(directory);
        Path lockPath = root.resolve("journal.lock");
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) throw new IOException("Workspace journal is incomplete");
            try { PrivateWorkspaceFiles.create(root, "journal.lock", new byte[]{1}); }
            catch (java.nio.file.FileAlreadyExistsException concurrent) { }
        }
        if (!java.util.Arrays.equals(new byte[]{1}, PrivateWorkspaceFiles.read(lockPath, 16)))
            throw new IOException("Invalid workspace journal lock");
        if (!ACTIVE_LEASES.add(lockPath)) throw busy();
        FileChannel channel;
        try { channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); }
        catch (IOException | RuntimeException failure) { ACTIVE_LEASES.remove(lockPath); throw failure; }
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw busy();
            return new Lease(root, lockPath, channel, lock);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } finally { ACTIVE_LEASES.remove(lockPath); }
            if (failure instanceof OverlappingFileLockException) throw busy();
            throw failure;
        }
    }

    record Entry(String id, int revision, String kind, Map<String, Object> data, String hash, long bytes) {
        Entry { data = Collections.unmodifiableMap(new LinkedHashMap<>(data)); }
    }

    static final class Lease implements AutoCloseable {
        private final Path root;
        private final Path lockPath;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;
        private Lease(Path root, Path lockPath, FileChannel channel, FileLock lock) {
            this.root = root; this.lockPath = lockPath; this.channel = channel; this.lock = lock;
        }

        Entry read(String id) throws IOException {
            check(); validId(id);
            Path folder = root.resolve(id);
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return null;
            PrivateWorkspaceFiles.directory(folder);
            List<Path> events = new ArrayList<>();
            try (var stream = Files.newDirectoryStream(folder)) {
                for (Path path : stream) {
                    String name = path.getFileName().toString();
                    if (name.matches("[0-9]{8}\\.json")) events.add(path);
                    else if (!name.matches("pending-[a-f0-9-]{36}\\.json")) throw new IOException("Unknown workspace journal entry");
                    if (events.size() > MAX_EVENTS) throw new IOException("Workspace journal event limit exceeded");
                }
            }
            events.sort(Comparator.comparing(path -> path.getFileName().toString()));
            Entry last = null;
            long total = 0;
            for (int index = 0; index < events.size(); index++) {
                if (!events.get(index).getFileName().toString().equals(String.format(java.util.Locale.ROOT, "%08d.json", index)))
                    throw new IOException("Workspace journal has missing revisions");
                byte[] bytes = PrivateWorkspaceFiles.read(events.get(index), MAX_EVENT_BYTES);
                total += bytes.length;
                if (total > MAX_RECORD_BYTES) throw new IOException("Workspace journal record limit exceeded");
                try {
                    Map<String, Object> event = MiniJson.asObject(MiniJson.parseStrict(WorkspaceFrames.decode(bytes)));
                    if (event == null || !event.keySet().equals(Set.of("schemaVersion", "id", "revision", "previous", "kind", "data"))
                        || !Long.valueOf(1).equals(event.get("schemaVersion")) || !id.equals(event.get("id"))
                        || !Long.valueOf(index).equals(event.get("revision")) || !(event.get("kind") instanceof String kind)
                        || !kind.matches("[A-Z][A-Z_]{0,63}") || MiniJson.asObject(event.get("data")) == null
                        || !(last == null ? "" : last.hash()).equals(event.get("previous")))
                        throw new IOException("Workspace journal integrity check failed");
                    last = new Entry(id, index, kind, MiniJson.asObject(event.get("data")), WorkspacePaths.hash(bytes), total);
                } catch (IllegalArgumentException invalid) { throw new IOException("Workspace journal contains invalid JSON", invalid); }
            }
            if (last == null) throw new IOException("Workspace journal preparation was interrupted");
            return last;
        }

        Entry append(String id, int expectedRevision, String kind, Map<String, Object> data) throws IOException {
            check(); validId(id);
            if (!kind.matches("[A-Z][A-Z_]{0,63}")) throw new IOException("Invalid workspace journal event kind");
            Entry previous = read(id);
            int revision = previous == null ? 0 : previous.revision() + 1;
            if (expectedRevision != revision - 1) throw new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Workspace journal revision changed");
            if (revision >= MAX_EVENTS) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Workspace journal event limit reached");
            byte[] bytes = canonical(Map.of("schemaVersion", 1, "id", id, "revision", revision,
                "previous", previous == null ? "" : previous.hash(), "kind", kind, "data", data)).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_EVENT_BYTES || (previous == null ? 0 : previous.bytes()) + bytes.length > MAX_RECORD_BYTES)
                throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Workspace journal record limit reached");
            if (previous == null && list().size() >= MAX_RECORDS)
                throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Workspace journal record count reached");
            Path folder = PrivateWorkspaceFiles.directory(root.resolve(id));
            publish(folder, String.format(java.util.Locale.ROOT, "%08d.json", revision), bytes);
            return new Entry(id, revision, kind, data, WorkspacePaths.hash(bytes), (previous == null ? 0 : previous.bytes()) + bytes.length);
        }

        List<String> list() throws IOException {
            check();
            List<String> result = new ArrayList<>();
            try (var stream = Files.newDirectoryStream(root)) {
                for (Path path : stream) {
                    String name = path.getFileName().toString();
                    if (name.matches("(proposal|task|validation|session)-[a-f0-9]{32}")) result.add(name);
                    else if (!name.equals("journal.lock") && !name.equals("blobs") && !name.equals("retained")) throw new IOException("Unknown workspace journal file");
                    if (result.size() > MAX_RECORDS) throw new IOException("Workspace journal record count exceeded");
                }
            }
            result.sort(String::compareTo);
            return List.copyOf(result);
        }

        Path retentionDirectory(long additionalBytes) throws IOException {
            check(); Path folder = PrivateWorkspaceFiles.directory(root.resolve("retained"));
            long bytes = additionalBytes; int count = 0;
            try (var children = Files.newDirectoryStream(folder)) {
                for (Path child : children) {
                    Path original = retainedPath(child.resolve("original").toUri().toString());
                    if (original == null) throw new IOException("Invalid retained resource directory");
                    if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                        var attributes = Files.readAttributes(original, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (!attributes.isRegularFile()) throw new IOException("Retained original is not a regular file");
                        bytes += attributes.size();
                    }
                    if (++count >= 1024) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Retained resource count exceeded");
                }
            }
            if (bytes > 512L * 1024 * 1024) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Retained resource storage limit exceeded");
            return folder;
        }

        /** Resolves only the owned retention namespace; inspection never creates missing directories. */
        Path retainedPath(String reference) throws IOException {
            check(); Path path;
            try { path = Path.of(java.net.URI.create(reference)).toAbsolutePath().normalize(); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid retained resource reference", invalid); }
            Path folder = root.resolve("retained");
            if (!path.startsWith(folder)) return null;
            if (!reference.equals(path.toUri().toString()) || path.getNameCount() != folder.getNameCount() + 2
                || !path.getFileName().toString().equals("original") || !path.getParent().getFileName().toString().matches("resource-[A-Za-z0-9-]{1,80}"))
                throw new IOException("Invalid retained resource path");
            for (Path parent : List.of(folder, path.getParent())) {
                try { if (!PrivateWorkspaceFiles.existingDirectory(parent).equals(parent)) throw new IOException("Retained resource directory was aliased"); }
                catch (java.nio.file.NoSuchFileException missing) { /* Missing evidence is observed, never recreated. */ }
            }
            return path;
        }

        String put(byte[] bytes) throws IOException {
            check();
            if (bytes.length > MAX_BLOB_BYTES) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Workspace preimage exceeds its size limit");
            String hash = WorkspacePaths.hash(bytes);
            Path folder = PrivateWorkspaceFiles.directory(root.resolve("blobs")), target = folder.resolve(hash + ".bin");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!WorkspacePaths.hash(PrivateWorkspaceFiles.read(target, MAX_BLOB_BYTES)).equals(hash)) throw new IOException("Workspace preimage integrity check failed");
            } else {
                long used = 0; int count = 0;
                try (var stream = Files.newDirectoryStream(folder)) {
                    for (Path file : stream) {
                        var info = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (!info.isRegularFile()) throw new IOException("Invalid workspace preimage resource");
                        used += info.size();
                        if (++count >= 16_384 || used + bytes.length > 512L * 1024 * 1024)
                            throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Workspace preimage storage limit reached");
                    }
                }
                publish(folder, hash + ".bin", bytes);
            }
            return hash;
        }

        byte[] get(String hash) throws IOException {
            check();
            if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid workspace preimage reference");
            byte[] bytes = PrivateWorkspaceFiles.read(root.resolve("blobs").resolve(hash + ".bin"), MAX_BLOB_BYTES);
            if (!WorkspacePaths.hash(bytes).equals(hash)) throw new IOException("Workspace preimage integrity check failed");
            return bytes;
        }

        String putText(String text) throws IOException {
            if ((long) text.length() * 2 > MAX_BLOB_BYTES) throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED, "Workspace text exceeds its size limit");
            ByteBuffer buffer = ByteBuffer.allocate(text.length() * 2);
            for (int i = 0; i < text.length(); i++) buffer.putChar(text.charAt(i));
            return put(buffer.array());
        }

        String getText(String hash) throws IOException {
            byte[] bytes = get(hash);
            if (bytes.length % 2 != 0) throw new IOException("Invalid workspace text preimage");
            return ByteBuffer.wrap(bytes).asCharBuffer().toString();
        }

        private void check() { if (closed || !lock.isValid()) throw new IllegalStateException("Workspace journal lease is closed"); }

        private static void publish(Path directory, String name, byte[] bytes) throws IOException {
            Path temporary = PrivateWorkspaceFiles.create(directory, "pending-" + UUID.randomUUID() + ".json", bytes);
            Path target = directory.resolve(name);
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workspace journal entry already exists");
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { lock.release(); }
            finally { try { channel.close(); } finally { ACTIVE_LEASES.remove(lockPath); } }
        }
    }

    static String canonical(Object value) { return MiniJson.stringify(sorted(value)); }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put((String) key, sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(WorkspaceChangeJournal::sorted).toList();
        return value;
    }
    private static void validId(String id) {
        if (id == null || !id.matches("(proposal|task|validation|session)-[a-f0-9]{32}"))
            throw new WorkspaceApiException(WorkspaceApiException.Code.INVALID_REQUEST, "Invalid workspace journal identity");
    }
    private static WorkspaceApiException busy() { return new WorkspaceApiException(WorkspaceApiException.Code.CONFLICT, "Workspace journal is busy; retry the operation"); }
}
