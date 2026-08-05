package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class WorkspaceRootsTest {
    @Test
    void retainsInsertionOrderAndMovesActiveRootWhenRemoved() {
        WorkspaceRoots roots = new WorkspaceRoots();
        Path first = Path.of("build/workspace-roots/first").toAbsolutePath().normalize();
        Path second = Path.of("build/workspace-roots/second").toAbsolutePath().normalize();

        assertTrue(roots.add(first));
        assertTrue(roots.add(second));
        assertFalse(roots.add(first));
        assertTrue(roots.activate(second));
        assertTrue(roots.remove(second));

        assertEquals(List.of(first), roots.all());
        assertEquals(first, roots.active());
    }

    @Test
    void restoresOnlyUniqueRootsAndFallsBackToFirstRoot() {
        WorkspaceRoots roots = new WorkspaceRoots();
        Path first = Path.of("build/workspace-roots/first").toAbsolutePath().normalize();
        Path second = Path.of("build/workspace-roots/second").toAbsolutePath().normalize();

        roots.replace(List.of(first, first, second), Path.of("build/missing").toAbsolutePath());

        assertEquals(List.of(first, second), roots.all());
        assertEquals(first, roots.active());
    }
}
