package shed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class KeymapOverlay {
    private static final String PREFIX = "keybind.";
    private static final Set<String> SCOPES = Set.of("normal", "insert", "visual", "visual_line", "replace", "command", "search", "global");

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
            parseKey(rawKey);
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
            throw new IllegalArgumentException("keybinding scope must be normal, insert, visual, visual_line, replace, command, search, or global");
        }
        return new Key(scope, normalizeToken(tail.substring(separator + 1), "keybinding lhs"));
    }

    static List<Binding> effectiveBindings(Map<String, String> config, KeymapProfile profile) {
        Map<Key, String> overlays = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (!isKeybindKey(entry.getKey())) {
                continue;
            }
            try {
                Key key = parseKey(entry.getKey());
                if (validateMapping(entry.getValue()) == null) {
                    overlays.put(key, entry.getValue().trim());
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        List<Binding> bindings = new ArrayList<>();
        for (Map.Entry<Key, String> entry : overlays.entrySet()) {
            Key key = entry.getKey();
            String status = overlayStatus(key, overlays, profile);
            bindings.add(new Binding(key.configKey(), key.scope(), key.lhs(), entry.getValue(), "User overlay", status));
        }
        bindings.addAll(profileBindings(profile));
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
        return text.append("\nPrecedence: scope-specific user overlay, then global user overlay, then the active profile binding.\n")
            .append("Vim dispatch applies user overlays; Plain and Emacs fixed bindings intentionally bypass them.\n").toString();
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
            return "Inactive: " + profile.configValue() + " uses fixed profile bindings";
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
        if (profile == KeymapProfile.PLAIN) {
            return List.of(
                fixed("Ctrl/Cmd-S", "Save current file"), fixed("Ctrl/Cmd-O/P", "Find file"),
                fixed("Ctrl/Cmd-Shift-P", "Command palette"), fixed("Ctrl/Cmd-B", "Buffer picker"),
                fixed("Cmd-D", "Horizontal split"), fixed("Cmd-Shift-D", "Vertical split"),
                fixed("Ctrl/Cmd-W", "Close active split"), fixed("F1", "Plain keymap help")
            );
        }
        if (profile == KeymapProfile.EMACS) {
            return List.of(
                fixed("C-f/C-b/C-n/C-p", "Character and line navigation"), fixed("C-x C-s/C-f/C-b", "Save, find file, buffers"),
                fixed("C-x b/k/C-c", "Buffers, kill buffer, quit"), fixed("M-x", "Command palette"), fixed("C-g", "Cancel prefix"), fixed("C-h/F1", "Emacs keymap help")
            );
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
