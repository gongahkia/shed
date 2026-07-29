package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class WorkspaceStatePersistenceService implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);

    private final Object lock = new Object();
    private final Path target;
    private final Writer writer;
    private final Observer observer;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> pendingTask;
    private WorkspaceState latestState;
    private long generation;
    private boolean closed;

    WorkspaceStatePersistenceService(Path target, Observer observer) {
        this(target, WorkspaceStatePersistenceService::writeAtomically, observer);
    }

    WorkspaceStatePersistenceService(Path target, Writer writer, Observer observer) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        this.writer = Objects.requireNonNull(writer, "writer");
        this.observer = observer == null ? Observer.NO_OP : observer;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "shed-workspace-state");
            thread.setDaemon(true);
            return thread;
        });
    }

    boolean requestSave(WorkspaceState state) {
        WorkspaceState snapshot = Objects.requireNonNull(state, "state");
        synchronized (lock) {
            if (closed) {
                return false;
            }
            latestState = snapshot;
            generation++;
            if (pendingTask != null) {
                pendingTask.cancel(false);
            }
            long scheduledGeneration = generation;
            pendingTask = executor.schedule(() -> write(scheduledGeneration, snapshot), 0, TimeUnit.MILLISECONDS);
            return true;
        }
    }

    @Override
    public void close() {
        WorkspaceState finalState;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (pendingTask != null) {
                pendingTask.cancel(true);
                pendingTask = null;
            }
            finalState = latestState;
            latestState = null;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                notifyFailure(finalState, new IOException("workspace state worker did not stop before shutdown"));
                return;
            }
            if (finalState != null) {
                writeNow(finalState);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            notifyFailure(finalState, error);
        }
    }

    private void write(long scheduledGeneration, WorkspaceState snapshot) {
        synchronized (lock) {
            if (closed || scheduledGeneration != generation) {
                return;
            }
            pendingTask = null;
        }
        writeNow(snapshot);
    }

    private void writeNow(WorkspaceState snapshot) {
        try {
            writer.write(target, snapshot.serialize().getBytes(StandardCharsets.UTF_8));
            synchronized (lock) {
                if (latestState == snapshot) {
                    latestState = null;
                }
            }
            notifySaved(snapshot);
        } catch (Exception error) {
            notifyFailure(snapshot, error);
        }
    }

    private void notifySaved(WorkspaceState state) {
        try {
            observer.onSaved(state);
        } catch (RuntimeException ignored) {
        }
    }

    private void notifyFailure(WorkspaceState state, Exception error) {
        try {
            observer.onFailure(state, error);
        } catch (RuntimeException ignored) {
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("workspace state target has no parent directory");
        }
        Files.createDirectories(parent);
        AtomicFileWriter.write(target, content);
    }

    interface Writer {
        void write(Path target, byte[] content) throws IOException;
    }

    interface Observer {
        Observer NO_OP = new Observer() {
            @Override
            public void onSaved(WorkspaceState state) {
            }

            @Override
            public void onFailure(WorkspaceState state, Exception error) {
            }
        };

        void onSaved(WorkspaceState state);

        void onFailure(WorkspaceState state, Exception error);
    }
}
