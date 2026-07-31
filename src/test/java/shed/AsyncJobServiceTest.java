package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AsyncJobServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultWorkerCountIsBounded() {
        assertTrue(AsyncJobService.workerCount() >= 2);
        assertTrue(AsyncJobService.workerCount() <= 4);
    }

    @Test
    void runsJobAndMarksSucceeded() throws Exception {
        AsyncJobService service = new AsyncJobService(20);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AsyncJobService.JobSnapshot> snapshotRef = new AtomicReference<>();
        AtomicReference<String> resultRef = new AtomicReference<>();

        int id = service.submit("sample", token -> "ok", (snapshot, result, error) -> {
            snapshotRef.set(snapshot);
            resultRef.set(result);
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        AsyncJobService.JobSnapshot snapshot = snapshotRef.get();
        assertNotNull(snapshot);
        assertEquals(id, snapshot.getId());
        assertEquals(AsyncJobService.Status.SUCCEEDED, snapshot.getStatus());
        assertEquals("ok", resultRef.get());
        service.shutdownNow();
    }

    @Test
    void returnsAsyncUiUpdateToEventDispatchThread() throws Exception {
        AsyncJobService service = new AsyncJobService(20);
        CountDownLatch completion = new CountDownLatch(1);
        AtomicBoolean workerRanOffEdt = new AtomicBoolean();
        AtomicBoolean completionRanOnEdt = new AtomicBoolean();
        AtomicReference<String> renderedText = new AtomicReference<>();
        JLabel status = new JLabel();

        service.submit("render status", token -> {
            workerRanOffEdt.set(!SwingUtilities.isEventDispatchThread());
            return "ready";
        }, (snapshot, result, error) -> {
            completionRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            status.setText(result);
            renderedText.set(status.getText());
            completion.countDown();
        });

        assertTrue(completion.await(2, TimeUnit.SECONDS));
        assertTrue(workerRanOffEdt.get());
        assertTrue(completionRanOnEdt.get());
        assertEquals("ready", renderedText.get());
        service.shutdownNow();
    }

    @Test
    void cancelsRunningJob() throws Exception {
        AsyncJobService service = new AsyncJobService(20);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<AsyncJobService.JobSnapshot> snapshotRef = new AtomicReference<>();

        int id = service.submit("long", token -> {
            started.countDown();
            while (!token.isCancelled()) {
                Thread.sleep(10);
            }
            throw new InterruptedException("cancelled");
        }, (snapshot, result, error) -> {
            snapshotRef.set(snapshot);
            finished.countDown();
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(service.cancel(id));
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        AsyncJobService.JobSnapshot snapshot = snapshotRef.get();
        assertNotNull(snapshot);
        assertEquals(AsyncJobService.Status.CANCELLED, snapshot.getStatus());
        service.shutdownNow();
    }

    @Test
    void submitRejectsNullTask() {
        AsyncJobService service = new AsyncJobService(20);
        assertThrows(IllegalArgumentException.class, () -> service.submit("bad", null, null));
        service.shutdownNow();
    }

    @Test
    void reportsCancelledCompletionWhenSubmittedAfterShutdown() throws Exception {
        AsyncJobService service = new AsyncJobService(20);
        service.shutdownNow();
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<AsyncJobService.JobSnapshot> snapshotRef = new AtomicReference<>();

        int id = service.submit("late", token -> "never", (snapshot, result, error) -> {
            snapshotRef.set(snapshot);
            completion.countDown();
        });

        assertTrue(completion.await(2, TimeUnit.SECONDS));
        assertEquals(id, snapshotRef.get().getId());
        assertEquals(AsyncJobService.Status.CANCELLED, snapshotRef.get().getStatus());
    }

    @Test
    void marksFailedWhenTaskThrows() throws Exception {
        AsyncJobService service = new AsyncJobService(20);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AsyncJobService.JobSnapshot> snapshotRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        int id = service.submit("fails", token -> {
            throw new IllegalStateException("boom");
        }, (snapshot, result, error) -> {
            snapshotRef.set(snapshot);
            errorRef.set(error);
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        AsyncJobService.JobSnapshot snapshot = snapshotRef.get();
        assertNotNull(snapshot);
        assertEquals(id, snapshot.getId());
        assertEquals(AsyncJobService.Status.FAILED, snapshot.getStatus());
        assertNotNull(errorRef.get());
        assertTrue(snapshot.getErrorMessage().contains("boom"));
        service.shutdownNow();
    }

    @Test
    void cancelReturnsFalseForUnknownOrCompletedJobs() throws Exception {
        AsyncJobService service = new AsyncJobService(20);

        assertFalse(service.cancel(9999));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AsyncJobService.JobSnapshot> snapshotRef = new AtomicReference<>();
        int id = service.submit("done", token -> "ok", (snapshot, result, error) -> {
            snapshotRef.set(snapshot);
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotNull(snapshotRef.get());
        assertFalse(service.cancel(id));
        service.shutdownNow();
    }

    @Test
    void cancellationBeforeWorkerStartCompletesAsCancelled() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AsyncJobService service = new AsyncJobService(executor, 20, null);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch cancelledCompletion = new CountDownLatch(1);
        service.submit("blocker", token -> {
            firstStarted.countDown();
            releaseFirst.await(2, TimeUnit.SECONDS);
            return "done";
        }, null);
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        AtomicReference<AsyncJobService.JobSnapshot> snapshotRef = new AtomicReference<>();
        int id = service.submit("queued", token -> "never", (snapshot, result, error) -> {
            snapshotRef.set(snapshot);
            cancelledCompletion.countDown();
        });

        assertTrue(service.cancel(id));
        assertTrue(cancelledCompletion.await(2, TimeUnit.SECONDS));
        assertEquals(AsyncJobService.Status.CANCELLED, snapshotRef.get().getStatus());
        releaseFirst.countDown();
        service.shutdownNow();
    }

    @Test
    void trimsFinishedHistoryToConfiguredLimit() throws Exception {
        AsyncJobService service = new AsyncJobService(10);
        CountDownLatch latch = new CountDownLatch(16);

        for (int i = 0; i < 16; i++) {
            final int index = i;
            service.submit("job-" + index, token -> "ok-" + index, (snapshot, result, error) -> latch.countDown());
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertTrue(service.list().size() <= 10);
        service.shutdownNow();
    }

    @Test
    void reportsUnexpectedBackgroundFailures() throws Exception {
        Path logPath = tempDir.resolve("shed-diagnostics.jsonl");
        List<String> notifications = new ArrayList<>();
        ApplicationErrorReporter reporter = new ApplicationErrorReporter(logPath, notifications::add);
        AsyncJobService service = new AsyncJobService(20, reporter);
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<AsyncJobService.JobSnapshot> snapshotRef = new AtomicReference<>();

        service.submit("unexpected", token -> {
            throw new AssertionError("background secret");
        }, (snapshot, result, error) -> {
            snapshotRef.set(snapshot);
            completion.countDown();
        });

        assertTrue(completion.await(2, TimeUnit.SECONDS));
        assertEquals(AsyncJobService.Status.FAILED, snapshotRef.get().getStatus());
        assertEquals(1, notifications.size());
        String log = Files.readString(logPath);
        assertTrue(log.contains("\"subsystem\":\"async-jobs\""));
        assertTrue(log.contains("background secret"));
        service.shutdownNow();
    }
}
