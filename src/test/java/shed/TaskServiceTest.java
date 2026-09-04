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
import shed.api.RemoteCommandRequest;

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
        List<String> lines = Files.readAllLines(project.resolve(".shedtasks"));
        assertEquals("# Shed workspace tasks", lines.get(0));
        assertEquals("schema_version = 1", lines.get(1));
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
        assertEquals(List.of("echo", project.relativize(source).toString()), plan.processCommand());
        assertEquals(scripts.toFile().getCanonicalFile(), plan.workingDirectory());
        assertEquals("check", plan.environment().get("MODE"));
        RemoteCommandRequest remote = service.buildRemoteCommandRequest(plan, project, project.toString(), source.toFile());
        assertEquals(List.of("echo", project.relativize(source).toString()), remote.command());
        assertEquals("scripts", remote.relativeWorkingDirectory());
        assertEquals("check", remote.environment().get("MODE"));
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

    @Test
    void remoteLoginTasksUsePortableRemoteShellAndStayInsideConnectionRoot() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("remote-task");
        Files.createDirectories(project);
        TaskService.WorkspaceTask task = TaskService.defaultWorkspaceTask("check", "echo ${workspaceFolder}");
        TaskService.TaskExecutionPlan plan = service.buildExecutionPlan(task, project.toFile(), null);

        RemoteCommandRequest request = service.buildRemoteCommandRequest(plan, project, project.toString(), null);

        assertEquals(List.of("sh", "-lc", "echo " + project), request.command());
        assertEquals("", request.relativeWorkingDirectory());
        assertThrows(IOException.class, () -> service.buildRemoteCommandRequest(plan, tempDir.resolve("other"), project.toString(), null));
    }

    @Test
    void remoteTaskVariablesUseProviderExecutionPathsInsteadOfLocalMirrorPaths() throws IOException {
        TaskService service = new TaskService();
        Path mirror = tempDir.resolve("mirror");
        Path project = mirror.resolve("nested-project");
        Path source = project.resolve("src/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Main {}\n");
        TaskService.WorkspaceTask task = new TaskService.WorkspaceTask("check",
            "echo ${workspaceFolder} ${file} ${relativeFile}", "${workspaceFolder}", Map.of("ROOT", "${workspaceFolder}"),
            TaskService.ShellPolicy.DIRECT, TaskService.ProblemMatcher.NONE, TaskService.Presentation.NEVER);
        TaskService.TaskExecutionPlan plan = service.buildExecutionPlan(task, project.toFile(), source.toFile());

        RemoteCommandRequest request = service.buildRemoteCommandRequest(plan, mirror, "/srv/workspace", source.toFile());

        assertEquals(List.of("echo", "/srv/workspace/nested-project", "/srv/workspace/nested-project/src/Main.java", "src/Main.java"), request.command());
        assertEquals("nested-project", request.relativeWorkingDirectory());
        assertEquals("/srv/workspace/nested-project", request.environment().get("ROOT"));
    }
}
