package shed;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Intentionally unreviewed admission target; contains no process or personal-file access. */
class NativeFixtureRejectedSentinelTest {
    @BeforeAll static void forbiddenSetup() throws Exception {
        Files.writeString(Path.of(System.getProperty("user.home"), "forbidden-test-setup"), "synthetic");
    }

    @Test void mustNeverExecute() {
        throw new AssertionError("Admission gate did not reject the synthetic test");
    }
}
