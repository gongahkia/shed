package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class RecoveryJournalSchedulerTest {
    @Test
    void debouncesToOneOffEdtWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean wroteOnEdt = new AtomicBoolean();
        AtomicReference<RecoveryJournalScheduler.Snapshot> written = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        RecoveryJournalScheduler scheduler = new RecoveryJournalScheduler(Duration.ofMillis(100), snapshot -> {
            writes.incrementAndGet();
            wroteOnEdt.set(SwingUtilities.isEventDispatchThread());
            written.set(snapshot);
            completed.countDown();
        }, () -> { }, RecoveryJournalScheduler.Observer.NO_OP);

        try {
            RecoveryJournal.Workspace workspace = new RecoveryJournal.Workspace("/work", null, 0);
            for (int index = 0; index < 100; index++) {
                scheduler.request(workspace, List.of(entry("draft-" + index)));
            }

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
            assertFalse(wroteOnEdt.get());
            assertEquals("draft-99", written.get().entries().get(0).content());
        } finally {
            scheduler.closeAndClear();
        }
    }

    @Test
    void clearCancelsPendingWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch cleared = new CountDownLatch(1);
        RecoveryJournalScheduler scheduler = new RecoveryJournalScheduler(Duration.ofMillis(200), snapshot -> writes.incrementAndGet(),
            cleared::countDown, RecoveryJournalScheduler.Observer.NO_OP);

        try {
            scheduler.request(new RecoveryJournal.Workspace("/work", null, 0), List.of(entry("draft")));
            scheduler.clear();

            assertTrue(cleared.await(2, TimeUnit.SECONDS));
            assertEquals(0, writes.get());
        } finally {
            scheduler.closeAndClear();
        }
    }

    @Test
    void reportsWriteFailures() throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        RecoveryJournalScheduler scheduler = new RecoveryJournalScheduler(Duration.ofMillis(1), snapshot -> {
            throw new IOException("disk unavailable");
        }, () -> { }, new RecoveryJournalScheduler.Observer() {
            @Override
            public void onWrite(long startedAtNanos, RecoveryJournalScheduler.Snapshot snapshot) {
            }

            @Override
            public void onFailure(Exception error) {
                failure.set(error);
                reported.countDown();
            }
        });

        try {
            scheduler.request(new RecoveryJournal.Workspace("/work", null, 0), List.of(entry("draft")));

            assertTrue(reported.await(2, TimeUnit.SECONDS));
            assertEquals("disk unavailable", failure.get().getMessage());
        } finally {
            scheduler.closeAndClear();
        }
    }

    private RecoveryJournal.Entry entry(String content) {
        return new RecoveryJournal.Entry("scratch", "scratch", null, content);
    }
}
