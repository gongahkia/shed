package shed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Native Windows process teardown can briefly retain a working-directory handle after exit. */
final class FixtureCleanup {
    private FixtureCleanup() { }

    static void deleteEventually(Path path) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try { Files.deleteIfExists(path); return; }
            catch (java.nio.file.FileSystemException busy) {
                if (System.nanoTime() >= deadline) throw busy;
                Thread.sleep(25);
            }
        }
    }
}
