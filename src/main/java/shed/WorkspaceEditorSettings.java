package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The intentionally small, non-executable editor-settings subset accepted from an imported
 * VS Code workspace.  This is a snapshot made by an explicit workspace import or reload, not a
 * general settings engine or an automatic project-configuration watcher.
 */
final class WorkspaceEditorSettings {
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_LANGUAGE_OVERRIDES = 64;

    private WorkspaceEditorSettings() {
    }

    record Preferences(Integer tabSize, Boolean insertSpaces) {
        static final Preferences EMPTY = new Preferences(null, null);

        Preferences overlay(Preferences later) {
            if (later == null) return this;
            return new Preferences(later.tabSize == null ? tabSize : later.tabSize,
                later.insertSpaces == null ? insertSpaces : later.insertSpaces);
        }

        boolean empty() {
            return tabSize == null && insertSpaces == null;
        }
    }

    record Scope(Preferences defaults, Map<String, Preferences> languages) {
        Scope {
            defaults = defaults == null ? Preferences.EMPTY : defaults;
            languages = languages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(languages));
        }

        Preferences preferencesFor(String languageId) {
            Preferences language = languageId == null ? null : languages.get(languageId.toLowerCase(Locale.ROOT));
            return defaults.overlay(language);
        }
    }

    record Snapshot(Scope workspace, Map<Path, Scope> folders, List<String> diagnostics) {
        Snapshot {
            workspace = workspace == null ? new Scope(Preferences.EMPTY, Map.of()) : workspace;
            folders = folders == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(folders));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        Preferences preferencesFor(Path resource, String languageId) {
            Preferences result = workspace.preferencesFor(languageId);
            Path root = WorkspaceRootResolver.configuredRoot(resource, List.copyOf(folders.keySet()));
            Scope folder = root == null ? null : folders.get(root);
            return folder == null ? result : result.overlay(folder.preferencesFor(languageId));
        }

        boolean empty() {
            return workspace.defaults().empty() && workspace.languages().isEmpty() && folders.isEmpty();
        }
    }

    static Snapshot empty() {
        return new Snapshot(null, Map.of(), List.of());
    }

    static Snapshot read(WorkspaceManifest.Document document) {
        if (document == null || !document.standardVsCodeWorkspace()) return empty();
        List<String> diagnostics = new ArrayList<>();
        Scope workspace = readScope(document.settings(), "workspace settings", diagnostics);
        Map<Path, Scope> folders = new LinkedHashMap<>();
        for (Path root : document.folders()) {
            Scope scope = readFolderScope(root, diagnostics);
            if (!scope.defaults().empty() || !scope.languages().isEmpty()) folders.put(root, scope);
        }
        return new Snapshot(workspace, folders, diagnostics);
    }

    private static Scope readFolderScope(Path root, List<String> diagnostics) {
        Path settings = root.resolve(".vscode").resolve("settings.json");
        try {
            if (!Files.exists(settings)) return new Scope(Preferences.EMPTY, Map.of());
            if (!Files.isRegularFile(settings)) {
                diagnostics.add("Ignored " + settings + ": it is not a regular file");
                return new Scope(Preferences.EMPTY, Map.of());
            }
            if (Files.size(settings) > MAX_FILE_BYTES) {
                diagnostics.add("Ignored " + settings + ": it exceeds 1 MiB");
                return new Scope(Preferences.EMPTY, Map.of());
            }
            return readScope(Jsonc.parseObject(Files.readString(settings, StandardCharsets.UTF_8)), settings.toString(), diagnostics);
        } catch (IOException | RuntimeException error) {
            diagnostics.add("Ignored " + settings + ": " + concise(error));
            return new Scope(Preferences.EMPTY, Map.of());
        }
    }

    private static Scope readScope(Object raw, String source, List<String> diagnostics) {
        Map<String, Object> settings = MiniJson.asObject(raw);
        if (raw == null) return new Scope(Preferences.EMPTY, Map.of());
        if (settings == null) {
            diagnostics.add("Ignored " + source + ": settings must be an object");
            return new Scope(Preferences.EMPTY, Map.of());
        }
        Preferences defaults = values(settings, source, diagnostics);
        Map<String, Preferences> languages = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String language = languageOverride(entry.getKey());
            if (language == null) continue;
            if (languages.size() >= MAX_LANGUAGE_OVERRIDES) {
                diagnostics.add("Ignored language overrides in " + source + ": maximum is " + MAX_LANGUAGE_OVERRIDES);
                break;
            }
            Map<String, Object> override = MiniJson.asObject(entry.getValue());
            if (override == null) {
                diagnostics.add("Ignored " + entry.getKey() + " in " + source + ": override must be an object");
                continue;
            }
            Preferences values = values(override, source + " " + entry.getKey(), diagnostics);
            if (!values.empty()) languages.put(language, values);
        }
        return new Scope(defaults, languages);
    }

    private static Preferences values(Map<String, Object> values, String source, List<String> diagnostics) {
        Integer tabSize = tabSize(values.get("editor.tabSize"), source, diagnostics);
        Boolean insertSpaces = insertSpaces(values.get("editor.insertSpaces"), source, diagnostics);
        return new Preferences(tabSize, insertSpaces);
    }

    private static Integer tabSize(Object value, String source, List<String> diagnostics) {
        if (value == null) return null;
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric) && numeric >= 1 && numeric <= 16 && numeric == Math.rint(numeric)) {
                return (int) numeric;
            }
        }
        diagnostics.add("Ignored editor.tabSize in " + source + ": Shed accepts an integer from 1 through 16");
        return null;
    }

    private static Boolean insertSpaces(Object value, String source, List<String> diagnostics) {
        if (value == null) return null;
        if (value instanceof Boolean enabled) return enabled;
        diagnostics.add("Ignored editor.insertSpaces in " + source + ": Shed accepts only true or false");
        return null;
    }

    private static String languageOverride(String key) {
        if (key == null || key.length() < 3 || key.charAt(0) != '[' || key.charAt(key.length() - 1) != ']') return null;
        String language = key.substring(1, key.length() - 1);
        if (!language.matches("[A-Za-z][A-Za-z0-9_.+-]{0,63}")) return null;
        return language.toLowerCase(Locale.ROOT);
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName()
            : message.replace('\n', ' ').replace('\r', ' ');
    }
}
