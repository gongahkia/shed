package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shed.api.RemoteCommandRequest;

class VsCodeTaskImporterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void importsOnlyDirectProcessArgumentsWithoutRewritingTheWorkspaceFile() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace with spaces"));
        Path source = Files.writeString(root.resolve("script with spaces.py"), "print('ok')\n");
        Path tasks = Files.createDirectories(root.resolve(".vscode")).resolve("tasks.json");
        String original = """
            {
              // comments and trailing commas are legal JSONC
              "version": "2.0.0",
              "tasks": [
                {
                  "label": "Run app",
                  "type": "process",
                  "command": "python",
                  "args": ["${file}", "--label", "two words",],
                  "options": {"cwd": "${workspaceFolder}", "env": {"MODE": "check"}},
                  "problemMatcher": [],
                  "presentation": {"reveal": "always"},
                },
              ],
            }
            """;
        Files.writeString(tasks, original);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of("build"));
        TaskService.TaskExecutionPlan plan = new TaskService().buildExecutionPlan(report.tasks().get("vscode-run-app"), root.toFile(), source.toFile());

        assertTrue(report.readable());
        assertEquals(java.util.List.of("vscode-run-app"), report.accepted());
        assertTrue(report.skipped().isEmpty());
        assertEquals(java.util.List.of("python", source.toString(), "--label", "two words"), plan.processCommand());
        assertEquals(root.toFile().getCanonicalFile(), plan.workingDirectory());
        assertEquals("check", plan.environment().get("MODE"));
        assertEquals(TaskService.ShellPolicy.DIRECT, plan.task().shell());
        assertFalse(Files.exists(root.resolve(".shedtasks")));
        assertEquals(original, Files.readString(tasks));
    }

    @Test
    void importsPosixShellTasksButRejectsParallelDependenciesProblemMatchersAndUnsupportedVariables() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path tasks = Files.createDirectories(root.resolve(".vscode")).resolve("tasks.json");
        Files.writeString(tasks, """
            {
              "version": "2.0.0",
              "tasks": [
                {"label":"Shell","type":"shell","command":"echo ok"},
                {"label":"Dependent","type":"process","command":"make","dependsOn":"build"},
                {"label":"Matched","type":"process","command":"javac","problemMatcher":"$javac"},
                {"label":"Input","type":"process","command":"echo","args":["${input:target}"]}
              ]
            }
            """);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of());

        assertTrue(report.readable());
        assertEquals(TaskService.ShellPolicy.SHELL, report.tasks().get("vscode-shell").shell());
        assertEquals(1, report.tasks().size());
        assertEquals(3, report.skipped().size());
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("dependsOrder: sequence")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("problemMatcher")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("unsupported VS Code variables")));
    }

    @Test
    void importsShellArgumentsWithExpansionBeforeStrongPosixQuotingLocallyAndRemotely() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace's name"));
        Path source = Files.writeString(root.resolve("file's value.py"), "print('ok')\n");
        Path tasks = Files.createDirectories(root.resolve(".vscode")).resolve("tasks.json");
        Files.writeString(tasks, """
            {"version":"2.0.0","tasks":[
              {"label":"Quote arguments","type":"shell","command":"printf",
               "args":["%s", "${file}", "two words", "$HOME"],"problemMatcher":[]},
              {"label":"Raw shell","type":"shell","command":"printf raw && printf $HOME","problemMatcher":[]}
            ]}
            """);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of());
        TaskService service = new TaskService();
        TaskService.TaskExecutionPlan quoted = service.buildExecutionPlan(report.tasks().get("vscode-quote-arguments"), root.toFile(), source.toFile());
        RemoteCommandRequest remote = service.buildRemoteCommandRequest(quoted, root, "/srv/project", source.toFile());
        TaskService.TaskExecutionPlan raw = service.buildExecutionPlan(report.tasks().get("vscode-raw-shell"), root.toFile(), source.toFile());

        assertTrue(report.readable());
        assertEquals(TaskService.ShellPolicy.SHELL, quoted.task().shell());
        assertTrue(quoted.task().hasShellArguments());
        assertTrue(quoted.task().sessionOnly());
        assertEquals("-c", quoted.processCommand().get(1));
        assertEquals("'printf' '%s' '" + source.toString().replace("'", "'\"'\"'") + "' 'two words' '$HOME'", quoted.expandedCommand());
        assertEquals(List.of("sh", "-c", "'printf' '%s' '/srv/project/file'\"'\"'s value.py' 'two words' '$HOME'"), remote.command());
        assertEquals("printf raw && printf $HOME", raw.expandedCommand());
        assertEquals("-c", raw.processCommand().get(1));
    }

    @Test
    void doesNotResolveAnAmbiguousLabelForImportedDebugPreLaunchWork() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path tasks = Files.createDirectories(root.resolve(".vscode")).resolve("tasks.json");
        Files.writeString(tasks, """
            {"version":"2.0.0","tasks":[
              {"label":"Build","type":"process","command":"make","args":["client"]},
              {"label":"Build","type":"process","command":"make","args":["server"]}
            ]}
            """);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of());

        assertEquals(2, report.tasks().size());
        assertTrue(report.taskNamesByLabel().isEmpty());
    }

    @Test
    void reportsMalformedJsoncWithoutMakingTasksAvailable() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path tasks = Files.createDirectories(root.resolve(".vscode")).resolve("tasks.json");
        Files.writeString(tasks, "{\"version\":\"2.0.0\",\"tasks\":[{\"label\":\"one\",\"label\":\"two\"}]}" );

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of());

        assertTrue(report.present());
        assertFalse(report.readable());
        assertTrue(report.tasks().isEmpty());
        assertTrue(report.failure().contains("duplicate object key"));
    }

    @Test
    void rejectsATasksDirectorySymlinkBeforeReadingExecutableMetadata() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path external = Files.createDirectories(temporaryDirectory.resolve("external-vscode"));
        Files.writeString(external.resolve("tasks.json"), "{\"version\":\"2.0.0\",\"tasks\":[]}");
        Files.createSymbolicLink(root.resolve(".vscode"), external);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of());

        assertTrue(report.present());
        assertFalse(report.readable());
        assertTrue(report.failure().contains("not a regular directory"));
    }

    @Test
    void importsTheSameDirectProcessSubsetFromAnExplicitWorkspaceDocument() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path source = Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        Path manifest = temporaryDirectory.resolve("team.code-workspace");
        Map<String, Object> tasks = Jsonc.parseObject("""
            {"version":"2.0.0","tasks":[
              {"label":"Check source","type":"process","command":"printf","args":["%s","${file}"],"problemMatcher":[]}
            ]}
            """);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.readWorkspaceConfiguration(manifest, tasks, Set.of());
        TaskService.TaskExecutionPlan plan = new TaskService().buildExecutionPlan(report.tasks().get("vscode-check-source"), root.toFile(), source.toFile());

        assertTrue(report.readable());
        assertEquals(manifest.toAbsolutePath().normalize(), report.source());
        assertEquals(java.util.List.of("printf", "%s", source.toString()), plan.processCommand());
        assertFalse(Files.exists(root.resolve(".shedtasks")));
    }

    @Test
    void removesTaskLabelsThatAreAmbiguousAcrossFolderAndWorkspaceSources() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path manifest = temporaryDirectory.resolve("team.code-workspace");
        VsCodeTaskImporter.Report folder = VsCodeTaskImporter.readWorkspaceConfiguration(root.resolve(".vscode/tasks.json"), Jsonc.parseObject("""
            {"version":"2.0.0","tasks":[{"label":"Build","type":"process","command":"make"}]}
            """), Set.of());
        VsCodeTaskImporter.Report workspace = VsCodeTaskImporter.readWorkspaceConfiguration(manifest, Jsonc.parseObject("""
            {"version":"2.0.0","tasks":[{"label":"Build","type":"process","command":"make","args":["all"]}]}
            """), Set.copyOf(folder.tasks().keySet()));

        assertEquals(2, folder.tasks().size() + workspace.tasks().size());
        assertTrue(VsCodeTaskImporter.uniqueTaskNamesByLabel(folder, workspace).isEmpty());
    }

    @Test
    void importsExplicitSequentialDependenciesByUnambiguousLabel() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path tasks = Files.createDirectories(root.resolve(".vscode")).resolve("tasks.json");
        Files.writeString(tasks, """
            {"version":"2.0.0","tasks":[
              {"label":"Compile","type":"process","command":"make","args":["compile"]},
              {"label":"Verify","type":"process","command":"make","args":["verify"],
               "dependsOn":"Compile","dependsOrder":"sequence"},
              {"label":"Package","type":"process","command":"make","args":["package"],
               "dependsOn":["Compile", "Verify"],"dependsOrder":"sequence"}
            ]}
            """);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of());
        List<TaskService.TaskExecutionPlan> plans = new TaskService().buildExecutionPlans("vscode-package", report.tasks(), root.toFile(), null);

        assertTrue(report.skipped().isEmpty());
        assertEquals(List.of("vscode-compile"), report.tasks().get("vscode-verify").dependencies());
        assertEquals(List.of("vscode-compile", "vscode-verify"), report.tasks().get("vscode-package").dependencies());
        assertEquals(List.of("vscode-compile", "vscode-verify", "vscode-package"), plans.stream().map(plan -> plan.task().name()).toList());
    }

    @Test
    void rejectsDependencyLabelsThatAreAmbiguousOrUnavailable() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path tasks = Files.createDirectories(root.resolve(".vscode")).resolve("tasks.json");
        Files.writeString(tasks, """
            {"version":"2.0.0","tasks":[
              {"label":"Build","type":"process","command":"make","args":["client"]},
              {"label":"Build","type":"process","command":"make","args":["server"]},
              {"label":"Ambiguous","type":"process","command":"make","dependsOn":"Build","dependsOrder":"sequence"},
              {"label":"Missing","type":"process","command":"make","dependsOn":"Nope","dependsOrder":"sequence"}
            ]}
            """);

        VsCodeTaskImporter.Report report = VsCodeTaskImporter.read(root, Set.of());

        assertEquals(2, report.tasks().size());
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("dependsOn label is ambiguous: Build")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("dependsOn label is not an accepted task: Nope")));
    }
}
