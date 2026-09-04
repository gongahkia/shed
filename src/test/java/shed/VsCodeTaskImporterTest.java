package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void rejectsShellProvidersDependenciesProblemMatchersAndUnsupportedVariables() throws Exception {
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
        assertTrue(report.tasks().isEmpty());
        assertEquals(4, report.skipped().size());
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("only type process")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("unsupported field dependsOn")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("problemMatcher")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("unsupported VS Code variables")));
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
}
