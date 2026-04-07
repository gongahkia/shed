package shed;

// Config Manager Class
// Loads and manages user configuration from ~/.shed/shedrc

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.awt.Color;
import java.awt.Toolkit;

public class ConfigManager {
    private final Map<String, String> config;
    private final Map<String, String> defaultConfig;
    private final Map<String, String> persistedConfig;
    private final Map<String, String> projectConfig;
    private final Map<String, String> projectPreviousValues;
    private File activeProjectConfigFile;
    private final String shedDirectoryPath;
    private String configPath;

    // Default configuration values
    private static final String DEFAULT_THEME = "one-dark-pro";
    private static final String DEFAULT_COLOR_NORMAL = "#282C34";
    private static final String DEFAULT_COLOR_INSERT = "#2C323C";
    private static final String DEFAULT_COLOR_COMMAND = "#3A3F4B";
    private static final String DEFAULT_COLOR_VISUAL = "#313A46";
    private static final String DEFAULT_COLOR_REPLACE = "#4B2F3A";
    private static final String DEFAULT_FONT_FAMILY = "Hack";
    private static final int DEFAULT_FONT_SIZE = 16;
    private static final int DEFAULT_TAB_SIZE = 4;
    private static final LineNumberMode DEFAULT_LINE_NUMBER_MODE = LineNumberMode.ABSOLUTE;
    private static final boolean DEFAULT_SHOW_CURRENT_LINE = true;
    private static final boolean DEFAULT_EXPAND_TAB = true;
    private static final boolean DEFAULT_AUTO_INDENT = true;
    private static final boolean DEFAULT_HIGHLIGHT_SEARCH = true;
    private static final int DEFAULT_ZEN_MODE_WIDTH = 80;
    private static final boolean DEFAULT_SESSION_RESTORE_ON_START = false;
    private static final String DEFAULT_SESSION_AUTOLOAD = "default";
    private static final long DEFAULT_LARGE_FILE_THRESHOLD_MB = 100L;
    private static final int DEFAULT_LARGE_FILE_LINE_THRESHOLD = 50000;
    private static final int DEFAULT_LARGE_FILE_PREVIEW_LINES = 1000;
    private static final int DEFAULT_PROCESS_TIMEOUT_MS = 15000;
    private static final int DEFAULT_PROCESS_OUTPUT_MAX_BYTES = 1024 * 1024;
    private static final int DEFAULT_SHELL_COMMAND_MAX_LENGTH = 4096;
    private static final int DEFAULT_SCROLLOFF = 0;
    private static final boolean DEFAULT_AUTO_PAIRS = true;
    private static final int DEFAULT_TEXTWIDTH = 0;
    private static final boolean DEFAULT_MINIMAP = false;
    private static final boolean DEFAULT_DRAMATIC_UI = false;
    private static final boolean DEFAULT_DRAMATIC_IDENTITY = true;
    private static final boolean DEFAULT_DRAMATIC_MODE_TRANSITIONS = true;
    private static final boolean DEFAULT_DRAMATIC_COMMAND_PALETTE = true;
    private static final boolean DEFAULT_DRAMATIC_EDITING_FEEDBACK = true;
    private static final boolean DEFAULT_DRAMATIC_PANEL_ANIMATIONS = true;
    private static final boolean DEFAULT_DRAMATIC_SOUND = false;
    private static final String DEFAULT_DRAMATIC_SOUND_PACK = "default";
    private static final int DEFAULT_DRAMATIC_SOUND_VOLUME = 75;
    private static final boolean DEFAULT_DRAMATIC_SOUND_CUE_MODE = true;
    private static final boolean DEFAULT_DRAMATIC_SOUND_CUE_NAVIGATE = true;
    private static final boolean DEFAULT_DRAMATIC_SOUND_CUE_SUCCESS = true;
    private static final boolean DEFAULT_DRAMATIC_SOUND_CUE_ERROR = true;
    private static final boolean DEFAULT_DRAMATIC_REDUCED_MOTION = false;
    private static final boolean DEFAULT_DRAMATIC_REDUCED_MOTION_SYNC = true;
    private static final int DEFAULT_DRAMATIC_ANIMATION_MS = 220;
    private static final int DEFAULT_DRAMATIC_MINIMAP_WIDTH = 84;
    private static final String SHED_DIRECTORY_NAME = ".shed";
    private static final String SHED_CONFIG_NAME = "shedrc";
    private static final String SHED_SESSIONS_NAME = "sessions";
    private static final String SHED_PLUGINS_NAME = "plugins";
    private static final String LEGACY_CONFIG_NAME = ".shedrc";
    private static final String LEGACY_CONFIG_ALT_RELATIVE = ".config/shed/shedrc";

    private static final Map<String, ThemePalette> THEMES = new LinkedHashMap<>();
    private static final Map<String, String> THEME_ALIASES = new HashMap<>();

