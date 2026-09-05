package shed;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declares local debug profiles whose adapters remain separately installed by the user. */
final class BuiltInDebugAdapterSupport {
    static final String PYTHON_DEBUGPY = "python-debugpy";
    static final String PYTHON_DEBUGPY_PYTEST = "python-debugpy-pytest";
    static final String PYTHON_DEBUGPY_UNITTEST = "python-debugpy-unittest";
    static final String GO_DELVE = "go-delve";
    static final String GO_DELVE_TEST = "go-delve-test";
    static final String CSHARP_NETCOREDBG = "csharp-netcoredbg";
    static final String NATIVE_LLDB = "native-lldb";
    static final String NATIVE_GDB = "native-gdb";

    private BuiltInDebugAdapterSupport() {
    }

    static List<String> contextualProfileIds() {
        return List.of(PYTHON_DEBUGPY, GO_DELVE);
    }

    static DebugAdapterRegistry.Validation effective(DebugAdapterRegistry.Validation base) {
        DebugAdapterRegistry.Adapter debugpy = new DebugAdapterRegistry.Adapter(PYTHON_DEBUGPY, DebugAdapterRegistry.Transport.STDIO,
            "debugpy-adapter", List.of(), Set.of(DebugAdapterRegistry.Capability.LAUNCH, DebugAdapterRegistry.Capability.CONFIGURATION_DONE,
                DebugAdapterRegistry.Capability.BREAKPOINTS, DebugAdapterRegistry.Capability.EXCEPTION_BREAKPOINTS,
                DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS,
                DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, DebugAdapterRegistry.Capability.LOG_POINTS,
                DebugAdapterRegistry.Capability.THREADS, DebugAdapterRegistry.Capability.STACK_TRACE,
                DebugAdapterRegistry.Capability.SCOPES, DebugAdapterRegistry.Capability.VARIABLES, DebugAdapterRegistry.Capability.EVALUATE,
                DebugAdapterRegistry.Capability.CONTINUE, DebugAdapterRegistry.Capability.NEXT, DebugAdapterRegistry.Capability.STEP_IN,
                DebugAdapterRegistry.Capability.STEP_OUT, DebugAdapterRegistry.Capability.PAUSE));
        DebugAdapterRegistry.Adapter delve = new DebugAdapterRegistry.Adapter(GO_DELVE, DebugAdapterRegistry.Transport.TCP, "dlv", List.of("dap"),
            Set.of(DebugAdapterRegistry.Capability.LAUNCH, DebugAdapterRegistry.Capability.CONFIGURATION_DONE,
                DebugAdapterRegistry.Capability.BREAKPOINTS, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS,
                DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS,
                DebugAdapterRegistry.Capability.THREADS,
                DebugAdapterRegistry.Capability.STACK_TRACE, DebugAdapterRegistry.Capability.SCOPES, DebugAdapterRegistry.Capability.VARIABLES,
                DebugAdapterRegistry.Capability.EVALUATE, DebugAdapterRegistry.Capability.CONTINUE, DebugAdapterRegistry.Capability.NEXT,
                DebugAdapterRegistry.Capability.STEP_IN, DebugAdapterRegistry.Capability.STEP_OUT, DebugAdapterRegistry.Capability.PAUSE),
            new DebugAdapterRegistry.SpawnedTcpStartup(List.of("--listen=127.0.0.1:0"), "DAP server listening at:"), Map.of("mode", "debug"));
        DebugAdapterRegistry.Adapter netcoredbg = new DebugAdapterRegistry.Adapter(CSHARP_NETCOREDBG, DebugAdapterRegistry.Transport.STDIO,
            "netcoredbg", List.of("--interpreter=vscode"), Set.of(DebugAdapterRegistry.Capability.LAUNCH,
                DebugAdapterRegistry.Capability.CONFIGURATION_DONE, DebugAdapterRegistry.Capability.BREAKPOINTS,
                DebugAdapterRegistry.Capability.THREADS, DebugAdapterRegistry.Capability.STACK_TRACE,
                DebugAdapterRegistry.Capability.SCOPES, DebugAdapterRegistry.Capability.VARIABLES, DebugAdapterRegistry.Capability.EVALUATE,
                DebugAdapterRegistry.Capability.CONTINUE, DebugAdapterRegistry.Capability.NEXT, DebugAdapterRegistry.Capability.STEP_IN,
                DebugAdapterRegistry.Capability.STEP_OUT, DebugAdapterRegistry.Capability.PAUSE));
        DebugAdapterRegistry.Adapter lldb = new DebugAdapterRegistry.Adapter(NATIVE_LLDB, DebugAdapterRegistry.Transport.STDIO,
            "lldb-dap", List.of(), Set.of(DebugAdapterRegistry.Capability.LAUNCH, DebugAdapterRegistry.Capability.CONFIGURATION_DONE,
                DebugAdapterRegistry.Capability.BREAKPOINTS, DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS, DebugAdapterRegistry.Capability.THREADS,
                DebugAdapterRegistry.Capability.STACK_TRACE, DebugAdapterRegistry.Capability.SCOPES,
                DebugAdapterRegistry.Capability.VARIABLES, DebugAdapterRegistry.Capability.EVALUATE,
                DebugAdapterRegistry.Capability.CONTINUE, DebugAdapterRegistry.Capability.NEXT, DebugAdapterRegistry.Capability.STEP_IN,
                DebugAdapterRegistry.Capability.STEP_OUT, DebugAdapterRegistry.Capability.PAUSE));
        DebugAdapterRegistry.Adapter gdb = new DebugAdapterRegistry.Adapter(NATIVE_GDB, DebugAdapterRegistry.Transport.STDIO,
            "gdb", List.of("--interpreter=dap"), Set.of(DebugAdapterRegistry.Capability.LAUNCH,
                DebugAdapterRegistry.Capability.CONFIGURATION_DONE, DebugAdapterRegistry.Capability.BREAKPOINTS,
                DebugAdapterRegistry.Capability.FUNCTION_BREAKPOINTS, DebugAdapterRegistry.Capability.INSTRUCTION_BREAKPOINTS,
                DebugAdapterRegistry.Capability.EXCEPTION_BREAKPOINTS, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS,
                DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, DebugAdapterRegistry.Capability.LOG_POINTS,
                DebugAdapterRegistry.Capability.THREADS, DebugAdapterRegistry.Capability.STACK_TRACE, DebugAdapterRegistry.Capability.SCOPES,
                DebugAdapterRegistry.Capability.VARIABLES, DebugAdapterRegistry.Capability.SET_VARIABLE,
                DebugAdapterRegistry.Capability.EVALUATE, DebugAdapterRegistry.Capability.CONTINUE, DebugAdapterRegistry.Capability.NEXT,
                DebugAdapterRegistry.Capability.STEP_IN, DebugAdapterRegistry.Capability.STEP_OUT, DebugAdapterRegistry.Capability.PAUSE,
                DebugAdapterRegistry.Capability.MODULES, DebugAdapterRegistry.Capability.LOADED_SOURCES,
                DebugAdapterRegistry.Capability.READ_MEMORY, DebugAdapterRegistry.Capability.DISASSEMBLE));
        DebugAdapterRegistry.Configuration python = new DebugAdapterRegistry.Configuration(PYTHON_DEBUGPY, PYTHON_DEBUGPY,
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${file}", "${workspaceFolder}", List.of(), "", "127.0.0.1", 0, List.of(".py", ".pyw"));
        DebugAdapterRegistry.Configuration go = new DebugAdapterRegistry.Configuration(GO_DELVE, GO_DELVE,
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${file}", "${workspaceFolder}", List.of(), "", "127.0.0.1", 0, List.of(".go"));
        DebugAdapterRegistry.Configuration pytest = new DebugAdapterRegistry.Configuration(PYTHON_DEBUGPY_PYTEST, PYTHON_DEBUGPY,
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "", "pytest", "", "${workspaceFolder}", List.of("${testId}"), "",
            "127.0.0.1", 0, List.of(), Map.of(), Map.of());
        DebugAdapterRegistry.Configuration unittest = new DebugAdapterRegistry.Configuration(PYTHON_DEBUGPY_UNITTEST, PYTHON_DEBUGPY,
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "", "unittest", "", "${workspaceFolder}", List.of("${testId}"), "",
            "127.0.0.1", 0, List.of(), Map.of(), Map.of());
        DebugAdapterRegistry.Configuration goTest = new DebugAdapterRegistry.Configuration(GO_DELVE_TEST, GO_DELVE,
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${workspaceFolder}", "", "", "${workspaceFolder}",
            List.of("-test.run", "^${testId}$"), "", "127.0.0.1", 0, List.of(), Map.of(), Map.of("mode", "test"));
        return DebugAdapterRegistry.withAdapterDefaults(base, Map.of(PYTHON_DEBUGPY, debugpy, GO_DELVE, delve, CSHARP_NETCOREDBG, netcoredbg,
            NATIVE_LLDB, lldb, NATIVE_GDB, gdb),
            Map.of(PYTHON_DEBUGPY, python, PYTHON_DEBUGPY_PYTEST, pytest, PYTHON_DEBUGPY_UNITTEST, unittest, GO_DELVE, go, GO_DELVE_TEST, goTest));
    }
}
