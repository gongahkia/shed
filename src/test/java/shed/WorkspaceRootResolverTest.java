package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceRootResolverTest {
    @Test
    void picksTheMostSpecificContainingConfiguredRoot() {
        Path outer = Path.of("work/outer").toAbsolutePath().normalize();
        Path nested = outer.resolve("module");

        assertEquals(nested, WorkspaceRootResolver.configuredRoot(nested.resolve("src/App.java"), List.of(outer, nested)));
        assertEquals(outer, WorkspaceRootResolver.configuredRoot(outer.resolve("readme.md"), List.of(outer, nested)));
    }

    @Test
    void ignoresRootsThatDoNotContainTheCandidate() {
        assertNull(WorkspaceRootResolver.configuredRoot(Path.of("other/source.txt").toAbsolutePath(), List.of(Path.of("work/outer").toAbsolutePath())));
    }

    @Test
    void choosesTheResourceFolderBeforeTheExplorerSelection() {
        Path client = Path.of("work/client").toAbsolutePath().normalize();
        Path server = Path.of("work/server").toAbsolutePath().normalize();

        assertEquals(client, WorkspaceRootResolver.configuredOrActive(client.resolve("src/App.java"), List.of(client, server), server));
        assertEquals(server, WorkspaceRootResolver.configuredOrActive(Path.of("unrelated/file.txt").toAbsolutePath(), List.of(client, server), server));
    }

    @Test
    void routesCoverageToTheRootOwningTheOpenFile() {
        Path client = Path.of("work/client").toAbsolutePath().normalize();
        Path server = Path.of("work/server").toAbsolutePath().normalize();

        assertEquals(client, TestController.coverageRootFor(client.resolve("src/App.java"), List.of(client, server), server));
        assertEquals(server, TestController.coverageRootFor(server.resolve("src/Main.java"), List.of(client, server), client));
        assertNull(TestController.coverageRootFor(Path.of("outside/Main.java").toAbsolutePath(), List.of(client, server), client));
    }
}
