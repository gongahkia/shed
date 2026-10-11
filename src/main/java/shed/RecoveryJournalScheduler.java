package shed;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
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
    private Capture latestCapture;
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
        List<RecoveryJournal.Entry> copied = entries == null ? List.of() : List.copyOf(entries);
        requestLazy(workspace, () -> copied);
    }

    void requestLazy(RecoveryJournal.Workspace workspace, Supplier<List<RecoveryJournal.Entry>> entries) {
        Capture capture = new Capture(workspace, Objects.requireNonNull(entries, "entries"));
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            latestCapture = capture;
            if (pendingTask != null) {
                pendingTask.cancel(false);
            }
            long scheduledGeneration = generation;
            pendingTask = executor.schedule(() -> write(scheduledGeneration, capture), debounceMillis, TimeUnit.MILLISECONDS);
        }
    }

    void clear() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            latestCapture = null;
            if (pendingTask != null) {
                pendingTask.cancel(false);
                pendingTask = null;
            }
            executor.execute(this::clearOnWorker);
        }
    }

    void closeAndClear() {
        close(ShutdownMode.CLEAR);
    }

    void close(ShutdownMode mode) {
        Capture finalCapture;
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
            finalCapture = latestCapture;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                observer.onFailure(new IOException("recovery journal worker did not stop before shutdown"));
                return;
            }
            if (mode == ShutdownMode.CLEAR) {
                clearer.clear();
            } else if (mode == ShutdownMode.FLUSH && finalCapture != null) {
                writer.write(finalCapture.snapshot());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            observer.onFailure(error);
        } catch (Exception error) {
            observer.onFailure(error);
        }
    }

    private void write(long scheduledGeneration, Capture capture) {
        synchronized (lock) {
            if (closed || scheduledGeneration != generation) {
                return;
            }
            pendingTask = null;
        }
        long startedAtNanos = System.nanoTime();
        try {
            Snapshot snapshot = capture.snapshot();
            writer.write(snapshot);
            synchronized (lock) {
                if (capture == latestCapture) {
                    latestCapture = null;
                }
            }
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

    enum ShutdownMode {
        CLEAR,
        FLUSH,
        PRESERVE
    }

    record Snapshot(RecoveryJournal.Workspace workspace, List<RecoveryJournal.Entry> entries) {
        Snapshot {
            workspace = workspace == null ? new RecoveryJournal.Workspace("", null, 0) : workspace;
            entries = List.copyOf(entries == null ? List.of() : entries);
        }
    }

    private record Capture(RecoveryJournal.Workspace workspace, Supplier<List<RecoveryJournal.Entry>> entries) {
        Snapshot snapshot() {
            return new Snapshot(workspace, entries.get());
        }
    }
}
