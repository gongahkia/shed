package shed.api;

import java.nio.file.Path;

/** A test location/result exchanged between a testing extension and Shed. */
public record ExtensionTestCase(
    String id,
    String name,
    String suite,
    Path file,
    int line,
    TestStatus status,
    long durationMillis,
    String output
) {
    public ExtensionTestCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("test id is required");
        }
        name = name == null || name.isBlank() ? id : name;
        suite = suite == null ? "" : suite;
        line = Math.max(1, line);
        status = status == null ? TestStatus.UNKNOWN : status;
        durationMillis = Math.max(0, durationMillis);
        output = output == null ? "" : output;
    }
}
