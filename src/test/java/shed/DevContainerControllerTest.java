package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DevContainerControllerTest {
    @Test
    void limitsAndMarksTruncatedProcessOutput() throws Exception {
        Path file = Files.createTempFile("shed-devcontainer-", ".log");
        Files.writeString(file, "x".repeat(128 * 1024 + 1));
        String output = DevContainerController.readCapped(file);
        assertTrue(output.endsWith("[shed: output truncated]\n"));
        assertEquals(128 * 1024 + "\n[shed: output truncated]\n".length(), output.length());
    }
}
