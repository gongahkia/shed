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

    @Test
    void directArgumentTasksPreserveArgumentsWithSpacesAndMapRemotePaths() throws IOException {
        TaskService service = new TaskService();
        Path workspace = tempDir.resolve("workspace with spaces");
        Path source = workspace.resolve("file with spaces.py");
        Files.createDirectories(workspace);
        Files.writeString(source, "print('ok')\n");
        TaskService.WorkspaceTask task = TaskService.directWorkspaceTask("vscode-run",
            List.of("runner", "${file}", "two words"), "${workspaceFolder}", Map.of("ROOT", "${workspaceFolder}"),
            TaskService.ProblemMatcher.NONE, TaskService.Presentation.NEVER);

        TaskService.TaskExecutionPlan plan = service.buildExecutionPlan(task, workspace.toFile(), source.toFile());
        RemoteCommandRequest remote = service.buildRemoteCommandRequest(plan, workspace, "/srv/project", source.toFile());

        assertEquals(List.of("runner", source.toString(), "two words"), plan.processCommand());
        assertEquals(List.of("runner", "/srv/project/file with spaces.py", "two words"), remote.command());
        assertEquals("/srv/project", remote.environment().get("ROOT"));
    }

    @Test
    void sessionOnlyShellArgumentsExpandBeforePosixQuotingAndUseNonLoginShells() throws IOException {
        TaskService service = new TaskService();
        Path workspace = tempDir.resolve("workspace's name");
        Path source = workspace.resolve("file's value.py");
        Files.createDirectories(workspace);
        Files.writeString(source, "print('ok')\n");
        TaskService.WorkspaceTask task = TaskService.shellWorkspaceTask("vscode-shell",
            List.of("printf", "%s", "${file}", "two words", "$HOME"), "${workspaceFolder}", Map.of(),
            TaskService.ProblemMatcher.NONE, TaskService.Presentation.NEVER);

        TaskService.TaskExecutionPlan plan = service.buildExecutionPlan(task, workspace.toFile(), source.toFile());
        RemoteCommandRequest remote = service.buildRemoteCommandRequest(plan, workspace, "/srv/project", source.toFile());

        assertTrue(task.sessionOnly());
        assertEquals(TaskService.ShellPolicy.SHELL, task.shell());
        assertEquals("-c", plan.processCommand().get(1));
        assertEquals("'printf' '%s' '" + source.toString().replace("'", "'\"'\"'") + "' 'two words' '$HOME'", plan.expandedCommand());
        assertEquals(List.of("sh", "-c", "'printf' '%s' '/srv/project/file'\"'\"'s value.py' 'two words' '$HOME'"), remote.command());
        assertThrows(IllegalArgumentException.class, () -> service.saveWorkspaceTasks(workspace.toFile(), Map.of("vscode-shell", task)));
    }

    @Test
    void taskVariablesExposeBoundedWorkspaceAndFileMetadataLocallyAndRemotely() throws IOException {
        TaskService service = new TaskService();
        Path mirror = tempDir.resolve("mirror");
        Path workspace = mirror.resolve("project with spaces");
        Path source = workspace.resolve("src/Example.test.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Example {}\n");
        TaskService.WorkspaceTask task = TaskService.directWorkspaceTask("vscode-metadata", List.of("runner",
                "${workspaceFolderBasename}", "${fileWorkspaceFolder}", "${relativeFileDirname}",
                "${fileBasenameNoExtension}", "${fileExtname}", "${fileDirname}", "${fileDirnameBasename}"),
            "${fileDirname}", Map.of("ROOT", "${fileWorkspaceFolder}"),
            TaskService.ProblemMatcher.NONE, TaskService.Presentation.NEVER);

        TaskService.TaskExecutionPlan plan = service.buildExecutionPlan(task, workspace.toFile(), source.toFile());
        RemoteCommandRequest remote = service.buildRemoteCommandRequest(plan, mirror, "/srv/project", source.toFile());

        assertEquals(List.of("runner", "project with spaces", workspace.toString(), "src", "Example.test", ".java",
            source.getParent().toString(), "src"), plan.processCommand());
        assertEquals(source.getParent().toFile().getCanonicalFile(), plan.workingDirectory());
        assertEquals(workspace.toString(), plan.environment().get("ROOT"));
        assertEquals(List.of("runner", "project with spaces", "/srv/project/project with spaces", "src", "Example.test", ".java",
            "/srv/project/project with spaces/src", "src"), remote.command());
        assertEquals("/srv/project/project with spaces", remote.environment().get("ROOT"));
    }

    @Test
    void buildsNativeDependenciesBeforeTheRequestedTaskAndPersistsThem() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("dependency-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".shedtasks"), """
            schema_version = 1

            [task.compile]
            command = "echo compile"

            [task.verify]
            command = "echo verify"
            depends_on = ["compile"]
            """);

        TaskService.TaskLoadResult loaded = service.loadWorkspaceTasks(project.toFile());
        List<TaskService.TaskExecutionPlan> plans = service.buildExecutionPlans("verify", loaded.tasks(), project.toFile(), null);

        assertTrue(loaded.isValid());
        assertEquals(List.of("compile"), loaded.tasks().get("verify").dependencies());
        assertEquals(List.of("compile", "verify"), plans.stream().map(plan -> plan.task().name()).toList());

        TaskService.WorkspaceTask persisted = TaskService.withDependencies(TaskService.defaultWorkspaceTask("release", "echo release"), List.of("verify"));
        service.saveWorkspaceTasks(project.toFile(), Map.of("compile", loaded.tasks().get("compile"), "verify", loaded.tasks().get("verify"), "release", persisted));
        assertTrue(Files.readString(project.resolve(".shedtasks")).contains("depends_on = [\"verify\"]"));
    }

    @Test
    void persistsLocalBackgroundWatchTasksAndRejectsThemAsDependencies() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("background-task");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".shedtasks"), """
            schema_version = 1

            [task.watch]
            command = "tool --watch"
            background = true
            ready_when = "watcher ready"

            [task.check]
            command = "tool check"
            depends_on = ["watch"]
            """);

        TaskService.TaskLoadResult loaded = service.loadWorkspaceTasks(project.toFile());
        List<TaskService.TaskExecutionPlan> watch = service.buildExecutionPlans("watch", loaded.tasks(), project.toFile(), null);
        List<TaskService.TaskExecutionPlan> check = service.buildExecutionPlans("check", loaded.tasks(), project.toFile(), null);

        assertTrue(loaded.isValid());
        assertTrue(loaded.tasks().get("watch").background());
        assertEquals("watcher ready", loaded.tasks().get("watch").readyWhen());
        assertNull(TaskService.backgroundPlanError(watch));
        assertEquals("background task 'watch' cannot be a dependency", TaskService.backgroundPlanError(check));
        service.saveWorkspaceTasks(project.toFile(), loaded.tasks());
        assertTrue(Files.readString(project.resolve(".shedtasks")).contains("background = true"));
        assertTrue(Files.readString(project.resolve(".shedtasks")).contains("ready_when = \"watcher ready\""));
    }

    @Test
    void rejectsAReadinessMarkerUnlessTheTaskIsBackground() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("invalid-readiness-marker");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".shedtasks"), """
            schema_version = 1

            [task.check]
            command = "tool check"
            ready_when = "ready"
            """);

        TaskService.TaskLoadResult loaded = service.loadWorkspaceTasks(project.toFile());

        assertTrue(!loaded.isValid());
        assertTrue(loaded.diagnostics().getFirst().contains("ready_when requires background = true"));
    }

    @Test
    void rejectsUnresolvedAndCyclicDependenciesBeforeBuildingAnyPlan() throws IOException {
        TaskService service = new TaskService();
        Path project = tempDir.resolve("dependency-invalid");
        Files.createDirectories(project);
        TaskService.WorkspaceTask first = TaskService.withDependencies(TaskService.defaultWorkspaceTask("first", "echo first"), List.of("second"));
        TaskService.WorkspaceTask second = TaskService.withDependencies(TaskService.defaultWorkspaceTask("second", "echo second"), List.of("first"));
        TaskService.WorkspaceTask missing = TaskService.withDependencies(TaskService.defaultWorkspaceTask("missing", "echo missing"), List.of("absent"));

        IOException cycle = assertThrows(IOException.class, () -> service.buildExecutionPlans("first", Map.of("first", first, "second", second), project.toFile(), null));
        IOException unresolved = assertThrows(IOException.class, () -> service.buildExecutionPlans("missing", Map.of("missing", missing), project.toFile(), null));

        assertTrue(cycle.getMessage().contains("first -> second -> first"));
        assertTrue(unresolved.getMessage().contains("task dependency not found: absent"));
    }
}
