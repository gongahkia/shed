package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
