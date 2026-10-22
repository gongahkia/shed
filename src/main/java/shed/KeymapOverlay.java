package shed;

import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class KeymapOverlay {
    private static final java.awt.Component KEY_SOURCE = new java.awt.Component() { };
    private static final String PREFIX = "keybind.";
    private static final Set<String> SCOPES = Set.of("normal", "insert", "visual", "visual_line", "replace", "command", "search", "global", "plain", "emacs", "vim");

    record Key(String scope, String lhs) {
        String configKey() {
            return PREFIX + scope + "." + lhs;
        }
    }

    record Binding(String configKey, String scope, String lhs, String mapping, String source, String status) {
    }

    private KeymapOverlay() {
    }

    static boolean isKeybindKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("keybind") || normalized.startsWith(PREFIX);
    }

    static String validate(String rawKey, String rawMapping) {
        try {
            Key key = parseKey(rawKey);
            if (key.lhs().length() == 1 && !Set.of("plain", "emacs", "vim").contains(key.scope())
                && ("nop".equalsIgnoreCase(rawMapping) || "<nop>".equalsIgnoreCase(rawMapping))) return null;
            if (isActionMapping(rawMapping) || Set.of("plain", "emacs", "vim").contains(key.scope())) {
                ShortcutKey shortcut = ShortcutKey.parse(key.lhs());
                if (!shortcut.safeForTextInput() && !Set.of("emacs", "vim").contains(key.scope()))
                    return "use a modified shortcut or function key so ordinary text input remains available";
                if (!isActionMapping(rawMapping)) return "profile overlays require action:<name>, command:<Ex command>, or nop";
                return validateActionMapping(rawMapping);
            }
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
        return validateMapping(rawMapping);
    }

    static String normalizeKey(String rawKey) {
        return parseKey(rawKey).configKey();
    }

    static Key parseKey(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (!key.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            throw new IllegalArgumentException("keybinding key must start with keybind.");
        }
        String tail = key.substring(PREFIX.length());
        int separator = tail.indexOf('.');
        if (separator <= 0 || separator == tail.length() - 1) {
            throw new IllegalArgumentException("keybinding key must be keybind.<scope>.<lhs>");
        }
        String scope = tail.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw new IllegalArgumentException("keybinding scope must be plain, emacs, vim, global, or a Vim mode");
        }
        String lhs = tail.substring(separator + 1).trim();
        if (lhs.startsWith("<")) {
            try { lhs = ShortcutKey.parse(lhs).token(); }
            catch (IllegalArgumentException error) {
                if (Set.of("plain", "emacs", "vim").contains(scope)) throw error;
                lhs = normalizeToken(lhs, "keybinding lhs");
            }
        } else lhs = normalizeToken(lhs, "keybinding lhs");
        return new Key(scope, lhs);
    }

    static List<Binding> effectiveBindings(Map<String, String> config, KeymapProfile profile) {
        Map<Key, String> overlays = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (!isKeybindKey(entry.getKey())) {
                continue;
            }
            try {
                Key key = parseKey(entry.getKey());
                if (validate(entry.getKey(), entry.getValue()) == null) {
                    overlays.put(key, entry.getValue().trim());
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        List<Binding> bindings = new ArrayList<>();
        for (Map.Entry<Key, String> entry : overlays.entrySet()) {
            Key key = entry.getKey();
            String status = key.lhs().startsWith("<") && isActionMapping(entry.getValue()) ? actionStatus(key, overlays, config, profile)
                : overlayStatus(key, overlays, profile);
            bindings.add(new Binding(key.configKey(), key.scope(), key.lhs(), entry.getValue(), "User overlay", status));
        }
        for (Binding binding : profileBindings(profile)) {
            String status = binding.status();
            try {
                ShortcutKey shortcut = ShortcutKey.parse(binding.lhs());
                String override = resolveAction(config, profile, "normal", event(shortcut));
                if (override != null) status = "Overridden in editor: " + override;
            } catch (IllegalArgumentException ignored) { }
            bindings.add(new Binding(binding.configKey(), binding.scope(), binding.lhs(), binding.mapping(), binding.source(), status));
        }
        bindings.sort(Comparator.comparing(Binding::source).thenComparing(Binding::scope).thenComparing(Binding::lhs));
        return List.copyOf(bindings);
    }

    static String formatBindings(List<Binding> bindings, String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        StringBuilder text = new StringBuilder("Effective keybindings\n\n");
        for (Binding binding : bindings) {
            String line = binding.scope() + "  " + binding.lhs() + "  " + binding.mapping() + "  " + binding.source() + "  " + binding.status();
            if (matches(binding, normalized)) {
                text.append(line).append('\n');
            }
        }
        if (text.toString().equals("Effective keybindings\n\n")) {
            text.append("No matching bindings.\n");
        }
        return text.append("\nPrecedence: Vim mode action, profile action, global action, then built-in bindings (legacy Vim overlays retain their mode/global precedence).\n")
            .append("Profile action bindings work in the editor only. Physical modifiers win over Mod aliases in the same scope.\n")
            .append("Legacy key sequences remain Vim-only; menu actions and other text fields keep their normal behavior.\n").toString();
    }

    static boolean matches(Binding binding, String query) {
        if (binding == null) {
            return false;
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return true;
        }
        String text = String.join(" ", binding.scope(), binding.lhs(), binding.mapping(), binding.source(), binding.status()).toLowerCase(Locale.ROOT);
        for (String term : normalized.split("\\s+")) {
            if (!text.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private static String overlayStatus(Key key, Map<Key, String> overlays, KeymapProfile profile) {
        if (profile != KeymapProfile.VIM) {
            return "Inactive: legacy key sequences require Vim";
        }
        if (key.scope().equals("global")) {
            List<String> shadowing = new ArrayList<>();
            for (Key candidate : overlays.keySet()) {
                if (!candidate.scope().equals("global") && candidate.lhs().equals(key.lhs())) {
                    shadowing.add(candidate.scope());
                }
            }
            return shadowing.isEmpty() ? "Active fallback in every Vim mode" : "Active except " + String.join(", ", shadowing) + " (scope-specific override)";
        }
        Key global = new Key("global", key.lhs());
        return overlays.containsKey(global) ? "Active in " + key.scope() + "; overrides global" : "Active in " + key.scope();
    }

    private static List<Binding> profileBindings(KeymapProfile profile) {
        if (profile != KeymapProfile.VIM) {
            List<Binding> result = new ArrayList<>();
            List<Integer> codes = new ArrayList<>();
            for (int code = java.awt.event.KeyEvent.VK_A; code <= java.awt.event.KeyEvent.VK_Z; code++) codes.add(code);
            codes.addAll(List.of(java.awt.event.KeyEvent.VK_F1, java.awt.event.KeyEvent.VK_F3,
                java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.VK_PAGE_DOWN,
                java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.VK_DOWN,
                java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.VK_MINUS));
            for (int code : codes) for (int modifiers : List.of(0, InputEvent.SHIFT_DOWN_MASK, InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK,
                InputEvent.ALT_DOWN_MASK, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) {
                ShortcutKey shortcut = new ShortcutKey(code, modifiers, false);
                java.awt.event.KeyEvent event = event(shortcut);
                String action;
                if (profile == KeymapProfile.PLAIN) {
                    PlainKeymap.Action resolved = PlainKeymap.actionFor(event);
                    if (resolved == PlainKeymap.Action.NONE) continue;
                    action = resolved.name();
                } else {
                    EmacsKeymap.Resolution resolved = EmacsKeymap.resolve(EmacsKeymap.Prefix.NONE, event);
                    if (resolved.action() == EmacsKeymap.Action.NONE) continue;
                    action = resolved.action().name();
                }
                String name = action.toLowerCase(Locale.ROOT).replace('_', '-');
                result.add(new Binding("keybind." + profile.configValue() + "." + shortcut.token(), profile.configValue(),
                    shortcut.token(), "action:" + name, "Profile", "Active built-in binding"));
            }
            if (profile == KeymapProfile.EMACS) result.add(fixed("<c-x> …", "Emacs prefix: C-s save, C-f find file, b buffers, k close buffer, C-c quit"));
            return List.copyOf(result);
        }
        return List.of(
            fixed("Ctrl/Cmd-Shift-P", "Command palette"), fixed("Cmd-D", "Horizontal split"),
            fixed("Cmd-Shift-D", "Vertical split"), fixed("Cmd-W", "Close active split"),
            new Binding(null, "vim", "built-in", "Vim mode dispatcher", "Profile", "Active; see :help and docs/KEYBINDS.md")
        );
    }

    private static Binding fixed(String lhs, String mapping) {
        return new Binding(null, "profile", lhs, mapping, "Profile", "Active fixed binding");
    }

    static boolean isActionMapping(String value) {
        String mapping = value == null ? "" : value.trim();
        return mapping.startsWith("action:") || mapping.startsWith("command:") || mapping.equalsIgnoreCase("nop") || mapping.equalsIgnoreCase("<nop>");
    }

    static List<String> actionNames() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(List.of("copy", "cut", "paste", "select-all", "settings", "zoom-in", "zoom-out", "zoom-reset"));
        for (PlainKeymap.Action action : PlainKeymap.Action.values()) if (action != PlainKeymap.Action.NONE)
            names.add(action.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        for (EmacsKeymap.Action action : EmacsKeymap.Action.values()) if (action != EmacsKeymap.Action.NONE)
            names.add(action.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        return List.copyOf(names);
    }

    private static String validateActionMapping(String value) {
        String mapping = value.trim();
        if (mapping.indexOf('\n') >= 0 || mapping.indexOf('\r') >= 0 || mapping.indexOf('\0') >= 0) return "mapping must be a single line";
        if (mapping.startsWith("command:")) return mapping.substring(8).isBlank() ? "command is required" : null;
        if (mapping.startsWith("action:") && !actionNames().contains(mapping.substring(7))) return "unknown editor action: " + mapping.substring(7);
        return null;
    }

    static String resolveAction(Map<String, String> config, KeymapProfile profile, String mode, java.awt.event.KeyEvent event) {
        List<String> scopes = profile == KeymapProfile.VIM ? List.of(mode, "vim", "global") : List.of(profile.configValue(), "global");
        for (String scope : scopes) for (String token : ShortcutKey.tokens(event)) {
            String value = config.get("keybind." + scope + "." + token);
            if (value != null && isActionMapping(value)) return value.trim();
        }
        return null;
    }

    private static java.awt.event.KeyEvent event(ShortcutKey key) {
        return new java.awt.event.KeyEvent(KEY_SOURCE, java.awt.event.KeyEvent.KEY_PRESSED,
            0, key.physicalModifiers(), key.code(), java.awt.event.KeyEvent.CHAR_UNDEFINED);
    }

    private static String actionStatus(Key key, Map<Key, String> overlays, Map<String, String> config, KeymapProfile profile) {
        boolean applicable = key.scope().equals("global") || key.scope().equals(profile.configValue())
            || profile == KeymapProfile.VIM && !Set.of("plain", "emacs").contains(key.scope());
        if (!applicable) return "Inactive in " + profile.configValue();
        String mode = SCOPES.contains(key.scope()) && !Set.of("plain", "emacs", "vim", "global").contains(key.scope()) ? key.scope() : "normal";
        String resolved = resolveAction(config, profile, mode, event(ShortcutKey.parse(key.lhs())));
        if (!java.util.Objects.equals(resolved, overlays.get(key))) return "Shadowed by a more specific binding or physical modifier";
        return "Active in " + key.scope() + "; overrides built-in shortcut";
    }

    private static String validateMapping(String rawMapping) {
        String mapping = rawMapping == null ? "" : rawMapping.trim();
        if (mapping.isEmpty()) {
            return null;
        }
        if (mapping.equalsIgnoreCase("nop") || mapping.equalsIgnoreCase("<nop>")) {
            return null;
        }
        int index = 0;
        while (index < mapping.length()) {
            char c = mapping.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '<') {
                int close = mapping.indexOf('>', index + 1);
                if (close < 0) {
                    return "keybinding rhs has an unterminated token";
                }
                try {
                    normalizeToken(mapping.substring(index, close + 1), "keybinding rhs token");
                } catch (IllegalArgumentException error) {
                    return error.getMessage();
                }
                index = close + 1;
                continue;
            }
            if (Character.isISOControl(c)) {
                return "keybinding rhs contains a control character";
            }
            index++;
        }
        return null;
    }

    private static String normalizeToken(String rawToken, String label) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.length() == 1 && token.charAt(0) != '<' && !Character.isWhitespace(token.charAt(0)) && !Character.isISOControl(token.charAt(0))) {
            return token;
        }
        if (!(token.startsWith("<") && token.endsWith(">"))) {
            throw new IllegalArgumentException(label + " must be one printable key or a supported <token>");
        }
        String inner = token.substring(1, token.length() - 1).trim().toLowerCase(Locale.ROOT);
        if (inner.startsWith("c-") && inner.length() > 2) {
            return "<c-" + normalizeToken(inner.substring(2), label) + ">";
        }
        if (Set.of("esc", "enter", "cr", "tab", "space", "bs", "backspace", "del", "delete", "up", "down", "left", "right", "lt").contains(inner)) {
            return "<" + inner + ">";
        }
        throw new IllegalArgumentException(label + " has unsupported token " + token);
    }
}
