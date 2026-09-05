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
    void importsTheBasicVsCodeGoLaunchSubsetIntoTheBuiltInDelveProfile() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("go-workspace"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {"configurations":[{"name":"Debug Go","type":"go","request":"launch","program":"${file}"}]}
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root, base);
        DebugAdapterRegistry.Validation effective = DebugAdapterRegistry.withExternalConfigurations(base, report.configurations());
        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(effective, "vscode:Debug Go", root, root.resolve("main.go"));

        assertTrue(report.readable());
        assertEquals(java.util.List.of("vscode:Debug Go"), report.accepted());
        assertEquals(BuiltInDebugAdapterSupport.GO_DELVE, plan.plan().adapter().id());
        assertTrue(plan.launchable());
    }

    @Test
    void translatesPreLaunchTaskLabelsOnlyWhenTheyResolveToAcceptedProcessTasks() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {"configurations":[{
              "name":"Run app", "type":"python", "request":"launch", "program":"${file}", "preLaunchTask":"Build app"
            }]}
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report accepted = VsCodeLaunchConfigurationImporter.read(root, base,
            Map.of("Build app", "vscode-build-app"));
        VsCodeLaunchConfigurationImporter.Report rejected = VsCodeLaunchConfigurationImporter.read(root, base, Map.of());

        assertEquals("vscode-build-app", accepted.configurations().get("vscode:Run app").prelaunchTask());
        assertTrue(rejected.configurations().isEmpty());
        assertTrue(rejected.skipped().getFirst().contains("accepted compatible VS Code task label"));
    }

    @Test
    void retainsTestPlaceholdersForTheExplicitTestDebugContextOnly() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path source = Files.writeString(root.resolve("sample_test.py"), "def test_one(): pass\n");
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {"configurations":[{
              "name":"Debug selected test", "type":"python", "request":"launch", "program":"${testFile}",
              "args":["--exact", "${testId}"]
            }]}
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));
        DebugAdapterRegistry.Validation effective = DebugAdapterRegistry.withExternalConfigurations(base,
            VsCodeLaunchConfigurationImporter.read(root, base).configurations());
        TestService.TestCase test = new TestService.TestCase("pytest", "sample_test.py::test_one", "test_one", "sample_test.py", source, 1,
            TestService.Status.UNKNOWN, 0, "");

        DebugAdapterRegistry.PlanResult testPlan = DebugAdapterRegistry.plan(effective, "vscode:Debug selected test", root,
            new DebugAdapterRegistry.LaunchContext(source, test.id(), source));
        DebugAdapterRegistry.PlanResult ordinaryPlan = DebugAdapterRegistry.plan(effective, "vscode:Debug selected test", root, source);

        assertTrue(testPlan.launchable());
        assertEquals(source, testPlan.plan().program());
        assertEquals(java.util.List.of("--exact", test.id()), testPlan.plan().args());
        assertFalse(ordinaryPlan.launchable());
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
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("unsupported VS Code variable")));
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

    @Test
    void importsTheSameValidatedLaunchSubsetFromAnExplicitWorkspaceDocument() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path manifest = temporaryDirectory.resolve("team.code-workspace");
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));
        Map<String, Object> launch = Jsonc.parseObject("""
            {"configurations":[{"name":"Run workspace","type":"python","request":"launch","program":"${file}","args":["--extension","${fileExtname}"]}]}
            """);

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.readWorkspaceConfiguration(manifest, launch, base, Map.of());
        DebugAdapterRegistry.Validation effective = DebugAdapterRegistry.withExternalConfigurations(base, report.configurations());

        assertTrue(report.readable());
        assertEquals(java.util.List.of("vscode:Run workspace"), report.accepted());
        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(effective, "vscode:Run workspace", root, root.resolve("Main.py"));
        assertTrue(plan.launchable());
        assertEquals(java.util.List.of("--extension", ".py"), plan.plan().args());
    }
}
