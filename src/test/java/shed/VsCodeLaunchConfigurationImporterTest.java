package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VsCodeLaunchConfigurationImporterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void importsTheLosslessPythonLaunchSubsetFromJsoncWithoutPersistingIt() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {
              // VS Code accepts JSONC comments and trailing commas.
              "configurations": [
                {
                  "name": "Run app",
                  "type": "python",
                  "request": "launch",
                  "program": "${file}",
                  "cwd": "${workspaceFolder}/src",
                  "args": ["--label", "two words"],
                  "preLaunchTask": "build",
                },
              ],
            }
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root, base);
        DebugAdapterRegistry.Validation effective = DebugAdapterRegistry.withExternalConfigurations(base, report.configurations());
        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(effective, "vscode:Run app", root, root.resolve("main.py"));

        assertTrue(report.readable());
        assertEquals(java.util.List.of("vscode:Run app"), report.accepted());
        assertTrue(report.skipped().isEmpty());
        assertTrue(effective.configurations().containsKey("vscode:Run app"));
        assertTrue(plan.launchable());
        assertEquals(root.resolve("src"), plan.plan().cwd());
        assertEquals(java.util.List.of("--label", "two words"), plan.plan().args());
        assertEquals("build", plan.plan().configuration().prelaunchTask());
        assertFalse(base.configurations().containsKey("vscode:Run app"));
    }

    @Test
    void reportsUnsupportedOrUnsafeProfilesWithoutAddingThemToTheLaunchRegistry() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {
              "configurations": [
                {"name":"Missing adapter","type":"node","request":"launch","program":"${file}"},
                {"name":"External program","type":"python","request":"launch","program":"/tmp/run.py"},
                {"name":"Environment omitted","type":"python","request":"launch","program":"${file}","env":{"TOKEN":"x"}},
                {"name":"Unknown variable","type":"python","request":"launch","program":"${file}","args":["${env:SECRET}"]}
              ]
            }
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root, base);
        DebugAdapterRegistry.Validation effective = DebugAdapterRegistry.withExternalConfigurations(base, report.configurations());

        assertTrue(report.readable());
        assertTrue(report.configurations().isEmpty());
        assertEquals(4, report.skipped().size());
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("no matching configured Shed adapter")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("program must remain")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("unsupported field env")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("variable other than")));
        assertFalse(effective.configurations().containsKey("vscode:Missing adapter"));
    }

    @Test
    void rejectsMalformedJsoncAndMakesNoProfilesAvailable() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, "{" + "\"configurations\":[" + "{" + "\"name\":\"one\",\"name\":\"two\"}" + "]}");

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root,
            BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of())));

        assertTrue(report.present());
        assertFalse(report.readable());
        assertTrue(report.configurations().isEmpty());
        assertTrue(report.failure().contains("duplicate object key"));
    }

    @Test
    void rejectsALaunchDirectorySymlinkBeforeReadingExecutableMetadata() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path external = Files.createDirectories(temporaryDirectory.resolve("external-vscode"));
        Files.writeString(external.resolve("launch.json"), "{\"configurations\":[]}");
        Files.createSymbolicLink(root.resolve(".vscode"), external);

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root,
            BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of())));

        assertTrue(report.present());
        assertFalse(report.readable());
        assertTrue(report.failure().contains("not a regular directory"));
    }
}