    static {
        // ordered by requested popularity ranking style
        registerTheme("one-dark-pro", "One Dark Pro",
            "#282C34", "#2C323C", "#3A3F4B", "#313A46", "#4B2F3A", "#ABB2BF", "#61AFEF", "#98C379");
        registerTheme("dracula", "Dracula",
            "#282A36", "#2F3140", "#3A3D4D", "#39414F", "#4B2F40", "#F8F8F2", "#BD93F9", "#F1FA8C");
        registerTheme("material-theme", "Material Theme",
            "#263238", "#2F3B46", "#37474F", "#33424D", "#4E3B40", "#EEFFFF", "#82AAFF", "#C3E88D");
        registerTheme("night-owl", "Night Owl",
            "#011627", "#0B2942", "#0D314D", "#15324A", "#4A2B3A", "#D6DEEB", "#82AAFF", "#ECC48D");
        registerTheme("ayu-mirage", "Ayu Mirage",
            "#1F2430", "#232A39", "#2A3142", "#293347", "#443240", "#CBCCC6", "#73D0FF", "#D5FF80");
        registerTheme("monokai-pro", "Monokai Pro",
            "#2D2A2E", "#363337", "#403E41", "#3A3640", "#513A46", "#FCFCFA", "#78DCE8", "#FFD866");
        registerTheme("tokyo-night", "Tokyo Night",
            "#1A1B26", "#1F2335", "#24283B", "#2A2F45", "#4A3049", "#C0CAF5", "#7AA2F7", "#9ECE6A");
        registerTheme("nord", "Nord",
            "#2E3440", "#3B4252", "#434C5E", "#4C566A", "#5E3D4D", "#D8DEE9", "#88C0D0", "#A3BE8C");
        registerTheme("gruvbox-dark", "Gruvbox Dark",
            "#282828", "#32302F", "#3C3836", "#504945", "#5D3B3B", "#EBDBB2", "#83A598", "#B8BB26");
        registerTheme("shades-of-purple", "Shades of Purple",
            "#2D2B55", "#3A376A", "#4B4679", "#4F4A87", "#6B3F66", "#FFFFFF", "#9EFFFF", "#A5FF90");
        registerTheme("palenight", "Palenight",
            "#292D3E", "#2F3347", "#343A52", "#3A3F58", "#5A3F5E", "#A6ACCD", "#82AAFF", "#C3E88D");
        registerTheme("catppuccin-mocha", "Catppuccin Mocha",
            "#1E1E2E", "#232334", "#2B2B40", "#313244", "#4B3349", "#CDD6F4", "#89B4FA", "#A6E3A1");
        registerTheme("github-dark", "GitHub Dark",
            "#0D1117", "#161B22", "#1F2630", "#263040", "#3D2F42", "#C9D1D9", "#58A6FF", "#7EE787");
        registerTheme("rose-pine", "Rosé Pine",
            "#191724", "#1F1D2E", "#26233A", "#2A273F", "#4A3046", "#E0DEF4", "#9CCFD8", "#F6C177");
        registerTheme("synthwave-84", "Synthwave '84",
            "#262335", "#2F2B45", "#3B3657", "#433D66", "#5E3B63", "#F8F8F2", "#F92AAD", "#72F1B8");
        registerTheme("cobalt2", "Cobalt2",
            "#193549", "#1F3F58", "#224969", "#2A5677", "#4B3A5E", "#FFFFFF", "#FFC600", "#3AD900");
        registerTheme("andromeda", "Andromeda",
            "#23262E", "#2B2F3A", "#343A47", "#3B4252", "#523B4F", "#D5CED9", "#9F7EFE", "#96E072");
        registerTheme("everforest-dark", "Everforest Dark",
            "#2D353B", "#343F44", "#3D484D", "#475258", "#5A464D", "#D3C6AA", "#7FBBB3", "#A7C080");
        registerTheme("kanagawa", "Kanagawa",
            "#1F1F28", "#252530", "#2A2A37", "#313142", "#483B4F", "#DCD7BA", "#7E9CD8", "#98BB6C");
        registerTheme("poimandres", "Poimandres",
            "#1B1E28", "#222633", "#2B3040", "#32394B", "#4A3F55", "#E4F0FB", "#89DDFF", "#5DE4C7");
        registerTheme("solarized-dark", "Solarized Dark",
            "#002B36", "#073642", "#0A4958", "#114B5F", "#4A3946", "#839496", "#268BD2", "#859900");
        registerTheme("noctis", "Noctis",
            "#1B1F2B", "#22283A", "#2A3246", "#313B53", "#4A3F5A", "#C5CDD9", "#82AAFF", "#ECC48D");
    }

    public ConfigManager() {
        this.config = new HashMap<>();
        this.defaultConfig = new HashMap<>();
        this.persistedConfig = new HashMap<>();
        this.projectConfig = new HashMap<>();
        this.projectPreviousValues = new HashMap<>();
        this.activeProjectConfigFile = null;
        String home = System.getProperty("user.home");
        this.shedDirectoryPath = home + "/" + SHED_DIRECTORY_NAME;
        this.configPath = shedDirectoryPath + "/" + SHED_CONFIG_NAME;
        migrateLegacyConfigIfNeeded();

        loadDefaults();
        loadConfig();
    }

