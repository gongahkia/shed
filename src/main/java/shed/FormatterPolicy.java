package shed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record FormatterPolicy(String extension, Mode mode, String command, List<String> args, boolean formatOnSave) {
    enum Mode { LSP, EXTERNAL, DISABLED }

    FormatterPolicy {
        extension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        mode = mode == null ? Mode.LSP : mode;
        command = command == null ? "" : command.trim();
        args = args == null ? List.of() : List.copyOf(args);
    }

    static FormatterPolicy resolve(ConfigManager config, String extension) {
        String ext = normalizeExtension(extension);
        String prefix = "formatter." + ext + ".";
        String rawMode = config == null ? null : config.get(prefix + "mode");
        Mode mode = parseMode(rawMode);
        String command = config == null ? "" : config.get(prefix + "command", "");
        String rawArgs = config == null ? "" : config.get(prefix + "args", "");
        String perLanguageSave = config == null ? null : config.get(prefix + "format.on.save");
        boolean onSave = perLanguageSave == null ? config != null && config.getLspFormatOnSaveEnabled() : Boolean.parseBoolean(perLanguageSave);
        return new FormatterPolicy(ext, mode, command, parseArguments(rawArgs), onSave);
    }

    static String validateConfig(String key, String value) {
        if (key == null || !key.startsWith("formatter.")) return null;
        String[] parts = key.split("\\.", -1);
        if (parts.length != 3 && parts.length != 5) return "invalid formatter key " + key;
        if (parts.length == 5 && !("format".equals(parts[2]) && "on".equals(parts[3]) && "save".equals(parts[4]))) {
            return "invalid formatter key " + key;
        }
        if (!parts[1].matches("[A-Za-z0-9_+-]+")) return "formatter extension must use letters, digits, _, +, or -";
        String field = key.substring(("formatter." + parts[1] + ".").length());
        String raw = value == null ? "" : value;
        return switch (field) {
            case "mode" -> validMode(raw) ? null : key + " must be lsp, external, or disabled";
            case "command" -> safeText(raw) ? null : key + " must be a single-line command";
            case "args" -> parseArgumentsOrNull(raw) == null ? key + " contains an invalid quoted argument" : null;
            case "format.on.save" -> "true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw) ? null : key + " must be boolean";
            default -> "invalid formatter key " + key;
        };
    }

    static String normalizeExtension(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        return normalized.matches("[a-z0-9_+-]+") ? normalized : "text";
    }

    private static Mode parseMode(String value) {
        if ("external".equalsIgnoreCase(value == null ? "" : value.trim())) return Mode.EXTERNAL;
        if ("disabled".equalsIgnoreCase(value == null ? "" : value.trim())) return Mode.DISABLED;
        return Mode.LSP;
    }

    private static boolean validMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "lsp".equals(normalized) || "external".equals(normalized) || "disabled".equals(normalized);
    }

    static List<String> parseArguments(String value) {
        List<String> parsed = parseArgumentsOrNull(value);
        return parsed == null ? List.of() : parsed;
    }

    private static List<String> parseArgumentsOrNull(String value) {
        String source = value == null ? "" : value;
        if (source.indexOf('\0') >= 0 || source.indexOf('\n') >= 0 || source.indexOf('\r') >= 0) return null;
        List<String> parsed = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (escaped) { token.append(current); escaped = false; continue; }
            if (current == '\\') { escaped = true; continue; }
            if (quote != 0) {
                if (current == quote) quote = 0;
                else token.append(current);
                continue;
            }
            if (current == '\'' || current == '"') { quote = current; continue; }
            if (Character.isWhitespace(current)) {
                if (!token.isEmpty()) { parsed.add(token.toString()); token.setLength(0); }
                continue;
            }
            token.append(current);
        }
        if (escaped || quote != 0) return null;
        if (!token.isEmpty()) parsed.add(token.toString());
        return List.copyOf(parsed);
    }

    private static boolean safeText(String value) {
        return value != null && value.indexOf('\0') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }
}
