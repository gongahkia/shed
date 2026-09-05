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
        if (key.startsWith("landing.")) {
            return "Interface";
        }
        if (key.startsWith("markdown.preview.")) {
            return "Markdown Preview";
        }
        if (key.startsWith("session.")) {
            return "Session";
        }
        if (key.startsWith("terminal.")) {
            return "Terminal";
        }
        if (key.startsWith("snippets.")) {
            return "Snippets";
        }
        if (key.startsWith("workspace.")) {
            return "Workspace";
        }
        if (key.startsWith("lsp.")) {
            return "Language Server";
        }
        if (key.startsWith("debug.")) {
            return "Debug";
        }
        if (key.startsWith("github.")) {
            return "GitHub";
        }
        if (key.startsWith("updates.")) {
            return "Updates";
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
            case "keymap.profile" -> "vim | plain | emacs";
            case "font.size", "terminal.font.size" -> "integer >= 1";
            case "ui.font.size" -> "integer >= 0";
            case "line.numbers" -> "none | absolute | relative | relativeabsolute | hybrid";
            case "multi.selection.max.cursors" -> "integer " + MultiSelectionPolicy.MIN_MAX_CURSORS + ".." + MultiSelectionPolicy.MAX_MAX_CURSORS;
            case "minimap.width" -> "integer >= 40";
            case "limelight.coefficient" -> "number 0.0..1.0";
            case "limelight.paragraph.span" -> "integer >= 0";
            case "recovery.retention.max.entries" -> "integer 1.." + RecoveryJournal.MAX_ENTRIES;
            case "recovery.retention.max.content.bytes" -> "integer 1.." + RecoveryJournal.MAX_CONTENT_BYTES;
            case "backup.directory" -> "string path";
            case "backup.mode" -> "idle | save-only";
            case "project.replace.backup.directory" -> "string path";
            case "project.replace.scope" -> "workspace | current-file";
            case "backup.retention.count" -> "integer 1.." + BackupPolicy.MAX_RETENTION_COUNT;
            case "undo.history.max.entries" -> "integer 1.." + UndoHistoryPolicy.MAX_ENTRIES;
            case "undo.history.max.bytes" -> "integer 1.." + UndoHistoryPolicy.MAX_BYTES;
            case "session.dir" -> "string path";
            case "landing.source" -> "local path, file URI, or HTTPS URL";
            case "landing.remote.cache.path" -> "string path";
            case "landing.remote.timeout.ms" -> "integer 1000..30000";
            case "lsp.completion.delay.ms" -> "integer 0..1000";
            case "updates.metadata.url" -> "HTTPS URL or empty";
            case "updates.metadata.public.key" -> "base64 Ed25519 key or empty";
            case "updates.check.timeout.ms" -> "integer 1000..30000";
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
        if (key.equals("terminal.session.restore")) {
            return "Live: checked when saving or loading a session";
        }
        if (key.equals("terminal.shell.integration")) {
            return "Live: applies to newly opened Bash, Zsh, Fish, and PowerShell terminals";
        }
        if (key.startsWith("terminal.font.")) {
            return "Live: used by newly opened terminals";
        }
        if (key.startsWith("ui.font.")) {
            return "Live: applies to the application UI";
        }
        if (key.startsWith("landing.")) {
            return "Live: used when the landing page next opens";
        }
        if (key.startsWith("markdown.preview.")) {
            return "Live: applies to open Markdown previews";
        }
        if (key.startsWith("workspace.index.")) {
            return "Live: used by subsequent workspace index operations";
        }
        if (key.startsWith("multi.selection.")) {
            return "Live: disabled clears extra cursors; limit applies to new cursors";
        }
        if (key.startsWith("lsp.completion.auto.") || key.startsWith("lsp.completion.delay.")
            || key.startsWith("lsp.completion.trigger.") || key.startsWith("lsp.completion.fuzzy.")
            || key.startsWith("lsp.completion.local.") || key.startsWith("lsp.completion.commit.")) {
            return "Live: used by the next completion request";
        }
        if (key.equals("lsp.format.on.save.enabled")) {
            return "Live: used by the next save request";
        }
        if (key.startsWith("lsp.")) {
            return "Restart: takes effect when an LSP server is started or restarted";
        }
        if (key.startsWith("debug.")) {
            return "Live: checked when explicit debug-session planning begins";
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
        if (key.equals("git.staging.enabled")) {
            return "Live: used by subsequent staging and unstaging commands";
        }
        if (key.equals("git.diffs.enabled")) {
            return "Live: used when loading graphical Git diffs and hunks";
        }
        if (key.equals("git.remote.actions.enabled")) {
            return "Live: checked before each graphical remote action";
        }
        if (key.startsWith("github.")) {
            return "Live: checked before explicit GitHub review actions";
        }
        if (key.startsWith("updates.")) {
            return "Live: checked before update requests; automatic checks run only after explicit consent";
        }
        if (key.startsWith("git.")) {
            return "Live: used when opening graphical Git documents";
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
        if ("backup.mode".equals(key)) {
            try {
                BackupPolicy.BackupMode.parse(value instanceof String ? (String) value : null);
            } catch (IllegalArgumentException error) {
                return error.getMessage();
            }
        }
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
            if (("font.size".equals(key) || "terminal.font.size".equals(key)) && number < 1) {
                return key + " must be at least 1";
            }
            if ("ui.font.size".equals(key) && number < 0) {
                return key + " must be non-negative";
            }
            if (("updates.check.timeout.ms".equals(key) || "landing.remote.timeout.ms".equals(key))
                && (number < 1000 || number > 30000)) {
                return key + " must be between 1000 and 30000";
            }
            if ("lsp.completion.delay.ms".equals(key) && number > 1000) {
                return key + " must be between 0 and 1000";
            }
            if ("multi.selection.max.cursors".equals(key)
                && (number < MultiSelectionPolicy.MIN_MAX_CURSORS || number > MultiSelectionPolicy.MAX_MAX_CURSORS)) {
                return key + " must be between " + MultiSelectionPolicy.MIN_MAX_CURSORS + " and " + MultiSelectionPolicy.MAX_MAX_CURSORS;
            }
            if ("minimap.width".equals(key) && number < 40) {
                return key + " must be at least 40";
            }
            if ("git.auto.refresh.interval.ms".equals(key) && (number < 500 || number > 60000)) {
                return key + " must be between 500 and 60000";
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
            if ("limelight.coefficient".equals(key) && (number < 0.0 || number > 1.0)) {
                return key + " must be between 0.0 and 1.0";
            }
        }
        if ("line.numbers".equals(key) && value instanceof String) {
            String mode = ((String) value).trim().toLowerCase(Locale.ROOT);
            if (!mode.equals("none") && !mode.equals("absolute") && !mode.equals("relative")
                && !mode.equals("relativeabsolute") && !mode.equals("hybrid")) {
                return key + " must be none, absolute, relative, relativeabsolute, or hybrid";
            }
        }
        if ("keymap.profile".equals(key) && value instanceof String
            && !"vim".equalsIgnoreCase(((String) value).trim()) && !"plain".equalsIgnoreCase(((String) value).trim())
            && !"emacs".equalsIgnoreCase(((String) value).trim())) {
            return key + " must be vim, plain, or emacs";
        }
        if (("backup.directory".equals(key) || "project.replace.backup.directory".equals(key)
            || "landing.source".equals(key) || "landing.remote.cache.path".equals(key))
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