    // Load default configuration
    private void loadDefaults() {
        config.put("theme", DEFAULT_THEME);
        config.put("font.family", DEFAULT_FONT_FAMILY);
        config.put("font.size", String.valueOf(DEFAULT_FONT_SIZE));
        config.put("tab.size", String.valueOf(DEFAULT_TAB_SIZE));
        config.put("line.numbers", DEFAULT_LINE_NUMBER_MODE.toConfigValue());
        config.put("show.current.line", String.valueOf(DEFAULT_SHOW_CURRENT_LINE));
        config.put("expand.tab", String.valueOf(DEFAULT_EXPAND_TAB));
        config.put("auto.indent", String.valueOf(DEFAULT_AUTO_INDENT));
        config.put("highlight.search", String.valueOf(DEFAULT_HIGHLIGHT_SEARCH));
        config.put("zen.mode.width", String.valueOf(DEFAULT_ZEN_MODE_WIDTH));
        config.put("session.restore.on.start", String.valueOf(DEFAULT_SESSION_RESTORE_ON_START));
        config.put("session.autoload", DEFAULT_SESSION_AUTOLOAD);
        config.put("session.dir", defaultSessionDirectoryPath());
        config.put("large.file.threshold.mb", String.valueOf(DEFAULT_LARGE_FILE_THRESHOLD_MB));
        config.put("large.file.line.threshold", String.valueOf(DEFAULT_LARGE_FILE_LINE_THRESHOLD));
        config.put("large.file.preview.lines", String.valueOf(DEFAULT_LARGE_FILE_PREVIEW_LINES));
        config.put("process.timeout.ms", String.valueOf(DEFAULT_PROCESS_TIMEOUT_MS));
        config.put("process.output.max.bytes", String.valueOf(DEFAULT_PROCESS_OUTPUT_MAX_BYTES));
        config.put("shell.command.max.length", String.valueOf(DEFAULT_SHELL_COMMAND_MAX_LENGTH));
        config.put("scrolloff", String.valueOf(DEFAULT_SCROLLOFF));
        config.put("auto.pairs", String.valueOf(DEFAULT_AUTO_PAIRS));
        config.put("textwidth", String.valueOf(DEFAULT_TEXTWIDTH));
        config.put("minimap", String.valueOf(DEFAULT_MINIMAP));
        config.put("ui.dramatic", String.valueOf(DEFAULT_DRAMATIC_UI));
        config.put("ui.dramatic.identity", String.valueOf(DEFAULT_DRAMATIC_IDENTITY));
        config.put("ui.dramatic.mode.transitions", String.valueOf(DEFAULT_DRAMATIC_MODE_TRANSITIONS));
        config.put("ui.dramatic.command.palette", String.valueOf(DEFAULT_DRAMATIC_COMMAND_PALETTE));
        config.put("ui.dramatic.editing.feedback", String.valueOf(DEFAULT_DRAMATIC_EDITING_FEEDBACK));
        config.put("ui.dramatic.panel.animations", String.valueOf(DEFAULT_DRAMATIC_PANEL_ANIMATIONS));
        config.put("ui.dramatic.sound", String.valueOf(DEFAULT_DRAMATIC_SOUND));
        config.put("ui.dramatic.sound.pack", DEFAULT_DRAMATIC_SOUND_PACK);
        config.put("ui.dramatic.sound.volume", String.valueOf(DEFAULT_DRAMATIC_SOUND_VOLUME));
        config.put("ui.dramatic.sound.cue.mode", String.valueOf(DEFAULT_DRAMATIC_SOUND_CUE_MODE));
        config.put("ui.dramatic.sound.cue.navigate", String.valueOf(DEFAULT_DRAMATIC_SOUND_CUE_NAVIGATE));
        config.put("ui.dramatic.sound.cue.success", String.valueOf(DEFAULT_DRAMATIC_SOUND_CUE_SUCCESS));
        config.put("ui.dramatic.sound.cue.error", String.valueOf(DEFAULT_DRAMATIC_SOUND_CUE_ERROR));
        config.put("ui.dramatic.reduced.motion", String.valueOf(DEFAULT_DRAMATIC_REDUCED_MOTION));
        config.put("ui.dramatic.reduced.motion.sync", String.valueOf(DEFAULT_DRAMATIC_REDUCED_MOTION_SYNC));
        config.put("ui.dramatic.animation.ms", String.valueOf(DEFAULT_DRAMATIC_ANIMATION_MS));
        config.put("ui.dramatic.minimap.width", String.valueOf(DEFAULT_DRAMATIC_MINIMAP_WIDTH));
        defaultConfig.clear();
        defaultConfig.putAll(config);
    }

    // Load configuration from file
    private void loadConfig() {
        File configFile = new File(configPath);
        persistedConfig.clear();
        if (!configFile.exists()) {
            return; // Use defaults
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Parse key=value pairs
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    persistedConfig.put(key, value);
                    config.put(key, value);
                }
            }

