package shed;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declares local debug profiles whose adapters remain separately installed by the user. */
final class BuiltInDebugAdapterSupport {
    static final String PYTHON_DEBUGPY = "python-debugpy";

    private BuiltInDebugAdapterSupport() {
    }

    static DebugAdapterRegistry.Validation effective(DebugAdapterRegistry.Validation base) {
        DebugAdapterRegistry.Adapter debugpy = new DebugAdapterRegistry.Adapter(PYTHON_DEBUGPY, DebugAdapterRegistry.Transport.STDIO,
            "debugpy-adapter", List.of(), Set.of(DebugAdapterRegistry.Capability.LAUNCH, DebugAdapterRegistry.Capability.CONFIGURATION_DONE,
                DebugAdapterRegistry.Capability.BREAKPOINTS, DebugAdapterRegistry.Capability.CONDITIONAL_BREAKPOINTS,
                DebugAdapterRegistry.Capability.HIT_CONDITIONAL_BREAKPOINTS, DebugAdapterRegistry.Capability.LOG_POINTS,
                DebugAdapterRegistry.Capability.THREADS, DebugAdapterRegistry.Capability.STACK_TRACE,
                DebugAdapterRegistry.Capability.SCOPES, DebugAdapterRegistry.Capability.VARIABLES, DebugAdapterRegistry.Capability.EVALUATE,
                DebugAdapterRegistry.Capability.CONTINUE, DebugAdapterRegistry.Capability.NEXT, DebugAdapterRegistry.Capability.STEP_IN,
                DebugAdapterRegistry.Capability.STEP_OUT, DebugAdapterRegistry.Capability.PAUSE));
        DebugAdapterRegistry.Configuration python = new DebugAdapterRegistry.Configuration(PYTHON_DEBUGPY, PYTHON_DEBUGPY,
            DebugAdapterRegistry.Request.LAUNCH, "workspace", "${file}", "${workspaceFolder}", List.of(), "127.0.0.1", 0, List.of(".py", ".pyw"));
        return DebugAdapterRegistry.withAdapterDefaults(base, Map.of(PYTHON_DEBUGPY, debugpy), Map.of(PYTHON_DEBUGPY, python));
    }
}
