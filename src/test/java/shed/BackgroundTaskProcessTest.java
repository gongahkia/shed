package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundTaskProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void waitsForALiteralReadinessMarkerAndRetainsProcessOutput() throws Exception {
        BackgroundTaskProcess.Running process = BackgroundTaskProcess.start(List.of("sh", "-c", "printf boot; sleep 0.1; printf watcher-ready; sleep 0.1; printf done"),
            temporaryDirectory.toFile(), Map.of(), 4096, "watcher-ready");

        assertEquals("", process.awaitReadiness(1000, null));
        CommandResult result = process.awaitCompletion(null);

        assertEquals(0, result.exitCode);
        assertEquals("bootwatcher-readydone", result.stdout);
    }

    @Test
    void reportsWhenTheWatchProcessExitsBeforeItsReadinessMarker() throws Exception {
        BackgroundTaskProcess.Running process = BackgroundTaskProcess.start(List.of("sh", "-c", "printf stopped"),
            temporaryDirectory.toFile(), Map.of(), 4096, "watcher-ready");

        String readiness = process.awaitReadiness(1000, null);

        assertTrue(readiness.contains("exited before reporting ready_when marker"));
    }
}
