package shed;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;

final class SessionConfigController {
    private final Texteditor editor;
    private final ConfigLiveReloadService configLiveReloadService;
    private String terminalRestoreSummary;

    SessionConfigController(Texteditor editor) {
        this.editor = editor;
        this.configLiveReloadService = new ConfigLiveReloadService(editor.configManager);
        this.terminalRestoreSummary = "";
    }

    public void toggleLineNumbers(boolean enabled) {
        editor.lineNumberMode = enabled ? LineNumberMode.ABSOLUTE : LineNumberMode.NONE;
        editor.configManager.setLineNumberMode(editor.lineNumberMode);
        editor.refreshLineNumberPanel();
    }


    public void setLineNumberMode(LineNumberMode mode) {
        editor.lineNumberMode = mode == null ? LineNumberMode.ABSOLUTE : mode;
        editor.configManager.setLineNumberMode(editor.lineNumberMode);
        editor.refreshLineNumberPanel();
    }


    public String setLineNumberMode(String value) {
        setLineNumberMode(LineNumberMode.fromConfigValue(value));
        return "Line numbers set to " + editor.lineNumberMode.toConfigValue();
    }


    public void setHighlightSearch(boolean enabled) {
        editor.configManager.set("highlight.search", String.valueOf(enabled));
        if (!enabled) {
            editor.searchManager.clearHighlights();
        }
        editor.updateStatusBar();
    }


    public void setAutoIndent(boolean enabled) {
        editor.configManager.set("auto.indent", String.valueOf(enabled));
    }


    public void setWrap(boolean enabled) {
        editor.writingArea.setLineWrap(enabled);
        editor.writingArea.setWrapStyleWord(enabled);
    }


    public void setExpandTab(boolean enabled) {
        editor.configManager.set("expand.tab", String.valueOf(enabled));
    }


    public void setShowCurrentLine(boolean enabled) {
        editor.configManager.set("show.current.line", String.valueOf(enabled));
        editor.updateCurrentLineHighlight();
        editor.refreshLineNumberPanel();
    }


    public String getCurrentThemeName() {
        return editor.configManager.getThemeId();
    }


    public List<String> getThemeIdsForPlugins() {
        return editor.configManager.getThemeIds();
    }


    public Map<String, String> getActiveThemePaletteHex() {
        Map<String, String> palette = new LinkedHashMap<>();
        palette.put("theme", editor.configManager.getThemeId());
        palette.put("color.normal", colorToHex(editor.configManager.getNormalColor()));
        palette.put("color.insert", colorToHex(editor.configManager.getInsertColor()));
        palette.put("color.command", colorToHex(editor.configManager.getCommandColor()));
        palette.put("color.visual", colorToHex(editor.configManager.getVisualColor()));
        palette.put("color.replace", colorToHex(editor.configManager.getReplaceColor()));
        palette.put("ui.foreground", colorToHex(editor.configManager.getEditorForeground()));
        palette.put("ui.caret", colorToHex(editor.configManager.getCaretColor()));
        palette.put("ui.selection", colorToHex(editor.configManager.getSelectionColor()));
        palette.put("ui.selection.text", colorToHex(editor.configManager.getSelectionTextColor()));
        palette.put("ui.status.background", colorToHex(editor.configManager.getStatusBarBackground()));
        palette.put("ui.status.foreground", colorToHex(editor.configManager.getStatusBarForeground()));
        palette.put("ui.command.background", colorToHex(editor.configManager.getCommandBarBackground()));
        palette.put("ui.command.foreground", colorToHex(editor.configManager.getCommandBarForeground()));
        palette.put("ui.linenumber.background", colorToHex(editor.configManager.getLineNumberBackground()));
        palette.put("ui.linenumber.foreground", colorToHex(editor.configManager.getLineNumberForeground()));
        palette.put("ui.currentline", colorToHex(editor.configManager.getCurrentLineHighlightColor()));
        palette.put("ui.syntax.keyword", colorToHex(editor.configManager.getSyntaxKeywordColor()));
        palette.put("ui.syntax.string", colorToHex(editor.configManager.getSyntaxStringColor()));
        palette.put("ui.syntax.comment", colorToHex(editor.configManager.getSyntaxCommentColor()));
        palette.put("ui.syntax.type", colorToHex(editor.configManager.getSyntaxTypeColor()));
        palette.put("ui.syntax.function", colorToHex(editor.configManager.getSyntaxFunctionColor()));
        palette.put("ui.syntax.constant", colorToHex(editor.configManager.getSyntaxConstantColor()));
        palette.put("ui.syntax.annotation", colorToHex(editor.configManager.getSyntaxAnnotationColor()));
        palette.put("ui.syntax.number", colorToHex(editor.configManager.getSyntaxNumberColor()));
        return palette;
    }


    public String resolveCommandAlias(String command) {
        return editor.configManager.resolveCommandAlias(command);
    }


    public String setThemeFromCommand(String value) {
        String appliedTheme = editor.configManager.setTheme(value);
        if (appliedTheme == null) {
            return "Unknown theme: " + value;
        }
        editor.applyThemeColors();
        firePluginEvent("ThemeChange");
        return "Theme set to " + appliedTheme;
    }


    public String applyThemeFromPlugin(String value, boolean persist) {
        String appliedTheme = editor.configManager.setTheme(value);
        if (appliedTheme == null) {
            return "Unknown theme: " + value;
        }
        if (persist) {
            try {
                editor.configManager.setAndPersist("theme", appliedTheme);
            } catch (IOException e) {
                return "Error saving theme: " + e.getMessage();
            }
        }
        editor.applyThemeColors();
        firePluginEvent("ThemeChange");
        return persist ? "Theme set and saved to " + appliedTheme : "Theme set to " + appliedTheme;
    }


    public String applyPaletteOverridesFromPlugin(Map<String, String> overrides, boolean persist) {
        if (overrides == null || overrides.isEmpty()) {
            return "No palette overrides";
        }
        int applied = 0;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String mappedKey = mapPaletteAliasToConfigKey(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (mappedKey == null || value.isEmpty()) {
                continue;
            }
            if (!editor.HEX_COLOR_VALUE_PATTERN.matcher(value).matches()) {
                continue;
            }
            editor.configManager.set(mappedKey, value);
            applied++;
        }
        if (applied == 0) {
            return "No valid palette keys/colors";
        }
        applyRuntimeConfigFromSettings();
        if (persist) {
            try {
                editor.configManager.persistCurrentConfig();
            } catch (IOException e) {
                return "Applied " + applied + " palette key(s), but failed to save: " + e.getMessage();
            }
        }
        firePluginEvent("ThemeChange");
        return (persist ? "Applied and saved " : "Applied ") + applied + " palette key" + (applied == 1 ? "" : "s");
    }


    String mapPaletteAliasToConfigKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "normal": return "color.normal";
            case "insert": return "color.insert";
            case "command": return "color.command";
            case "visual": return "color.visual";
            case "replace": return "color.replace";
            case "foreground": return "ui.foreground";
            case "caret": return "ui.caret";
            case "selection": return "ui.selection";
            case "selection_text":
            case "selectiontext": return "ui.selection.text";
            case "status_bg":
            case "statusbar_bg": return "ui.status.background";
            case "status_fg":
            case "statusbar_fg": return "ui.status.foreground";
            case "command_bg":
            case "commandbar_bg": return "ui.command.background";
            case "command_fg":
            case "commandbar_fg": return "ui.command.foreground";
            case "line_number_bg":
            case "linenumber_bg": return "ui.linenumber.background";
            case "line_number_fg":
            case "linenumber_fg": return "ui.linenumber.foreground";
            case "current_line":
            case "currentline": return "ui.currentline";
            case "syntax_keyword": return "ui.syntax.keyword";
            case "syntax_string": return "ui.syntax.string";
            case "syntax_comment": return "ui.syntax.comment";
            case "syntax_type": return "ui.syntax.type";
            case "syntax_function": return "ui.syntax.function";
            case "syntax_constant": return "ui.syntax.constant";
            case "syntax_annotation": return "ui.syntax.annotation";
            case "syntax_number": return "ui.syntax.number";
            default:
                if (key.startsWith("color.") || key.startsWith("ui.")) {
                    return key;
                }
                return null;
        }
    }


    String colorToHex(Color color) {
        if (color == null) {
            return "#000000";
        }
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }


    public String setConfigOption(String key, String value) {
        if (key == null || key.isEmpty()) {
            return "Error: Missing config key";
        }
        if (editor.configManager.isSchemaVersionKey(key)) {
            return "Error: schema_version is managed by Shed";
        }
        String validationError = editor.configManager.validateSettingValue(key, value);
        if (validationError != null) {
            return "Error: " + validationError;
        }
        editor.configManager.set(key, value == null ? "" : value);
        applyRuntimeConfigFromSettings();
        if (isThemeRelatedConfigKey(key)) {
            firePluginEvent("ThemeChange");
        }
        return "Set " + key;
    }


    public String setConfigOptionPersistent(String key, String value) {
        if (key == null || key.isEmpty()) {
            return "Error: Missing config key";
        }
        if (editor.configManager.isSchemaVersionKey(key)) {
            return "Error: schema_version is managed by Shed";
        }
        String validationError = editor.configManager.validateSettingValue(key, value);
        if (validationError != null) {
            return "Error: " + validationError;
        }
        try {
            editor.configManager.setAndPersist(key, value == null ? "" : value);
            applyRuntimeConfigFromSettings();
            if (isThemeRelatedConfigKey(key)) {
                firePluginEvent("ThemeChange");
            }
            return "Set and saved " + key;
        } catch (IOException e) {
            return "Error saving config: " + e.getMessage();
        }
    }

    public String resetConfigOptionPersistent(String key) {
        if (key == null || key.isBlank()) {
            return "Error: Missing config key";
        }
        try {
            editor.configManager.resetAndPersist(key);
            applyRuntimeConfigFromSettings();
            if (isThemeRelatedConfigKey(key)) {
                firePluginEvent("ThemeChange");
            }
            return "Reset and saved " + key;
        } catch (IOException e) {
            return "Error resetting config: " + e.getMessage();
        }
    }

    String setKeybindingPersistent(String scope, String lhs, String mapping) {
        String key = "keybind." + (scope == null ? "" : scope.trim()) + "." + (lhs == null ? "" : lhs.trim());
        return setConfigOptionPersistent(key, mapping == null ? "" : mapping);
    }

    String resetKeybindingPersistent(String scope, String lhs) {
        try {
            editor.configManager.resetKeybindingAndPersist(scope, lhs);
            return "Reset keybinding keybind." + scope + "." + lhs;
        } catch (IOException e) {
            return "Error resetting keybinding: " + e.getMessage();
        }
    }

    List<KeymapOverlay.Binding> getEffectiveKeybindings() {
        return editor.configManager.effectiveKeybindings();
    }

    String showKeymapInspector() {
        KeymapInspectorDialog.showFor(editor);
        return "Keymap inspector opened";
    }

    String showEffectiveKeybindings(String query) {
        showScratchBuffer("[keymap]", editor.configManager.effectiveKeybindingsText(query));
        return "Showing effective keybindings";
    }


    boolean isThemeRelatedConfigKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("theme")
            || normalized.startsWith("color.")
            || normalized.startsWith("ui.");
    }


    public String saveConfigToDisk() {
        try {
            int persisted = editor.configManager.persistCurrentConfig();
            return "Saved config (" + persisted + " key" + (persisted == 1 ? "" : "s") + ")";
        } catch (IOException e) {
            return "Error saving config: " + e.getMessage();
        }
    }

    public String materializeDefaultConfig(boolean overwrite) {
        try {
            editor.configManager.materializeDefaultConfig(overwrite);
            editor.configManager.reload();
            applyRuntimeConfigFromSettings();
            return "Materialized default configuration";
        } catch (IOException e) {
            return "Error materializing defaults: " + e.getMessage();
        }
    }

    public String showSettingsInspector() {
        SettingsEditorDialog.showFor(editor);
        return "Settings editor opened";
    }

    public String showTypedSettingsReference() {
        showScratchBuffer("[settings reference]", editor.configManager.typedSettingsReference());
        return "Showing typed settings reference";
    }


    public String reloadConfigFromDisk() {
        editor.configManager.reload();
        applyRuntimeConfigFromSettings();
        return configReloadResult();
    }

    String reloadConfigIfChanged() {
        if (!configLiveReloadService.reloadIfChanged()) {
            return null;
        }
        applyRuntimeConfigFromSettings();
        return configReloadResult();
    }


    public String reloadConfigIfSettingsBuffer(FileBuffer buffer) {
        return reloadConfigIfSettingsBuffer(buffer, null, null);
    }


    public String reloadConfigIfSettingsBuffer(FileBuffer buffer, String previousContent, String updatedContent) {
        if (buffer == null || buffer.getFile() == null) {
            return null;
        }
        if (!isSettingsFile(buffer.getFile())) {
            return null;
        }
        boolean canDetectThemeChange = previousContent != null && updatedContent != null;
        boolean themeChangedInFile = canDetectThemeChange && didConfigKeyChange(previousContent, updatedContent, "theme");
        String activeThemeBeforeReload = editor.configManager.getThemeId();
        editor.configManager.reload();
        if (!editor.configManager.hasConfigLoadFailure() && canDetectThemeChange && !themeChangedInFile) {
            editor.configManager.setTheme(activeThemeBeforeReload);
        }
        applyRuntimeConfigFromSettings();
        return configReloadResult();
    }

    private String configReloadResult() {
        if (editor.configManager.hasConfigLoadFailure()) {
            showScratchBuffer("[config recovery]", editor.configManager.getConfigLoadReport());
            return "Configuration rejected; last-known-good configuration remains active";
        }
        return "Settings reloaded";
    }


    boolean isSettingsFile(File file) {
        if (file == null) {
            return false;
        }
        try {
            File settings = new File(editor.configManager.getConfigPath());
            return file.getCanonicalFile().equals(settings.getCanonicalFile());
        } catch (IOException e) {
            return file.getAbsolutePath().equals(new File(editor.configManager.getConfigPath()).getAbsolutePath());
        }
    }


    boolean didConfigKeyChange(String previousContent, String updatedContent, String key) {
        String previousValue = extractConfigValue(previousContent, key);
        String updatedValue = extractConfigValue(updatedContent, key);
        return !Objects.equals(previousValue, updatedValue);
    }


    String extractConfigValue(String content, String key) {
        if (content == null || key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = key.trim();
        String[] lines = content.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String parsedKey = trimmed.substring(0, separator).trim();
            if (!parsedKey.equalsIgnoreCase(normalizedKey)) {
                continue;
            }
            return trimmed.substring(separator + 1).trim();
        }
        return null;
    }


    void applyRuntimeConfigFromSettings() {
        MultiSelectionPolicy multiSelection = editor.configManager.getMultiSelectionPolicy();
        if (!multiSelection.enabled()) {
            editor.clearExtraCursors();
        } else if (editor.extraSelections.size() > multiSelection.maxCursors() - 1) {
            editor.extraSelections.subList(multiSelection.maxCursors() - 1, editor.extraSelections.size()).clear();
            editor.refreshExtraSelectionHighlights();
        }
        editor.lineNumberMode = editor.configManager.getLineNumberMode();
        for (FileBuffer buffer : editor.buffers) {
            buffer.applyUndoHistoryPolicy();
        }
        editor.applyUiFont();
        Font editorFont = editor.resolveEditorFont();
        int tabSize = Math.max(1, editor.configManager.getTabSize());
        for (EditorPane pane : editor.editorPanes) {
            JTextArea area = pane.getTextArea();
            area.setFont(editorFont);
            area.setTabSize(tabSize);
            pane.getScrollPane().getVerticalScrollBar().setUnitIncrement(Math.max(16, area.getFontMetrics(area.getFont()).getHeight()));
        }
        if (!editor.configManager.getHighlightSearch() && editor.searchManager != null) {
            editor.searchManager.clearHighlights();
        }
        editor.applyThemeColors();
        editor.refreshLineNumberPanel();
        editor.updateCurrentLineHighlight();
        if (editor.activeMinimapPanel != null) {
            editor.activeMinimapPanel.setPixelWidth(editor.configManager.getMinimapWidth());
        }
        editor.updateStatusBar();
    }


    public String showThemes() {
        showScratchBuffer("[themes]", editor.configManager.getThemeListText());
        return "Showing themes";
    }


    public String openSettingsBuffer() {
        File settingsFile = new File(editor.configManager.getConfigPath());
        try {
            ensureSettingsFileSeeded(settingsFile);
            editor.openFile(settingsFile);
            return "Opened settings: " + settingsFile.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening settings: " + e.getMessage();
        }
    }


    public String openCommandLogBuffer() {
        try {
            ensureStoreDirectory(editor.commandLogStore);
            if (!editor.commandLogStore.exists()) {
                Files.write(editor.commandLogStore.toPath(),
                    new byte[0],
                    StandardOpenOption.CREATE);
            }
            editor.openFile(editor.commandLogStore);
            return "Opened command log: " + editor.commandLogStore.getAbsolutePath();
        } catch (IOException e) {
            return "Error opening command log: " + e.getMessage();
        }
    }


    public String cleanShedDataFiles() {
        Path root = new File(editor.configManager.getShedDirectoryPath()).toPath();
        if (!Files.exists(root)) {
            return "No Shed data found: " + root.toAbsolutePath();
        }
        int deleted = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.deleteIfExists(path)) {
                    deleted++;
                }
            }
            Files.createDirectories(root);
            editor.recentFiles.clear();
            editor.commandHistory.clear();
            editor.commandHistoryIndex = -1;
            editor.commandHistoryPrefix = "";
            reloadConfigFromDisk();
            return "Cleaned Shed data: " + deleted + " path(s)";
        } catch (IOException e) {
            return "Shed clean failed: " + e.getMessage();
        }
    }


    public String handleSessionCommand(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return "Usage: :session save [name] | load[!] [name] | list";
        }
        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed.toLowerCase() : trimmed.substring(0, split).toLowerCase();
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        switch (subcommand) {
            case "save":
                return saveSession(args);
            case "load":
                return loadSession(args, false);
            case "load!":
                return loadSession(args, true);
            case "list":
                return listSessions();
            default:
                return "Usage: :session save [name] | load[!] [name] | list";
        }
    }


    public String handleWorkspaceProfileCommand(String argument) {
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return "Usage: :workspace save [name] | load[!] [name] | list | index [status|enable|disable|benchmark]";
        }
        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed.toLowerCase(Locale.ROOT) : trimmed.substring(0, split).toLowerCase(Locale.ROOT);
        String args = split < 0 ? "" : trimmed.substring(split + 1).trim();
        if ("index".equals(subcommand)) {
            return handleWorkspaceIndexCommand(args);
        }
        String profileName = args.isBlank() ? defaultWorkspaceProfileName() : sanitizeSessionName(args);
        String sessionName = editor.WORKSPACE_PROFILE_PREFIX + profileName;
        switch (subcommand) {
            case "save":
                return saveSession(sessionName);
            case "load":
                return loadSession(sessionName, false);
            case "load!":
                return loadSession(sessionName, true);
            case "list":
                return listWorkspaceProfiles();
            default:
                return "Usage: :workspace save [name] | load[!] [name] | list | index [status|enable|disable|benchmark]";
        }
    }


    private String handleWorkspaceIndexCommand(String argument) {
        String subcommand = argument == null || argument.isBlank() ? "status" : argument.trim().toLowerCase(Locale.ROOT);
        Path workspaceRoot = workspaceIndexRoot();
        switch (subcommand) {
            case "status":
                return showWorkspaceIndexComparison(workspaceRoot);
            case "enable":
                return setWorkspaceIndexEnabled(true, workspaceRoot);
            case "disable":
                return setWorkspaceIndexEnabled(false, workspaceRoot);
            case "benchmark":
                return benchmarkWorkspaceIndex(workspaceRoot);
            default:
                return "Usage: :workspace index [status|enable|disable|benchmark]";
        }
    }


    private String setWorkspaceIndexEnabled(boolean enabled, Path workspaceRoot) {
        try {
            editor.configManager.setAndPersist("workspace.index.enabled", Boolean.toString(enabled));
            showWorkspaceIndexComparison(workspaceRoot);
            return "Persistent workspace indexing " + (enabled ? "enabled" : "disabled");
        } catch (IOException error) {
            return "Unable to update workspace index preference: " + error.getMessage();
        }
    }


    private String showWorkspaceIndexComparison(Path workspaceRoot) {
        WorkspaceIndexComparison.Report report = workspaceIndexComparison().inspect(editor.configManager.getWorkspaceIndexEnabled(), workspaceRoot);
        showScratchBuffer("[workspace index]", report.format());
        return "Showing workspace index comparison";
    }


    private String benchmarkWorkspaceIndex(Path workspaceRoot) {
        if (workspaceRoot == null) {
            return "Workspace index benchmark requires a file-backed buffer or tree root";
        }
        WorkspaceIndexService.CancellationSource cancellation = new WorkspaceIndexService.CancellationSource();
        int jobId = editor.asyncJobService.submit("workspace index benchmark: " + workspaceRoot,
            token -> {
                token.onCancel(cancellation::cancel);
                return new WorkspaceIndexBenchmark(workspaceIndexService()).measure(workspaceRoot, cancellation);
            },
            (snapshot, report, error) -> {
                if (snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
                    editor.showMessage("Workspace index benchmark cancelled");
                } else if (error != null) {
                    editor.showMessage("Workspace index benchmark failed: " + error.getMessage());
                } else if (report != null) {
                    showScratchBuffer("[workspace index benchmark]", report.format());
                    editor.showMessage("Workspace index benchmark completed");
                }
            });
        return "Started workspace index benchmark job " + jobId;
    }


    boolean canBenchmarkWorkspaceIndex() {
        return workspaceIndexRoot() != null;
    }


    private WorkspaceIndexComparison workspaceIndexComparison() {
        return new WorkspaceIndexComparison(workspaceIndexService());
    }


    private WorkspaceIndexService workspaceIndexService() {
        return new WorkspaceIndexService(Path.of(editor.configManager.getShedDirectoryPath(), "workspace-index"));
    }


    private Path workspaceIndexRoot() {
        FileBuffer current = editor.getCurrentBuffer();
        if (current != null && current.hasFilePath()) {
            File file = new File(current.getFilePath());
            File root = detectProjectTrustRoot(file);
            File directory = root == null ? file.getParentFile() : root;
            return directory == null ? null : directory.toPath();
        }
        return editor.treeRoot != null && editor.treeRoot.isDirectory() ? editor.treeRoot.toPath() : null;
    }


    String saveSession(String nameArgument) {
        File sessionFile = resolveSessionFile(nameArgument);
        File sessionDir = sessionFile.getParentFile();
        if (sessionDir != null && !sessionDir.exists()) {
            sessionDir.mkdirs();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 2);
        payload.put("cwd", new File(".").getAbsolutePath());
        Map<FileBuffer, String> bufferIds = new HashMap<>();
        List<Map<String, Object>> serializedBuffers = serializeSessionBuffers(bufferIds);
        payload.put("buffers", serializedBuffers);
        payload.put("panes", serializeSessionPanes(bufferIds));
        if (editor.configManager.getTerminalSessionRestoreEnabled()) {
            payload.put("terminals", editor.serializeTerminalSessionMetadata());
        }
        payload.put("layout", serializeWindowLayout(editor.windowLayoutRoot));
        payload.put("toolPlacements", editor.workbenchPlacementState.toList());
        payload.put("activePaneIndex", editor.activePaneIndex);
        FileBuffer current = editor.getCurrentBuffer();
        if (current != null) {
            String activeBufferId = bufferIds.get(current);
            if (activeBufferId != null) {
                payload.put("activeBufferId", activeBufferId);
            }
            payload.put("activeCaret", editor.writingArea.getCaretPosition());
        }
        if (editor.treeRoot != null) {
            payload.put("treeRoot", editor.treeRoot.getAbsolutePath());
        }
        payload.put("uiSettings", captureSessionUiSettings());
        payload.put("savedAt", editor.commandLogTimeFormat.format(LocalDateTime.now()));
        try {
            Files.writeString(sessionFile.toPath(),
                MiniJson.stringify(payload),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            return "Session saved: " + sessionFile.getAbsolutePath();
        } catch (IOException e) {
            return "Session save failed: " + e.getMessage();
        }
    }


    String loadSession(String nameArgument, boolean force) {
        File sessionFile = resolveSessionFile(nameArgument);
        if (!sessionFile.exists()) {
            return "Session not found: " + sessionFile.getAbsolutePath();
        }
        if (!force && hasUnsavedChangesInAnyBuffer()) {
            return "Unsaved buffers exist (use :session load! <name>)";
        }

        try {
            String json = Files.readString(sessionFile.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> payload = MiniJson.asObject(MiniJson.parse(json));
            if (payload == null) {
                return "Session file is invalid";
            }
            if (restoreSessionV2(payload)) {
                return "Restored session: " + sessionFile.getAbsolutePath() + terminalRestoreSummary;
            }
            if (restoreLegacySession(payload)) {
                return "Restored legacy session: " + sessionFile.getAbsolutePath();
            }
            editor.openLandingPage();
            return "Session loaded (no existing files)";
        } catch (IOException e) {
            return "Session load failed: " + e.getMessage();
        }
    }


    boolean restoreSessionV2(Map<String, Object> payload) {
        terminalRestoreSummary = "";
        Map<String, FileBuffer> idToBuffer = new HashMap<>();
        List<FileBuffer> restoredBuffers = deserializeSessionBuffers(payload.get("buffers"), idToBuffer);
        TerminalSessionState.ParseResult terminalStates = TerminalSessionState.parseAll(payload.get("terminals"));
        FileBuffer fallback = null;
        if (restoredBuffers.isEmpty() && !terminalStates.states().isEmpty()) {
            fallback = FileBuffer.createScratch("[terminal session]", "Terminal session restore in progress.");
            restoredBuffers.add(fallback);
        }
        if (restoredBuffers.isEmpty()) {
            return false;
        }

        editor.closeAllTerminalSessions();
        editor.specialBufferReturns.clear();
        editor.treeLineTargets.clear();
        editor.treeBuffer = null;
        editor.treePane = null;
        editor.quickfixBuffer = null;
        editor.buffers.clear();
        editor.buffers.addAll(restoredBuffers);

        List<Object> paneObjects = MiniJson.asArray(payload.get("panes"));
        int paneCount = paneObjects == null || paneObjects.isEmpty() ? 1 : paneObjects.size();
        Map<String, Object> layoutObject = MiniJson.asObject(payload.get("layout"));
        resetEditorPanesForSession(paneCount, layoutObject);
        if (editor.editorPanes.isEmpty()) {
            return false;
        }

        FileBuffer defaultBuffer = editor.buffers.get(0);
        for (int i = 0; i < editor.editorPanes.size(); i++) {
            EditorPane pane = editor.editorPanes.get(i);
            FileBuffer paneBuffer = defaultBuffer;
            int caret = 0;
            if (paneObjects != null && i < paneObjects.size()) {
                Map<String, Object> paneState = MiniJson.asObject(paneObjects.get(i));
                if (paneState != null) {
                    String bufferId = MiniJson.asString(paneState.get("bufferId"));
                    Integer paneCaret = MiniJson.asInt(paneState.get("caret"));
                    if (bufferId != null && idToBuffer.containsKey(bufferId)) {
                        paneBuffer = idToBuffer.get(bufferId);
                    }
                    if (paneCaret != null) {
                        caret = Math.max(0, paneCaret);
                    }
                }
            }
            editor.loadBufferIntoPane(pane, paneBuffer, caret);
        }

        Integer activePane = MiniJson.asInt(payload.get("activePaneIndex"));
        int activeIndex = activePane == null ? 0 : Math.max(0, Math.min(activePane, editor.editorPanes.size() - 1));
        editor.activateEditorPane(editor.editorPanes.get(activeIndex));

        String activeBufferId = MiniJson.asString(payload.get("activeBufferId"));
        if (activeBufferId != null && idToBuffer.containsKey(activeBufferId)) {
            editor.loadBufferIntoEditor(idToBuffer.get(activeBufferId));
        }
        Integer activeCaret = MiniJson.asInt(payload.get("activeCaret"));
        if (activeCaret != null) {
            editor.writingArea.setCaretPosition(Math.min(Math.max(0, activeCaret), editor.writingArea.getText().length()));
        }

        terminalRestoreSummary = editor.restoreTerminalSessionMetadata(payload.get("terminals"));
        FileBuffer terminalFallback = fallback;
        if (terminalFallback != null && editor.editorPanes.stream().noneMatch(pane -> pane.getBuffer() == terminalFallback)) {
            editor.buffers.remove(terminalFallback);
        }
        editor.currentBufferIndex = editor.buffers.indexOf(editor.getActivePane().getBuffer());
        if (editor.getActivePane().getTerminalPane() != null) {
            editor.setMode(EditorMode.INSERT);
        }
        editor.updateStatusBar();
        editor.requestActivePaneFocus();

        String savedTreeRoot = MiniJson.asString(payload.get("treeRoot"));
        if (savedTreeRoot != null && !savedTreeRoot.isBlank()) {
            File root = new File(savedTreeRoot);
            editor.treeRoot = root.exists() ? root : null;
        } else {
            editor.treeRoot = null;
        }
        editor.workbenchPlacementState = WorkbenchPlacementState.fromList(payload.get("toolPlacements"));
        applySessionUiSettings(MiniJson.asObject(payload.get("uiSettings")));
        return true;
    }


    boolean restoreLegacySession(Map<String, Object> payload) {
        terminalRestoreSummary = "";
        List<String> filePaths = extractSessionFilePaths(payload.get("files"));
        if (filePaths.isEmpty()) {
            return false;
        }
        String activePath = MiniJson.asString(payload.get("activePath"));
        Integer activeCaret = MiniJson.asInt(payload.get("activeCaret"));
        String savedTreeRoot = MiniJson.asString(payload.get("treeRoot"));

        editor.specialBufferReturns.clear();
        editor.treeLineTargets.clear();
        editor.treeBuffer = null;
        editor.treePane = null;
        editor.quickfixBuffer = null;
        editor.workbenchPlacementState = new WorkbenchPlacementState();
        editor.buffers.clear();

        for (String filePath : filePaths) {
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }
            try {
                editor.buffers.add(new FileBuffer(file, editor.configManager));
            } catch (IOException ignored) {
            }
        }
        if (editor.buffers.isEmpty()) {
            return false;
        }

        resetEditorPanesForSession(1, null);
        FileBuffer primary = editor.buffers.get(0);
        editor.loadBufferIntoPane(editor.editorPanes.get(0), primary, 0);
        FileBuffer target = primary;
        if (activePath != null && !activePath.isBlank()) {
            FileBuffer maybe = editor.findBufferByPath(new File(activePath));
            if (maybe != null) {
                target = maybe;
            }
        }
        editor.loadBufferIntoEditor(target);
        if (activeCaret != null) {
            editor.writingArea.setCaretPosition(Math.min(Math.max(0, activeCaret), editor.writingArea.getText().length()));
        }
        if (savedTreeRoot != null && !savedTreeRoot.isBlank()) {
            File root = new File(savedTreeRoot);
            editor.treeRoot = root.exists() ? root : null;
        } else {
            editor.treeRoot = null;
        }
        return true;
    }


    Map<String, Object> captureSessionUiSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        String[] keys = {
            "theme",
            "minimap",
            "minimap.width",
            "limelight.coefficient",
            "limelight.paragraph.span",
            "ui.whichkey.hints"
        };
        for (String key : keys) {
            String value = editor.configManager.get(key);
            if (value != null) {
                settings.put(key, value);
            }
        }
        return settings;
    }


    void applySessionUiSettings(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            editor.configManager.set(key, value == null ? "" : String.valueOf(value));
        }
        applyRuntimeConfigFromSettings();
    }


    List<Map<String, Object>> serializeSessionBuffers(Map<FileBuffer, String> bufferIds) {
        List<Map<String, Object>> entries = new ArrayList<>();
        int scratchIndex = 1;
        for (FileBuffer buffer : editor.buffers) {
            if (buffer == null || editor.ptyTerminalPanes.containsKey(buffer)) {
                continue;
            }
            String id;
            if (buffer.hasFilePath()) {
                id = "file:" + buffer.getFilePath();
            } else {
                id = "scratch:" + scratchIndex++;
            }
            bufferIds.put(buffer, id);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            if (buffer.hasFilePath()) {
                entry.put("type", "file");
                entry.put("path", buffer.getFilePath());
                entry.put("modified", buffer.isModified());
                if (buffer.isModified()) {
                    entry.put("content", buffer.getContent());
                }
            } else {
                entry.put("type", "scratch");
                entry.put("name", buffer.getDisplayName());
                entry.put("content", buffer.getContent());
                entry.put("modified", buffer.isModified());
            }
            entries.add(entry);
        }
        return entries;
    }


    List<Map<String, Object>> serializeSessionPanes(Map<FileBuffer, String> bufferIds) {
        List<Map<String, Object>> panes = new ArrayList<>();
        for (EditorPane pane : editor.editorPanes) {
            if (pane == null) {
                continue;
            }
            Map<String, Object> state = new LinkedHashMap<>();
            String bufferId = pane.getTerminalPane() == null ? bufferIds.get(pane.getBuffer()) : null;
            if (bufferId != null) {
                state.put("bufferId", bufferId);
            }
            state.put("caret", pane.getTextArea().getCaretPosition());
            panes.add(state);
        }
        return panes;
    }


    List<FileBuffer> deserializeSessionBuffers(Object bufferObject, Map<String, FileBuffer> idToBuffer) {
        List<FileBuffer> restored = new ArrayList<>();
        List<Object> items = MiniJson.asArray(bufferObject);
        if (items == null) {
            return restored;
        }
        for (Object item : items) {
            Map<String, Object> entry = MiniJson.asObject(item);
            if (entry == null) {
                continue;
            }
            String id = MiniJson.asString(entry.get("id"));
            String type = MiniJson.asString(entry.get("type"));
            boolean modified = Boolean.TRUE.equals(entry.get("modified"));
            FileBuffer restoredBuffer = null;
            try {
                if ("file".equals(type)) {
                    String path = MiniJson.asString(entry.get("path"));
                    if (path == null || path.isBlank()) {
                        continue;
                    }
                    File file = new File(path);
                    if (file.exists() && file.isFile()) {
                        restoredBuffer = new FileBuffer(file, editor.configManager);
                    } else {
                        String content = MiniJson.asString(entry.get("content"));
                        if (content != null) {
                            restoredBuffer = new FileBuffer(path, editor.configManager);
                            restoredBuffer.setContent(content, true);
                        }
                    }
                    if (restoredBuffer != null && modified) {
                        String content = MiniJson.asString(entry.get("content"));
                        if (content != null) {
                            restoredBuffer.setContent(content, true);
                        } else {
                            restoredBuffer.setModified(true);
                        }
                    }
                } else if ("scratch".equals(type)) {
                    String name = MiniJson.asString(entry.get("name"));
                    String content = MiniJson.asString(entry.get("content"));
                    restoredBuffer = FileBuffer.createScratch(name == null ? "[scratch]" : name, content == null ? "" : content);
                    restoredBuffer.setModified(modified);
                }
            } catch (IOException ignored) {
            }

            if (restoredBuffer != null) {
                restored.add(restoredBuffer);
                if (id != null && !id.isBlank()) {
                    idToBuffer.put(id, restoredBuffer);
                }
            }
        }
        return restored;
    }


    Map<String, Object> serializeWindowLayout(WindowLayoutNode node) {
        if (node == null) {
            return null;
        }
        Map<String, Object> serialized = new LinkedHashMap<>();
        if (node.isLeaf()) {
            serialized.put("type", "leaf");
            serialized.put("paneIndex", editor.editorPanes.indexOf(node.getPane()));
            return serialized;
        }
        serialized.put("type", "split");
        WindowLayoutNode.Orientation orientation = node.getOrientation();
        serialized.put("orientation", orientation == WindowLayoutNode.Orientation.HORIZONTAL ? "horizontal" : "vertical");
        serialized.put("ratio", node.getRatio());
        serialized.put("first", serializeWindowLayout(node.getFirst()));
        serialized.put("second", serializeWindowLayout(node.getSecond()));
        return serialized;
    }


    WindowLayoutNode deserializeWindowLayout(Map<String, Object> layout, List<EditorPane> panes) {
        if (layout == null || panes == null || panes.isEmpty()) {
            return null;
        }
        String type = MiniJson.asString(layout.get("type"));
        if ("leaf".equals(type)) {
            Integer paneIndex = MiniJson.asInt(layout.get("paneIndex"));
            if (paneIndex == null || paneIndex < 0 || paneIndex >= panes.size()) {
                return WindowLayoutNode.leaf(panes.get(0));
            }
            return WindowLayoutNode.leaf(panes.get(paneIndex));
        }
        if ("split".equals(type)) {
            String orientationRaw = MiniJson.asString(layout.get("orientation"));
            WindowLayoutNode.Orientation orientation = "vertical".equalsIgnoreCase(orientationRaw)
                ? WindowLayoutNode.Orientation.VERTICAL
                : WindowLayoutNode.Orientation.HORIZONTAL;
            double ratio = 0.5;
            Object ratioObject = layout.get("ratio");
            if (ratioObject instanceof Number) {
                ratio = ((Number) ratioObject).doubleValue();
            }
            WindowLayoutNode first = deserializeWindowLayout(MiniJson.asObject(layout.get("first")), panes);
            WindowLayoutNode second = deserializeWindowLayout(MiniJson.asObject(layout.get("second")), panes);
            if (first == null || second == null) {
                return null;
            }
            return WindowLayoutNode.split(orientation, ratio, first, second);
        }
        return null;
    }


    void resetEditorPanesForSession(int paneCount, Map<String, Object> layoutObject) {
        editor.detachActiveDocumentListener();
        editor.editorPanes.clear();
        int totalPanes = Math.max(1, paneCount);
        Dimension size = editor.getSize();
        for (int i = 0; i < totalPanes; i++) {
            editor.editorPanes.add(editor.createEditorPane(size));
        }
        editor.activePaneIndex = 0;
        editor.bindActivePane(editor.editorPanes.get(0));
        WindowLayoutNode restoredLayout = deserializeWindowLayout(layoutObject, editor.editorPanes);
        if (restoredLayout == null) {
            restoredLayout = defaultLayoutForPanes(editor.editorPanes);
        }
        editor.windowLayoutRoot = restoredLayout;
        editor.renderWindowLayout();
        editor.attachActiveDocumentListener();
    }


    WindowLayoutNode defaultLayoutForPanes(List<EditorPane> panes) {
        if (panes == null || panes.isEmpty()) {
            return null;
        }
        WindowLayoutNode root = WindowLayoutNode.leaf(panes.get(0));
        EditorPane splitTarget = panes.get(0);
        for (int i = 1; i < panes.size(); i++) {
            root.splitLeaf(splitTarget, panes.get(i), WindowLayoutNode.Orientation.HORIZONTAL);
            splitTarget = panes.get(i);
        }
        return root;
    }


    List<String> extractSessionFilePaths(Object filesObject) {
        List<String> paths = new ArrayList<>();
        List<Object> files = MiniJson.asArray(filesObject);
        if (files == null) {
            return paths;
        }
        for (Object item : files) {
            String direct = MiniJson.asString(item);
            if (direct != null) {
                paths.add(direct);
                continue;
            }
            Map<String, Object> object = MiniJson.asObject(item);
            if (object == null) {
                continue;
            }
            String path = MiniJson.asString(object.get("path"));
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }


    String listSessions() {
        File dir = new File(editor.configManager.getSessionDirectory());
        if (!dir.exists() || !dir.isDirectory()) {
            return "No sessions";
        }
        File[] files = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null || files.length == 0) {
            return "No sessions";
        }
        java.util.Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        StringBuilder builder = new StringBuilder();
        builder.append("Sessions\n\n");
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                name = name.substring(0, name.length() - ".json".length());
            }
            builder.append(name).append("  ").append(file.getAbsolutePath()).append("\n");
        }
        showScratchBuffer("[sessions]", builder.toString().stripTrailing() + "\n");
        return "Showing sessions";
    }


    String listWorkspaceProfiles() {
        File dir = new File(editor.configManager.getSessionDirectory());
        if (!dir.exists() || !dir.isDirectory()) {
            return "No workspace profiles";
        }
        File[] files = dir.listFiles(file -> file.isFile()
            && file.getName().startsWith(editor.WORKSPACE_PROFILE_PREFIX)
            && file.getName().endsWith(".json"));
        if (files == null || files.length == 0) {
            return "No workspace profiles";
        }
        java.util.Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        StringBuilder builder = new StringBuilder();
        builder.append("Workspace Profiles\n\n");
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                name = name.substring(0, name.length() - ".json".length());
            }
            if (name.startsWith(editor.WORKSPACE_PROFILE_PREFIX)) {
                name = name.substring(editor.WORKSPACE_PROFILE_PREFIX.length());
            }
            builder.append(name).append("  ").append(file.getAbsolutePath()).append("\n");
        }
        showScratchBuffer("[workspace profiles]", builder.toString().stripTrailing() + "\n");
        return "Showing workspace profiles";
    }


    String defaultWorkspaceProfileName() {
        FileBuffer current = editor.getCurrentBuffer();
        if (current != null && current.hasFilePath()) {
            File root = detectProjectTrustRoot(new File(current.getFilePath()));
            if (root != null) {
                String name = root.getName();
                if (name != null && !name.isBlank()) {
                    return sanitizeSessionName(name);
                }
            }
        }
        return "default";
    }


    File resolveSessionFile(String nameArgument) {
        String rawName = nameArgument == null || nameArgument.isBlank()
            ? editor.configManager.getSessionAutoloadName()
            : nameArgument.trim();
        String safeName = sanitizeSessionName(rawName);
        File dir = new File(editor.configManager.getSessionDirectory());
        return new File(dir, safeName + ".json");
    }


    String sanitizeSessionName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "default";
        }
        StringBuilder builder = new StringBuilder(rawName.length());
        for (int i = 0; i < rawName.length(); i++) {
            char c = rawName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        String sanitized = builder.toString().trim();
        return sanitized.isEmpty() ? "default" : sanitized;
    }


    boolean hasUnsavedChangesInAnyBuffer() {
        for (FileBuffer buffer : editor.buffers) {
            if (buffer != null && buffer.isModified()) {
                return true;
            }
        }
        return false;
    }


    void ensureSettingsFileSeeded(File settingsFile) throws IOException {
        if (settingsFile == null) {
            return;
        }
        File parent = settingsFile.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        if (!settingsFile.exists() || settingsFile.length() == 0L) {
            Files.write(settingsFile.toPath(),
                editor.configManager.defaultConfigTemplate().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        }
    }


    public String setTabSizeFromCommand(String value) {
        try {
            int parsed = Math.max(1, Math.min(16, Integer.parseInt(value)));
            editor.configManager.set("tab.size", String.valueOf(parsed));
            for (EditorPane pane : editor.editorPanes) {
                pane.getTextArea().setTabSize(parsed);
            }
            return "Tab size set to " + parsed;
        } catch (NumberFormatException e) {
            return "Invalid tab size: " + value;
        }
    }


    public String gotoLine(int lineNum) {
        try {
            int totalLines = editor.writingArea.getLineCount();
            if (lineNum < 1 || lineNum > totalLines) {
                return "Invalid line number: " + lineNum;
            }

            editor.recordJumpPosition();
            int offset = editor.writingArea.getLineStartOffset(lineNum - 1);
            editor.writingArea.setCaretPosition(offset);
            return "Line " + lineNum;
        } catch (BadLocationException e) {
            return "Error: " + e.getMessage();
        }
    }


    public void showHelp(String topic) {
        String normalized = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("settings") || normalized.equals("config")) {
            showTypedSettingsReference();
            return;
        }
        String helpText = getHelpText(topic);
        openScratchBuffer(topic == null || topic.isEmpty() ? "[help]" : "[help " + topic + "]", helpText, true);
    }


    String getHelpText(String topic) {
        return editor.helpService.getHelpText(topic, editor.VERSION);
    }


    void addToRecentFiles(String filepath) {
        if (filepath == null || filepath.isEmpty()) {
            return;
        }

        editor.recentFiles.remove(filepath);
        editor.recentFiles.add(0, filepath);
        while (editor.recentFiles.size() > 50) {
            editor.recentFiles.remove(editor.recentFiles.size() - 1);
        }
        saveRecentFiles();
    }


    public String showRecentFiles() {
        if (editor.recentFiles.isEmpty()) {
            return "No recent files";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Recent files\n\n");
        for (int i = 0; i < editor.recentFiles.size(); i++) {
            builder.append(i + 1).append(". ").append(editor.recentFiles.get(i)).append("\n");
        }
        builder.append("\nuse :e <path> to reopen a file.");
        openScratchBuffer("[recent files]", builder.toString(), true);
        return "Showing recent files";
    }


    void showBufferListDialog(String list) {
        JTextArea textArea = new JTextArea(list);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(400, 200));

        JOptionPane.showMessageDialog(editor, scrollPane, "Buffer List", JOptionPane.INFORMATION_MESSAGE);
    }


    void handleQuit(boolean force) {
        String message = requestQuit(force);
        if (!"Quitting".equals(message)) {
            editor.showMessage(message);
        }
    }


    int confirmDiscardChanges(String prompt) {
        return JOptionPane.showConfirmDialog(editor,
            prompt,
            "Unsaved Changes",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    }


    public String requestQuit(boolean force) {
        if (closeReturnableScratchBuffer()) {
            return "Returned from scratch buffer";
        }

        FileBuffer buffer = editor.getCurrentBuffer();
        if (DocumentLifecycle.needsDiscardConfirmation(buffer, force)) {
            int result = confirmDiscardChanges("File has unsaved changes. Quit anyway?");
            if (!DocumentLifecycle.discardConfirmed(result)) {
                return "Quit cancelled";
            }
        }

        editor.closeEditor();
        return "Quitting";
    }


    public ConfigManager getConfigManager() {
        return editor.configManager;
    }


    public PluginManager getPluginManager() {
        return editor.pluginManager;
    }


    void firePluginEvent(String event) {
        if (editor.pluginManager == null) return;
        editor.pluginManager.fireEvent(event);
    }


    public String reloadPlugins() {
        editor.pluginManager.reload();
        int count = editor.pluginManager.getPlugins().size();
        return "Reloaded " + count + " plugin(s)";
    }


    public String showPluginList() {
        showScratchBuffer("[plugins]", editor.pluginManager.getPluginListText());
        return "Showing plugins";
    }


    public String showPluginPackages() {
        showScratchBuffer("[plugin packages]", editor.pluginManager.getPackageListText());
        return "Showing plugin packages";
    }


    public String enablePlugin(String name) {
        return editor.pluginManager.enablePlugin(name);
    }


    public String disablePlugin(String name) {
        return editor.pluginManager.disablePlugin(name);
    }


    public String showPluginInfo(String name) {
        String text = editor.pluginManager.getPluginInfoText(name);
        showScratchBuffer("[plugin " + name + "]", text);
        return "Showing plugin info";
    }


    public String showPluginPath() {
        String path = editor.pluginManager.getPluginsDirectoryPath();
        List<String> disabled = editor.pluginManager.listDisabledPlugins();
        StringBuilder sb = new StringBuilder();
        sb.append("Plugin directory: ").append(path).append("\n\n");
        if (!disabled.isEmpty()) {
            sb.append("Disabled plugins:\n");
            for (String d : disabled) sb.append("  ").append(d).append("\n");
        }
        showScratchBuffer("[plugin path]", sb.toString());
        return path;
    }


    public String createAndOpenPlugin(String name) {
        try {
            File file = editor.pluginManager.createPluginFile(name);
            editor.openFile(file);
            editor.pluginManager.reload();
            return "Opened plugin: " + file.getName();
        } catch (IOException e) {
            return "Error creating plugin: " + e.getMessage();
        }
    }


    public String installPluginPackage(String args) {
        return editor.pluginManager.installPackage(args);
    }


    public String updatePluginPackage(String args) {
        return editor.pluginManager.updatePackage(args);
    }


    public String removePluginPackage(String name) {
        return editor.pluginManager.removePackage(name);
    }


    public String pinPluginPackage(String name) {
        return editor.pluginManager.setPackagePinned(name, true);
    }


    public String unpinPluginPackage(String name) {
        return editor.pluginManager.setPackagePinned(name, false);
    }


    public String executeCommand(String cmd) {
        if (editor.commandHandler == null) return "";
        return editor.commandHandler.execute(cmd);
    }


    public String getModeName() {
        return editor.editorState.mode == null ? "normal" : editor.editorState.mode.getDisplayName().toLowerCase();
    }


    public String runUserCommand(String name, String shellCmd) {
        try {
            FileBuffer buf = editor.getCurrentBuffer();
            String filePath = (buf != null && buf.hasFilePath()) ? buf.getFilePath() : "";
            int line = editor.getCurrentLineNumber();
            int col = 0;
            try { col = editor.writingArea.getCaretPosition() - editor.writingArea.getLineStartOffset(editor.getCurrentCaretLine()); } catch (BadLocationException ignored) {}
            String word = editor.getWordAtCaret();
            String selection = editor.writingArea.getSelectedText();
            String interpolated = PluginManager.interpolate(shellCmd, filePath, line, col, word, selection);
            String validationError = editor.validateShellCommand(interpolated);
            if (validationError != null) {
                return validationError;
            }
            File workingDirectory = new File(".");
            if (buf != null && buf.getFile() != null && buf.getFile().getParentFile() != null) {
                workingDirectory = buf.getFile().getParentFile();
            }
            CommandResult result = editor.runExternalCommand(
                ShellCommand.forCommand(interpolated),
                workingDirectory,
                null,
                null,
                editor.configManager.getProcessTimeoutMs(),
                editor.configManager.getProcessOutputMaxBytes(),
                true
            );
            String output = result.stdout == null ? "" : result.stdout.stripTrailing();
            if (result.exitCode != 0) {
                if (!output.isEmpty()) {
                    showScratchBuffer("[" + name + "]", output + "\n");
                }
                return ":" + name + " failed (exit " + result.exitCode + ")";
            }
            if (output.isEmpty()) {
                return ":" + name + " completed";
            }
            showScratchBuffer("[" + name + "]", output + "\n");
            return ":" + name + " completed";
        } catch (Exception e) {
            return "Error running user command: " + e.getMessage();
        }
    }


    public String showUndoHistory() {
        StringBuilder sb = new StringBuilder();
        sb.append("Undo History\n");
        sb.append("=".repeat(40)).append("\n\n");
        javax.swing.undo.UndoManager um = editor.undoManager;
        if (um instanceof BoundedUndoManager bounded) {
            UndoHistoryPolicy policy = bounded.policy();
            sb.append("Retained edits: ").append(bounded.retainedEditCount()).append(" / ")
                .append(policy.maxEntries()).append("\n");
            sb.append("Estimated bytes: ").append(bounded.retainedBytes()).append(" / ")
                .append(policy.maxBytes()).append("\n");
        }
        sb.append("Can undo: ").append(um.canUndo()).append("\n");
        sb.append("Can redo: ").append(um.canRedo()).append("\n\n");
        sb.append("  u     = undo one step\n");
        sb.append("  Ctrl+r = redo one step\n");
        showScratchBuffer("[Undo History]", sb.toString());
        return "Showing undo history";
    }


    public String clearSearchHighlights() {
        editor.searchManager.clearHighlights();
        return "Search highlights cleared";
    }


    public String writeAll() {
        int saved = 0;
        for (FileBuffer buffer : editor.buffers) {
            if (buffer.isModified() && buffer.getFile() != null) {
                try {
                    buffer.save();
                    saved++;
                } catch (Exception e) {
                    return "Error saving " + buffer.getDisplayName() + ": " + e.getMessage();
                }
            }
        }
        editor.persistRecoverySnapshotsSafely();
        return saved + " file(s) written";
    }


    public String quitAll(boolean force) {
        if (!force) {
            for (FileBuffer buffer : editor.buffers) {
                if (DocumentLifecycle.needsDiscardConfirmation(buffer, force)) {
                    int result = confirmDiscardChanges("There are unsaved changes. Quit anyway?");
                    if (!DocumentLifecycle.discardConfirmed(result)) {
                        return "Quit cancelled";
                    }
                    break;
                }
            }
        }
        editor.closeEditor();
        return "Quitting";
    }


    public void showScratchBuffer(String title, String content) {
        openScratchBuffer(title, content, true);
    }


    void openScratchBuffer(String title, String content, boolean returnable) {
        editor.persistCurrentBufferState();

        FileBuffer scratchBuffer = FileBuffer.createScratch(title, content);
        FileBuffer returnBuffer = editor.getCurrentBuffer();
        int returnCaretPosition = editor.writingArea.getCaretPosition();

        editor.buffers.add(scratchBuffer);
        if (returnable && returnBuffer != null) {
            editor.specialBufferReturns.push(new SpecialBufferReturnState(scratchBuffer, returnBuffer, returnCaretPosition));
        }
        editor.loadBufferIntoEditor(scratchBuffer);
    }


    boolean closeReturnableScratchBuffer() {
        FileBuffer current = editor.getCurrentBuffer();
        if (current == null || editor.specialBufferReturns.isEmpty()) {
            return false;
        }

        SpecialBufferReturnState state = editor.specialBufferReturns.peek();
        if (state.scratchBuffer != current) {
            return false;
        }

        editor.specialBufferReturns.pop();
        editor.buffers.remove(current);
        if (current == editor.quickfixBuffer) {
            editor.quickfixBuffer = null;
        }

        int returnIndex = editor.buffers.indexOf(state.returnBuffer);
        if (returnIndex < 0) {
            if (editor.buffers.isEmpty()) {
                editor.openLandingPage();
                return true;
            }
            returnIndex = 0;
        }

        editor.loadBufferIntoEditor(editor.buffers.get(returnIndex));
        editor.writingArea.setCaretPosition(Math.min(state.returnCaretPosition, editor.writingArea.getText().length()));
        return true;
    }


    void loadRecentFiles() {
        editor.recentFiles.clear();
        if (!editor.recentFilesStore.exists()) {
            return;
        }
        try {
            editor.recentFiles.addAll(Files.readAllLines(editor.recentFilesStore.toPath(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }


    void saveRecentFiles() {
        try {
            ensureStoreDirectory(editor.recentFilesStore);
            Files.write(editor.recentFilesStore.toPath(), editor.recentFiles, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }


    void loadTrustedProjectRoots() {
        editor.trustedProjectRoots.clear();
        if (editor.trustedProjectsStore == null || !editor.trustedProjectsStore.exists()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(editor.trustedProjectsStore.toPath(), StandardCharsets.UTF_8)) {
                String normalized = line == null ? "" : line.trim();
                if (!normalized.isEmpty()) {
                    editor.trustedProjectRoots.add(normalized);
                }
            }
        } catch (IOException ignored) {
        }
    }


    void saveTrustedProjectRoots() {
        if (editor.trustedProjectsStore == null) {
            return;
        }
        try {
            ensureStoreDirectory(editor.trustedProjectsStore);
            List<String> roots = new ArrayList<>(editor.trustedProjectRoots);
            Collections.sort(roots);
            Files.write(
                editor.trustedProjectsStore.toPath(),
                roots,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException ignored) {
        }
    }


    boolean ensureProjectTrustForFile(File file) {
        File projectRoot = detectProjectTrustRoot(file);
        if (projectRoot == null) {
            return true;
        }
        String canonicalRoot;
        try {
            canonicalRoot = projectRoot.getCanonicalPath();
        } catch (IOException e) {
            canonicalRoot = projectRoot.getAbsolutePath();
        }
        if (editor.trustedProjectRoots.contains(canonicalRoot)) {
            return true;
        }
        if (!hasProjectLocalExecutionSurface(projectRoot)) {
            return true;
        }

        int result = JOptionPane.showConfirmDialog(
            editor,
            "This project contains local execution surfaces (.shed.toml and/or .shed/plugins).\n"
                + "Trust this project root for local config/plugin behavior?\n"
                + canonicalRoot,
            "Project Trust",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result == JOptionPane.YES_OPTION) {
            editor.trustedProjectRoots.add(canonicalRoot);
            saveTrustedProjectRoots();
            return true;
        }
        return false;
    }


    File detectProjectTrustRoot(File file) {
        if (file == null) {
            return null;
        }
        File cursor = file.isDirectory() ? file : file.getParentFile();
        File firstConfigRoot = null;
        while (cursor != null) {
            File gitDir = new File(cursor, ".git");
            if (gitDir.exists()) {
                return cursor;
            }
            File localConfig = new File(cursor, ".shed.toml");
            if (localConfig.isFile() && firstConfigRoot == null) {
                firstConfigRoot = cursor;
            }
            cursor = cursor.getParentFile();
        }
        return firstConfigRoot;
    }


    boolean hasProjectLocalExecutionSurface(File projectRoot) {
        if (projectRoot == null || !projectRoot.isDirectory()) {
            return false;
        }
        File localConfig = new File(projectRoot, ".shed.toml");
        if (localConfig.isFile()) {
            return true;
        }
        File pluginDir = new File(projectRoot, ".shed/plugins");
        if (!pluginDir.isDirectory()) {
            return false;
        }
        File[] pluginFiles = pluginDir.listFiles(file -> file.isFile()
            && (file.getName().endsWith(".shed") || file.getName().endsWith(".lua")));
        return pluginFiles != null && pluginFiles.length > 0;
    }


    void appendCommandLog(String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        String line = editor.commandLogTimeFormat.format(LocalDateTime.now()) + " " + entry.strip() + "\n";
        try {
            ensureStoreDirectory(editor.commandLogStore);
            Files.write(editor.commandLogStore.toPath(),
                line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }


    void ensureStoreDirectory(File store) throws IOException {
        if (store == null) {
            return;
        }
        File parent = store.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
    }

}
