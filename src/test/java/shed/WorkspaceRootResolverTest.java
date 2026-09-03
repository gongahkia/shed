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
}
