package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class AsyncJobPayloadLifecycleTest {
    @Test void finishedTokensReleaseHooksEvenWhenTheTokenIsStillReferenced() throws Exception {
        var service = new AsyncJobService(20); var tokenRef = new AtomicReference<AsyncJobService.JobToken>();
        var delivered = new CountDownLatch(1);
        try {
            service.submit("hook payload", token -> {
                tokenRef.set(token); Object payload = new Object(); token.onCancel(() -> payload.toString()); return null;
            }, (snapshot, result, error) -> delivered.countDown());
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertTrue(((List<?>) field(tokenRef.get(), "cancelHooks")).isEmpty());
        } finally { service.shutdownNow(); }
    }

    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static Object record(AsyncJobService service, int id) throws Exception {
        return ((Map<?,?>) field(service, "jobs")).get(id);
    }
    private static void assertReleased(AsyncJobService service, int id) throws Exception {
        Object record = record(service, id); assertNotNull(record);
        assertNull(field(record, "cancelledBeforeStart"), "job history must not own completion closures");
        assertTrue(((List<?>) field(field(record, "token"), "cancelHooks")).isEmpty(), "finished tokens must release cancellation hooks");
    }

    @Test void successfulAndFailedHistoryReleasesPayloads() throws Exception {
        var service = new AsyncJobService(20);
        try {
            for (boolean fail : List.of(false, true)) {
                var delivered = new CountDownLatch(1); var tokenRef = new AtomicReference<AsyncJobService.JobToken>();
                Object payload = new Object();
                int id = service.submit("payload", token -> {
                    tokenRef.set(token); token.onCancel(() -> payload.toString());
                    if (fail) throw new IllegalStateException("expected"); return payload;
                }, (snapshot, result, error) -> delivered.countDown());
                assertTrue(delivered.await(5, TimeUnit.SECONDS)); assertReleased(service, id);
                assertFalse(service.cancel(id));
                tokenRef.get().onCancel(() -> payload.toString());
                assertReleased(service, id);
            }
        } finally { service.shutdownNow(); }
    }

    @Test void queuedCancellationAndRejectionReleaseHistoryAndCompleteOnce() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var service = new AsyncJobService(executor, 20, null);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            service.submit("blocker", token -> { started.countDown(); release.await(); return null; }, null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (boolean reject : List.of(false, true)) {
                if (reject) service.shutdownNow();
                var delivered = new CountDownLatch(1); var count = new AtomicInteger();
                int id = service.submit("queued", token -> { fail("cancelled task ran"); return null; }, (snapshot, result, error) -> {
                    assertEquals(AsyncJobService.Status.CANCELLED, snapshot.getStatus()); count.incrementAndGet(); delivered.countDown();
                });
                if (!reject) assertTrue(service.cancel(id));
                assertTrue(delivered.await(5, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, count.get()); assertReleased(service, id); assertFalse(service.cancel(id));
            }
        } finally { release.countDown(); service.shutdownNow(); }
    }

    @Test void shutdownDeliversQueuedCancellationWithoutRunningTheTask() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var service = new AsyncJobService(executor, 20, null);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var delivered = new CountDownLatch(1);
        try {
            service.submit("blocker", token -> { started.countDown(); release.await(); return null; }, null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            int id = service.submit("queued shutdown", token -> { fail("shutdown task ran"); return null; }, (snapshot, result, error) -> delivered.countDown());
            service.shutdownNow(); assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(AsyncJobService.Status.CANCELLED, service.get(id).getStatus()); assertReleased(service, id);
        } finally { release.countDown(); service.shutdownNow(); }
    }

    @Test void runningNoninterruptibleTaskKeepsHooksUntilItActuallyFinishes() throws Exception {
        var service = new AsyncJobService(20); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(1); var hooks = new AtomicInteger(); var completions = new AtomicInteger();
        try {
            int id = service.submit("noninterruptible", token -> {
                token.onCancel(hooks::incrementAndGet); entered.countDown();
                boolean done = false; while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) { } }
                return "finished";
            }, (snapshot, result, error) -> { assertEquals(AsyncJobService.Status.CANCELLED, snapshot.getStatus()); completions.incrementAndGet(); delivered.countDown(); });
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(service.cancel(id));
            assertEquals(1, hooks.get()); assertEquals(1, delivered.getCount());
            release.countDown(); assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(1, completions.get()); assertReleased(service, id);
        } finally { release.countDown(); service.shutdownNow(); }
    }

    @Test void cancellationBeforeTheSubmitReturnCannotLoseQueuedCompletion() throws Exception {
        for (boolean shutdown : List.of(false, true)) {
            var accepted = new CountDownLatch(1); var allowReturn = new CountDownLatch(1);
            var blockerEntered = new CountDownLatch(1); var releaseBlocker = new CountDownLatch(1);
            var arm = new AtomicBoolean(); var delivered = new CountDownLatch(1); var calls = new AtomicInteger();
            var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
                @Override public void execute(Runnable command) {
                    super.execute(command);
                    if (arm.get()) {
                        accepted.countDown();
                        try { assertTrue(allowReturn.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { throw new AssertionError(error); }
                    }
                }
            };
            var service = new AsyncJobService(executor, 20, null);
            Thread submitter = null;
            try {
                executor.submit(() -> {
                    blockerEntered.countDown();
                    boolean done = false; while (!done) { try { releaseBlocker.await(); done = true; } catch (InterruptedException ignored) { } }
                });
                assertTrue(blockerEntered.await(5, TimeUnit.SECONDS)); arm.set(true);
                submitter = Thread.ofPlatform().start(() -> service.submit("delayed submit return", token -> {
                    fail("cancelled queued task ran"); return null;
                }, (snapshot, result, error) -> { calls.incrementAndGet(); delivered.countDown(); }));
                assertTrue(accepted.await(5, TimeUnit.SECONDS)); int id = service.list().getFirst().getId();
                if (shutdown) service.shutdownNow(); else assertTrue(service.cancel(id));
                allowReturn.countDown(); submitter.join(5000); assertFalse(submitter.isAlive());
                assertTrue(delivered.await(5, TimeUnit.SECONDS)); SwingUtilities.invokeAndWait(() -> { });
                assertEquals(AsyncJobService.Status.CANCELLED, service.get(id).getStatus());
                assertEquals(1, calls.get()); assertReleased(service, id);
            } finally {
                allowReturn.countDown(); releaseBlocker.countDown(); service.shutdownNow();
                if (submitter != null) submitter.join(5000);
            }
        }
    }

    @Test void aPendingEdtCompletionStillReceivesItsResultAfterHistoryCleanup() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var service = new AsyncJobService(executor, 20, null);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var delivered = new CountDownLatch(1);
        Object payload = new Object(); var result = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new AssertionError(error); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            int id = service.submit("pending EDT", token -> payload, (snapshot, value, error) -> { result.set(value); delivered.countDown(); });
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertReleased(service, id); assertEquals(1, delivered.getCount());
            release.countDown(); assertTrue(delivered.await(5, TimeUnit.SECONDS)); assertSame(payload, result.get());
        } finally { release.countDown(); service.shutdownNow(); }
    }

    @Test void finishingDoesNotWaitForACancellationHookHoldingAnExternalResource() throws Exception {
        var service = new AsyncJobService(20); var entered = new CountDownLatch(1); var finishTask = new CountDownLatch(1);
        var hookEntered = new CountDownLatch(1); var releaseHook = new CountDownLatch(1); var delivered = new CountDownLatch(1);
        Thread canceller = null;
        try {
            int id = service.submit("external cancel hook", token -> {
                token.onCancel(() -> { hookEntered.countDown(); try { releaseHook.await(15, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new AssertionError(error); } });
                entered.countDown(); finishTask.await(); return null;
            }, (snapshot, result, error) -> delivered.countDown());
            assertTrue(entered.await(5, TimeUnit.SECONDS)); canceller = Thread.ofPlatform().start(() -> service.cancel(id));
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS)); finishTask.countDown();
            assertTrue(delivered.await(5, TimeUnit.SECONDS)); assertReleased(service, id);
        } finally { finishTask.countDown(); releaseHook.countDown(); if (canceller != null) canceller.join(5000); service.shutdownNow(); }
    }
}
