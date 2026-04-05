// Config Manager Class
// Loads and manages user configuration from ~/.shedrc

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.awt.Color;

public class ConfigManager {
    private final Map<String, String> config;
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
    private static final long DEFAULT_LARGE_FILE_THRESHOLD_MB = 100L;
    private static final int DEFAULT_LARGE_FILE_LINE_THRESHOLD = 50000;
    private static final int DEFAULT_LARGE_FILE_PREVIEW_LINES = 1000;

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
        this.configPath = System.getProperty("user.home") + "/.shedrc";

        // Try alternate config location
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            String altPath = System.getProperty("user.home") + "/.config/shed/shedrc";
            File altFile = new File(altPath);
            if (altFile.exists()) {
                this.configPath = altPath;
            }
        }

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
        config.put("large.file.threshold.mb", String.valueOf(DEFAULT_LARGE_FILE_THRESHOLD_MB));
        config.put("large.file.line.threshold", String.valueOf(DEFAULT_LARGE_FILE_LINE_THRESHOLD));
        config.put("large.file.preview.lines", String.valueOf(DEFAULT_LARGE_FILE_PREVIEW_LINES));
    }

    // Load configuration from file
    private void loadConfig() {
        File configFile = new File(configPath);
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

    public void set(String key, String value) {
        config.put(key, value);
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

    // Get config file path
    public String getConfigPath() {
        return configPath;
    }

    // Check if config file exists
    public boolean configExists() {
        return new File(configPath).exists();
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
