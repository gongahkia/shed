package shed;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class RecoveryJournalScheduler {
    static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(750);
    private final Object lock = new Object();
    private final ScheduledExecutorService executor;
    private final long debounceMillis;
    private final Writer writer;
    private final Clearer clearer;
    private final Observer observer;
    private ScheduledFuture<?> pendingTask;
    private long generation;
    private boolean closed;

    RecoveryJournalScheduler(Writer writer, Clearer clearer, Observer observer) {
        this(DEFAULT_DEBOUNCE, writer, clearer, observer);
    }

    RecoveryJournalScheduler(Duration debounce, Writer writer, Clearer clearer, Observer observer) {
        this.debounceMillis = Math.max(1L, Objects.requireNonNull(debounce, "debounce").toMillis());
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clearer = Objects.requireNonNull(clearer, "clearer");
        this.observer = observer == null ? Observer.NO_OP : observer;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "shed-recovery-journal");
            thread.setDaemon(true);
            return thread;
        });
    }

    void request(RecoveryJournal.Workspace workspace, List<RecoveryJournal.Entry> entries) {
        Snapshot snapshot = new Snapshot(workspace, entries == null ? List.of() : List.copyOf(entries));
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            if (pendingTask != null) {
                pendingTask.cancel(false);
            }
            long scheduledGeneration = generation;
            pendingTask = executor.schedule(() -> write(scheduledGeneration, snapshot), debounceMillis, TimeUnit.MILLISECONDS);
        }
    }

    void clear() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            if (pendingTask != null) {
                pendingTask.cancel(false);
                pendingTask = null;
            }
            executor.execute(this::clearOnWorker);
        }
    }

    void closeAndClear() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            if (pendingTask != null) {
                pendingTask.cancel(true);
                pendingTask = null;
            }
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
            clearer.clear();
        } catch (Exception error) {
            observer.onFailure(error);
        }
    }

    private void write(long scheduledGeneration, Snapshot snapshot) {
        synchronized (lock) {
            if (closed || scheduledGeneration != generation) {
                return;
            }
            pendingTask = null;
        }
        long startedAtNanos = System.nanoTime();
        try {
            writer.write(snapshot);
            observer.onWrite(startedAtNanos, snapshot);
        } catch (Exception error) {
            observer.onFailure(error);
        }
    }

    private void clearOnWorker() {
        try {
            clearer.clear();
        } catch (Exception error) {
            observer.onFailure(error);
        }
    }

    interface Writer {
        void write(Snapshot snapshot) throws IOException;
    }

    interface Clearer {
        void clear() throws IOException;
    }

    interface Observer {
        Observer NO_OP = new Observer() {
            @Override
            public void onWrite(long startedAtNanos, Snapshot snapshot) {
            }

            @Override
            public void onFailure(Exception error) {
            }
        };

        void onWrite(long startedAtNanos, Snapshot snapshot);

        void onFailure(Exception error);
    }

    record Snapshot(RecoveryJournal.Workspace workspace, List<RecoveryJournal.Entry> entries) {
        Snapshot {
            workspace = workspace == null ? new RecoveryJournal.Workspace("", null, 0) : workspace;
            entries = List.copyOf(entries == null ? List.of() : entries);
        }
    }
}
