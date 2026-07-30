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

        String text = Files.readString(project.resolve(".shedtasks"));
        assertTrue(text.startsWith("# Shed workspace tasks\nschema_version = 1\n"));
        assertTrue(text.indexOf("[task.alpha]") < text.indexOf("[task.beta]"));
        assertTrue(text.indexOf("[task.beta]") < text.indexOf("[task.zeta]"));
        assertTrue(text.contains("command = \"echo a\""));
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

    @Test
    void loadsStructuredWorkspaceTaskAndBuildsBoundedExecutionPlan() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("project-structured");
        Path scripts = project.resolve("scripts");
        Path source = project.resolve("src/Main.java");
        Files.createDirectories(scripts);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Main {}\n");
        Files.writeString(project.resolve(".shedtasks"), """
            schema_version = 1

            [task.check]
            command = "echo ${relativeFile}"
            cwd = "scripts"
            shell = "direct"
            problem_matcher = "none"
            presentation = "always"

            [task.check.env]
            MODE = "check"
            """);

        TaskService.TaskLoadResult loaded = service.loadWorkspaceTasks(project.toFile());
        assertTrue(loaded.isValid());
        TaskService.WorkspaceTask task = loaded.tasks().get("check");
        assertEquals(TaskService.ShellPolicy.DIRECT, task.shell());
        assertEquals(TaskService.ProblemMatcher.NONE, task.problemMatcher());
        assertEquals(TaskService.Presentation.ALWAYS, task.presentation());
        TaskService.TaskExecutionPlan plan = service.buildExecutionPlan(task, project.toFile(), source.toFile());
        assertEquals(List.of("echo", "src/Main.java"), plan.processCommand());
        assertEquals(scripts.toFile().getCanonicalFile(), plan.workingDirectory());
        assertEquals("check", plan.environment().get("MODE"));
    }

    @Test
    void rejectsStructuredTasksWithoutVersionOrWithEscapingCwd() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("project-invalid");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".shedtasks"), "[task.check]\ncommand = \"echo ok\"\n");

        TaskService.TaskLoadResult invalid = service.loadWorkspaceTasks(project.toFile());
        assertTrue(!invalid.isValid());
        assertTrue(invalid.diagnostics().get(0).contains("schema_version is required"));

        TaskService.WorkspaceTask escaping = new TaskService.WorkspaceTask("check", "echo ok", "..", Map.of(),
            TaskService.ShellPolicy.DIRECT, TaskService.ProblemMatcher.GENERIC, TaskService.Presentation.ON_FAILURE);
        assertThrows(IOException.class, () -> service.buildExecutionPlan(escaping, project.toFile(), null));
    }
}
