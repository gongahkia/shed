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
    void writesStructuredLocalDetailsAndShowsOneSanitizedNotification() throws Exception {
        Path logPath = tempDir.resolve("logs/shed-diagnostics.jsonl");
        List<String> notifications = new ArrayList<>();
        ApplicationErrorReporter reporter = new ApplicationErrorReporter(logPath, notifications::add);

        reporter.report(new IllegalStateException("first secret"), "async-jobs", "first context", "docs/THREADING.md#background-work");
        reporter.report(new IllegalArgumentException("second secret"), "ui", "second context", "docs/THREADING.md#edt-ownership");

        String log = Files.readString(logPath);
        assertTrue(log.contains("\"severity\":\"ERROR\""));
        assertTrue(log.contains("\"subsystem\":\"async-jobs\""));
        assertTrue(log.contains("\"type\":\"java.lang.IllegalStateException\""));
        assertTrue(log.contains("\"subsystem\":\"ui\""));
        assertTrue(log.contains("\"type\":\"java.lang.IllegalArgumentException\""));
        assertTrue(log.contains("\"remediation\":\"docs/THREADING.md#background-work\""));
        assertEquals(1, notifications.size());
        assertTrue(notifications.get(0).contains(logPath.toString()));
        assertFalse(notifications.get(0).contains("secret"));
        assertFalse(notifications.get(0).contains("IllegalStateException"));
    }

    @Test
    void guardedEventQueueLogsUiFailuresWithoutRethrowing() throws Exception {
        Path logPath = tempDir.resolve("ui-diagnostics.jsonl");
        List<String> notifications = new ArrayList<>();
        ApplicationErrorReporter reporter = new ApplicationErrorReporter(logPath, notifications::add);
        ApplicationErrorReporter.GuardedEventQueue queue = new ApplicationErrorReporter.GuardedEventQueue(reporter);

        queue.dispatchEvent(new InvocationEvent(this, () -> {
            throw new IllegalStateException("ui secret");
        }));

        String log = Files.readString(logPath);
        assertTrue(log.contains("\"subsystem\":\"ui\""));
        assertTrue(log.contains("\"remediation\":\"docs/THREADING.md#edt-ownership\""));
        assertTrue(log.contains("ui secret"));
        assertEquals(1, notifications.size());
        assertFalse(notifications.get(0).contains("ui secret"));
    }
}
