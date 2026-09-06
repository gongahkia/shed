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

    @Test
    void acceptsBoundedTypedAdapterSpecificLaunchOptionsButReservesCoreFields() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.adapter_options", "{\"type\":\"pwa-node\",\"sourceMaps\":true,\"outFiles\":[\"dist/**/*.js\"]}");

        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);

        assertTrue(validation.valid());
        assertEquals(Map.of("type", "pwa-node", "sourceMaps", true, "outFiles", java.util.List.of("dist/**/*.js")),
            validation.configurations().get("main").adapterOptions());
        values.put("debug.configuration.main.adapter_options", "{\"program\":\"outside.java\"}");
        assertFalse(DebugAdapterRegistry.validate(values).valid());
        values.put("debug.configuration.main.adapter_options", "{not-json}");
        assertFalse(DebugAdapterRegistry.validate(values).valid());
    }

    @Test
    void resolvesDeclaredDebugInputsOnlyInsideLaunchArguments() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.args", "--target ${input:target} --literal ${input:literal}");
        values.put("debug.configuration.main.input.target.default", "staging");
        values.put("debug.configuration.main.input.target.options", "staging,production");
        values.put("debug.configuration.main.input.literal.default", "initial");
        DebugAdapterRegistry.Validation validation = DebugAdapterRegistry.validate(values);
        Path workspace = Path.of("build/debug-input-workspace").toAbsolutePath();

        DebugAdapterRegistry.PlanResult defaultPlan = DebugAdapterRegistry.plan(validation, "main", workspace,
            new DebugAdapterRegistry.LaunchContext(workspace.resolve("Main.java"), "", null), Map.of("literal", "${file}"));
        DebugAdapterRegistry.PlanResult productionPlan = DebugAdapterRegistry.plan(validation, "main", workspace,
            new DebugAdapterRegistry.LaunchContext(workspace.resolve("Main.java"), "", null), Map.of("target", "production", "literal", "literal"));

        assertTrue(validation.valid());
        assertEquals(java.util.List.of("--target", "staging", "--literal", "${file}"), defaultPlan.plan().args());
        assertEquals(java.util.List.of("--target", "production", "--literal", "literal"), productionPlan.plan().args());
        assertFalse(DebugAdapterRegistry.plan(validation, "main", workspace, null, Map.of("target", "preview")).launchable());
        assertFalse(DebugAdapterRegistry.plan(validation, "main", workspace, null, Map.of("unknown", "value")).launchable());
    }

    @Test
    void rejectsUndeclaredOrUnresolvedDebugInputsBeforePlanning() {
        Map<String, Object> values = configuration("launch");
        values.put("debug.configuration.main.args", "${input:target}");
        DebugAdapterRegistry.Validation unresolved = DebugAdapterRegistry.validate(values);

        assertTrue(unresolved.valid());
        assertFalse(DebugAdapterRegistry.plan(unresolved, "main", Path.of("build/debug-input-workspace"), null, Map.of()).launchable());

        values.put("debug.configuration.main.input.target.default", "staging");
        values.put("debug.configuration.main.input.target.options", "production");
        DebugAdapterRegistry.Validation invalidDefault = DebugAdapterRegistry.validate(values);
        assertFalse(invalidDefault.valid());
        assertTrue(invalidDefault.errors().stream().anyMatch(error -> error.key().endsWith(".input.target.default")));

        values.remove("debug.configuration.main.input.target.options");
        values.put("debug.configuration.main.input.target.invalid", "value");
        DebugAdapterRegistry.Validation invalidKey = DebugAdapterRegistry.validate(values);
        assertFalse(invalidKey.valid());
        assertTrue(invalidKey.errors().stream().anyMatch(error -> error.key().endsWith(".input.target.invalid")));

        Map<String, Object> tooManyInputs = configuration("launch");
        for (int index = 0; index < 33; index++) {
            tooManyInputs.put("debug.configuration.main.input.value" + index + ".default", "value");
        }
        DebugAdapterRegistry.Validation tooMany = DebugAdapterRegistry.validate(tooManyInputs);
        assertFalse(tooMany.valid());
        assertTrue(tooMany.errors().stream().anyMatch(error -> error.key().endsWith(".input")));
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
