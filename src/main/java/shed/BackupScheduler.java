package shed;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

final class BackupScheduler implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private final ExecutorService executor;
    private boolean closed;

    BackupScheduler() {
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "shed-backup-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized boolean submit(FileBuffer buffer, FileBuffer.BackupSnapshot snapshot, Consumer<Exception> failure) {
        if (closed || buffer == null || snapshot == null) {
            return false;
        }
        executor.execute(() -> {
            try {
                buffer.writeBackupSnapshot(snapshot);
            } catch (Exception error) {
                if (failure != null) {
                    SwingUtilities.invokeLater(() -> failure.accept(error));
                }
            }
        });
        return true;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
