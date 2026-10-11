package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitProjectFileListingTest {
    @TempDir Path temporaryDirectory;

    @Test
    void listsTrackedAndVisibleFilesWithGitIgnoreApplied() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        assertEquals(0, new ProcessBuilder("git", "init", "--quiet", root.toString()).start().waitFor());
        Files.writeString(root.resolve(".gitignore"), "*.tmp\n");
        Files.writeString(root.resolve("visible file.txt"), "visible");
        Files.writeString(root.resolve("ignored.tmp"), "ignored");
        Files.writeString(root.resolve("tracked.tmp"), "tracked");
        assertEquals(0, new ProcessBuilder("git", "-C", root.toString(), "add", "-f", "tracked.tmp").start().waitFor());

        List<String> files = GitProjectFileListing.list(root, 100, ProjectFileScanner.Cancellation.NONE);

        assertEquals(List.of(".gitignore", "tracked.tmp", "visible file.txt"), files);
    }

    @Test
    void returnsFallbackSignalOutsideGitRepository() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("plain-folder"));
        Files.writeString(root.resolve("note.txt"), "note");
        assertNull(GitProjectFileListing.list(root, 100, ProjectFileScanner.Cancellation.NONE));
    }
}
