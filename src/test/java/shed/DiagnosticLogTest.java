package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DiagnosticLogTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsStructuredLocalDiagnostic() throws Exception {
        Path logPath = tempDir.resolve("shed-diagnostics.jsonl");
        DiagnosticLog log = new DiagnosticLog(logPath);

        assertTrue(log.record(
            DiagnosticLog.Severity.ERROR,
            "async-jobs",
            "completing shell job",
            new IllegalStateException("diagnostic secret"),
            "docs/THREADING.md#background-work"
        ));

        Map<String, Object> entry = MiniJson.asObject(MiniJson.parse(Files.readString(logPath)));
        assertNotNull(entry);
        assertNotNull(MiniJson.asString(entry.get("timestamp")));
        assertEquals("ERROR", MiniJson.asString(entry.get("severity")));
        assertEquals("async-jobs", MiniJson.asString(entry.get("subsystem")));
        assertEquals("docs/THREADING.md#background-work", MiniJson.asString(entry.get("remediation")));
        Map<String, Object> cause = MiniJson.asObject(entry.get("cause"));
        assertNotNull(cause);
        assertEquals("java.lang.IllegalStateException", MiniJson.asString(cause.get("type")));
        assertEquals("diagnostic secret", MiniJson.asString(cause.get("message")));
    }

    @Test
    void boundsLogAndRetainsNewestEntries() throws Exception {
        Path logPath = tempDir.resolve("shed-diagnostics.jsonl");
        DiagnosticLog log = new DiagnosticLog(logPath, 1_024);

        for (int index = 0; index < 12; index++) {
            assertTrue(log.record(
                DiagnosticLog.Severity.ERROR,
                "test",
                "entry " + index,
                testCause("message-" + index + "-" + "x".repeat(128)),
                "docs/DIAGNOSTICS.md"
            ));
        }

        String content = Files.readString(logPath);
        assertTrue(Files.size(logPath) <= 1_024);
        assertTrue(content.contains("message-11"));
        assertFalse(content.contains("message-0-"));
        for (String line : content.lines().toList()) {
            assertTrue(line.startsWith("{") && line.endsWith("}"));
        }
    }

    private static IllegalStateException testCause(String message) {
        IllegalStateException cause = new IllegalStateException(message);
        cause.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("shed.DiagnosticLogTest", "testCause", "DiagnosticLogTest.java", 1)
        });
        return cause;
    }
}
