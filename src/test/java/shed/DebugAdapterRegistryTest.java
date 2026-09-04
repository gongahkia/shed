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
