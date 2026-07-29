package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.awt.event.InvocationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ApplicationErrorReporterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesLocalDetailsAndShowsOneSanitizedNotification() throws Exception {
        Path logPath = tempDir.resolve("logs/shed-errors.log");
        List<String> notifications = new ArrayList<>();
        ApplicationErrorReporter reporter = new ApplicationErrorReporter(logPath, notifications::add);

        reporter.report(new IllegalStateException("first secret"), "first context");
        reporter.report(new IllegalArgumentException("second secret"), "second context");

        String log = Files.readString(logPath);
        assertTrue(log.contains("first context"));
        assertTrue(log.contains("java.lang.IllegalStateException"));
        assertTrue(log.contains("second context"));
        assertTrue(log.contains("java.lang.IllegalArgumentException"));
        assertEquals(1, notifications.size());
        assertTrue(notifications.get(0).contains(logPath.toString()));
        assertFalse(notifications.get(0).contains("secret"));
        assertFalse(notifications.get(0).contains("IllegalStateException"));
    }

    @Test
    void guardedEventQueueLogsUiFailuresWithoutRethrowing() throws Exception {
        Path logPath = tempDir.resolve("ui-errors.log");
        List<String> notifications = new ArrayList<>();
        ApplicationErrorReporter reporter = new ApplicationErrorReporter(logPath, notifications::add);
        ApplicationErrorReporter.GuardedEventQueue queue = new ApplicationErrorReporter.GuardedEventQueue(reporter);

        queue.dispatchEvent(new InvocationEvent(this, () -> {
            throw new IllegalStateException("ui secret");
        }));

        assertTrue(Files.readString(logPath).contains("ui secret"));
        assertEquals(1, notifications.size());
        assertFalse(notifications.get(0).contains("ui secret"));
    }
}
