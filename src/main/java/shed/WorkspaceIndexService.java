package shed;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkspaceIndexService {
    static final int VERSION = 1;

    private final Path storageDirectory;
    private final IgnoreMatcher ignoreMatcher;
    private volatile Status status;

    WorkspaceIndexService(Path storageDirectory) {
        this(storageDirectory, new GitIgnoreMatcher());
    }

    WorkspaceIndexService(Path storageDirectory, IgnoreMatcher ignoreMatcher) {
        this.storageDirectory = Objects.requireNonNull(storageDirectory, "storageDirectory").toAbsolutePath().normalize();
        this.ignoreMatcher = Objects.requireNonNull(ignoreMatcher, "ignoreMatcher");
        this.status = Status.disabled();
    }

    BuildResult build(boolean enabled, Path workspaceRoot, Observer observer) {
        return build(enabled, workspaceRoot, Cancellation.NONE, observer);
    }

    BuildResult build(boolean enabled, Path workspaceRoot, Cancellation cancellation, Observer observer) {
        Cancellation effectiveCancellation = cancellation == null ? Cancellation.NONE : cancellation;
        Observer effectiveObserver = observer == null ? Observer.NO_OP : observer;
        if (!enabled) {
            Status disabled = Status.disabled();
            publish(disabled, effectiveObserver);
            return new BuildResult(null, disabled, null);
        }
        return scanWorkspace(workspaceRoot, effectiveCancellation, effectiveObserver, true);
    }

    BuildResult scan(Path workspaceRoot, Cancellation cancellation, Observer observer) {
        return scanWorkspace(workspaceRoot, cancellation == null ? Cancellation.NONE : cancellation,
            observer == null ? Observer.NO_OP : observer, false);
    }

    private BuildResult scanWorkspace(Path workspaceRoot, Cancellation cancellation, Observer observer, boolean persist) {
        Cancellation effectiveCancellation = cancellation;
        Observer effectiveObserver = observer;
        Progress progress = new Progress();
        try {
            Path root = normalizedDirectory(workspaceRoot);
            progress.root = root;
            String activity = persist ? "indexing" : "scanning";
            publish(progress.status(State.BUILDING, activity), effectiveObserver);
            List<Entry> candidates = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (effectiveCancellation.isCancelled()) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!directory.equals(root) && ".git".equals(directory.getFileName().toString())) {
                        progress.excludedDirectories++;
                        publish(progress.status(State.BUILDING, activity), effectiveObserver);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (effectiveCancellation.isCancelled()) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path normalized = file.toAbsolutePath().normalize();
                    if (".git".equals(normalized.getFileName().toString())) {
                        progress.skipped++;
                    } else if (!normalized.startsWith(root)) {
                        progress.outsideBoundary++;
                    } else if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                        progress.skipped++;
                    } else {
                        progress.visited++;
                        Path relative = root.relativize(normalized);
                        candidates.add(new Entry(relativePath(relative), attributes.size(), attributes.lastModifiedTime().toMillis()));
                    }
                    publish(progress.status(State.BUILDING, activity), effectiveObserver);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    progress.unreadable++;
                    publish(progress.status(State.BUILDING, activity), effectiveObserver);
                    return FileVisitResult.CONTINUE;
                }
            });
            if (effectiveCancellation.isCancelled()) {
                Status cancelled = progress.status(State.CANCELLED, activity + " cancelled");
                publish(cancelled, effectiveObserver);
                return new BuildResult(null, cancelled, null);
            }
            Set<String> ignored = ignoredPaths(root, candidates, effectiveCancellation);
            if (effectiveCancellation.isCancelled()) {
                Status cancelled = progress.status(State.CANCELLED, activity + " cancelled");
                publish(cancelled, effectiveObserver);
                return new BuildResult(null, cancelled, null);
            }
            List<Entry> entries = new ArrayList<>();
            for (Entry candidate : candidates) {
                if (effectiveCancellation.isCancelled()) {
                    Status cancelled = progress.status(State.CANCELLED, activity + " cancelled");
                    publish(cancelled, effectiveObserver);
                    return new BuildResult(null, cancelled, null);
                }
                if (ignored.contains(candidate.relativePath())) {
                    progress.ignored++;
                } else {
                    entries.add(candidate);
                    progress.indexed++;
                }
            }
            WorkspaceIndex index = new WorkspaceIndex(root.toString(), entries);
            Path target = null;
            if (persist) {
                target = indexPath(root);
                Files.createDirectories(storageDirectory);
                AtomicFileWriter.write(target, MiniJson.stringify(index.toMap()).getBytes(StandardCharsets.UTF_8));
            }
            Status ready = progress.status(State.READY, persist ? "indexed" : "scanned");
            publish(ready, effectiveObserver);
            return new BuildResult(index, ready, target);
        } catch (IOException | SecurityException error) {
            Status failed = progress.status(State.FAILED, failureMessage(error));
            publish(failed, effectiveObserver);
            return new BuildResult(null, failed, null);
        }
    }

    BuildResult recover(boolean enabled, Path workspaceRoot, Cancellation cancellation, Observer observer) {
        if (!enabled) {
            return build(false, workspaceRoot, cancellation, observer);
        }
        WorkspaceIndex previous = null;
        String priorState = "missing";
        try {
            previous = load(workspaceRoot);
            if (previous != null) {
                priorState = "present";
            }
        } catch (IOException error) {
            priorState = "incomplete";
        }
        BuildResult rebuilt = build(true, workspaceRoot, cancellation, observer);
        if (rebuilt.status().state() != State.READY) {
            return rebuilt;
        }
        String message = previous == null ? "rebuilt " + priorState + " index"
            : previous.equals(rebuilt.index()) ? "revalidated persisted index" : "rebuilt stale index";
        Status recovered = new Status(State.READY, rebuilt.status().workspaceRoot(), rebuilt.status().visited(), rebuilt.status().indexed(),
            rebuilt.status().ignored(), rebuilt.status().skipped(), rebuilt.status().unreadable(), rebuilt.status().outsideBoundary(),
            rebuilt.status().excludedDirectories(), message);
        Observer effectiveObserver = observer == null ? Observer.NO_OP : observer;
        publish(recovered, effectiveObserver);
        return new BuildResult(rebuilt.index(), recovered, rebuilt.persistedPath());
    }

    Status status() {
        return status;
    }

    Path indexPath(Path workspaceRoot) throws IOException {
        Path root = normalizedDirectory(workspaceRoot);
        return storageDirectory.resolve("workspace-index-" + sha256(root.toString()).substring(0, 16) + ".json");
    }

    WorkspaceIndex load(Path workspaceRoot) throws IOException {
        Path root = normalizedDirectory(workspaceRoot);
        Path target = indexPath(root);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            return WorkspaceIndex.fromMap(MiniJson.asObject(MiniJson.parse(Files.readString(target, StandardCharsets.UTF_8))), root);
        } catch (RuntimeException error) {
            throw new IOException("workspace index is invalid: " + error.getMessage(), error);
        }
    }

    private void publish(Status next, Observer observer) {
        status = next;
        observer.onStatus(next);
    }

    private Set<String> ignoredPaths(Path root, List<Entry> candidates, Cancellation cancellation) throws IOException {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        if (ignoreMatcher instanceof GitIgnoreMatcher matcher) {
            return matcher.ignoredPaths(root, candidates, cancellation);
        }
        Set<String> ignored = new LinkedHashSet<>();
        for (Entry candidate : candidates) {
            if (cancellation.isCancelled()) {
                return ignored;
            }
            Path relative = Path.of(candidate.relativePath());
            if (ignoreMatcher.isIgnored(root, relative)) {
                ignored.add(candidate.relativePath());
            }
        }
        return ignored;
    }

    private static Path normalizedDirectory(Path root) throws IOException {
        Path normalized = Objects.requireNonNull(root, "workspaceRoot").toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("workspace root must be a non-symbolic directory: " + normalized);
        }
        return normalized;
    }

    private static String relativePath(Path relative) throws IOException {
        if (relative.isAbsolute() || relative.toString().isBlank() || relative.startsWith("..")) {
            throw new IOException("index path is outside workspace boundary");
        }
        return relative.toString().replace(java.io.File.separatorChar, '/');
    }

    private static String sha256(String value) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String failureMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    interface IgnoreMatcher {
        boolean isIgnored(Path workspaceRoot, Path relativePath) throws IOException;
    }

    interface Cancellation {
        Cancellation NONE = () -> false;

        boolean isCancelled();
    }

    static final class CancellationSource implements Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    interface Observer {
        Observer NO_OP = status -> { };

        void onStatus(Status status);
    }

    enum State {
        DISABLED,
        BUILDING,
        READY,
        CANCELLED,
        FAILED
    }

    record Status(State state, String workspaceRoot, long visited, long indexed, long ignored, long skipped,
                  long unreadable, long outsideBoundary, long excludedDirectories, String message) {
        static Status disabled() {
            return new Status(State.DISABLED, null, 0, 0, 0, 0, 0, 0, 0, "workspace index disabled");
        }
    }

    record BuildResult(WorkspaceIndex index, Status status, Path persistedPath) {
    }

    record Entry(String relativePath, long size, long modifiedAtMillis) {
        Entry {
            if (!validRelativePath(relativePath)) {
                throw new IllegalArgumentException("index entry path is invalid");
            }
            if (size < 0 || modifiedAtMillis < 0) {
                throw new IllegalArgumentException("index entry metadata is invalid");
            }
        }

        private static boolean validRelativePath(String value) {
            if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")) {
                return false;
            }
            for (String segment : value.split("/")) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    return false;
                }
            }
            return true;
        }
    }

    record WorkspaceIndex(String root, List<Entry> entries) {
        WorkspaceIndex {
            root = Objects.requireNonNull(root, "root");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            Set<String> paths = new LinkedHashSet<>();
            for (Entry entry : entries) {
                if (!paths.add(entry.relativePath())) {
                    throw new IllegalArgumentException("index entry paths must be unique");
                }
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("version", VERSION);
            values.put("root", root);
            List<Object> encodedEntries = new ArrayList<>();
            for (Entry entry : entries) {
                Map<String, Object> encoded = new LinkedHashMap<>();
                encoded.put("path", entry.relativePath());
                encoded.put("size", entry.size());
                encoded.put("modifiedAtMillis", entry.modifiedAtMillis());
                encodedEntries.add(encoded);
            }
            values.put("entries", encodedEntries);
            return values;
        }

        static WorkspaceIndex fromMap(Map<String, Object> values, Path expectedRoot) {
            if (values == null || !values.keySet().equals(Set.of("version", "root", "entries"))) {
                throw new IllegalArgumentException("index fields are invalid");
            }
            if (integer(values.get("version"), "index version") != VERSION) {
                throw new IllegalArgumentException("index version is unsupported");
            }
            String root = string(values.get("root"), "index root");
            if (!Path.of(root).toAbsolutePath().normalize().equals(expectedRoot)) {
                throw new IllegalArgumentException("index root does not match workspace");
            }
            List<Object> encodedEntries = MiniJson.asArray(values.get("entries"));
            if (encodedEntries == null) {
                throw new IllegalArgumentException("index entries must be an array");
            }
            List<Entry> entries = new ArrayList<>();
            for (Object encoded : encodedEntries) {
                Map<String, Object> entry = MiniJson.asObject(encoded);
                if (entry == null || !entry.keySet().equals(Set.of("path", "size", "modifiedAtMillis"))) {
                    throw new IllegalArgumentException("index entry fields are invalid");
                }
                entries.add(new Entry(string(entry.get("path"), "index entry path"), nonNegativeLong(entry.get("size"), "index entry size"),
                    nonNegativeLong(entry.get("modifiedAtMillis"), "index entry modification time")));
            }
            return new WorkspaceIndex(root, entries);
        }

        private static String string(Object value, String field) {
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(field + " must be a string");
            }
            return (String) value;
        }

        private static int integer(Object value, String field) {
            long number = nonNegativeLong(value, field);
            if (number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            return (int) number;
        }

        private static long nonNegativeLong(Object value, String field) {
            if (!(value instanceof Long) || (Long) value < 0) {
                throw new IllegalArgumentException(field + " must be a non-negative integer");
            }
            return (Long) value;
        }
    }

    private static final class Progress {
        private Path root;
        private long visited;
        private long indexed;
        private long ignored;
        private long skipped;
        private long unreadable;
        private long outsideBoundary;
        private long excludedDirectories;

        private Status status(State state, String message) {
            return new Status(state, root == null ? null : root.toString(), visited, indexed, ignored, skipped, unreadable, outsideBoundary,
                excludedDirectories, message);
        }
    }

    private static final class GitIgnoreMatcher implements IgnoreMatcher {
        @Override
        public boolean isIgnored(Path workspaceRoot, Path relativePath) throws IOException {
            Process process = new ProcessBuilder("git", "-C", workspaceRoot.toString(), "check-ignore", "--no-index", "--quiet", "--stdin", "-z")
                .redirectErrorStream(true)
                .start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(relativePath(relativePath).getBytes(StandardCharsets.UTF_8));
                input.write(0);
            }
            try {
                int exitCode = process.waitFor();
                byte[] output = process.getInputStream().readAllBytes();
                if (exitCode == 0) {
                    return true;
                }
                if (exitCode == 1) {
                    return false;
                }
                throw new IOException("git check-ignore failed: " + new String(output, StandardCharsets.UTF_8).trim());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("git check-ignore interrupted", error);
            }
        }

        Set<String> ignoredPaths(Path workspaceRoot, List<Entry> candidates, Cancellation cancellation) throws IOException {
            Process process = new ProcessBuilder("git", "-C", workspaceRoot.toString(), "check-ignore", "--no-index", "-z", "--stdin")
                .redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.startVirtualThread(() -> {
                try (java.io.InputStream input = process.getInputStream()) {
                    input.transferTo(output);
                } catch (IOException ignored) {
                }
            });
            try (OutputStream input = process.getOutputStream()) {
                for (Entry candidate : candidates) {
                    if (cancellation.isCancelled()) {
                        process.destroyForcibly();
                        return Set.of();
                    }
                    input.write(candidate.relativePath().getBytes(StandardCharsets.UTF_8));
                    input.write(0);
                }
            }
            try {
                int exitCode = process.waitFor();
                reader.join();
                if (exitCode == 1) {
                    return Set.of();
                }
                if (exitCode != 0) {
                    throw new IOException("git check-ignore failed: " + output.toString(StandardCharsets.UTF_8).trim());
                }
                Set<String> ignored = new LinkedHashSet<>();
                for (String value : output.toString(StandardCharsets.UTF_8).split("\\u0000")) {
                    if (!value.isBlank()) {
                        ignored.add(value);
                    }
                }
                return ignored;
            } catch (InterruptedException error) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("git check-ignore interrupted", error);
            }
        }
    }
}
