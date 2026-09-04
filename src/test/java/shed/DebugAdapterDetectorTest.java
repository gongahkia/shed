package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class DebugAdapterDetectorTest {
    @Test
    void reportsMissingConfiguredExecutablesWithoutBlockingEditing() {
        DebugAdapterDetector detector = new DebugAdapterDetector((command, workspace) -> null);

        DebugAdapterDetector.WorkspaceReport report = detector.detect(Path.of("build/debug-detect"), validation("launch"), enabled());

        DebugAdapterDetector.AdapterReport adapter = report.adapters().getFirst();
        assertTrue(report.normalEditingAvailable());
        assertEquals(DebugAdapterDetector.Availability.EXECUTABLE_MISSING, adapter.availability());
        assertEquals(DebugAdapterDetector.VersionState.NOT_PROBED, adapter.versionState());
        assertEquals(DebugAdapterDetector.CapabilityState.AVAILABLE, adapter.capabilities().get(DebugAdapterRegistry.Capability.LAUNCH));
        assertTrue(adapter.remediation().contains("debug.adapter.java.command"));
        assertFalse(report.configurations().getFirst().usable());
    }

    @Test
    void reportsConfiguredRemoteStdioAdapterWithoutLocalExecutable() {
        DebugAdapterDetector detector = new DebugAdapterDetector((command, workspace) -> null);
        DebugAdapterDetector.WorkspaceReport report = detector.detect(Path.of("build/debug-detect"), validation("launch"), enabled(), Set.of("java"));
        assertEquals(DebugAdapterDetector.Availability.AVAILABLE, report.adapters().getFirst().availability());
        assertEquals("remote", report.adapters().getFirst().executable());
    }

    @Test
    void skipsExecutableResolutionWhileDebuggingIsDisabled() {
        DebugAdapterDetector detector = new DebugAdapterDetector((command, workspace) -> { throw new AssertionError("resolver must not run"); });

        DebugAdapterDetector.WorkspaceReport report = detector.detect(Path.of("build/debug-detect"), validation("launch"), DebugFeatureSettings.defaults());

        assertEquals(DebugAdapterDetector.Availability.DISABLED, report.adapters().getFirst().availability());
        assertEquals(DebugAdapterDetector.CapabilityState.DISABLED,
            report.adapters().getFirst().capabilities().get(DebugAdapterRegistry.Capability.LAUNCH));
        assertEquals(DebugAdapterDetector.Availability.DISABLED, report.configurations().getFirst().availability());
        assertTrue(report.normalEditingAvailable());
    }

    @Test
    void exposesPerCapabilitySettingsAndAttachState() {
        DebugAdapterDetector detector = new DebugAdapterDetector((command, workspace) -> Path.of("/tools/java-debug-adapter"));
        DebugFeatureSettings settings = new DebugFeatureSettings(true, true, true, true, true, false, true, false);

        DebugAdapterDetector.WorkspaceReport report = detector.detect(Path.of("build/debug-detect"), validation("attach"), settings);

        DebugAdapterDetector.AdapterReport adapter = report.adapters().getFirst();
        assertEquals(DebugAdapterDetector.Availability.AVAILABLE, adapter.availability());
        assertEquals(DebugAdapterDetector.CapabilityState.DISABLED, adapter.capabilities().get(DebugAdapterRegistry.Capability.ATTACH));
        assertEquals(DebugAdapterDetector.CapabilityState.DISABLED, adapter.capabilities().get(DebugAdapterRegistry.Capability.VARIABLES));
        assertEquals(DebugAdapterDetector.Availability.DISABLED, report.configurations().getFirst().availability());
        assertTrue(report.configurations().getFirst().remediation().contains("debug.attach.enabled"));
    }

    @Test
    void surfacesInvalidConfigurationWithoutBlockingEditing() {
        Map<String, Object> invalid = configuration("launch");
        invalid.put("debug.configuration.main.request", "run");
        DebugAdapterDetector detector = new DebugAdapterDetector((command, workspace) -> { throw new AssertionError("resolver must not run"); });

        DebugAdapterDetector.WorkspaceReport report = detector.detect(Path.of("build/debug-detect"), DebugAdapterRegistry.validate(invalid), DebugFeatureSettings.defaults());

        assertFalse(report.validConfiguration());
        assertTrue(report.validationErrors().getFirst().contains("must be launch or attach"));
        assertTrue(report.normalEditingAvailable());
    }

    private static DebugAdapterRegistry.Validation validation(String request) {
        return DebugAdapterRegistry.validate(configuration(request));
    }

    private static Map<String, Object> configuration(String request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.java.command", "java-debug-adapter");
        values.put("debug.adapter.java.capabilities", "launch,attach,breakpoints,threads,stack_trace,scopes,variables,evaluate");
        values.put("debug.configuration.main.adapter", "java");
        values.put("debug.configuration.main.request", request);
        values.put("debug.configuration.main.scope", "workspace");
        values.put("debug.configuration.main.program", "${workspaceFolder}/Main.java");
        values.put("debug.configuration.main.cwd", "${workspaceFolder}");
        if ("attach".equals(request)) values.put("debug.configuration.main.port", "5005");
        return values;
    }

    private static DebugFeatureSettings enabled() {
        return new DebugFeatureSettings(true, true, true, true, true, true, true, true);
    }
}
