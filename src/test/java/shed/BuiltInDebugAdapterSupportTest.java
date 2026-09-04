package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltInDebugAdapterSupportTest {
    @Test
    void contributesAUserInstalledDebugpyProfileForPythonFilesOnly() {
        DebugAdapterRegistry.Validation validation = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));
        Path workspace = Path.of("build/debugpy-profile").toAbsolutePath();

        assertTrue(validation.valid());
        assertEquals("debugpy-adapter", validation.registry().adapter(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY).command());
        assertEquals(java.util.List.of(".py", ".pyw"), validation.configurations().get(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY).fileExtensions());
        assertTrue(validation.registry().adapter(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY).capabilities()
            .contains(DebugAdapterRegistry.Capability.EXCEPTION_BREAKPOINTS));
        assertTrue(DebugAdapterRegistry.plan(validation, BuiltInDebugAdapterSupport.PYTHON_DEBUGPY, workspace, workspace.resolve("main.py")).launchable());
        assertFalse(DebugAdapterRegistry.plan(validation, BuiltInDebugAdapterSupport.PYTHON_DEBUGPY, workspace, workspace.resolve("Main.java")).launchable());
    }

    @Test
    void neverOverridesAUserManagedAdapterWithTheProfileId() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("debug.adapter.python-debugpy.command", "custom-debug-adapter");
        values.put("debug.adapter.python-debugpy.capabilities", "launch");
        DebugAdapterRegistry.Validation validation = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(values));

        assertEquals("custom-debug-adapter", validation.registry().adapter(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY).command());
        assertFalse(validation.configurations().containsKey(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY));
    }
}
