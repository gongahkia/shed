package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevContainerSessionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsOnlyDeclaredContainerWorkspacePathsToTheHostWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("project"));
        Path source = Files.createDirectories(workspace.resolve("src")).resolve("Main.java");
        Files.writeString(source, "class Main {}\n");
        DevContainerSessionService service = new DevContainerSessionService();
        service.connect(workspace, "/workspaces/project/");

        DevContainerSessionService.Connection connection = service.connectionFor(source);
        assertEquals("/workspaces/project", connection.remoteWorkingDirectory());
        assertEquals(source, connection.sourcePathMapper().map("/workspaces/project/src/Main.java", workspace));
        assertEquals(source, connection.sourcePathMapper().map("src/Main.java", workspace));
        assertNull(connection.sourcePathMapper().map("/workspaces/other/Main.java", workspace));
        assertNull(connection.sourcePathMapper().map("/workspaces/project/../outside.txt", workspace));
        assertNull(connection.sourcePathMapper().map("src/Main.java", tempDir));
    }

    @Test
    void choosesTheDeepestConnectedWorkspaceAndDisconnectsOnlyThatWorkspace() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("parent"));
        Path nested = Files.createDirectories(parent.resolve("nested"));
        Path file = nested.resolve("file.txt");
        Files.writeString(file, "x");
        DevContainerSessionService service = new DevContainerSessionService();
        service.connect(parent, "/workspaces/parent");
        service.connect(nested, "/workspaces/nested");

        assertEquals(nested.toAbsolutePath().normalize(), service.connectionFor(file).workspace());
        assertTrue(service.disconnect(nested));
        assertEquals(parent.toAbsolutePath().normalize(), service.connectionFor(file).workspace());
        assertFalse(service.isConnected(nested));
        assertTrue(service.isConnected(parent));
    }

    @Test
    void rejectsInvalidContainerRoots() {
        assertThrows(IllegalArgumentException.class, () -> new DevContainerSessionService.Connection(tempDir, "relative"));
        assertThrows(IllegalArgumentException.class, () -> new DevContainerSessionService.Connection(tempDir, "/workspaces/project\nnext"));
    }
}