            reader.close();
        } catch (IOException e) {
            System.err.println("Error loading config: " + e.getMessage());
        }
    }

    // Get color setting
    public Color getColor(String mode) {
        String key = "color." + mode.toLowerCase();
        if (config.containsKey(key)) {
            return decodeColor(config.get(key), colorForMode(activeTheme(), mode));
        }
        return colorForMode(activeTheme(), mode);
    }

    private Color colorForMode(ThemePalette theme, String mode) {
        switch (mode.toLowerCase()) {
            case "insert":
                return theme.insert;
            case "command":
            case "search":
                return theme.command;
            case "visual":
            case "visual_line":
            case "visual_block":
                return theme.visual;
            case "replace":
                return theme.replace;
            case "normal":
            default:
                return theme.normal;
        }
    }

    // Get normal mode color
    public Color getNormalColor() {
        return getColor("normal");
    }

    // Get insert mode color
    public Color getInsertColor() {
        return getColor("insert");
    }

    // Get command mode color
    public Color getCommandColor() {
        return getColor("command");
    }

    // Get visual mode color
    public Color getVisualColor() {
        ThemePalette theme = activeTheme();
        String key = "color.visual";
        if (config.containsKey(key)) {
            return decodeColor(config.get(key), theme.visual);
        }
        return theme.visual;
    }

    // Get replace mode color
    public Color getReplaceColor() {
        return getColor("replace");
    }

    public Color getEditorForeground() {
        return getUiColor("ui.foreground", activeTheme().foreground);
    }

    public Color getCaretColor() {
        return getUiColor("ui.caret", activeTheme().accent);
    }

    public Color getSelectionColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.selection", blend(theme.normal, theme.accent, 0.33));
    }

    public Color getSelectionTextColor() {
        return getUiColor("ui.selection.text", activeTheme().foreground);
    }

    public Color getStatusBarBackground() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.status.background", blend(theme.normal, Color.BLACK, 0.26));
    }

    public Color getStatusBarForeground() {
        return getUiColor("ui.status.foreground", activeTheme().foreground);
    }

    public Color getCommandBarBackground() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.command.background", blend(theme.command, Color.BLACK, 0.30));
    }

    public Color getCommandBarForeground() {
        return getUiColor("ui.command.foreground", activeTheme().foreground);
    }

    public Color getLineNumberBackground() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.linenumber.background", blend(theme.normal, Color.BLACK, 0.20));
    }

    public Color getLineNumberForeground() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.linenumber.foreground", blend(theme.foreground, theme.normal, 0.40));
    }

    public Color getLineNumberActiveForeground() {
        return getUiColor("ui.linenumber.active", activeTheme().foreground);
    }

    public Color getCurrentLineHighlightColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.currentline", blend(theme.normal, theme.foreground, 0.14));
    }

    public Color getSubstitutePreviewColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.substitute.preview", blend(theme.command, theme.accent, 0.28));
    }

    public Color getSyntaxKeywordColor() {
        return getUiColor("ui.syntax.keyword", activeTheme().accent);
    }

    public Color getSyntaxStringColor() {
        return getUiColor("ui.syntax.string", activeTheme().stringAccent);
    }

    public Color getSyntaxCommentColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.syntax.comment", blend(theme.foreground, theme.normal, 0.52));
    }

    public Color getSyntaxTypeColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.syntax.type", blend(theme.accent, theme.foreground, 0.35));
    }
    public Color getSyntaxFunctionColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.syntax.function", blend(theme.accent, theme.stringAccent, 0.55));
    }
    public Color getSyntaxConstantColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.syntax.constant", blend(theme.stringAccent, theme.foreground, 0.40));
    }
    public Color getSyntaxAnnotationColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.syntax.annotation", blend(theme.accent, theme.foreground, 0.50));
    }
    public Color getSyntaxNumberColor() {
        ThemePalette theme = activeTheme();
        return getUiColor("ui.syntax.number", blend(theme.accent, theme.stringAccent, 0.42));
    }

    public String getThemeId() {
        ThemePalette theme = activeTheme();
        return theme == null ? DEFAULT_THEME : theme.id;
    }

    public String getThemeDisplayName() {
        ThemePalette theme = activeTheme();
        return theme == null ? "One Dark Pro" : theme.displayName;
    }

    public String setTheme(String requestedTheme) {
        if (requestedTheme == null || requestedTheme.trim().isEmpty()) {
            return null;
        }
        String alias = THEME_ALIASES.get(normalizeThemeName(requestedTheme));
        if (alias == null || !THEMES.containsKey(alias)) {
            return null;
        }
        config.put("theme", alias);
        return alias;
    }

    public List<String> getThemeIds() {
        return new ArrayList<>(THEMES.keySet());
    }

    public String getThemeListText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Themes\n\n");
        int index = 1;
        for (ThemePalette palette : THEMES.values()) {
            builder.append(index++).append(". ").append(palette.id);
            if (palette.id.equals(getThemeId())) {
                builder.append("  (active)");
            }
            builder.append("\n");
        }
        builder.append("\nUse :set theme=<name> to apply.");
        return builder.toString();
    }

    // Get font family
    public String getFontFamily() {
        return config.getOrDefault("font.family", DEFAULT_FONT_FAMILY);
    }

    // Get font size
    public int getFontSize() {
        try {
            return Integer.parseInt(config.getOrDefault("font.size", String.valueOf(DEFAULT_FONT_SIZE)));
        } catch (NumberFormatException e) {
            return DEFAULT_FONT_SIZE;
        }
    }

    // Get tab size
    public int getTabSize() {
        try {
            return Integer.parseInt(config.getOrDefault("tab.size", String.valueOf(DEFAULT_TAB_SIZE)));
        } catch (NumberFormatException e) {
            return DEFAULT_TAB_SIZE;
        }
    }

    // Get line numbers preference
    public boolean getLineNumbers() {
        return getLineNumberMode() != LineNumberMode.NONE;
    }

    public LineNumberMode getLineNumberMode() {
        return LineNumberMode.fromConfigValue(config.getOrDefault("line.numbers", DEFAULT_LINE_NUMBER_MODE.toConfigValue()));
    }

    public void setLineNumberMode(LineNumberMode mode) {
        config.put("line.numbers", mode.toConfigValue());
    }

    public boolean getShowCurrentLine() {
        return getBoolean("show.current.line", DEFAULT_SHOW_CURRENT_LINE);
    }

    public boolean getExpandTab() {
        return getBoolean("expand.tab", DEFAULT_EXPAND_TAB);
    }

    public boolean getAutoIndent() {
        return getBoolean("auto.indent", DEFAULT_AUTO_INDENT);
    }

    public boolean getHighlightSearch() {
        return getBoolean("highlight.search", DEFAULT_HIGHLIGHT_SEARCH);
    }

    public int getZenModeWidth() {
        return getInt("zen.mode.width", DEFAULT_ZEN_MODE_WIDTH);
    }

    public int getRulerColumn() {
        return getInt("ruler.column", 0);
    }

    public boolean getShowWhitespace() {
        return getBoolean("list", false);
    }

    public boolean getSessionRestoreOnStart() {
        return getBoolean("session.restore.on.start", DEFAULT_SESSION_RESTORE_ON_START);
    }

    public String getSessionAutoloadName() {
        String configured = config.getOrDefault("session.autoload", DEFAULT_SESSION_AUTOLOAD);
        String trimmed = configured == null ? "" : configured.trim();
        return trimmed.isEmpty() ? DEFAULT_SESSION_AUTOLOAD : trimmed;
    }

    public String getSessionDirectory() {
        String configured = config.get("session.dir");
        if (configured == null || configured.isBlank()) {
            return defaultSessionDirectoryPath();
        }
        return configured.trim();
    }

    public long getLargeFileThresholdMb() {
        try {
            return Long.parseLong(config.getOrDefault("large.file.threshold.mb", String.valueOf(DEFAULT_LARGE_FILE_THRESHOLD_MB)));
        } catch (NumberFormatException e) {
            return DEFAULT_LARGE_FILE_THRESHOLD_MB;
        }
    }

    public int getLargeFileLineThreshold() {
        return getInt("large.file.line.threshold", DEFAULT_LARGE_FILE_LINE_THRESHOLD);
    }

    public int getLargeFilePreviewLines() {
        return getInt("large.file.preview.lines", DEFAULT_LARGE_FILE_PREVIEW_LINES);
    }

    public int getProcessTimeoutMs() {
        return getInt("process.timeout.ms", DEFAULT_PROCESS_TIMEOUT_MS);
    }

    public int getProcessOutputMaxBytes() {
        return getInt("process.output.max.bytes", DEFAULT_PROCESS_OUTPUT_MAX_BYTES);
    }

    public int getShellCommandMaxLength() {
        return getInt("shell.command.max.length", DEFAULT_SHELL_COMMAND_MAX_LENGTH);
    }
    public int getScrolloff() {
        return getInt("scrolloff", DEFAULT_SCROLLOFF);
    }
    public boolean getAutoPairs() {
        return getBoolean("auto.pairs", DEFAULT_AUTO_PAIRS);
    }
    public int getTextWidth() {
        return getInt("textwidth", DEFAULT_TEXTWIDTH);
    }
    public boolean getMinimap() {
        return getBoolean("minimap", DEFAULT_MINIMAP);
    }

    public boolean getDramaticUiEnabled() {
        return getBoolean("ui.dramatic", DEFAULT_DRAMATIC_UI);
    }

    public boolean getDramaticIdentityEnabled() {
        return getBoolean("ui.dramatic.identity", DEFAULT_DRAMATIC_IDENTITY);
    }

    public boolean getDramaticModeTransitionsEnabled() {
        return getBoolean("ui.dramatic.mode.transitions", DEFAULT_DRAMATIC_MODE_TRANSITIONS);
    }

    public boolean getDramaticCommandPaletteEnabled() {
        return getBoolean("ui.dramatic.command.palette", DEFAULT_DRAMATIC_COMMAND_PALETTE);
    }

    public boolean getDramaticEditingFeedbackEnabled() {
        return getBoolean("ui.dramatic.editing.feedback", DEFAULT_DRAMATIC_EDITING_FEEDBACK);
    }

    public boolean getDramaticPanelAnimationsEnabled() {
        return getBoolean("ui.dramatic.panel.animations", DEFAULT_DRAMATIC_PANEL_ANIMATIONS);
    }

    public boolean getDramaticSoundEnabled() {
        return getBoolean("ui.dramatic.sound", DEFAULT_DRAMATIC_SOUND);
    }

    public String getDramaticSoundPack() {
        String pack = config.getOrDefault("ui.dramatic.sound.pack", DEFAULT_DRAMATIC_SOUND_PACK);
        if (pack == null || pack.isBlank()) {
            return DEFAULT_DRAMATIC_SOUND_PACK;
        }
        return pack.trim().toLowerCase(Locale.ROOT);
    }

    public int getDramaticSoundVolume() {
        return Math.max(0, Math.min(100, getInt("ui.dramatic.sound.volume", DEFAULT_DRAMATIC_SOUND_VOLUME)));
    }

    public boolean getDramaticSoundModeCueEnabled() {
        return getBoolean("ui.dramatic.sound.cue.mode", DEFAULT_DRAMATIC_SOUND_CUE_MODE);
    }

    public boolean getDramaticSoundNavigateCueEnabled() {
        return getBoolean("ui.dramatic.sound.cue.navigate", DEFAULT_DRAMATIC_SOUND_CUE_NAVIGATE);
    }

    public boolean getDramaticSoundSuccessCueEnabled() {
        return getBoolean("ui.dramatic.sound.cue.success", DEFAULT_DRAMATIC_SOUND_CUE_SUCCESS);
    }

    public boolean getDramaticSoundErrorCueEnabled() {
        return getBoolean("ui.dramatic.sound.cue.error", DEFAULT_DRAMATIC_SOUND_CUE_ERROR);
    }

    public boolean getDramaticReducedMotionEnabled() {
        if (getBoolean("ui.dramatic.reduced.motion", DEFAULT_DRAMATIC_REDUCED_MOTION)) {
            return true;
        }
        if (getBoolean("ui.dramatic.reduced.motion.sync", DEFAULT_DRAMATIC_REDUCED_MOTION_SYNC)) {
            return detectSystemReducedMotionPreference();
        }
        return false;
    }

    public int getDramaticAnimationMs() {
        return getInt("ui.dramatic.animation.ms", DEFAULT_DRAMATIC_ANIMATION_MS);
    }

    public int getDramaticMinimapWidth() {
        return Math.max(40, getInt("ui.dramatic.minimap.width", DEFAULT_DRAMATIC_MINIMAP_WIDTH));
    }

    public void set(String key, String value) {
        config.put(key, value);
    }

    public void setAndPersist(String key, String value) throws IOException {
        String normalized = value == null ? "" : value;
        config.put(key, normalized);
        persistedConfig.put(key, normalized);
        writeConfigFile();
    }

    public int persistCurrentConfig() throws IOException {
        persistedConfig.clear();
        List<String> keys = new ArrayList<>(config.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            String value = config.get(key);
            if (value == null) {
                continue;
            }
            String defaultValue = defaultConfig.get(key);
            if (defaultValue == null || !defaultValue.equals(value)) {
                persistedConfig.put(key, value);
            }
        }
        writeConfigFile();
        return persistedConfig.size();
    }

    public void reload() {
        config.clear();
        projectConfig.clear();
        projectPreviousValues.clear();
        activeProjectConfigFile = null;
        loadDefaults();
        loadConfig();
    }

    public String applyProjectConfigForFile(File file) {
        File localConfig = findProjectConfig(file);
        try {
            if (localConfig != null) {
                localConfig = localConfig.getCanonicalFile();
            }
        } catch (IOException ignored) {
            if (localConfig != null) {
                localConfig = localConfig.getAbsoluteFile();
            }
        }

        if ((activeProjectConfigFile == null && localConfig == null)
                || (activeProjectConfigFile != null && activeProjectConfigFile.equals(localConfig))) {
            return "";
        }

        restoreProjectOverrides();
        projectConfig.clear();
        projectPreviousValues.clear();
        activeProjectConfigFile = null;

        if (localConfig == null) {
            return "Project config cleared";
        }

        try {
            Map<String, String> parsed = parseConfigFile(localConfig);
            if (parsed.isEmpty()) {
                activeProjectConfigFile = localConfig;
                return "Project config loaded: " + localConfig.getAbsolutePath() + " (no overrides)";
            }
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                String key = entry.getKey();
                if (!projectPreviousValues.containsKey(key)) {
                    projectPreviousValues.put(key, config.get(key));
                }
                projectConfig.put(key, entry.getValue());
                config.put(key, entry.getValue());
            }
            activeProjectConfigFile = localConfig;
            return "Project config loaded: " + localConfig.getAbsolutePath();
        } catch (IOException e) {
            return "Project config load failed: " + e.getMessage();
        }
    }

    public String getActiveProjectConfigPath() {
        return activeProjectConfigFile == null ? null : activeProjectConfigFile.getAbsolutePath();
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(config.getOrDefault(key, String.valueOf(defaultValue)));
    }

    private int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(config.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // Get any config value by key
    public String get(String key) {
        return config.get(key);
    }

    // Get config value with default
    public String get(String key, String defaultValue) {
        return config.getOrDefault(key, defaultValue);
    }

    public String getLspCommand(String extension) {
        return config.get("lsp." + extension + ".command");
    }

    public String[] getLspArgs(String extension) {
        String raw = config.get("lsp." + extension + ".args");
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return raw.trim().split("\\s+");
    }

    public String resolveCommandAlias(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String resolved = command.trim().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        while (seen.add(resolved)) {
            String aliased = config.get("command.alias." + resolved);
            if (aliased == null || aliased.isBlank()) {
                break;
            }
            String normalized = aliased.trim().toLowerCase(Locale.ROOT);
            int separator = normalized.indexOf(' ');
            resolved = separator >= 0 ? normalized.substring(0, separator) : normalized;
        }
        return resolved;
    }

    public List<String> getConfiguredCommandAliases() {
        List<String> aliases = new ArrayList<>();
        String prefix = "command.alias.";
        for (String key : config.keySet()) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                aliases.add(key.substring(prefix.length()));
            }
        }
        Collections.sort(aliases);
        return aliases;
    }

    public Map<String, String> getUserCommands() {
        Map<String, String> commands = new java.util.LinkedHashMap<>();
        String prefix = "command.user.";
        for (String key : config.keySet()) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                commands.put(key.substring(prefix.length()), config.get(key));
            }
        }
        return commands;
    }

    public Map<String, String> getConfiguredLspServers() {
        Map<String, String> servers = new java.util.LinkedHashMap<>();
        String prefix = "lsp.";
        String suffix = ".command";
        for (String key : config.keySet()) {
            if (key.startsWith(prefix) && key.endsWith(suffix) && key.length() > prefix.length() + suffix.length()) {
                String ext = key.substring(prefix.length(), key.length() - suffix.length());
                String cmd = config.get(key);
                String[] args = getLspArgs(ext);
                String full = cmd + (args.length > 0 ? " " + String.join(" ", args) : "");
                servers.put(ext, full);
            }
        }
        return servers;
    }

    public String getKeybinding(String mode, String keySpec) {
        if (keySpec == null || keySpec.isEmpty()) {
            return null;
        }
        String normalizedMode = mode == null ? "normal" : mode.toLowerCase(Locale.ROOT);
        String modeSpecific = config.get("keybind." + normalizedMode + "." + keySpec);
        if (modeSpecific != null) {
            return modeSpecific.trim();
        }
        String global = config.get("keybind.global." + keySpec);
        if (global != null) {
            return global.trim();
        }
        return null;
    }

    // Get config file path
    public String getConfigPath() {
        return configPath;
    }

    public String getShedDirectoryPath() {
        return shedDirectoryPath;
    }

    public String getPluginsDirectoryPath() {
        return shedDirectoryPath + "/" + SHED_PLUGINS_NAME;
    }

    // Check if config file exists
    public boolean configExists() {
        return new File(configPath).exists();
    }

    public String defaultConfigTemplate() {
        return "# Shed config\n"
            + "# Generated because ~/.shed/shedrc was missing or empty.\n"
            + "# Remove or change any key; unspecified keys use built-in defaults.\n\n"
            + "theme=" + DEFAULT_THEME + "\n"
            + "font.family=" + DEFAULT_FONT_FAMILY + "\n"
            + "font.size=" + DEFAULT_FONT_SIZE + "\n"
            + "tab.size=" + DEFAULT_TAB_SIZE + "\n"
            + "line.numbers=" + DEFAULT_LINE_NUMBER_MODE.toConfigValue() + "\n"
            + "show.current.line=" + DEFAULT_SHOW_CURRENT_LINE + "\n"
            + "expand.tab=" + DEFAULT_EXPAND_TAB + "\n"
            + "auto.indent=" + DEFAULT_AUTO_INDENT + "\n"
            + "highlight.search=" + DEFAULT_HIGHLIGHT_SEARCH + "\n"
            + "zen.mode.width=" + DEFAULT_ZEN_MODE_WIDTH + "\n"
            + "scrolloff=" + DEFAULT_SCROLLOFF + "\n\n"
            + "session.restore.on.start=" + DEFAULT_SESSION_RESTORE_ON_START + "\n"
            + "session.autoload=" + DEFAULT_SESSION_AUTOLOAD + "\n"
            + "session.dir=" + defaultSessionDirectoryPath() + "\n\n"
            + "process.timeout.ms=" + DEFAULT_PROCESS_TIMEOUT_MS + "\n"
            + "process.output.max.bytes=" + DEFAULT_PROCESS_OUTPUT_MAX_BYTES + "\n"
            + "shell.command.max.length=" + DEFAULT_SHELL_COMMAND_MAX_LENGTH + "\n\n"
            + "# Dramatic UI (theater mode)\n"
            + "ui.dramatic=" + DEFAULT_DRAMATIC_UI + "\n"
            + "ui.dramatic.identity=" + DEFAULT_DRAMATIC_IDENTITY + "\n"
            + "ui.dramatic.mode.transitions=" + DEFAULT_DRAMATIC_MODE_TRANSITIONS + "\n"
            + "ui.dramatic.command.palette=" + DEFAULT_DRAMATIC_COMMAND_PALETTE + "\n"
            + "ui.dramatic.editing.feedback=" + DEFAULT_DRAMATIC_EDITING_FEEDBACK + "\n"
            + "ui.dramatic.panel.animations=" + DEFAULT_DRAMATIC_PANEL_ANIMATIONS + "\n"
            + "ui.dramatic.sound=" + DEFAULT_DRAMATIC_SOUND + "\n"
            + "ui.dramatic.sound.pack=" + DEFAULT_DRAMATIC_SOUND_PACK + "\n"
            + "ui.dramatic.sound.volume=" + DEFAULT_DRAMATIC_SOUND_VOLUME + "\n"
            + "ui.dramatic.sound.cue.mode=" + DEFAULT_DRAMATIC_SOUND_CUE_MODE + "\n"
            + "ui.dramatic.sound.cue.navigate=" + DEFAULT_DRAMATIC_SOUND_CUE_NAVIGATE + "\n"
            + "ui.dramatic.sound.cue.success=" + DEFAULT_DRAMATIC_SOUND_CUE_SUCCESS + "\n"
            + "ui.dramatic.sound.cue.error=" + DEFAULT_DRAMATIC_SOUND_CUE_ERROR + "\n"
            + "ui.dramatic.reduced.motion=" + DEFAULT_DRAMATIC_REDUCED_MOTION + "\n"
            + "ui.dramatic.reduced.motion.sync=" + DEFAULT_DRAMATIC_REDUCED_MOTION_SYNC + "\n"
            + "ui.dramatic.animation.ms=" + DEFAULT_DRAMATIC_ANIMATION_MS + "\n"
            + "ui.dramatic.minimap.width=" + DEFAULT_DRAMATIC_MINIMAP_WIDTH + "\n\n"
            + "# Per-project override file support\n"
            + "# Place .shedrc.local at a repo root (or parent folder).\n"
            + "# It is auto-applied when opening files under that folder.\n\n"
            + "# Plugins\n"
            + "# Place .shed or .lua files in ~/.shed/plugins/ to load plugins.\n"
            + "# .shed files: declarative (# @command, # @bind, # @event directives)\n"
            + "# .lua files: scripted via shed.* API (shed.command, shed.on, etc.)\n"
            + "# Use :help plugins for full reference, :plugin to list, :plugin reload.\n\n"
            + "# Command aliases (left side is what you type after :, right side is built-in command)\n"
            + "# command.alias.ww=w\n"
            + "# command.alias.qq=q\n\n"
            + "# Keybindings\n"
            + "# keybind.<mode>.<lhs>=<rhs>\n"
            + "# modes: normal, insert, visual, visual_line, replace, command, search, global\n"
            + "# tokens: <esc> <enter> <tab> <space> <bs> <del> <up>/<down>/<left>/<right> <c-x>\n"
            + "# examples\n"
            + "# keybind.normal.H=^\n"
            + "# keybind.normal.L=$\n"
            + "# keybind.insert.<c-s>=<esc>:w<enter>\n\n"
            + "# LSP examples\n"
            + "# lsp.py.command=pyright-langserver\n"
            + "# lsp.py.args=--stdio\n";
    }

    private String defaultSessionDirectoryPath() {
        return shedDirectoryPath + "/" + SHED_SESSIONS_NAME;
    }

    private void migrateLegacyConfigIfNeeded() {
        File currentConfig = new File(configPath);
        if (currentConfig.exists()) {
            return;
        }
        File legacy = resolveLegacyConfigFile();
        if (legacy == null) {
            return;
        }
        try {
            File parent = currentConfig.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            Files.copy(legacy.toPath(), currentConfig.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private File resolveLegacyConfigFile() {
        String home = System.getProperty("user.home");
        File legacy = new File(home, LEGACY_CONFIG_NAME);
        if (legacy.exists()) {
            return legacy;
        }
        File legacyAlt = new File(home + "/" + LEGACY_CONFIG_ALT_RELATIVE);
        if (legacyAlt.exists()) {
            return legacyAlt;
        }
        return null;
    }

    private void restoreProjectOverrides() {
        for (Map.Entry<String, String> entry : projectPreviousValues.entrySet()) {
            String key = entry.getKey();
            String previous = entry.getValue();
            if (previous == null) {
                config.remove(key);
            } else {
                config.put(key, previous);
            }
        }
    }

    private File findProjectConfig(File file) {
        if (file == null) {
            return null;
        }
        File cursor = file.isDirectory() ? file : file.getParentFile();
        while (cursor != null) {
            File candidate = new File(cursor, ".shedrc.local");
            if (candidate.isFile()) {
                return candidate;
            }
            cursor = cursor.getParentFile();
        }
        return null;
    }

    private Map<String, String> parseConfigFile(File file) throws IOException {
        Map<String, String> parsed = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (!key.isEmpty()) {
                parsed.put(key, value);
            }
        }
        return parsed;
    }

    private void writeConfigFile() throws IOException {
        File configFile = new File(configPath);
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Shed config");
        lines.add("# Auto-generated by :set! and :config save");
        lines.add("");
        List<String> keys = new ArrayList<>(persistedConfig.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            String value = persistedConfig.get(key);
            if (value == null) {
                continue;
            }
            lines.add(key + "=" + value);
        }
        Files.write(
            configFile.toPath(),
            lines,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    }

    private boolean detectSystemReducedMotionPreference() {
        String envOverride = normalizedTruth(System.getenv("PREFER_REDUCED_MOTION"));
        if (envOverride != null) {
            return Boolean.parseBoolean(envOverride);
        }
        String propOverride = normalizedTruth(System.getProperty("prefers.reduced.motion"));
        if (propOverride != null) {
            return Boolean.parseBoolean(propOverride);
        }
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Object awtPreference = toolkit.getDesktopProperty("awt.prefersReducedMotion");
            if (awtPreference instanceof Boolean) {
                return (Boolean) awtPreference;
            }
            Object gnomeAnimations = toolkit.getDesktopProperty("gnome.Net/EnableAnimations");
            if (gnomeAnimations instanceof Boolean) {
                return !((Boolean) gnomeAnimations);
            }
            Object kdeAnimations = toolkit.getDesktopProperty("kde.kdecoration.animationEnabled");
            if (kdeAnimations instanceof Boolean) {
                return !((Boolean) kdeAnimations);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String normalizedTruth(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return "true";
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return "false";
        }
        return null;
    }

    private Color getUiColor(String key, Color fallback) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return decodeColor(value, fallback);
    }

    private ThemePalette activeTheme() {
        String raw = config.getOrDefault("theme", DEFAULT_THEME);
        String alias = THEME_ALIASES.get(normalizeThemeName(raw));
        if (alias == null) {
            alias = DEFAULT_THEME;
        }
        ThemePalette palette = THEMES.get(alias);
        if (palette == null) {
            palette = THEMES.get(DEFAULT_THEME);
        }
        return palette;
    }

    private static String normalizeThemeName(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private static Color decodeColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        try {
            return Color.decode(hex.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Color blend(Color base, Color overlay, double ratio) {
        double clampedRatio = Math.max(0.0, Math.min(1.0, ratio));
        int r = (int) Math.round(base.getRed() * (1.0 - clampedRatio) + overlay.getRed() * clampedRatio);
        int g = (int) Math.round(base.getGreen() * (1.0 - clampedRatio) + overlay.getGreen() * clampedRatio);
        int b = (int) Math.round(base.getBlue() * (1.0 - clampedRatio) + overlay.getBlue() * clampedRatio);
        return new Color(r, g, b);
    }

    private static void registerTheme(
        String id,
        String displayName,
        String normal,
        String insert,
        String command,
        String visual,
        String replace,
        String foreground,
        String accent,
        String stringAccent
    ) {
        ThemePalette palette = new ThemePalette(id, displayName, normal, insert, command, visual, replace, foreground, accent, stringAccent);
        THEMES.put(id, palette);

        THEME_ALIASES.put(normalizeThemeName(id), id);
        THEME_ALIASES.put(normalizeThemeName(displayName), id);
        THEME_ALIASES.put(normalizeThemeName(displayName.replace("'", "")), id);
    }

    private static final class ThemePalette {
        private final String id;
        private final String displayName;
        private final Color normal;
        private final Color insert;
        private final Color command;
        private final Color visual;
        private final Color replace;
        private final Color foreground;
        private final Color accent;
        private final Color stringAccent;

        private ThemePalette(
            String id,
            String displayName,
            String normal,
            String insert,
            String command,
            String visual,
            String replace,
            String foreground,
            String accent,
            String stringAccent
        ) {
            this.id = id;
            this.displayName = displayName;
            this.normal = decodeColor(normal, Color.decode(DEFAULT_COLOR_NORMAL));
            this.insert = decodeColor(insert, Color.decode(DEFAULT_COLOR_INSERT));
            this.command = decodeColor(command, Color.decode(DEFAULT_COLOR_COMMAND));
            this.visual = decodeColor(visual, Color.decode(DEFAULT_COLOR_VISUAL));
            this.replace = decodeColor(replace, Color.decode(DEFAULT_COLOR_REPLACE));
            this.foreground = decodeColor(foreground, Color.decode("#E6EDF3"));
            this.accent = decodeColor(accent, Color.decode("#58A6FF"));
            this.stringAccent = decodeColor(stringAccent, Color.decode("#7EE787"));
        }
    }
}
