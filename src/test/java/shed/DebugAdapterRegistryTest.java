package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DebugAdapterRegistryTest {
    @Test
    void validatesAdapterCapabilitiesAndCreatesWorkspaceScopedPlansWithoutLaunching() {
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(configuration("launch"));
        Path workspace = Path.of("build/debug-workspace").toAbsolutePath();
        Path activeFile = workspace.resolve("src/Main.java");

        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(validation, "main", workspace, activeFile);

        assertTrue(validation.valid());
        assertTrue(plan.launchable());
        assertEquals("java", plan.plan().adapter().id());
        assertEquals(activeFile, plan.plan().program());
        assertEquals(workspace, plan.plan().cwd());
    }

    @Test
    void rejectsInvalidConfigurationBeforeItCanProduceALaunchPlan() {
        Map<String, Object> values = configuration("attach");
        values.put("debug.adapter.java.capabilities", "launch");
        values.put("debug.configuration.main.host", "debug.example.com");
        values.put("debug.configuration.main.port", "5005");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);

        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(validation, "main", Path.of("build/debug-workspace"));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.key().equals("debug.configuration.main.request")));
        assertFalse(plan.launchable());
        assertTrue(plan.error().contains("no process"));
    }

    @Test
    void expandsOnlyKnownTestLaunchPlaceholdersInsideTheWorkspace() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.args", "--test ${testId} ${testFile}");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);
        Path workspace = Path.of("build/debug-test-workspace").toAbsolutePath();
        Path test = workspace.resolve("src/test/java/SampleTest.java");

        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(validation, "main", workspace,
            new DebugAdapterRegistry.LaunchContext(test, "sample#works", test));

        assertTrue(plan.launchable());
        assertEquals(java.util.List.of("--test", "sample#works", test.toString()), plan.plan().args());
        values.put("debug.configuration.main.args", "${unknown}");
        assertFalse(DebugAdapterRegistry.plan(DebugAdapterRegistry.validate(values), "main", workspace,
            new DebugAdapterRegistry.LaunchContext(test, "sample#works", test)).launchable());
    }

    @Test
    void expandsBoundedActiveFileMetadataInsideDebugArguments() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.args", "${workspaceFolderBasename} ${fileWorkspaceFolder} ${relativeFileDirname} ${fileBasenameNoExtension} ${fileExtname} ${fileDirname} ${fileDirnameBasename}");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);
        Path workspace = Path.of("build/debug-argument-workspace").toAbsolutePath();
        Path source = workspace.resolve("src/Sample.test.java");

        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(validation, "main", workspace, source);

        assertTrue(plan.launchable());
        assertEquals(java.util.List.of("debug-argument-workspace", workspace.toString(), "src", "Sample.test", ".java",
            source.getParent().toString(), "src"), plan.plan().args());
    }

    @Test
    void acceptsOptionalConfigurationFileExtensionsAndRejectsUnsupportedPrograms() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.file_extensions", ".py,.pyw");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);
        Path workspace = Path.of("build/debug-extension-workspace").toAbsolutePath();

        assertTrue(validation.valid());
        assertEquals(java.util.List.of(".py", ".pyw"), validation.configurations().get("main").fileExtensions());
        assertFalse(DebugAdapterRegistry.plan(validation, "main", workspace, workspace.resolve("Main.java")).launchable());
        assertTrue(DebugAdapterRegistry.plan(validation, "main", workspace, workspace.resolve("main.py")).launchable());
    }

    @Test
    void acceptsModuleOrInlineCodeAsExclusiveLaunchTargets() {
        Map<String, Object> values = configuration("launch");
        values.remove("debug.configuration.main.program");
        values.put("debug.configuration.main.module", "package.main");
        DebugAdapterRegistry.Validation moduleValidation = DebugAdapterRegistry.validate(values);

        DebugAdapterRegistry.PlanResult modulePlan = DebugAdapterRegistry.plan(moduleValidation, "main", Path.of("build/debug-module-workspace"));
        assertTrue(moduleValidation.valid());
        assertTrue(modulePlan.launchable());
        assertEquals("package.main", modulePlan.plan().module());
        assertEquals("", modulePlan.plan().code());
        assertEquals(null, modulePlan.plan().program());

        values.remove("debug.configuration.main.module");
        values.put("debug.configuration.main.code", "print('hello from Shed')");
        DebugAdapterRegistry.Validation codeValidation = DebugAdapterRegistry.validate(values);
        DebugAdapterRegistry.PlanResult codePlan = DebugAdapterRegistry.plan(codeValidation, "main", Path.of("build/debug-code-workspace"));
        assertTrue(codeValidation.valid());
        assertTrue(codePlan.launchable());
        assertEquals("print('hello from Shed')", codePlan.plan().code());

        values.put("debug.configuration.main.program", "${file}");
        DebugAdapterRegistry.Validation ambiguous = DebugAdapterRegistry.validate(values);
        assertFalse(ambiguous.valid());
        assertTrue(ambiguous.errors().stream().anyMatch(error -> error.message().contains("exactly one")));
    }

    @Test
    void rejectsFileExtensionsWithoutAProgramOrUnsafeModuleNames() {
        Map<String, Object> values = configuration("launch");
        values.remove("debug.configuration.main.program");
        values.put("debug.configuration.main.module", "package.main");
        values.put("debug.configuration.main.file_extensions", ".py");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.key().endsWith(".file_extensions")));

        values.remove("debug.configuration.main.file_extensions");
        values.put("debug.configuration.main.module", "package;main");
        validation = DebugAdapterRegistry.validate(values);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.key().endsWith(".module")));
    }

    @Test
    void retainsLegacyProgramValuesOnAttachWithoutSendingThemAsLaunchTargets() {
        Map<String, Object> values = configuration("attach");
        values.put("debug.configuration.main.port", "5005");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);

        DebugAdapterRegistry.PlanResult plan = DebugAdapterRegistry.plan(validation, "main", Path.of("build/debug-attach-workspace"));
        assertTrue(validation.valid());
        assertTrue(plan.launchable());
        assertEquals(null, plan.plan().program());
    }

    @Test
    void rejectsMalformedConfigurationFileExtensionLists() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.file_extensions", "py,*.py");

        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.key().equals("debug.configuration.main.file_extensions")));
    }

    @Test
    void validatesAnOptionalPreLaunchWorkspaceTaskIdentifier() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.prelaunch_task", "compile_assets");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);

        assertTrue(validation.valid());
        assertEquals("compile_assets", validation.configurations().get("main").prelaunchTask());

        values.put("debug.configuration.main.prelaunch_task", "compile;assets");
        validation = DebugAdapterRegistry.validate(values);
        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.key().equals("debug.configuration.main.prelaunch_task")));
    }

    @Test
    void acceptsTheStoppedFrameSourceNavigationPreferenceAsACoreSetting() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.open.source.on.stop", "false");

        assertTrue(DebugAdapterRegistry.validate(values).valid());
    }

    private static Map<String, Object> configuration(String request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.java.command", "java-debug-adapter");
        values.put("debug.adapter.java.args", "--stdio");
        values.put("debug.adapter.java.capabilities", "launch,attach,breakpoints,threads,stack_trace,scopes,variables,evaluate");
        values.put("debug.configuration.main.adapter", "java");
        values.put("debug.configuration.main.request", request);
        values.put("debug.configuration.main.scope", "workspace");
        values.put("debug.configuration.main.program", "${file}");
        values.put("debug.configuration.main.cwd", "${workspaceFolder}");
        return values;
    }
}
