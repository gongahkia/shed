package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
}
