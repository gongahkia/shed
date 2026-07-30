package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeymapOverlayTest {
    @Test
    void canonicalizesSupportedOverlayKeysAndRejectsInvalidTokens() {
        assertEquals("keybind.normal.<c-s>", KeymapOverlay.normalizeKey("keybind.NORMAL.<C-S>"));
        assertNull(KeymapOverlay.validate("keybind.insert.j", "<esc>:w<enter>"));
        assertEquals("keybinding scope must be normal, insert, visual, visual_line, replace, command, search, or global",
            KeymapOverlay.validate("keybind.plain.x", "a"));
        assertEquals("keybinding rhs token has unsupported token <f1>", KeymapOverlay.validate("keybind.normal.x", "<f1>"));
        assertEquals("keybinding rhs has an unterminated token", KeymapOverlay.validate("keybind.normal.x", "<esc"));
    }

    @Test
    void reportsProfileAndScopePrecedencePrecisely() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("keybind.global.x", "a");
        config.put("keybind.normal.x", "b");

        List<KeymapOverlay.Binding> vim = KeymapOverlay.effectiveBindings(config, KeymapProfile.VIM);
        KeymapOverlay.Binding global = vim.stream().filter(binding -> binding.configKey() != null && binding.scope().equals("global")).findFirst().orElseThrow();
        KeymapOverlay.Binding normal = vim.stream().filter(binding -> binding.configKey() != null && binding.scope().equals("normal")).findFirst().orElseThrow();
        assertEquals("Active except normal (scope-specific override)", global.status());
        assertEquals("Active in normal; overrides global", normal.status());

        KeymapOverlay.Binding plain = KeymapOverlay.effectiveBindings(config, KeymapProfile.PLAIN).stream()
            .filter(binding -> binding.configKey() != null && binding.scope().equals("normal")).findFirst().orElseThrow();
        assertEquals("Inactive: plain uses fixed profile bindings", plain.status());
        assertTrue(KeymapOverlay.formatBindings(vim, "normal x").contains("overrides global"));
    }
}
