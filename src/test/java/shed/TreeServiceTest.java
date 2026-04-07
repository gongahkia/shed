package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TreeServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativeActionPathAgainstTreeRoot() {
        TreeService service = new TreeService();
        File root = tempDir.resolve("workspace").toFile();
        File resolved = service.resolveActionPath("src/Main.java", root);
        assertEquals(new File(root, "src/Main.java").getAbsolutePath(), resolved.getAbsolutePath());
    }

    @Test
    void createsRenamesAndDeletesPaths() throws Exception {
        TreeService service = new TreeService();
        File root = tempDir.resolve("root").toFile();
        File file = new File(root, "a/b.txt");
        service.createFile(file);
        assertTrue(file.exists());

        File renamed = new File(root, "a/c.txt");
        service.rename(file, renamed);
        assertFalse(file.exists());
        assertTrue(renamed.exists());

        int deleted = service.deleteRecursively(root);
        assertTrue(deleted >= 2);
        assertFalse(root.exists());
    }

    @Test
    void resolveActionPathHandlesBlankAndAbsolutePaths() {
        TreeService service = new TreeService();
        File root = tempDir.resolve("workspace-abs").toFile();
        File absolute = tempDir.resolve("workspace-abs/notes.txt").toFile().getAbsoluteFile();

        assertNull(service.resolveActionPath("   ", root));
        assertEquals(absolute.getAbsolutePath(), service.resolveActionPath(absolute.getAbsolutePath(), root).getAbsolutePath());
    }

    @Test
    void revealRootAndDeleteMissingPathBehaveAsExpected() throws IOException {
        TreeService service = new TreeService();
        Path root = tempDir.resolve("reveal-root");
        Files.createDirectories(root.resolve("src"));
        Path file = root.resolve("src/Main.java");
        Files.writeString(file, "class Main {}\n");

        assertEquals(root.resolve("src").toFile().getAbsolutePath(), service.revealRootForPath(file.toFile()).getAbsolutePath());
        assertEquals(root.resolve("src").toFile().getAbsolutePath(), service.revealRootForPath(root.resolve("src").toFile()).getAbsolutePath());
        assertEquals(0, service.deleteRecursively(tempDir.resolve("does-not-exist").toFile()));
    }

    @Test
    void createOperationsRequireNonNullPaths() {
        TreeService service = new TreeService();

        assertThrows(IOException.class, () -> service.createFile(null));
        assertThrows(IOException.class, () -> service.createDirectory(null));
    }
}
