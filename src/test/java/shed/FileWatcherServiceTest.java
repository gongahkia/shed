package shed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWatcherServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void notifiesIndependentRegistrationsForTheSameFile() throws Exception {
        Path file = tempDir.resolve("watched.txt");
        Files.writeString(file, "before");
        FileWatcherService watcher = new FileWatcherService();
        CountDownLatch received = new CountDownLatch(2);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        try {
            watcher.start();
            FileWatcherService.WatchRegistration firstRegistration = watcher.watch(file.toFile(), changed -> {
                first.incrementAndGet();
                received.countDown();
            });
            watcher.watch(file.toFile(), changed -> {
                second.incrementAndGet();
                received.countDown();
            });
            Files.writeString(file, "after");

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(first.get() >= 1);
            assertTrue(second.get() >= 1);
            firstRegistration.close();
        } finally {
            watcher.stop();
        }
    }
}
