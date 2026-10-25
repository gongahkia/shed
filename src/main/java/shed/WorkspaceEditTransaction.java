package shed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Common resource transaction for language and agent edits. Callers own workspace/version
 * preflight and thread affinity of editor callbacks. This is not yet a durable task journal.
 */
final class WorkspaceEditTransaction implements AutoCloseable {
    enum State { OPEN, PREPARED, COMMITTED, ROLLED_BACK, RECOVERY_REQUIRED }
    @FunctionalInterface interface IoAction { void run() throws IOException; }
    record Failure(String phase, String operation, String message) { }
    record Report(State state, List<Failure> failures, List<Path> recoveryPaths) {
        Report { failures = List.copyOf(failures); recoveryPaths = List.copyOf(recoveryPaths); }
        String notice() {
            if (failures.isEmpty()) return "";
            return failures.stream().map(failure -> failure.phase() + ": " + failure.operation() + " (" + failure.message() + ")")
                .collect(java.util.stream.Collectors.joining("; "))
                + (recoveryPaths.isEmpty() ? "" : "; retained recovery paths: " + recoveryPaths);
        }
    }
    private record Action(String label, IoAction action) { }
    private record Backup(Path directory, Path original, Fingerprint expected) { }
    private record Fingerprint(Object key, boolean directory, boolean link, long size, FileTime modified, String digest,
                               Map<String, Fingerprint> children) { }

    private State state = State.OPEN;
    private boolean cleanupAttempted;
    private final List<Action> undo = new ArrayList<>();
    private final List<Action> commits = new ArrayList<>();
    private final Map<Path, Fingerprint> temps = new LinkedHashMap<>();
    private final List<Backup> backups = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();

    State state() { return state; }

    void addRollback(String label, IoAction action) { requireOpen(); undo.add(new Action(label, Objects.requireNonNull(action))); }
    void addRollback(IoAction action) { addRollback("restore editor state", action); }
    void addCommit(IoAction action) { requireOpen(); commits.add(new Action("finalize editor state", Objects.requireNonNull(action))); }

    Path stage(Path target, byte[] content) throws IOException {
        requireOpen(); createParents(target);
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".shed-edit-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            temps.put(temporary, fingerprint(temporary));
            return temporary;
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void replace(Path target, Path staged) throws IOException {
        requireOpen();
        Fingerprint expected = temps.get(staged);
        if (expected == null || !expected.equals(fingerprint(staged))) throw new IOException("Staged edit is unavailable or changed");
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Write target is a directory: " + target);
        if (exists(target)) {
            AtomicFileWriter.copyMetadata(target, staged);
            expected = fingerprint(staged); temps.put(staged, expected);
        }
        if (exists(target)) backup(target);
        Fingerprint applied = expected;
        addRollback("remove applied text " + target, () -> {
            if (!exists(target)) return;
            requireMatching(target, applied);
            Files.delete(target);
        });
        if (exists(target)) throw new IOException("Write target changed before replacement: " + target);
        move(staged, target, false);
    }

    void rename(Path source, Path target, boolean overwrite) throws IOException {
        requireOpen();
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) return;
        createParents(target);
        if (exists(target)) {
            if (!overwrite) throw new IOException("Rename target already exists: " + target);
            backup(target);
        }
        Fingerprint expected = fingerprint(source);
        move(source, target, false);
        addRollback("restore renamed resource " + source, () -> {
            if (exists(source)) throw new IOException("Original rename path is now occupied: " + source);
            requireMatching(target, expected);
            move(target, source, false);
        });
    }

    void delete(Path target) throws IOException { requireOpen(); backup(target); }

    private void backup(Path target) throws IOException {
        Fingerprint expected = fingerprint(target);
        Path directory = Files.createTempDirectory(target.toAbsolutePath().getParent(), ".shed-edit-backup-");
        Path original = directory.resolve("original");
        try { move(target, original, false); }
        catch (IOException failed) { Files.deleteIfExists(directory); throw failed; }
        backups.add(new Backup(directory, original, expected));
        addRollback("restore original " + target, () -> {
            if (exists(target)) throw new IOException("Restore target now contains newer data: " + target);
            // An external writer can still hold the original inode. Preserve its newer data on rollback.
            move(original, target, false);
        });
    }

    void prepareCommit() throws IOException {
        requireOpen();
        try {
            for (Backup backup : backups) requireMatching(backup.original(), backup.expected());
            for (Action action : commits) action.action().run();
            state = State.PREPARED;
        } catch (IOException | RuntimeException failure) {
            failures.add(new Failure("commit", "finalize workspace edit", message(failure)));
            throw failure;
        }
    }

    void commit() throws IOException {
        if (state == State.OPEN) prepareCommit();
        if (state != State.PREPARED) throw new IllegalStateException("Workspace transaction cannot commit from " + state);
        state = State.COMMITTED;
    }

    Report rollback() {
        if (state != State.OPEN && state != State.PREPARED) return report();
        boolean failed = false;
        for (int i = undo.size() - 1; i >= 0; i--) {
            Action action = undo.get(i);
            try { action.action().run(); }
            catch (IOException | RuntimeException failure) {
                failed = true; failures.add(new Failure("rollback", action.label(), message(failure)));
            }
        }
        state = failed ? State.RECOVERY_REQUIRED : State.ROLLED_BACK;
        return report();
    }

