package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The intentionally small, non-executable editor-settings subset accepted from an imported
 * VS Code workspace.  This is a snapshot made by an explicit workspace import or reload, not a
 * general settings engine or an automatic project-configuration watcher. `files.exclude` is
 * intentionally consumed only by the Explorer, while `search.exclude` is consumed by workspace
 * search and project replace.
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

    record Indentation(Preferences generic, Preferences language) {
        static final Indentation EMPTY = new Indentation(Preferences.EMPTY, Preferences.EMPTY);

        Indentation {
            generic = generic == null ? Preferences.EMPTY : generic;
            language = language == null ? Preferences.EMPTY : language;
        }
    }

    record Exclusion(String pattern, boolean excluded, PathMatcher matcher) {
        boolean matches(Path relative) {
            if (relative == null) return false;
            if (matchesPattern(relative, pattern)) return true;
            if (pattern.endsWith("/**") && matchesPattern(relative, pattern.substring(0, pattern.length() - 3))) return true;
            for (Path parent = relative.getParent(); parent != null; parent = parent.getParent()) {
                if (matchesPattern(parent, pattern)) return true;
                if (pattern.endsWith("/**") && matchesPattern(parent, pattern.substring(0, pattern.length() - 3))) return true;
            }
            return false;
        }

        private boolean matchesPattern(Path candidate, String candidatePattern) {
            try {
                if (candidatePattern.equals(pattern) && matcher.matches(candidate)) return true;
                if (matcherFor(candidatePattern).matches(candidate)) return true;
                if (candidatePattern.startsWith("**/")) return matcherFor(candidatePattern.substring(3)).matches(candidate);
                return false;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
    }

    record Scope(Preferences defaults, Map<String, Preferences> singleLanguages, Map<String, Preferences> combinedLanguages, List<Exclusion> fileExclusions,
                 List<Exclusion> searchExclusions) {
        Scope {
            defaults = defaults == null ? Preferences.EMPTY : defaults;
            singleLanguages = singleLanguages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(singleLanguages));
            combinedLanguages = combinedLanguages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(combinedLanguages));
            fileExclusions = fileExclusions == null ? List.of() : List.copyOf(fileExclusions);
            searchExclusions = searchExclusions == null ? List.of() : List.copyOf(searchExclusions);
        }

        Preferences preferencesFor(String languageId) {
            return defaults.overlay(languagePreferences(languageId));
        }

        Preferences languagePreferences(String languageId) {
            String key = languageKey(languageId);
            Preferences combined = combinedLanguages.get(key);
            return (combined == null ? Preferences.EMPTY : combined).overlay(singleLanguages.get(key));
        }

        Boolean fileExclusion(Path relative) {
            return exclusion(fileExclusions, relative);
        }

        Boolean searchExclusion(Path relative) {
            return exclusion(searchExclusions, relative);
        }

        private static Boolean exclusion(List<Exclusion> exclusions, Path relative) {
            Boolean result = null;
            for (Exclusion exclusion : exclusions) {
                if (exclusion.matches(relative)) result = exclusion.excluded();
            }
            return result;
        }
    }

    record Snapshot(Scope workspace, List<Path> roots, Map<Path, Scope> folders, List<String> diagnostics) {
        Snapshot {
            workspace = workspace == null ? new Scope(Preferences.EMPTY, Map.of(), Map.of(), List.of(), List.of()) : workspace;
            roots = roots == null ? List.of() : List.copyOf(roots);
            folders = folders == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(folders));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        Preferences preferencesFor(Path resource, String languageId) {
            Indentation indentation = indentationFor(resource, languageId);
            return indentation.generic().overlay(indentation.language());
        }

        Indentation indentationFor(Path resource, String languageId) {
            Path root = WorkspaceRootResolver.configuredRoot(resource, roots);
            if (root == null) return Indentation.EMPTY;
            Preferences generic = workspace.defaults();
            Preferences language = workspace.languagePreferences(languageId);
            Scope folder = root == null ? null : folders.get(root);
            if (folder != null) {
                generic = generic.overlay(folder.defaults());
                language = language.overlay(folder.languagePreferences(languageId));
            }
            return new Indentation(generic, language);
        }

        boolean excluded(Path resource) {
            Path root = WorkspaceRootResolver.configuredRoot(resource, roots);
            if (root == null) return false;
            Path relative;
            try {
                relative = root.relativize(resource.toAbsolutePath().normalize());
            } catch (IllegalArgumentException error) {
                return false;
            }
            Boolean excluded = workspace.fileExclusion(relative);
            Scope folder = folders.get(root);
            Boolean folderValue = folder == null ? null : folder.fileExclusion(relative);
            return folderValue != null ? folderValue : Boolean.TRUE.equals(excluded);
        }

        boolean searchExcluded(Path resource) {
            Path root = WorkspaceRootResolver.configuredRoot(resource, roots);
            if (root == null) return false;
            Path relative;
            try {
                relative = root.relativize(resource.toAbsolutePath().normalize());
            } catch (IllegalArgumentException error) {
                return false;
            }
            Boolean excluded = workspace.searchExclusion(relative);
            Scope folder = folders.get(root);
            Boolean folderValue = folder == null ? null : folder.searchExclusion(relative);
            return folderValue != null ? folderValue : Boolean.TRUE.equals(excluded);
        }

        boolean empty() {
            return workspace.defaults().empty() && workspace.singleLanguages().isEmpty() && workspace.combinedLanguages().isEmpty() && workspace.fileExclusions().isEmpty()
                && workspace.searchExclusions().isEmpty() && folders.isEmpty();
        }
    }

    static Snapshot empty() {
        return new Snapshot(null, List.of(), Map.of(), List.of());
    }

    private static String languageKey(String languageId) {
        return languageId == null ? "" : languageId.toLowerCase(Locale.ROOT);
    }

    static Snapshot read(WorkspaceManifest.Document document) {
        if (document == null || !document.standardVsCodeWorkspace()) return empty();
        List<String> diagnostics = new ArrayList<>();
        Scope workspace = readScope(document.settings(), "workspace settings", diagnostics);
        Map<Path, Scope> folders = new LinkedHashMap<>();
        for (Path root : document.folders()) {
            Scope scope = readFolderScope(root, diagnostics);
            if (!scope.defaults().empty() || !scope.singleLanguages().isEmpty() || !scope.combinedLanguages().isEmpty() || !scope.fileExclusions().isEmpty()
                || !scope.searchExclusions().isEmpty()) folders.put(root, scope);
        }
        return new Snapshot(workspace, document.folders(), folders, diagnostics);
    }

    private static Scope readFolderScope(Path root, List<String> diagnostics) {
        Path settings = root.resolve(".vscode").resolve("settings.json");
        try {
            if (!Files.exists(settings)) return new Scope(Preferences.EMPTY, Map.of(), Map.of(), List.of(), List.of());
            if (!Files.isRegularFile(settings)) {
                diagnostics.add("Ignored " + settings + ": it is not a regular file");
                return new Scope(Preferences.EMPTY, Map.of(), Map.of(), List.of(), List.of());
            }
            if (Files.size(settings) > MAX_FILE_BYTES) {
                diagnostics.add("Ignored " + settings + ": it exceeds 1 MiB");
                return new Scope(Preferences.EMPTY, Map.of(), Map.of(), List.of(), List.of());
            }
            return readScope(Jsonc.parseObject(Files.readString(settings, StandardCharsets.UTF_8)), settings.toString(), diagnostics);
        } catch (IOException | RuntimeException error) {
            diagnostics.add("Ignored " + settings + ": " + concise(error));
            return new Scope(Preferences.EMPTY, Map.of(), Map.of(), List.of(), List.of());
        }
    }

    private static Scope readScope(Object raw, String source, List<String> diagnostics) {
        Map<String, Object> settings = MiniJson.asObject(raw);
        if (raw == null) return new Scope(Preferences.EMPTY, Map.of(), Map.of(), List.of(), List.of());
        if (settings == null) {
            diagnostics.add("Ignored " + source + ": settings must be an object");
            return new Scope(Preferences.EMPTY, Map.of(), Map.of(), List.of(), List.of());
        }
        Preferences defaults = values(settings, source, diagnostics);
        Map<String, Preferences> singleLanguages = new LinkedHashMap<>();
        Map<String, Preferences> combinedLanguages = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            List<String> languages = languageOverrides(entry.getKey());
            if (languages.isEmpty()) continue;
            Set<String> configured = new LinkedHashSet<>(singleLanguages.keySet());
            configured.addAll(combinedLanguages.keySet());
            long additions = languages.stream().filter(language -> !configured.contains(language)).count();
            if (configured.size() + additions > MAX_LANGUAGE_OVERRIDES) {
                diagnostics.add("Ignored language overrides in " + source + ": maximum is " + MAX_LANGUAGE_OVERRIDES);
                break;
            }
            Map<String, Object> override = MiniJson.asObject(entry.getValue());
            if (override == null) {
                diagnostics.add("Ignored " + entry.getKey() + " in " + source + ": override must be an object");
                continue;
            }
            Preferences values = values(override, source + " " + entry.getKey(), diagnostics);
            if (!values.empty()) {
                Map<String, Preferences> target = languages.size() == 1 ? singleLanguages : combinedLanguages;
                for (String language : languages) target.put(language, values);
            }
        }
        return new Scope(defaults, singleLanguages, combinedLanguages, exclusions(settings.get("files.exclude"), "files.exclude", source, diagnostics),
            exclusions(settings.get("search.exclude"), "search.exclude", source, diagnostics));
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

    private static List<Exclusion> exclusions(Object value, String setting, String source, List<String> diagnostics) {
        if (value == null) return List.of();
        Map<String, Object> entries = MiniJson.asObject(value);
        if (entries == null) {
            diagnostics.add("Ignored " + setting + " in " + source + ": Shed accepts an object of boolean glob values");
            return List.of();
        }
        List<Exclusion> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            if (result.size() >= 100) {
                diagnostics.add("Ignored " + setting + " entries in " + source + ": maximum is 100");
                break;
            }
            String pattern = entry.getKey();
            if (!validPattern(pattern)) {
                diagnostics.add("Ignored " + setting + " pattern in " + source + ": patterns must be relative slash-separated globs of at most 512 characters");
                continue;
            }
            if (!(entry.getValue() instanceof Boolean enabled)) {
                diagnostics.add("Ignored " + setting + " '" + pattern + "' in " + source + ": only boolean values are supported");
                continue;
            }
            try {
                result.add(new Exclusion(pattern, enabled, matcherFor(pattern)));
            } catch (IllegalArgumentException error) {
                diagnostics.add("Ignored " + setting + " '" + pattern + "' in " + source + ": invalid glob syntax");
            }
        }
        return List.copyOf(result);
    }

    private static boolean validPattern(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 512 || pattern.startsWith("/") || pattern.indexOf('\\') >= 0
            || pattern.indexOf('\0') >= 0 || pattern.indexOf('\n') >= 0 || pattern.indexOf('\r') >= 0) return false;
        for (String segment : pattern.split("/", -1)) {
            if ("..".equals(segment)) return false;
        }
        return true;
    }

    private static PathMatcher matcherFor(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    private static List<String> languageOverrides(String key) {
        if (key == null || key.length() < 3 || key.charAt(0) != '[' || key.charAt(key.length() - 1) != ']') return List.of();
        List<String> languages = new ArrayList<>();
        for (int index = 0; index < key.length();) {
            if (key.charAt(index) != '[') return List.of();
            int close = key.indexOf(']', index + 1);
            if (close < 0) return List.of();
            String language = key.substring(index + 1, close);
            if (!language.matches("[A-Za-z][A-Za-z0-9_.+-]{0,63}")) return List.of();
            if (!languages.add(language.toLowerCase(Locale.ROOT))) return List.of();
            index = close + 1;
        }
        return List.copyOf(languages);
    }

    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName()
            : message.replace('\n', ' ').replace('\r', ' ');
    }
}
