package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import shed.api.DebugAdapterContribution;
import org.junit.jupiter.api.Test;

class ExtensionDebugAdapterSupportTest {
    @Test
    void launchCapableContributionsBecomeExplicitLaunchConfigurations() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerDebugger("sample", new DebugAdapterContribution("runtime", "Runtime", List.of("runtime-dap", "--stdio"),
            List.of("--verbose"), Set.of("launch", "breakpoints")));
        ExtensionRegistry.Owned<DebugAdapterContribution> contribution = registry.debuggers().getFirst();

        DebugAdapterRegistry.Validation result = ExtensionDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()), registry);
        String id = ExtensionDebugAdapterSupport.configurationId(contribution);

        assertTrue(result.valid());
        assertEquals(id, result.configurations().get(id).adapter());
        assertEquals("runtime-dap", result.registry().adapter(id).command());
        assertEquals(List.of("--stdio", "--verbose"), result.registry().adapter(id).args());
        assertTrue(result.registry().adapter(id).supports(DebugAdapterRegistry.Request.LAUNCH));
    }

    @Test
    void adaptersWithoutLaunchAreAvailableButDoNotCreateAStartConfiguration() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.registerDebugger("sample", new DebugAdapterContribution("attach", "Attach", List.of("runtime-dap"), List.of(), Set.of("attach")));
        String id = ExtensionDebugAdapterSupport.configurationId(registry.debuggers().getFirst());

        DebugAdapterRegistry.Validation result = ExtensionDebugAdapterSupport.effective(DebugAdapterRegistry.validate(Map.of()), registry);

        assertTrue(result.registry().adapters().containsKey(id));
        assertFalse(result.configurations().containsKey(id));
    }
}
