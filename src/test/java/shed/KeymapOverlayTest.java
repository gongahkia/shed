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
    void protectsPrintablePunctuationAndReportsIdenticalMappingConflicts() {
        for (String key : java.util.List.of("<minus>", "<plus>", "<comma>"))
            org.junit.jupiter.api.Assertions.assertNotNull(KeymapOverlay.validate("keybind.plain." + key, "action:find"));
        var config = java.util.Map.of("keybind.global.<f2>", "action:find", "keybind.plain.<f2>", "action:find");
        var fallback = KeymapOverlay.effectiveBindings(config, KeymapProfile.PLAIN).stream()
            .filter(binding -> "keybind.global.<f2>".equals(binding.configKey())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(fallback.status().startsWith("Shadowed"));
    }

    @Test
    void actionBindingsApplyByProfileAndPhysicalModifierBeforePortableFallback() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("keybind.global.<mod-s>", "action:save");
        values.put("keybind.plain.<mod-s>", "action:find");
        String physical = ShortcutKey.isMac() ? "<meta-s>" : "<c-s>";
        values.put("keybind.plain." + physical, "action:undo");
        java.awt.event.KeyEvent event = new java.awt.event.KeyEvent(new javax.swing.JTextArea(),
            java.awt.event.KeyEvent.KEY_PRESSED, 0, ShortcutKey.primaryMask(), java.awt.event.KeyEvent.VK_S, 's');
        assertEquals("action:undo", KeymapOverlay.resolveAction(values, KeymapProfile.PLAIN, "insert", event));
        assertEquals("action:save", KeymapOverlay.resolveAction(values, KeymapProfile.EMACS, "insert", event));
        assertTrue(KeymapOverlay.effectiveBindings(values, KeymapProfile.PLAIN).stream()
            .anyMatch(binding -> binding.lhs().equals("<mod-s>") && binding.status().startsWith("Shadowed")));
        assertNull(KeymapOverlay.validate("keybind.emacs.<a-x>", "command:buffers"));
        assertNull(KeymapOverlay.validate("keybind.vim.<mod-s-k>", "action:find-in-files"));
        assertNull(KeymapOverlay.validate("keybind.normal.x", "nop"));
        assertTrue(KeymapOverlay.validate("keybind.plain.<c-a-q>", "action:find").contains("international"));
        assertTrue(KeymapOverlay.validate("keybind.plain.<a-e>", "action:find").contains("text input"));
        assertTrue(KeymapOverlay.validate("keybind.plain.<f2>", "action:missing").contains("unknown"));
    }

    @Test
    void canonicalizesSupportedOverlayKeysAndRejectsInvalidTokens() {
        assertEquals("keybind.normal.<c-s>", KeymapOverlay.normalizeKey("keybind.NORMAL.<C-S>"));
        assertNull(KeymapOverlay.validate("keybind.insert.j", "<esc>:w<enter>"));
        assertTrue(KeymapOverlay.validate("keybind.plain.x", "a").contains("shortcut"));
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
        assertEquals("Inactive: legacy key sequences require Vim", plain.status());
        assertTrue(KeymapOverlay.formatBindings(vim, "normal x").contains("overrides global"));
    }
}
