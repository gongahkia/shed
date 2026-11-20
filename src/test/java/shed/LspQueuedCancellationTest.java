package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LspQueuedCancellationTest {
    @Test void cancelledQueuedJobsDoNotAccumulateBehindBusyWorkers() throws Exception {
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.submit(() -> { occupied.countDown(); try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        var jobs = new AsyncJobService(pool, 20, null);
        try {
            for (int i = 0; i < 32; i++) {
                CountDownLatch completed = new CountDownLatch(1);
                int id = jobs.submit("queued definition replacement", token -> { fail("cancelled queued work ran"); return null; },
                    (job, result, error) -> completed.countDown());
                assertTrue(jobs.cancel(id)); assertTrue(completed.await(2, TimeUnit.SECONDS));
            }
            assertEquals(0, pool.getQueue().size(), "cancelled definition tasks remain queued until unrelated workers finish");
            assertTrue(jobs.list().size() <= 20);
        } finally { release.countDown(); jobs.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); }
    }
}
