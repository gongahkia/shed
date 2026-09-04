package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevContainerControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void limitsAndMarksTruncatedProcessOutput() throws Exception {
        Path file = Files.createTempFile("shed-devcontainer-", ".log");
        Files.writeString(file, "x".repeat(128 * 1024 + 1));
        String output = DevContainerController.readCapped(file);
        assertTrue(output.endsWith("[shed: output truncated]\n"));
        assertEquals(128 * 1024 + "\n[shed: output truncated]\n".length(), output.length());
    }

    @Test
    void buildsOnlyValidatedDirectArgvLanguageServerCommands() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("project"));

        assertEquals(List.of("devcontainer", "exec", "--workspace-folder", workspace.toAbsolutePath().normalize().toString(), "pylsp", "--stdio"),
            DevContainerController.languageServerInvocation(workspace, List.of("pylsp", "--stdio")));
        assertThrows(java.io.IOException.class, () -> DevContainerController.languageServerInvocation(workspace, List.of("pylsp\nbad")));
    }
}
