package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComposeControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsTheStandardComposeConfigurationInPriorityOrder() throws Exception {
        assertNull(ComposeController.configuration(tempDir));
        Files.writeString(tempDir.resolve("docker-compose.yml"), "services: {}\n");
        Files.writeString(tempDir.resolve("compose.yaml"), "services: {}\n");

        assertEquals(tempDir.resolve("compose.yaml"), ComposeController.configuration(tempDir));
    }

    @Test
    void buildsDirectDockerComposeInvocationForOneLocalFile() {
        assertEquals(List.of("docker", "compose", "-f", "/project/compose.yaml", "exec", "-T", "web", "pytest"),
            ComposeController.invocation(Path.of("/project/compose.yaml"), List.of("exec", "-T", "web", "pytest")));
    }
}
