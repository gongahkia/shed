package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class AsyncJobServiceTest {
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
}
