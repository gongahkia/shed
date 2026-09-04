package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceRootResolverTest {
    @Test
    void picksTheMostSpecificContainingConfiguredRoot() {
        Path outer = Path.of("/work/outer");
        Path nested = Path.of("/work/outer/module");

        assertEquals(nested, WorkspaceRootResolver.configuredRoot(Path.of("/work/outer/module/src/App.java"), List.of(outer, nested)));
        assertEquals(outer, WorkspaceRootResolver.configuredRoot(Path.of("/work/outer/readme.md"), List.of(outer, nested)));
    }

    @Test
    void ignoresRootsThatDoNotContainTheCandidate() {
        assertNull(WorkspaceRootResolver.configuredRoot(Path.of("/other/source.txt"), List.of(Path.of("/work/outer"))));
    }

    @Test
    void choosesTheResourceFolderBeforeTheExplorerSelection() {
        Path client = Path.of("/work/client");
        Path server = Path.of("/work/server");

        assertEquals(client, WorkspaceRootResolver.configuredOrActive(Path.of("/work/client/src/App.java"), List.of(client, server), server));
        assertEquals(server, WorkspaceRootResolver.configuredOrActive(Path.of("/unrelated/file.txt"), List.of(client, server), server));
    }

    @Test
    void routesCoverageToTheRootOwningTheOpenFile() {
        Path client = Path.of("/work/client");
        Path server = Path.of("/work/server");

        assertEquals(client, TestController.coverageRootFor(Path.of("/work/client/src/App.java"), List.of(client, server), server));
        assertEquals(server, TestController.coverageRootFor(Path.of("/work/server/src/Main.java"), List.of(client, server), client));
        assertNull(TestController.coverageRootFor(Path.of("/outside/Main.java"), List.of(client, server), client));
    }
}