    Report cleanup() {
        if (state == State.OPEN || state == State.PREPARED || state == State.RECOVERY_REQUIRED) return report();
        if (cleanupAttempted) return report();
        cleanupAttempted = true;
        for (Map.Entry<Path, Fingerprint> entry : temps.entrySet()) {
            if (!exists(entry.getKey())) continue;
            try { requireMatching(entry.getKey(), entry.getValue()); Files.delete(entry.getKey()); }
            catch (IOException | RuntimeException failure) { failures.add(new Failure("cleanup", entry.getKey().toString(), message(failure))); }
        }
        for (Backup backup : backups) {
            if (!exists(backup.directory())) continue;
            try {
                if (state == State.COMMITTED) requireMatching(backup.original(), backup.expected());
                else if (exists(backup.original())) throw new IOException("Restored backup path now contains data; retained");
                // Only the exact owned original and empty backup directory are removed; never traverse foreign additions.
                if (exists(backup.original())) deleteOwned(backup.original(), backup.expected());
                Files.delete(backup.directory());
            } catch (IOException | RuntimeException failure) {
                failures.add(new Failure("cleanup", backup.directory().toString(), message(failure)));
            }
        }
        return report();
    }

    Report report() {
        List<Path> retained = new ArrayList<>();
        for (Backup backup : backups) if (exists(backup.directory())) retained.add(backup.directory());
        for (Path temp : temps.keySet()) if (exists(temp)) retained.add(temp);
        return new Report(state, failures, retained);
    }

    @Override public void close() { if (state == State.OPEN || state == State.PREPARED) rollback(); cleanup(); }

    private void createParents(Path target) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path parent = target.toAbsolutePath().getParent();
        while (parent != null && !exists(parent)) { missing.add(parent); parent = parent.getParent(); }
        for (int i = missing.size() - 1; i >= 0; i--) {
            Path path = missing.get(i);
            try {
                Files.createDirectory(path);
                Object directoryKey = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                addRollback("remove created directory " + path, () -> {
                    if (!exists(path)) return;
                    BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!current.isDirectory() || !Objects.equals(directoryKey, current.fileKey()))
                        throw new IOException("Created directory was replaced; newer resource retained: " + path);
                    try { Files.deleteIfExists(path); }
                    catch (java.nio.file.DirectoryNotEmptyException newerFiles) { /* Preserve concurrent additions. */ }
                });
            } catch (java.nio.file.FileAlreadyExistsException concurrent) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw concurrent;
            }
        }
    }

    private void requireOpen() { if (state != State.OPEN) throw new IllegalStateException("Workspace transaction is already " + state); }
    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
    private static String message(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
    private static void requireMatching(Path path, Fingerprint expected) throws IOException {
        if (!expected.equals(fingerprint(path))) throw new IOException("Resource changed during workspace edit: " + path);
    }

    private static Fingerprint fingerprint(Path path) throws IOException { return fingerprint(path, true); }

    private static Fingerprint fingerprint(Path path, boolean includeTree) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String digest = "";
        Map<String, Fingerprint> children = new LinkedHashMap<>();
        if (before.isRegularFile()) {
            MessageDigest hash;
            try { hash = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            long read = 0;
            try (var input = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                int count;
                while ((count = input.read(buffer)) != -1) {
                    read += count;
                    if (read > before.size()) throw new IOException("Resource grew while being fingerprinted: " + path);
                    buffer.flip(); hash.update(buffer); buffer.clear();
                }
            }
            digest = HexFormat.of().formatHex(hash.digest());
        } else if (before.isSymbolicLink()) digest = Files.readSymbolicLink(path).toString();
        else if (!before.isDirectory()) throw new IOException("Special filesystem resources cannot be edited: " + path);
        if (before.isDirectory() && includeTree) {
            try (var paths = Files.walk(path)) {
                var descendants = paths.skip(1).limit(10_001).toList();
                if (descendants.size() > 10_000) throw new IOException("Directory edit exceeds the 10,000-resource limit: " + path);
                for (Path child : descendants) children.put(path.relativize(child).toString(), fingerprint(child, false));
            } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
            || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Resource changed while being fingerprinted: " + path);
        return new Fingerprint(after.fileKey(), after.isDirectory(), after.isSymbolicLink(), after.size(), after.lastModifiedTime(),
            digest, Map.copyOf(children));
    }

    private static void deleteOwned(Path path, Fingerprint expected) throws IOException {
        if (expected.directory()) {
            for (String relative : expected.children().keySet().stream().sorted(Comparator.reverseOrder()).toList()) {
                Path child = path.resolve(relative);
                Fingerprint entry = expected.children().get(relative);
                if (entry.directory()) requireDirectoryIdentity(child, entry);
                else if (!entry.equals(fingerprint(child, false))) throw new IOException("Backup changed before cleanup: " + child);
                Files.delete(child);
            }
            requireDirectoryIdentity(path, expected);
        } else requireMatching(path, expected);
        Files.delete(path);
    }

    private static void requireDirectoryIdentity(Path path, Fingerprint expected) throws IOException {
        BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!current.isDirectory() || !Objects.equals(current.fileKey(), expected.key()))
            throw new IOException("Backup directory was replaced before cleanup: " + path);
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        if (!replace && exists(target)) throw new IOException("Move target already exists: " + target);
        try {
            if (replace) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unavailable) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        }
    }
}
