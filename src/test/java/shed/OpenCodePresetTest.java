package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenCodePresetTest {
    @TempDir Path temporary;

    @Test void defaultProviderIsDiscoverableWithoutLaunchingOrInstallingAnything() {
        String home = System.getProperty("user.home"); System.setProperty("user.home", temporary.toString());
        try {
            var config = new ConfigManager(); assertTrue(config.getAgentProviderNames().contains("opencode"));
            var provider = AgentProvider.configured(config, "opencode"); assertEquals(List.of("opencode", "acp"), provider.command());
            var json = MiniJson.asObject(MiniJson.parseStrict(provider.environment().get("OPENCODE_CONFIG_CONTENT")));
            assertFalse((Boolean) json.get("autoupdate")); assertEquals("shed-review", json.get("default_agent")); assertFalse(json.containsKey("model"));
            var permissions = MiniJson.asObject(json.get("permission")); assertEquals("allow", permissions.get("grep")); assertEquals("allow", permissions.get("glob"));
            assertEquals("deny", permissions.get("*"));
            for (String tool : List.of("edit", "bash", "task", "question", "webfetch", "external_directory")) assertEquals("deny", permissions.get(tool));
            var agent = MiniJson.asObject(MiniJson.asObject(json.get("agent")).get("shed-review")); assertEquals(permissions, agent.get("permission"));
            config.set("agent.opencode.model", "provider/model");
            config.set("agent.opencode.command", "'/custom path/opencode'");
            var customized = AgentProvider.configured(config, "opencode"); assertEquals(List.of("/custom path/opencode", "acp"), customized.command());
            assertEquals("provider/model", MiniJson.asObject(MiniJson.parseStrict(customized.environment().get("OPENCODE_CONFIG_CONTENT"))).get("model"));
            assertFalse(config.isProjectConfigKeyAllowed("agent.opencode.model"));
        } finally { System.setProperty("user.home", home); }
    }

    @Test void genericAcpProvidersKeepTheirOwnInvocationAndModelConfiguration() {
        String home = System.getProperty("user.home"); System.setProperty("user.home", temporary.toString());
        try {
            var config = new ConfigManager(); config.set("agent.custom.command", "custom-acp");
            assertTrue(AgentProvider.configured(config, "custom").environment().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> OpenCodePreset.environment("bad\nmodel"));
        } finally { System.setProperty("user.home", home); }
    }
}
