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
                  "env": {"APP_MODE": "development", "PORT": "3000"},
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
        assertEquals(Map.of("APP_MODE", "development", "PORT", "3000"), plan.plan().configuration().environment());
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
    void retainsBoundedAdapterSpecificVsCodeFieldsWithoutLettingThemReplaceCoreFields() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("adapter-options"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {"configurations":[{"name":"Run app","type":"python","request":"launch","program":"${file}",
            "justMyCode":true,"pathMappings":[{"localRoot":"src","remoteRoot":"/srv/app"}]}]}
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root, base);
        DebugAdapterRegistry.Configuration configuration = report.configurations().get("vscode:Run app");

        assertTrue(report.readable());
        assertEquals(true, configuration.adapterOptions().get("justMyCode"));
        assertEquals("python", configuration.adapterOptions().get("type"));
        assertEquals(java.util.List.of(Map.of("localRoot", "src", "remoteRoot", "/srv/app")), configuration.adapterOptions().get("pathMappings"));
        assertFalse(configuration.adapterOptions().containsKey("program"));
    }

    @Test
    void importsCoreClrLaunchConfigurationAgainstTheExplicitNetcoredbgAdapter() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("coreclr"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {"configurations":[{"name":"Debug .NET","type":"coreclr","request":"launch","program":"${workspaceFolder}/bin/Debug/net9.0/app.dll"}]}
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root, base);
        DebugAdapterRegistry.Validation effective = DebugAdapterRegistry.withExternalConfigurations(base, report.configurations());
        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(effective, "vscode:Debug .NET", root, root.resolve("Program.cs"));

        assertTrue(report.readable());
        assertEquals(java.util.List.of("vscode:Debug .NET"), report.accepted());
        assertEquals(BuiltInDebugAdapterSupport.CSHARP_NETCOREDBG, plan.plan().adapter().id());
        assertTrue(plan.launchable());
    }

    @Test
    void importsLldbDapLaunchConfigurationAgainstTheExplicitNativeAdapter() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("native"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {"configurations":[{"name":"Debug native","type":"lldb-dap","request":"launch","program":"${workspaceFolder}/build/app"}]}
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root, base);
        DebugAdapterRegistry.Validation effective = DebugAdapterRegistry.withExternalConfigurations(base, report.configurations());
        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(effective, "vscode:Debug native", root, root.resolve("main.cpp"));

        assertTrue(report.readable());
        assertEquals(java.util.List.of("vscode:Debug native"), report.accepted());
        assertEquals(BuiltInDebugAdapterSupport.NATIVE_LLDB, plan.plan().adapter().id());
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
                {"name":"Invalid environment","type":"python","request":"launch","program":"${file}","env":{"NOT-PORTABLE":"x"}},
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
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("env must be an object")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("unsupported VS Code variable")));
        assertFalse(effective.configurations().containsKey("vscode:Missing adapter"));
    }

    @Test
    void rejectsEnvironmentUnsetsVariablesAndAttachEnvironments() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("environment-boundary"));
        Path launch = Files.createDirectories(root.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launch, """
            {"configurations":[
              {"name":"Unset","type":"python","request":"launch","program":"${file}","env":{"APP_MODE":null}},
              {"name":"Variable","type":"python","request":"launch","program":"${file}","env":{"APP_MODE":"${env:MODE}"}},
              {"name":"Attach","type":"python","request":"attach","host":"127.0.0.1","port":5678,"env":{"APP_MODE":"development"}}
            ]}
            """);
        DebugAdapterRegistry.Validation base = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));

        VsCodeLaunchConfigurationImporter.Report report = VsCodeLaunchConfigurationImporter.read(root, base);

        assertTrue(report.configurations().isEmpty());
        assertEquals(3, report.skipped().size());
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("env must be an object")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("env values may not use VS Code variables")));
        assertTrue(report.skipped().stream().anyMatch(value -> value.contains("env is launch-only")));
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
