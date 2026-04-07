package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TaskServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsTasksFromProjectFile() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);

        Map<String, String> tasks = new LinkedHashMap<>();
        tasks.put("build", "mvn -q -DskipTests package");
        tasks.put("test", "mvn -q test");
        service.saveTasks(project.toFile(), tasks);

        Map<String, String> loaded = service.loadTasks(project.toFile());
        assertEquals(2, loaded.size());
        assertEquals("mvn -q test", loaded.get("test"));
        assertTrue(Files.exists(project.resolve(".shedtasks")));
    }

    @Test
    void loadIgnoresCommentsAndMalformedLines() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("project-parse");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".shedtasks"),
            "# comment\n"
                + "build=mvn package\n"
                + "malformed line\n"
                + "test = mvn test\n");

        Map<String, String> loaded = service.loadTasks(project.toFile());
        assertEquals(2, loaded.size());
        assertEquals("mvn package", loaded.get("build"));
        assertEquals("mvn test", loaded.get("test"));
    }

    @Test
    void saveSortsAndSkipsBlankEntries() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("project-sorted");
        Files.createDirectories(project);

        Map<String, String> tasks = new LinkedHashMap<>();
        tasks.put("zeta", "echo z");
        tasks.put("alpha", "echo a");
        tasks.put("blank-command", "   ");
        tasks.put("", "echo missing-name");
        tasks.put("beta", "echo b");
        service.saveTasks(project.toFile(), tasks);

        List<String> lines = Files.readAllLines(project.resolve(".shedtasks"));
        assertEquals("# Shed project tasks", lines.get(0));
        assertEquals("alpha=echo a", lines.get(1));
        assertEquals("beta=echo b", lines.get(2));
        assertEquals("zeta=echo z", lines.get(3));
        assertEquals(4, lines.size());
    }

    @Test
    void saveThrowsWhenProjectRootIsMissing() {
        TaskService service = new TaskService();
        Map<String, String> tasks = new LinkedHashMap<>();
        tasks.put("test", "mvn -q test");

        assertThrows(IOException.class, () -> service.saveTasks(null, tasks));
    }

    @Test
    void loadHandlesNullOrMissingProjectRoot() {
        TaskService service = new TaskService();

        assertTrue(service.loadTasks(null).isEmpty());
        assertTrue(service.loadTasks(tempDir.resolve("does-not-exist").toFile()).isEmpty());
        assertNull(service.taskFile(null));
    }

    @Test
    void loadUsesLastDuplicateTaskDefinition() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("project-duplicate");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".shedtasks"),
            "build=echo old\n"
                + "build=echo new\n");

        Map<String, String> loaded = service.loadTasks(project.toFile());
        assertEquals(1, loaded.size());
        assertEquals("echo new", loaded.get("build"));
    }
}
