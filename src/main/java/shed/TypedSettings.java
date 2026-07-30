package shed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TypedSettings {
    private final Map<String, Object> defaults = new LinkedHashMap<>();
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();

    void clearDefaults() {
        defaults.clear();
        values.clear();
        descriptions.clear();
    }

    void define(String key, Object value, String description) {
        defaults.put(key, value);
        descriptions.put(key, description);
    }

    void reset() {
        values.clear();
        values.putAll(defaults);
    }

    boolean knows(String key) {
        return defaults.containsKey(key);
    }

    String defaultValue(String key) {
        Object value = defaults.get(key);
        return value == null ? null : stringify(value);
    }

    Set<String> keys() {
        return Set.copyOf(defaults.keySet());
    }

    List<Descriptor> descriptors() {
        List<Descriptor> descriptors = new ArrayList<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            descriptors.add(new Descriptor(key, category(key), type(entry.getValue()),
                stringify(values.get(key)), stringify(entry.getValue()), descriptions.getOrDefault(key, ""),
                allowedValues(key, entry.getValue()), applyBehavior(key)));
        }
        descriptors.sort(java.util.Comparator.comparing(Descriptor::key));
        return descriptors;
    }

    List<Descriptor> search(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return descriptors();
        }
        String[] terms = normalized.split("\\s+");
        List<Descriptor> matches = new ArrayList<>();
        for (Descriptor descriptor : descriptors()) {
            String haystack = (descriptor.key() + " " + descriptor.category() + " " + descriptor.type() + " "
                + descriptor.currentValue() + " " + descriptor.defaultValue() + " " + descriptor.description() + " "
                + descriptor.allowedValues() + " " + descriptor.applyBehavior())
                .toLowerCase(Locale.ROOT);
            boolean matched = true;
            for (String term : terms) {
                if (!haystack.contains(term)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                matches.add(descriptor);
            }
        }
        return matches;
    }

    String validateToml(String key, Object value) {
        if (!knows(key)) {
            return value instanceof String ? null : key + " must be a TOML string";
        }
        Object defaultValue = defaults.get(key);
        if (defaultValue instanceof Boolean && !(value instanceof Boolean)) {
            return key + " must be TOML boolean";
        }
        if (defaultValue instanceof Integer && !(value instanceof Long)) {
            return key + " must be TOML integer";
        }
        if (defaultValue instanceof Long && !(value instanceof Long)) {
            return key + " must be TOML integer";
        }
        if (defaultValue instanceof Double && !(value instanceof Long || value instanceof Double)) {
            return key + " must be TOML number";
        }
        if (defaultValue instanceof String && !(value instanceof String)) {
            return key + " must be TOML string";
        }
        return valueError(key, value);
    }

    String validateRuntime(String key, String value) {
        if (!knows(key)) {
            return null;
        }
        Object defaultValue = defaults.get(key);
        try {
            Object typed;
            if (defaultValue instanceof Boolean) {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    return key + " must be boolean";
                }
                typed = Boolean.parseBoolean(value);
            } else if (defaultValue instanceof Integer) {
                typed = Long.parseLong(value);
            } else if (defaultValue instanceof Long) {
                typed = Long.parseLong(value);
            } else if (defaultValue instanceof Double) {
                typed = Double.parseDouble(value);
            } else {
                typed = value;
            }
            return valueError(key, typed);
        } catch (NumberFormatException error) {
            return key + " must be " + (defaultValue instanceof Double ? "number" : "integer");
        }
    }

    void apply(String key, Object value) {
        if (knows(key)) {
            values.put(key, coerce(value, defaults.get(key)));
        }
    }

    void applyRuntime(String key, String value) {
        if (!knows(key)) {
            return;
        }
        Object defaultValue = defaults.get(key);
        if (defaultValue instanceof Boolean) {
            values.put(key, Boolean.parseBoolean(value));
        } else if (defaultValue instanceof Integer || defaultValue instanceof Long) {
            values.put(key, coerce(Long.parseLong(value), defaultValue));
        } else if (defaultValue instanceof Double) {
            values.put(key, Double.parseDouble(value));
        } else {
            values.put(key, value);
        }
    }

    boolean booleanValue(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    int intValue(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    long longValue(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : fallback;
    }

    double doubleValue(String key, double fallback) {
        Object value = values.get(key);
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        }
        if (value instanceof Long) {
            return ((Long) value).doubleValue();
        }
        return fallback;
    }

    String stringValue(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    String stringify(Object value) {
        return String.valueOf(value);
    }

    Object activeValue(String key) {
        return values.get(key);
    }

    Map<String, Object> copyValues() {
        return new LinkedHashMap<>(values);
    }

    void restoreValues(Map<String, Object> snapshot) {
        values.clear();
        values.putAll(snapshot);
    }

    private String category(String key) {
        if (key.equals("theme") || key.startsWith("font.")) {
            return "Appearance";
        }
        if (key.startsWith("ui.")) {
            return "Interface";
        }
        if (key.startsWith("session.")) {
            return "Session";
        }
        if (key.startsWith("workspace.")) {
            return "Workspace";
        }
        if (key.startsWith("lsp.")) {
            return "Language Server";
        }
        if (key.startsWith("git.")) {
            return "Git";
        }
        if (key.startsWith("recovery.")) {
            return "Reliability";
        }
        if (key.startsWith("backup.")) {
            return "Reliability";
        }
        if (key.startsWith("undo.")) {
            return "Reliability";
        }
        if (key.startsWith("large.") || key.startsWith("process.") || key.startsWith("shell.")) {
            return "Performance";
        }
        if (key.startsWith("project.") || key.startsWith("tree.")) {
            return "Project & Safety";
        }
        return "Editor";
    }

    private String type(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Double) {
            return "number";
        }
        return "string";
    }

    private String allowedValues(String key, Object value) {
        if (value instanceof Boolean) {
            return "true | false";
        }
        return switch (key) {
            case "tab.size" -> "integer 1..16";
            case "font.size" -> "integer >= 1";
            case "line.numbers" -> "none | absolute | relative | relativeabsolute | hybrid";
            case "multi.selection.max.cursors" -> "integer " + MultiSelectionPolicy.MIN_MAX_CURSORS + ".." + MultiSelectionPolicy.MAX_MAX_CURSORS;
            case "ui.dramatic.sound.volume" -> "integer 0..100";
            case "ui.dramatic.performance.cpu.threshold" -> "number 0.1..1.0";
            case "ui.dramatic.performance.line.threshold" -> "integer >= 1000";
            case "ui.dramatic.animation.ms" -> "integer >= 80";
            case "ui.dramatic.minimap.width" -> "integer >= 40";
            case "recovery.retention.max.entries" -> "integer 1.." + RecoveryJournal.MAX_ENTRIES;
            case "recovery.retention.max.content.bytes" -> "integer 1.." + RecoveryJournal.MAX_CONTENT_BYTES;
            case "backup.directory" -> "string path";
            case "project.replace.backup.directory" -> "string path";
            case "project.replace.scope" -> "workspace | current-file";
            case "backup.retention.count" -> "integer 1.." + BackupPolicy.MAX_RETENTION_COUNT;
            case "undo.history.max.entries" -> "integer 1.." + UndoHistoryPolicy.MAX_ENTRIES;
            case "undo.history.max.bytes" -> "integer 1.." + UndoHistoryPolicy.MAX_BYTES;
            case "session.dir" -> "string path";
            default -> value instanceof Integer || value instanceof Long ? "integer >= 0"
                : value instanceof Double ? "number >= 0" : "string";
        };
    }

    private String applyBehavior(String key) {
        if (key.equals("session.restore.on.start") || key.equals("session.autoload")) {
            return "Restart: read at next startup";
        }
        if (key.equals("session.dir")) {
            return "Live: used by future session operations";
        }
        if (key.startsWith("workspace.index.")) {
            return "Live: used by subsequent workspace index operations";
        }
        if (key.startsWith("multi.selection.")) {
            return "Live: disabled clears extra cursors; limit applies to new cursors";
        }
        if (key.startsWith("lsp.")) {
            return "Restart: takes effect when an LSP server is started or restarted";
        }
        if (key.startsWith("large.")) {
            return "Live: used when opening files";
        }
        if (key.startsWith("recovery.")) {
            return "Live: used by subsequent recovery operations";
        }
        if (key.startsWith("backup.")) {
            return "Live: used by subsequent backup operations";
        }
        if (key.startsWith("undo.")) {
            return "Live: trims existing undo and redo history";
        }
        if (key.startsWith("process.") || key.startsWith("shell.")) {
            return "Live: used by new helper commands";
        }
        if (key.startsWith("project.")) {
            return "Live: used when project config is next evaluated";
        }
        if (key.startsWith("tree.")) {
            return "Live: used by subsequent tree operations";
        }
        if (key.startsWith("git.")) {
            return "Live: used when opening or refreshing the Git workbench";
        }
        if (key.equals("minimap")) {
            return "Live: stored; :minimap controls visibility";
        }
        return "Live: applied immediately and on config reload";
    }

    private Object coerce(Object value, Object defaultValue) {
        if (defaultValue instanceof Integer && value instanceof Long) {
            return Math.toIntExact((Long) value);
        }
        if (defaultValue instanceof Double && value instanceof Long) {
            return ((Long) value).doubleValue();
        }
        return value;
    }

    private String valueError(String key, Object value) {
        if (value instanceof Long) {
            long number = (Long) value;
            if (number < 0) {
                return key + " must be non-negative";
            }
            if (defaults.get(key) instanceof Integer && number > Integer.MAX_VALUE) {
                return key + " must be at most " + Integer.MAX_VALUE;
            }
            if ("tab.size".equals(key) && (number < 1 || number > 16)) {
                return key + " must be between 1 and 16";
            }
            if ("font.size".equals(key) && number < 1) {
                return key + " must be at least 1";
            }
            if ("multi.selection.max.cursors".equals(key)
                && (number < MultiSelectionPolicy.MIN_MAX_CURSORS || number > MultiSelectionPolicy.MAX_MAX_CURSORS)) {
                return key + " must be between " + MultiSelectionPolicy.MIN_MAX_CURSORS + " and " + MultiSelectionPolicy.MAX_MAX_CURSORS;
            }
            if ("ui.dramatic.sound.volume".equals(key) && number > 100) {
                return key + " must be between 0 and 100";
            }
            if ("ui.dramatic.animation.ms".equals(key) && number < 80) {
                return key + " must be at least 80";
            }
            if ("ui.dramatic.minimap.width".equals(key) && number < 40) {
                return key + " must be at least 40";
            }
            if ("ui.dramatic.performance.line.threshold".equals(key) && number < 1000) {
                return key + " must be at least 1000";
            }
            if ("recovery.retention.max.entries".equals(key) && (number < 1 || number > RecoveryJournal.MAX_ENTRIES)) {
                return key + " must be between 1 and " + RecoveryJournal.MAX_ENTRIES;
            }
            if ("recovery.retention.max.content.bytes".equals(key) && (number < 1 || number > RecoveryJournal.MAX_CONTENT_BYTES)) {
                return key + " must be between 1 and " + RecoveryJournal.MAX_CONTENT_BYTES;
            }
            if ("backup.retention.count".equals(key) && (number < 1 || number > BackupPolicy.MAX_RETENTION_COUNT)) {
                return key + " must be between 1 and " + BackupPolicy.MAX_RETENTION_COUNT;
            }
            if ("undo.history.max.entries".equals(key) && (number < 1 || number > UndoHistoryPolicy.MAX_ENTRIES)) {
                return key + " must be between 1 and " + UndoHistoryPolicy.MAX_ENTRIES;
            }
            if ("undo.history.max.bytes".equals(key) && (number < 1 || number > UndoHistoryPolicy.MAX_BYTES)) {
                return key + " must be between 1 and " + UndoHistoryPolicy.MAX_BYTES;
            }
        }
        if (value instanceof Double) {
            double number = (Double) value;
            if (!Double.isFinite(number)) {
                return key + " must be finite";
            }
            if ("ui.dramatic.performance.cpu.threshold".equals(key) && (number < 0.1 || number > 1.0)) {
                return key + " must be between 0.1 and 1.0";
            }
        }
        if ("line.numbers".equals(key) && value instanceof String) {
            String mode = ((String) value).trim().toLowerCase(Locale.ROOT);
            if (!mode.equals("none") && !mode.equals("absolute") && !mode.equals("relative")
                && !mode.equals("relativeabsolute") && !mode.equals("hybrid")) {
                return key + " must be none, absolute, relative, relativeabsolute, or hybrid";
            }
        }
        if (("backup.directory".equals(key) || "project.replace.backup.directory".equals(key))
            && value instanceof String && ((String) value).isBlank()) {
            return key + " must be a non-empty path";
        }
        if ("project.replace.scope".equals(key) && value instanceof String
            && !"workspace".equals(value) && !"current-file".equals(value)) {
            return key + " must be workspace or current-file";
        }
        return null;
    }

    record Descriptor(String key, String category, String type, String currentValue, String defaultValue, String description,
                      String allowedValues, String applyBehavior) {
    }
}
