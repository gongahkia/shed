package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentModelOptionsTest {
    @Test void onlyAdvertisedModelSelectorsAreExposedAndGroupsAreFlattened() {
        var mode = Map.of("id", "mode", "category", "mode", "type", "select", "currentValue", "build", "options", List.of(Map.of("value", "build", "name", "Unrestricted")));
        var model = Map.of("id", "models", "category", "model", "type", "select", "currentValue", "provider/fast", "options", List.of(
            Map.of("group", "provider", "options", List.of(Map.of("value", "provider/fast", "name", "Fast")))));
        assertTrue(AgentModelOptions.parse(Map.of("configOptions", List.of(mode))).choices().isEmpty());
        var selected = AgentModelOptions.parse(Map.of("configOptions", List.of(mode, model)));
        assertEquals("models", selected.configId()); assertEquals(List.of(new AgentModelOptions.Choice("provider/fast", "Fast")), selected.choices());
        assertFalse(selected.legacy());
        assertThrows(UnsupportedOperationException.class, () -> selected.choices().clear());
    }
    @Test void rejectsMalformedOrOversizedModelMetadata() {
        for (String value : List.of("bad\nmodel", "x".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> AgentModelOptions.parse(Map.of("models", Map.of(
                "currentModelId", value, "availableModels", List.of(Map.of("modelId", value, "name", "model"))))));
        }
    }
}
