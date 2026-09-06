package shed;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit, bounded local workspace watcher for continuous Test Explorer runs. */
final class TestWatchService implements AutoCloseable {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_DIRECTORIES = 4_096;
    private static final long DEBOUNCE_MILLIS = 300;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
        ".git", ".gradle", ".idea", ".vscode", ".venv", "venv", "env", "node_modules", "target", "build", "out",
        ".shed-devcontainer-test-reports"
    );

    private final Path root;
    private final Consumer<Path> listener;
    private final WatchService watchService;
    private final Map<WatchKey, Path> directories = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread thread;

    private TestWatchService(Path root, Consumer<Path> listener) throws IOException {
        if (root == null || listener == null) throw new IOException("test watch root and listener are required");
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("test watch root must be a non-symlink directory");
        }
        this.root = normalized;
        this.listener = listener;
        this.watchService = FileSystems.getDefault().newWatchService();
        try {
            registerTree(normalized);
        } catch (IOException error) {
            close();
            throw error;
        }
    }

    static TestWatchService start(Path root, Consumer<Path> listener) throws IOException {
        TestWatchService service = new TestWatchService(root, listener);
        service.thread = Thread.ofVirtual().name("shed-test-watch-", 0).start(service::watch);
        return service;
    }

    boolean watching() { return !closed.get(); }

    static boolean watches(Path root, Path candidate) {
        if (root == null || candidate == null) return false;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) return false;
        for (Path segment : normalizedRoot.relativize(normalizedCandidate)) if (IGNORED_DIRECTORIES.contains(segment.toString())) return false;
        return true;
    }

    private void registerTree(Path directory) throws IOException {
        Files.walkFileTree(directory, Set.of(), MAX_DEPTH, new FileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(current)) return FileVisitResult.SKIP_SUBTREE;
                if (!watches(root, current)) return FileVisitResult.SKIP_SUBTREE;
                register(current);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) { return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) { return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path current, IOException error) throws IOException {
                if (error != null) throw error;
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (directories.containsValue(normalized)) return;
        if (directories.size() >= MAX_DIRECTORIES) throw new IOException("test watch exceeds " + MAX_DIRECTORIES + " directories");
        WatchKey key = normalized.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        directories.put(key, normalized);
    }

    private void watch() {
        Path changed = null;
        long deadline = 0;
        try {
            while (!closed.get()) {
                WatchKey key = watchService.poll(100, TimeUnit.MILLISECONDS);
                if (key != null) {
                    Path parent = directories.get(key);
                    if (parent != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                changed = root;
                                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MILLIS);
                                continue;
                            }
                            if (!(event.context() instanceof Path relative)) continue;
                            Path candidate = parent.resolve(relative).toAbsolutePath().normalize();
                            if (!watches(root, candidate)) continue;
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                                try { registerTree(candidate); } catch (IOException ignored) { }
                            }
                            changed = candidate;
                            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MILLIS);
                        }
                    }
                    if (!key.reset()) directories.remove(key);
                }
                if (changed != null && System.nanoTime() >= deadline) {
                    Path notified = changed;
                    changed = null;
                    try { listener.accept(notified); } catch (RuntimeException ignored) { }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (java.nio.file.ClosedWatchServiceException ignored) {
            // close() owns normal watcher shutdown
        } finally {
            closed.set(true);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { watchService.close(); } catch (IOException ignored) { }
        if (thread != null) thread.interrupt();
        directories.clear();
    }
}
