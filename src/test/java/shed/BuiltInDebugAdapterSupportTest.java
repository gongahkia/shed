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
    void contributesUserInstalledPythonGoCsharpAndNativeDebugSupport() {
        DebugAdapterRegistry.Validation validation = BuiltInDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()));
        Path workspace = Path.of("build/debugpy-profile").toAbsolutePath();

        assertTrue(validation.valid());
        assertEquals("debugpy-adapter", validation.registry().adapter(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY).command());
        assertEquals(java.util.List.of(".py", ".pyw"), validation.configurations().get(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY).fileExtensions());
        assertTrue(validation.registry().adapter(BuiltInDebugAdapterSupport.PYTHON_DEBUGPY).capabilities()
            .contains(DebugAdapterRegistry.Capability.EXCEPTION_BREAKPOINTS));
        assertTrue(DebugAdapterRegistry.plan(validation, BuiltInDebugAdapterSupport.PYTHON_DEBUGPY, workspace, workspace.resolve("main.py")).launchable());
        assertFalse(DebugAdapterRegistry.plan(validation, BuiltInDebugAdapterSupport.PYTHON_DEBUGPY, workspace, workspace.resolve("Main.java")).launchable());
        DebugAdapterRegistry.Adapter delve = validation.registry().adapter(BuiltInDebugAdapterSupport.GO_DELVE);
        assertEquals("dlv", delve.command());
        assertEquals(DebugAdapterRegistry.Transport.TCP, delve.transport());
        assertEquals(java.util.List.of("dap"), delve.args());
        assertEquals(java.util.List.of("--listen=127.0.0.1:0"), delve.spawnedTcpStartup().arguments());
        assertEquals("debug", delve.launchDefaults().get("mode"));
        assertTrue(delve.capabilities().contains(DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS));
        assertEquals(java.util.List.of(".go"), validation.configurations().get(BuiltInDebugAdapterSupport.GO_DELVE).fileExtensions());
        assertTrue(DebugAdapterRegistry.plan(validation, BuiltInDebugAdapterSupport.GO_DELVE, workspace, workspace.resolve("main.go")).launchable());
        assertFalse(DebugAdapterRegistry.plan(validation, BuiltInDebugAdapterSupport.GO_DELVE, workspace, workspace.resolve("main.py")).launchable());
        DebugAdapterRegistry.Adapter netcoredbg = validation.registry().adapter(BuiltInDebugAdapterSupport.CSHARP_NETCOREDBG);
        assertEquals("netcoredbg", netcoredbg.command());
        assertEquals(java.util.List.of("--interpreter=vscode"), netcoredbg.args());
        assertTrue(netcoredbg.capabilities().contains(DebugAdapterRegistry.Capability.BREAKPOINTS));
        assertFalse(validation.configurations().containsKey(BuiltInDebugAdapterSupport.CSHARP_NETCOREDBG));
        DebugAdapterRegistry.Adapter lldb = validation.registry().adapter(BuiltInDebugAdapterSupport.NATIVE_LLDB);
        assertEquals("lldb-dap", lldb.command());
        assertTrue(lldb.capabilities().contains(DebugAdapterRegistry.Capability.BREAKPOINTS));
        assertTrue(lldb.capabilities().contains(DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS));
        assertFalse(validation.configurations().containsKey(BuiltInDebugAdapterSupport.NATIVE_LLDB));
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
