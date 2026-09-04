package shed;

import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
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
    private final TypedSettings settings;
    private File activeProjectConfigFile;
    private final String shedDirectoryPath;
    private String configPath;
    private String configLoadReport;
    private boolean configLoadFailed;
    private Map<String, String> reloadFallbackConfig;
    private DebugAdapterRegistry.Validation debugConfiguration;

    // Default configuration values
    private static final String DEFAULT_THEME = "one-dark-pro";
    private static final String DEFAULT_COLOR_NORMAL = "#282C34";
    private static final String DEFAULT_COLOR_INSERT = "#2C323C";
    private static final String DEFAULT_COLOR_COMMAND = "#3A3F4B";
    private static final String DEFAULT_COLOR_VISUAL = "#313A46";
    private static final String DEFAULT_COLOR_REPLACE = "#4B2F3A";
    private static final String DEFAULT_FONT_FAMILY = "Monospaced";
    private static final int DEFAULT_FONT_SIZE = 16;
    private static final String DEFAULT_UI_FONT_FAMILY = "";
    private static final int DEFAULT_UI_FONT_SIZE = 0;
    private static final String DEFAULT_TERMINAL_FONT_FAMILY = "Monospaced";
    private static final int DEFAULT_TERMINAL_FONT_SIZE = 14;
    private static final int DEFAULT_TAB_SIZE = 4;
    private static final KeymapProfile DEFAULT_KEYMAP_PROFILE = KeymapProfile.VIM;
    private static final LineNumberMode DEFAULT_LINE_NUMBER_MODE = LineNumberMode.ABSOLUTE;
    private static final boolean DEFAULT_SHOW_CURRENT_LINE = true;
    private static final boolean DEFAULT_EXPAND_TAB = true;
    private static final boolean DEFAULT_AUTO_INDENT = true;
    private static final boolean DEFAULT_HIGHLIGHT_SEARCH = true;
    private static final int DEFAULT_ZEN_MODE_WIDTH = 80;
    private static final boolean DEFAULT_SESSION_RESTORE_ON_START = false;
    private static final String DEFAULT_SESSION_AUTOLOAD = "default";
    private static final boolean DEFAULT_TERMINAL_SESSION_RESTORE = false;
    private static final boolean DEFAULT_TERMINAL_SHELL_INTEGRATION = true;
    private static final boolean DEFAULT_WORKSPACE_INDEX_ENABLED = false;
    private static final long DEFAULT_LARGE_FILE_THRESHOLD_MB = 25L;
    private static final int DEFAULT_LARGE_FILE_LINE_THRESHOLD = 500000;
    private static final int DEFAULT_LARGE_FILE_PREVIEW_LINES = 1000;
    private static final int DEFAULT_RECOVERY_RETENTION_MAX_ENTRIES = RecoveryJournal.MAX_ENTRIES;
    private static final int DEFAULT_RECOVERY_RETENTION_MAX_CONTENT_BYTES = RecoveryJournal.MAX_CONTENT_BYTES;
    private static final boolean DEFAULT_RECOVERY_CLEANUP_ON_CLEAN_EXIT = true;
    private static final boolean DEFAULT_BACKUP_ENABLED = false;
    private static final String DEFAULT_BACKUP_MODE = "idle";
    private static final int DEFAULT_BACKUP_RETENTION_COUNT = BackupPolicy.DEFAULT_RETENTION_COUNT;
    private static final int DEFAULT_UNDO_HISTORY_MAX_ENTRIES = UndoHistoryPolicy.DEFAULT_MAX_ENTRIES;
    private static final long DEFAULT_UNDO_HISTORY_MAX_BYTES = UndoHistoryPolicy.DEFAULT_MAX_BYTES;
    private static final int DEFAULT_PROCESS_TIMEOUT_MS = 15000;
    private static final int DEFAULT_PROCESS_OUTPUT_MAX_BYTES = 1024 * 1024;
    private static final boolean DEFAULT_SHELL_COMMAND_ENABLED = true;
    private static final int DEFAULT_SHELL_COMMAND_MAX_LENGTH = 4096;
    private static final int DEFAULT_SCROLLOFF = 0;
    private static final boolean DEFAULT_AUTO_PAIRS = true;
    private static final int DEFAULT_TEXTWIDTH = 0;
    private static final boolean DEFAULT_MINIMAP = false;
    private static final int DEFAULT_MINIMAP_WIDTH = 84;
    private static final double DEFAULT_LIMELIGHT_COEFFICIENT = 0.5;
    private static final int DEFAULT_LIMELIGHT_PARAGRAPH_SPAN = 0;
    private static final boolean DEFAULT_MULTI_SELECTION_ENABLED = false;
    private static final boolean DEFAULT_MARKDOWN_PREVIEW_SCROLL_SYNC = true;
    private static final boolean DEFAULT_LSP_SEMANTIC_TOKENS_INLINE = true;
    private static final boolean DEFAULT_LSP_INLAY_HINTS_INLINE = true;
    private static final boolean DEFAULT_LSP_COMPLETION_AUTO_SHOW = true;
    private static final int DEFAULT_LSP_COMPLETION_DELAY_MS = 90;
    private static final boolean DEFAULT_LSP_COMPLETION_TRIGGER_CHARACTERS = true;
    private static final boolean DEFAULT_LSP_COMPLETION_FUZZY_MATCHING = true;
    private static final boolean DEFAULT_LSP_COMPLETION_LOCAL_WORDS = true;
    private static final boolean DEFAULT_LSP_COMPLETION_COMMIT_CHARACTERS = true;
    private static final boolean DEFAULT_UI_WHICHKEY_HINTS = true;
    private static final boolean DEFAULT_PROJECT_CONFIG_ENABLED = true;
    private static final boolean DEFAULT_PROJECT_CONFIG_ALLOW_UNSAFE = false;
    private static final boolean DEFAULT_PROJECT_CONFIG_REQUIRE_TRUSTED_FILE = true;
    private static final boolean DEFAULT_PROJECT_REPLACE_ENABLED = false;
    private static final boolean DEFAULT_PROJECT_REPLACE_PREVIEW_REQUIRED = true;
    private static final boolean DEFAULT_PROJECT_REPLACE_CONFIRM_REQUIRED = true;
    private static final boolean DEFAULT_PROJECT_REPLACE_BACKUP_ENABLED = true;
    private static final String DEFAULT_PROJECT_REPLACE_SCOPE = "workspace";
    private static final boolean DEFAULT_TREE_DELETE_PROTECT_CRITICAL = true;
    private static final boolean DEFAULT_GIT_WORKBENCH_ENABLED = true;
    private static final boolean DEFAULT_GIT_CHANGES_ENABLED = true;
    private static final boolean DEFAULT_GIT_DIFFS_ENABLED = true;
    private static final boolean DEFAULT_GIT_STAGING_ENABLED = true;
    private static final boolean DEFAULT_GIT_CONFLICT_RESOLUTION_ENABLED = true;
    private static final boolean DEFAULT_GIT_HISTORY_ENABLED = true;
    private static final boolean DEFAULT_GIT_REMOTE_ACTIONS_ENABLED = true;
    private static final boolean DEFAULT_GIT_PANEL_PRESENTATION_ENABLED = true;
    private static final boolean DEFAULT_GIT_AUTO_REFRESH_ENABLED = true;
    private static final int DEFAULT_GIT_AUTO_REFRESH_INTERVAL_MS = 1500;
    private static final boolean DEFAULT_GITHUB_REVIEW_ENABLED = false;
    private static final boolean DEFAULT_GITHUB_REVIEW_CONSENT_GRANTED = false;
    private static final boolean DEFAULT_UPDATES_ENABLED = false;
    private static final boolean DEFAULT_UPDATES_CONSENT_GRANTED = false;
    private static final String DEFAULT_UPDATES_METADATA_URL = "";
    private static final String DEFAULT_UPDATES_METADATA_PUBLIC_KEY = "";
    private static final int DEFAULT_UPDATES_CHECK_TIMEOUT_MS = 5000;
    private static final boolean DEFAULT_DEBUG_OPEN_SOURCE_ON_STOP = true;
    private static final int DEFAULT_LANDING_REMOTE_TIMEOUT_MS = 5000;
    private static final String SHED_DIRECTORY_NAME = ".shed";
    private static final String SHED_CONFIG_NAME = "config.toml";
    private static final String PROJECT_CONFIG_NAME = ".shed.toml";
    private static final String SHED_SESSIONS_NAME = "sessions";
    private static final String SHED_PLUGINS_NAME = "plugins";

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
        registerTheme("oxocarbon-dark", "Oxocarbon Dark",
            "#161616", "#1E1E1E", "#262626", "#2E2E2E", "#3A2E3A", "#DDE1E6", "#78A9FF", "#42BE65");
        registerTheme("vesper", "Vesper",
            "#101010", "#161616", "#1E1E1E", "#262626", "#39293A", "#D5D5D5", "#A1BDFC", "#99FFE4");
        registerTheme("sonokai", "Sonokai",
            "#2C2E34", "#33353B", "#3B3E48", "#454751", "#583B4D", "#E2E2E3", "#7FBBB3", "#B0E57C");
        registerTheme("doom-one", "Doom One",
            "#282C34", "#313640", "#3A3F4B", "#434852", "#5A3D4D", "#BBC2CF", "#51AFEF", "#98BE65");
        registerTheme("horizon", "Horizon",
            "#1C1E26", "#232530", "#2B2E3B", "#333748", "#4D3A4C", "#E0E0E0", "#E95678", "#09F7A0");
        registerTheme("papercolor-dark", "PaperColor Dark",
            "#1C1C1C", "#262626", "#303030", "#3A3A3A", "#4D3A42", "#D0D0D0", "#5FAFD7", "#87AF87");
        registerTheme("xcode-dark", "Xcode Dark",
            "#1F1F24", "#27272E", "#30303A", "#3A3A47", "#4C3A45", "#F8F8F2", "#9CDCFE", "#A6E22E");
        registerTheme("dimmed-monokai", "Dimmed Monokai",
            "#1E1E1E", "#252525", "#2C2C2C", "#333333", "#4A3843", "#B9BCBF", "#5E9CD3", "#93C763");
        registerTheme("fleet-dark", "Fleet Dark",
            "#1F2430", "#252B39", "#2D3444", "#353D50", "#4A3B4F", "#CDD3DE", "#7CC4FF", "#B8E36A");
        registerTheme("nightfox", "Nightfox",
            "#192330", "#1F2A38", "#263445", "#2E3D52", "#4A3E57", "#CDCECF", "#719CD6", "#81B29A");
    }

    public ConfigManager() {
        this.config = new HashMap<>();
        this.defaultConfig = new HashMap<>();
        this.persistedConfig = new HashMap<>();
        this.projectConfig = new HashMap<>();
        this.projectPreviousValues = new HashMap<>();
        this.settings = new TypedSettings();
        this.activeProjectConfigFile = null;
        this.configLoadReport = "";
        this.configLoadFailed = false;
        this.debugConfiguration = DebugAdapterRegistry.validate(Map.of());
        Path home = Path.of(System.getProperty("user.home"));
        this.shedDirectoryPath = home.resolve(SHED_DIRECTORY_NAME).toString();
        this.configPath = Path.of(shedDirectoryPath).resolve(SHED_CONFIG_NAME).toString();

        loadDefaults();
        loadConfig();
    }

    // Load default configuration
    private void loadDefaults() {
        config.clear();
        settings.clearDefaults();
        defineDefault("theme", DEFAULT_THEME);
        defineDefault("font.family", DEFAULT_FONT_FAMILY);
        defineDefault("font.size", DEFAULT_FONT_SIZE);
        defineDefault("ui.font.family", DEFAULT_UI_FONT_FAMILY);
        defineDefault("ui.font.size", DEFAULT_UI_FONT_SIZE);
        defineDefault("terminal.font.family", DEFAULT_TERMINAL_FONT_FAMILY);
        defineDefault("terminal.font.size", DEFAULT_TERMINAL_FONT_SIZE);
        defineDefault("tab.size", DEFAULT_TAB_SIZE);
        defineDefault(KeymapProfile.CONFIG_KEY, DEFAULT_KEYMAP_PROFILE.configValue());
        defineDefault("line.numbers", DEFAULT_LINE_NUMBER_MODE.toConfigValue());
        defineDefault("show.current.line", DEFAULT_SHOW_CURRENT_LINE);
        defineDefault("expand.tab", DEFAULT_EXPAND_TAB);
        defineDefault("auto.indent", DEFAULT_AUTO_INDENT);
        defineDefault("highlight.search", DEFAULT_HIGHLIGHT_SEARCH);
        defineDefault("zen.mode.width", DEFAULT_ZEN_MODE_WIDTH);
        defineDefault("ruler.column", 0);
        defineDefault("list", false);
        defineDefault("session.restore.on.start", DEFAULT_SESSION_RESTORE_ON_START);
        defineDefault("session.autoload", DEFAULT_SESSION_AUTOLOAD);
        defineDefault("session.dir", defaultSessionDirectoryPath());
        defineDefault("terminal.session.restore", DEFAULT_TERMINAL_SESSION_RESTORE);
        defineDefault("terminal.shell.integration", DEFAULT_TERMINAL_SHELL_INTEGRATION);
        defineDefault("snippets.directory", defaultSnippetsDirectoryPath());
        defineDefault("landing.source", defaultLandingSourcePath());
        defineDefault("landing.remote.cache.path", defaultLandingRemoteCachePath());
        defineDefault("landing.remote.timeout.ms", DEFAULT_LANDING_REMOTE_TIMEOUT_MS);
        defineDefault("workspace.index.enabled", DEFAULT_WORKSPACE_INDEX_ENABLED);
        defineDefault("large.file.threshold.mb", DEFAULT_LARGE_FILE_THRESHOLD_MB);
        defineDefault("large.file.line.threshold", DEFAULT_LARGE_FILE_LINE_THRESHOLD);
        defineDefault("large.file.preview.lines", DEFAULT_LARGE_FILE_PREVIEW_LINES);
        defineDefault("recovery.retention.max.entries", DEFAULT_RECOVERY_RETENTION_MAX_ENTRIES);
        defineDefault("recovery.retention.max.content.bytes", DEFAULT_RECOVERY_RETENTION_MAX_CONTENT_BYTES);
        defineDefault("recovery.cleanup.on.clean.exit", DEFAULT_RECOVERY_CLEANUP_ON_CLEAN_EXIT);
        defineDefault("backup.enabled", DEFAULT_BACKUP_ENABLED);
        defineDefault("backup.mode", DEFAULT_BACKUP_MODE);
        defineDefault("backup.directory", Path.of(shedDirectoryPath).resolve("backups").toString());
        defineDefault("backup.retention.count", DEFAULT_BACKUP_RETENTION_COUNT);
        defineDefault("undo.history.max.entries", DEFAULT_UNDO_HISTORY_MAX_ENTRIES);
        defineDefault("undo.history.max.bytes", DEFAULT_UNDO_HISTORY_MAX_BYTES);
        defineDefault("process.timeout.ms", DEFAULT_PROCESS_TIMEOUT_MS);
        defineDefault("process.output.max.bytes", DEFAULT_PROCESS_OUTPUT_MAX_BYTES);
        defineDefault("shell.command.enabled", DEFAULT_SHELL_COMMAND_ENABLED);
        defineDefault("shell.command.max.length", DEFAULT_SHELL_COMMAND_MAX_LENGTH);
        defineDefault("scrolloff", DEFAULT_SCROLLOFF);
        defineDefault("auto.pairs", DEFAULT_AUTO_PAIRS);
        defineDefault("textwidth", DEFAULT_TEXTWIDTH);
        defineDefault("minimap", DEFAULT_MINIMAP);
        defineDefault("minimap.width", DEFAULT_MINIMAP_WIDTH);
        defineDefault("limelight.coefficient", DEFAULT_LIMELIGHT_COEFFICIENT);
        defineDefault("limelight.paragraph.span", DEFAULT_LIMELIGHT_PARAGRAPH_SPAN);
        defineDefault("multi.selection.enabled", DEFAULT_MULTI_SELECTION_ENABLED);
        defineDefault("multi.selection.max.cursors", MultiSelectionPolicy.DEFAULT_MAX_CURSORS);
        defineDefault("markdown.preview.scroll.sync", DEFAULT_MARKDOWN_PREVIEW_SCROLL_SYNC);
        LspFeatureSettings lspFeatures = LspFeatureSettings.defaults();
        defineDefault("lsp.completion.enabled", lspFeatures.completion());
        defineDefault("lsp.snippets.enabled", lspFeatures.snippets());
        defineDefault("lsp.completion.auto.show", DEFAULT_LSP_COMPLETION_AUTO_SHOW);
        defineDefault("lsp.completion.delay.ms", DEFAULT_LSP_COMPLETION_DELAY_MS);
        defineDefault("lsp.completion.trigger.characters", DEFAULT_LSP_COMPLETION_TRIGGER_CHARACTERS);
        defineDefault("lsp.completion.fuzzy.matching", DEFAULT_LSP_COMPLETION_FUZZY_MATCHING);
        defineDefault("lsp.completion.local.words", DEFAULT_LSP_COMPLETION_LOCAL_WORDS);
        defineDefault("lsp.completion.commit.characters", DEFAULT_LSP_COMPLETION_COMMIT_CHARACTERS);
        defineDefault("lsp.signature.help.enabled", lspFeatures.signatureHelp());
        defineDefault("lsp.hover.enabled", lspFeatures.hover());
        defineDefault("lsp.semantic.tokens.enabled", lspFeatures.semanticTokens());
        defineDefault("lsp.inlay.hints.enabled", lspFeatures.inlayHints());
        defineDefault("lsp.semantic.tokens.inline", DEFAULT_LSP_SEMANTIC_TOKENS_INLINE);
        defineDefault("lsp.inlay.hints.inline", DEFAULT_LSP_INLAY_HINTS_INLINE);
        defineDefault("lsp.definition.enabled", lspFeatures.definition());
        defineDefault("lsp.type.definition.enabled", lspFeatures.typeDefinition());
        defineDefault("lsp.call.hierarchy.enabled", lspFeatures.callHierarchy());
        defineDefault("lsp.type.hierarchy.enabled", lspFeatures.typeHierarchy());
        defineDefault("lsp.references.enabled", lspFeatures.references());
        defineDefault("lsp.rename.enabled", lspFeatures.rename());
        defineDefault("lsp.code.actions.enabled", lspFeatures.codeActions());
        defineDefault("lsp.command.execution.enabled", lspFeatures.commandExecution());
        defineDefault("lsp.formatting.enabled", lspFeatures.formatting());
        defineDefault("lsp.format.on.save.enabled", false);
        defineDefault("remote.lsp.enabled", false);
        defineDefault("ui.whichkey.hints", DEFAULT_UI_WHICHKEY_HINTS);
        defineDefault("project.config.enabled", DEFAULT_PROJECT_CONFIG_ENABLED);
        defineDefault("project.config.allow.unsafe", DEFAULT_PROJECT_CONFIG_ALLOW_UNSAFE);
        defineDefault("project.config.require.trusted.file", DEFAULT_PROJECT_CONFIG_REQUIRE_TRUSTED_FILE);
        defineDefault("project.replace.enabled", DEFAULT_PROJECT_REPLACE_ENABLED);
        defineDefault("project.replace.preview.required", DEFAULT_PROJECT_REPLACE_PREVIEW_REQUIRED);
        defineDefault("project.replace.confirm.required", DEFAULT_PROJECT_REPLACE_CONFIRM_REQUIRED);
        defineDefault("project.replace.backup.enabled", DEFAULT_PROJECT_REPLACE_BACKUP_ENABLED);
        defineDefault("project.replace.backup.directory", Path.of(shedDirectoryPath).resolve("project-replace-backups").toString());
        defineDefault("project.replace.scope", DEFAULT_PROJECT_REPLACE_SCOPE);
        defineDefault("tree.delete.protect.critical", DEFAULT_TREE_DELETE_PROTECT_CRITICAL);
        defineDefault("git.workbench.enabled", DEFAULT_GIT_WORKBENCH_ENABLED);
        defineDefault("git.changes.enabled", DEFAULT_GIT_CHANGES_ENABLED);
        defineDefault("git.diffs.enabled", DEFAULT_GIT_DIFFS_ENABLED);
        defineDefault("git.staging.enabled", DEFAULT_GIT_STAGING_ENABLED);
        defineDefault("git.conflict.resolution.enabled", DEFAULT_GIT_CONFLICT_RESOLUTION_ENABLED);
        defineDefault("git.history.enabled", DEFAULT_GIT_HISTORY_ENABLED);
        defineDefault("git.remote.actions.enabled", DEFAULT_GIT_REMOTE_ACTIONS_ENABLED);
        defineDefault("git.panel.presentation.enabled", DEFAULT_GIT_PANEL_PRESENTATION_ENABLED);
        defineDefault("git.auto.refresh.enabled", DEFAULT_GIT_AUTO_REFRESH_ENABLED);
        defineDefault("git.auto.refresh.interval.ms", DEFAULT_GIT_AUTO_REFRESH_INTERVAL_MS);
        defineDefault("github.review.enabled", DEFAULT_GITHUB_REVIEW_ENABLED);
        defineDefault("github.review.consent.granted", DEFAULT_GITHUB_REVIEW_CONSENT_GRANTED);
        defineDefault("updates.enabled", DEFAULT_UPDATES_ENABLED);
        defineDefault("updates.consent.granted", DEFAULT_UPDATES_CONSENT_GRANTED);
        defineDefault("updates.metadata.url", DEFAULT_UPDATES_METADATA_URL);
        defineDefault("updates.metadata.public.key", DEFAULT_UPDATES_METADATA_PUBLIC_KEY);
        defineDefault("updates.check.timeout.ms", DEFAULT_UPDATES_CHECK_TIMEOUT_MS);
        DebugFeatureSettings debugFeatures = DebugFeatureSettings.defaults();
        defineDefault("debug.enabled", debugFeatures.enabled());
        defineDefault("debug.breakpoints.enabled", debugFeatures.breakpoints());
        defineDefault("debug.threads.enabled", debugFeatures.threads());
        defineDefault("debug.stacktrace.enabled", debugFeatures.stackTrace());
        defineDefault("debug.scopes.enabled", debugFeatures.scopes());
        defineDefault("debug.variables.enabled", debugFeatures.variables());
        defineDefault("debug.evaluate.enabled", debugFeatures.evaluate());
        defineDefault("debug.attach.enabled", debugFeatures.attach());
        defineDefault("debug.open.source.on.stop", DEFAULT_DEBUG_OPEN_SOURCE_ON_STOP);
        settings.reset();
        defaultConfig.clear();
        defaultConfig.putAll(config);
    }

    private void defineDefault(String key, Object value) {
        settings.define(key, value, settingDescription(key));
        config.put(key, settings.stringify(value));
    }

    private String settingDescription(String key) {
        return switch (key) {
            case "theme" -> "Built-in theme identifier";
            case "font.family" -> "Editor font family";
            case "font.size" -> "Editor font size";
            case "ui.font.family" -> "Application UI font family; empty uses the system UI font";
            case "ui.font.size" -> "Application UI font size; zero uses each system UI default size";
            case "terminal.font.family" -> "Terminal font family";
            case "terminal.font.size" -> "Terminal font size";
            case "tab.size" -> "Tab width in spaces";
            case "keymap.profile" -> "Input keymap profile";
            case "line.numbers" -> "Line number display mode";
            case "show.current.line" -> "Highlight the active line";
            case "expand.tab" -> "Insert spaces for tab input";
            case "auto.indent" -> "Continue indentation on new lines";
            case "highlight.search" -> "Highlight search results";
            case "zen.mode.width" -> "Preferred zen-mode content width";
            case "ruler.column" -> "Vertical ruler column, zero disables it";
            case "list" -> "Show whitespace markers";
            case "session.restore.on.start" -> "Restore the saved session at startup";
            case "session.autoload" -> "Session name loaded at startup";
            case "session.dir" -> "Directory for saved sessions";
            case "terminal.session.restore" -> "Persist terminal panel working directories and restore fresh shells";
            case "snippets.directory" -> "Directory containing VS Code-compatible JSON snippet files";
            case "landing.source" -> "Local path, file URI, or explicitly configured HTTPS landing-page source";
            case "landing.remote.cache.path" -> "Local file used to cache an HTTPS landing-page source";
            case "landing.remote.timeout.ms" -> "HTTPS landing-page connection and request timeout";
            case "workspace.index.enabled" -> "Enable persisted Git-ignore-aware workspace indexing";
            case "large.file.threshold.mb" -> "Large-file size threshold in megabytes";
            case "large.file.line.threshold" -> "Large-file line-count threshold";
            case "large.file.preview.lines" -> "Lines shown in a large-file preview";
            case "recovery.retention.max.entries" -> "Maximum retained recovery journal entries";
            case "recovery.retention.max.content.bytes" -> "Maximum retained recovery journal UTF-8 bytes";
            case "recovery.cleanup.on.clean.exit" -> "Remove recovery data only after a clean exit";
            case "backup.enabled" -> "Create local versioned backups while editing";
            case "backup.mode" -> "Backup timing: idle or save-only";
            case "backup.directory" -> "Directory for local versioned backups";
            case "backup.retention.count" -> "Maximum retained backups per source file";
            case "undo.history.max.entries" -> "Maximum retained undo and redo edits per buffer";
            case "undo.history.max.bytes" -> "Maximum estimated retained undo and redo payload bytes per buffer";
            case "process.timeout.ms" -> "Timeout for helper processes";
            case "process.output.max.bytes" -> "Maximum captured helper-process output";
            case "shell.command.enabled" -> "Enable shell command execution";
            case "shell.command.max.length" -> "Maximum accepted shell command length";
            case "scrolloff" -> "Context lines retained while scrolling";
            case "auto.pairs" -> "Insert matching brackets and quotes";
            case "textwidth" -> "Paragraph width, zero disables wrapping";
            case "minimap" -> "Persisted minimap visibility setting";
            case "minimap.width" -> "Minimap width in pixels";
            case "limelight.coefficient" -> "Background blend strength for unfocused paragraphs";
            case "limelight.paragraph.span" -> "Adjacent paragraphs retained at full brightness";
            case "multi.selection.enabled" -> "Enable experimental multi-selection editing";
            case "multi.selection.max.cursors" -> "Maximum total cursors for experimental multi-selection";
            case "markdown.preview.scroll.sync" -> "Synchronize Markdown preview position with its source cursor and scroll position";
            case "lsp.completion.enabled" -> "Enable LSP completion requests";
            case "lsp.snippets.enabled" -> "Advertise LSP snippet-completion support";
            case "lsp.completion.auto.show" -> "Show completion suggestions while typing";
            case "lsp.completion.delay.ms" -> "Idle delay before automatic completion requests";
            case "lsp.completion.trigger.characters" -> "Request completion after server-advertised trigger characters";
            case "lsp.completion.fuzzy.matching" -> "Fuzzy-filter and rank completion labels";
            case "lsp.completion.local.words" -> "Include cached words from open buffers when LSP results are unavailable";
            case "lsp.completion.commit.characters" -> "Accept a completion when its server-provided commit character is typed";
            case "lsp.signature.help.enabled" -> "Enable LSP signature-help requests";
            case "lsp.hover.enabled" -> "Enable LSP hover requests";
            case "lsp.semantic.tokens.enabled" -> "Enable LSP semantic-token requests";
            case "lsp.inlay.hints.enabled" -> "Enable LSP inlay-hint requests";
            case "lsp.semantic.tokens.inline" -> "Render available LSP semantic tokens in the editor";
            case "lsp.inlay.hints.inline" -> "Render available LSP inlay hints in the editor";
            case "lsp.definition.enabled" -> "Enable LSP definition requests";
            case "lsp.type.definition.enabled" -> "Enable LSP type-definition requests";
            case "lsp.call.hierarchy.enabled" -> "Enable LSP call-hierarchy requests";
            case "lsp.type.hierarchy.enabled" -> "Enable LSP type-hierarchy requests";
            case "lsp.references.enabled" -> "Enable LSP reference requests";
            case "lsp.rename.enabled" -> "Enable LSP rename requests";
            case "lsp.code.actions.enabled" -> "Enable LSP code-action requests";
            case "lsp.command.execution.enabled" -> "Enable LSP execute-command requests";
            case "lsp.formatting.enabled" -> "Enable LSP document-formatting requests";
            case "lsp.format.on.save.enabled" -> "Format with LSP before saving; failed formatting leaves the buffer unsaved";
            case "remote.lsp.enabled" -> "Run explicitly configured LSP servers through connected SSH/container/WSL workspaces or an already running Dev Container";
            case "ui.whichkey.hints" -> "Show prefix-key hint overlays";
            case "project.config.enabled" -> "Enable project-local configuration";
            case "project.config.allow.unsafe" -> "Allow unsafe project-local keys";
            case "project.config.require.trusted.file" -> "Require trusted project configuration files";
            case "project.replace.enabled" -> "Enable project-wide replacement commands";
            case "project.replace.preview.required" -> "Require an in-memory preview before project replacement";
            case "project.replace.confirm.required" -> "Require the explicit apply confirm argument";
            case "project.replace.backup.enabled" -> "Create retained backups before project replacement";
            case "project.replace.backup.directory" -> "Directory for project replacement backups";
            case "project.replace.scope" -> "Project replacement scope";
            case "tree.delete.protect.critical" -> "Protect critical paths from tree deletion";
            case "git.workbench.enabled" -> "Enable the graphical read-only Git changes workbench";
            case "git.changes.enabled" -> "Enable the graphical Git changes document";
            case "git.diffs.enabled" -> "Enable graphical Git diff and hunk navigation";
            case "git.staging.enabled" -> "Enable Git index staging and unstaging commands";
            case "git.conflict.resolution.enabled" -> "Enable the graphical Git conflict-resolution document";
            case "git.history.enabled" -> "Enable the graphical Git history document";
            case "git.remote.actions.enabled" -> "Enable explicit Fetch, Pull, and Push controls in Git history";
            case "git.panel.presentation.enabled" -> "Enable graphical Git workbench documents";
            case "git.auto.refresh.enabled" -> "Refresh the visible Git Changes panel when the repository state changes";
            case "git.auto.refresh.interval.ms" -> "Git Changes panel polling interval while visible";
            case "github.review.enabled" -> "Enable explicit GitHub review integration actions";
            case "github.review.consent.granted" -> "Record explicit GitHub review integration consent";
            case "updates.enabled" -> "Enable automatic signed update metadata checks only with consent";
            case "updates.consent.granted" -> "Record explicit consent for automatic update metadata checks";
            case "updates.metadata.url" -> "HTTPS endpoint for signed update metadata; empty disables checks";
            case "updates.metadata.public.key" -> "Base64 Ed25519 SubjectPublicKeyInfo used to verify update metadata";
            case "updates.check.timeout.ms" -> "Connect and request timeout for an explicit update metadata check";
            case "debug.enabled" -> "Enable explicit debug-session planning";
            case "debug.breakpoints.enabled" -> "Enable debug breakpoint configuration";
            case "debug.threads.enabled" -> "Enable debug thread presentation";
            case "debug.stacktrace.enabled" -> "Enable debug stack-trace presentation";
            case "debug.scopes.enabled" -> "Enable debug scope presentation";
            case "debug.variables.enabled" -> "Enable debug variable presentation";
            case "debug.evaluate.enabled" -> "Enable debug expression evaluation";
            case "debug.attach.enabled" -> "Enable debug attach planning";
            case "debug.open.source.on.stop" -> "Open the selected local source frame when debugging pauses";
            default -> key;
        };
    }

    private void loadConfig() {
        persistedConfig.clear();
        configLoadFailed = false;
        Path path = Path.of(configPath);
        List<String> errors = new ArrayList<>();
        Map<String, Object> parsed;
        try {
            parsed = parseTomlConfig(path, errors);
        } catch (java.nio.file.NoSuchFileException error) {
            configLoadReport = "Configuration not found: " + path
                + "\nSafe defaults are active. Run :config save to create it.";
            return;
        } catch (IOException | SecurityException error) {
            errors.add("read failed: " + loadErrorMessage(error));
            parsed = Map.of();
        }
        if (!errors.isEmpty()) {
            configLoadFailed = true;
            configLoadReport = configRecoveryReport(path, errors);
            return;
        }
        debugConfiguration = DebugAdapterRegistry.validate(parsed);
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            settings.apply(entry.getKey(), entry.getValue());
            String value = settings.stringify(entry.getValue());
            persistedConfig.put(entry.getKey(), value);
            config.put(entry.getKey(), value);
        }
        configLoadReport = "Configuration loaded: " + path;
    }

    private Map<String, Object> parseTomlConfig(Path path, List<String> errors) throws IOException {
        TomlParseResult result = Toml.parse(path);
        for (TomlParseError error : result.errors()) {
            errors.add(tomlLocation(error.position()) + error.getMessage());
        }
        if (!errors.isEmpty()) {
            return Map.of();
        }
        String versionError = ConfigSchema.versionError(result);
        if (versionError != null) {
            errors.add(tomlLocation(result.inputPositionOf(ConfigSchema.VERSION_KEY)) + versionError);
        }
        if (!errors.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<List<String>, Object> entry : result.entryPathSet()) {
            if (ConfigSchema.isVersionEntry(entry.getKey())) {
                continue;
            }
            String key = String.join(".", entry.getKey());
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Long || value instanceof Double || value instanceof Boolean)) {
                errors.add(tomlLocation(result.inputPositionOf(entry.getKey()))
                    + "unsupported TOML value for " + key + fallbackDescription(key));
                continue;
            }
            String validationError = settings.validateToml(key, value);
            if (key.startsWith("formatter.") && key.endsWith(".format.on.save")) {
                validationError = value instanceof Boolean ? FormatterPolicy.validateConfig(key, Boolean.toString((Boolean) value)) : key + " must be a TOML boolean";
            } else if (validationError == null && key.startsWith("formatter.")) {
                validationError = value instanceof String ? FormatterPolicy.validateConfig(key, (String) value) : key + " must be a TOML string";
            }
            if (validationError == null && KeymapOverlay.isKeybindKey(key)) {
                validationError = value instanceof String ? KeymapOverlay.validate(key, (String) value) : key + " must be a TOML string";
            }
            if (validationError != null) {
                errors.add(tomlLocation(result.inputPositionOf(entry.getKey())) + validationError + fallbackDescription(key));
                continue;
            }
            try {
                parsed.put(normalizePersistedKey(key), value);
            } catch (IOException error) {
                errors.add(tomlLocation(result.inputPositionOf(entry.getKey())) + error.getMessage() + fallbackDescription(key));
            }
        }
        DebugAdapterRegistry.Validation debug = DebugAdapterRegistry.validate(parsed);
        for (DebugAdapterRegistry.Error error : debug.errors()) {
            errors.add(tomlLocation(debugTomlPosition(result, error.key())) + error.message() + fallbackDescription(error.key()));
        }
        return parsed;
    }

    private TomlPosition debugTomlPosition(TomlParseResult result, String key) {
        for (Map.Entry<List<String>, Object> entry : result.entryPathSet()) {
            if (key.equals(String.join(".", entry.getKey()))) return result.inputPositionOf(entry.getKey());
        }
        return null;
    }

    private String fallbackDescription(String key) {
        if (reloadFallbackConfig != null) {
            String retained = reloadFallbackConfig.get(key);
            return retained == null ? " (active fallback: no override)" : " (active fallback: " + retained + ")";
        }
        Object typedValue = settings.activeValue(key);
        if (typedValue != null) {
            return " (active fallback: " + settings.stringify(typedValue) + ")";
        }
        String value = config.get(key);
        return value == null ? " (active fallback: no override)" : " (active fallback: " + value + ")";
    }

    private String tomlLocation(TomlPosition position) {
        return position == null ? "" : "line " + position.line() + ", column " + position.column() + ": ";
    }

    private String loadErrorMessage(Exception error) {
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.getClass().getSimpleName() : message;
    }

    private String configRecoveryReport(Path path, List<String> errors) {
        StringBuilder report = new StringBuilder("Configuration recovery: ").append(path)
            .append("\nInvalid configuration was preserved unchanged.\nSafe defaults are active.\n\nValidation:");
        for (String error : errors) {
            report.append("\n- ").append(error);
        }
        return report.append("\n\nRemediation: correct the listed line(s), then run :reload.").toString();
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
        settings.applyRuntime("theme", alias);
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
        return getString("font.family", DEFAULT_FONT_FAMILY);
    }

    // Get font size
    public int getFontSize() {
        return getInt("font.size", DEFAULT_FONT_SIZE);
    }

    public String getUiFontFamily() {
        return getString("ui.font.family", DEFAULT_UI_FONT_FAMILY);
    }

    public int getUiFontSize() {
        return getInt("ui.font.size", DEFAULT_UI_FONT_SIZE);
    }

    public String getTerminalFontFamily() {
        return getString("terminal.font.family", DEFAULT_TERMINAL_FONT_FAMILY);
    }

    public int getTerminalFontSize() {
        return getInt("terminal.font.size", DEFAULT_TERMINAL_FONT_SIZE);
    }

    public String getSnippetsDirectory() {
        String configured = getString("snippets.directory", defaultSnippetsDirectoryPath());
        String value = configured == null || configured.isBlank() ? defaultSnippetsDirectoryPath() : configured.trim();
        if (value.equals("~")) return System.getProperty("user.home");
        if (value.startsWith("~/") || value.startsWith("~\\")) return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        return value;
    }

    // Get tab size
    public int getTabSize() {
        return getInt("tab.size", DEFAULT_TAB_SIZE);
    }

    // Get line numbers preference
    public boolean getLineNumbers() {
        return getLineNumberMode() != LineNumberMode.NONE;
    }

    public LineNumberMode getLineNumberMode() {
        return LineNumberMode.fromConfigValue(getString("line.numbers", DEFAULT_LINE_NUMBER_MODE.toConfigValue()));
    }

    public void setLineNumberMode(LineNumberMode mode) {
        config.put("line.numbers", mode.toConfigValue());
        settings.applyRuntime("line.numbers", mode.toConfigValue());
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
        String configured = getString("session.autoload", DEFAULT_SESSION_AUTOLOAD);
        String trimmed = configured == null ? "" : configured.trim();
        return trimmed.isEmpty() ? DEFAULT_SESSION_AUTOLOAD : trimmed;
    }

    public String getSessionDirectory() {
        String configured = getString("session.dir", "");
        if (configured == null || configured.isBlank()) {
            return defaultSessionDirectoryPath();
        }
        return configured.trim();
    }

    public String getLandingSource() {
        String configured = getString("landing.source", defaultLandingSourcePath());
        return configured == null || configured.isBlank() ? defaultLandingSourcePath() : configured.trim();
    }

    public String getLandingRemoteCachePath() {
        String configured = getString("landing.remote.cache.path", defaultLandingRemoteCachePath());
        return configured == null || configured.isBlank() ? defaultLandingRemoteCachePath() : configured.trim();
    }

    public int getLandingRemoteTimeoutMs() {
        return getInt("landing.remote.timeout.ms", DEFAULT_LANDING_REMOTE_TIMEOUT_MS);
    }

    public boolean getTerminalSessionRestoreEnabled() {
        return getBoolean("terminal.session.restore", DEFAULT_TERMINAL_SESSION_RESTORE);
    }

    public boolean getTerminalShellIntegrationEnabled() {
        return getBoolean("terminal.shell.integration", DEFAULT_TERMINAL_SHELL_INTEGRATION);
    }

    public boolean getWorkspaceIndexEnabled() {
        return getBoolean("workspace.index.enabled", DEFAULT_WORKSPACE_INDEX_ENABLED);
    }

    public ProjectReplacePolicy getProjectReplacePolicy() {
        return new ProjectReplacePolicy(
            getBoolean("project.replace.enabled", DEFAULT_PROJECT_REPLACE_ENABLED),
            getBoolean("project.replace.preview.required", DEFAULT_PROJECT_REPLACE_PREVIEW_REQUIRED),
            getBoolean("project.replace.confirm.required", DEFAULT_PROJECT_REPLACE_CONFIRM_REQUIRED),
            getBoolean("project.replace.backup.enabled", DEFAULT_PROJECT_REPLACE_BACKUP_ENABLED),
            getString("project.replace.backup.directory", Path.of(shedDirectoryPath).resolve("project-replace-backups").toString()),
            getString("project.replace.scope", DEFAULT_PROJECT_REPLACE_SCOPE)
        );
    }

    public long getLargeFileThresholdMb() {
        return getLong("large.file.threshold.mb", DEFAULT_LARGE_FILE_THRESHOLD_MB);
    }

    public int getLargeFileLineThreshold() {
        return getInt("large.file.line.threshold", DEFAULT_LARGE_FILE_LINE_THRESHOLD);
    }

    public int getLargeFilePreviewLines() {
        return getInt("large.file.preview.lines", DEFAULT_LARGE_FILE_PREVIEW_LINES);
    }

    public RecoveryJournal.RetentionPolicy getRecoveryRetentionPolicy() {
        return new RecoveryJournal.RetentionPolicy(
            getInt("recovery.retention.max.entries", DEFAULT_RECOVERY_RETENTION_MAX_ENTRIES),
            getInt("recovery.retention.max.content.bytes", DEFAULT_RECOVERY_RETENTION_MAX_CONTENT_BYTES)
        );
    }

    public boolean getRecoveryCleanupOnCleanExit() {
        return getBoolean("recovery.cleanup.on.clean.exit", DEFAULT_RECOVERY_CLEANUP_ON_CLEAN_EXIT);
    }

    public BackupPolicy getBackupPolicy() {
        String directory = getString("backup.directory", Path.of(shedDirectoryPath).resolve("backups").toString());
        return new BackupPolicy(getBoolean("backup.enabled", DEFAULT_BACKUP_ENABLED), directory,
            getInt("backup.retention.count", DEFAULT_BACKUP_RETENTION_COUNT),
            BackupPolicy.BackupMode.parse(getString("backup.mode", DEFAULT_BACKUP_MODE)));
    }

    public UndoHistoryPolicy getUndoHistoryPolicy() {
        return new UndoHistoryPolicy(
            getInt("undo.history.max.entries", DEFAULT_UNDO_HISTORY_MAX_ENTRIES),
            getLong("undo.history.max.bytes", DEFAULT_UNDO_HISTORY_MAX_BYTES)
        );
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
    public boolean getShellCommandEnabled() {
        return getBoolean("shell.command.enabled", DEFAULT_SHELL_COMMAND_ENABLED);
    }
    public int getScrolloff() {
        return getInt("scrolloff", DEFAULT_SCROLLOFF);
    }
    public boolean getAutoPairs() {
        return getBoolean("auto.pairs", DEFAULT_AUTO_PAIRS);
    }

    public boolean getMarkdownPreviewScrollSync() {
        return getBoolean("markdown.preview.scroll.sync", DEFAULT_MARKDOWN_PREVIEW_SCROLL_SYNC);
    }
    public int getTextWidth() {
        return getInt("textwidth", DEFAULT_TEXTWIDTH);
    }
    public boolean getMinimap() {
        return getBoolean("minimap", DEFAULT_MINIMAP);
    }

    public MultiSelectionPolicy getMultiSelectionPolicy() {
        return new MultiSelectionPolicy(
            getBoolean("multi.selection.enabled", DEFAULT_MULTI_SELECTION_ENABLED),
            getInt("multi.selection.max.cursors", MultiSelectionPolicy.DEFAULT_MAX_CURSORS)
        );
    }

    public KeymapProfile getKeymapProfile() {
        return KeymapProfile.fromConfig(getString(KeymapProfile.CONFIG_KEY, DEFAULT_KEYMAP_PROFILE.configValue()));
    }

    public LspFeatureSettings getLspFeatureSettings() {
        LspFeatureSettings defaults = LspFeatureSettings.defaults();
        return new LspFeatureSettings(
            getBoolean("lsp.completion.enabled", defaults.completion()),
            getBoolean("lsp.snippets.enabled", defaults.snippets()),
            getBoolean("lsp.signature.help.enabled", defaults.signatureHelp()),
            getBoolean("lsp.hover.enabled", defaults.hover()),
            getBoolean("lsp.semantic.tokens.enabled", defaults.semanticTokens()),
            getBoolean("lsp.inlay.hints.enabled", defaults.inlayHints()),
            getBoolean("lsp.definition.enabled", defaults.definition()),
            getBoolean("lsp.type.definition.enabled", defaults.typeDefinition()),
            getBoolean("lsp.call.hierarchy.enabled", defaults.callHierarchy()),
            getBoolean("lsp.type.hierarchy.enabled", defaults.typeHierarchy()),
            getBoolean("lsp.references.enabled", defaults.references()),
            getBoolean("lsp.rename.enabled", defaults.rename()),
            getBoolean("lsp.code.actions.enabled", defaults.codeActions()),
            getBoolean("lsp.command.execution.enabled", defaults.commandExecution()),
            getBoolean("lsp.formatting.enabled", defaults.formatting())
        );
    }

    public boolean getLspFormatOnSaveEnabled() {
        return getBoolean("lsp.format.on.save.enabled", false);
    }

    public boolean getRemoteLspEnabled() {
        return getBoolean("remote.lsp.enabled", false);
    }

    public boolean getLspSemanticTokensInline() {
        return getBoolean("lsp.semantic.tokens.inline", DEFAULT_LSP_SEMANTIC_TOKENS_INLINE);
    }

    public boolean getLspCompletionAutoShow() {
        return getBoolean("lsp.completion.auto.show", DEFAULT_LSP_COMPLETION_AUTO_SHOW);
    }

    public int getLspCompletionDelayMs() {
        return Math.max(0, Math.min(1000, getInt("lsp.completion.delay.ms", DEFAULT_LSP_COMPLETION_DELAY_MS)));
    }

    public boolean getLspCompletionTriggerCharacters() {
        return getBoolean("lsp.completion.trigger.characters", DEFAULT_LSP_COMPLETION_TRIGGER_CHARACTERS);
    }

    public boolean getLspCompletionFuzzyMatching() {
        return getBoolean("lsp.completion.fuzzy.matching", DEFAULT_LSP_COMPLETION_FUZZY_MATCHING);
    }

    public boolean getLspCompletionLocalWords() {
        return getBoolean("lsp.completion.local.words", DEFAULT_LSP_COMPLETION_LOCAL_WORDS);
    }

    public boolean getLspCompletionCommitCharacters() {
        return getBoolean("lsp.completion.commit.characters", DEFAULT_LSP_COMPLETION_COMMIT_CHARACTERS);
    }

    public boolean getLspInlayHintsInline() {
        return getBoolean("lsp.inlay.hints.inline", DEFAULT_LSP_INLAY_HINTS_INLINE);
    }

    public int getMinimapWidth() {
        return Math.max(40, getInt("minimap.width", DEFAULT_MINIMAP_WIDTH));
    }

    public double getLimelightCoefficient() {
        return Math.max(0.0, Math.min(1.0, getDouble("limelight.coefficient", DEFAULT_LIMELIGHT_COEFFICIENT)));
    }

    public int getLimelightParagraphSpan() {
        return Math.max(0, getInt("limelight.paragraph.span", DEFAULT_LIMELIGHT_PARAGRAPH_SPAN));
    }

    public boolean getWhichKeyHintsEnabled() {
        return getBoolean("ui.whichkey.hints", DEFAULT_UI_WHICHKEY_HINTS);
    }

    public boolean getProjectConfigEnabled() {
        return getBoolean("project.config.enabled", DEFAULT_PROJECT_CONFIG_ENABLED);
    }

    public boolean getProjectConfigAllowUnsafe() {
        return getBoolean("project.config.allow.unsafe", DEFAULT_PROJECT_CONFIG_ALLOW_UNSAFE);
    }

    public boolean getProjectConfigRequireTrustedFile() {
        return getBoolean("project.config.require.trusted.file", DEFAULT_PROJECT_CONFIG_REQUIRE_TRUSTED_FILE);
    }

    public boolean getTreeDeleteProtectCritical() {
        return getBoolean("tree.delete.protect.critical", DEFAULT_TREE_DELETE_PROTECT_CRITICAL);
    }

    public boolean getGitWorkbenchEnabled() {
        return getBoolean("git.workbench.enabled", DEFAULT_GIT_WORKBENCH_ENABLED);
    }

    public boolean getGitChangesEnabled() {
        return getBoolean("git.changes.enabled", DEFAULT_GIT_CHANGES_ENABLED);
    }

    public boolean getGitDiffsEnabled() {
        return getBoolean("git.diffs.enabled", DEFAULT_GIT_DIFFS_ENABLED);
    }

    public boolean getGitStagingEnabled() {
        return getBoolean("git.staging.enabled", DEFAULT_GIT_STAGING_ENABLED);
    }

    public boolean getGitConflictResolutionEnabled() {
        return getBoolean("git.conflict.resolution.enabled", DEFAULT_GIT_CONFLICT_RESOLUTION_ENABLED);
    }

    public boolean getGitHistoryEnabled() {
        return getBoolean("git.history.enabled", DEFAULT_GIT_HISTORY_ENABLED);
    }

    public boolean getGitRemoteActionsEnabled() {
        return getBoolean("git.remote.actions.enabled", DEFAULT_GIT_REMOTE_ACTIONS_ENABLED);
    }

    public boolean getGitPanelPresentationEnabled() {
        return getBoolean("git.panel.presentation.enabled", DEFAULT_GIT_PANEL_PRESENTATION_ENABLED);
    }

    public boolean getGitAutoRefreshEnabled() {
        return getBoolean("git.auto.refresh.enabled", DEFAULT_GIT_AUTO_REFRESH_ENABLED);
    }

    public int getGitAutoRefreshIntervalMs() {
        return Math.max(500, Math.min(60000, getInt("git.auto.refresh.interval.ms", DEFAULT_GIT_AUTO_REFRESH_INTERVAL_MS)));
    }

    public boolean getGitHubReviewEnabled() {
        return getGitHubReviewConsent().enabled();
    }

    public boolean getGitHubReviewConsentGranted() {
        return getBoolean("github.review.consent.granted", DEFAULT_GITHUB_REVIEW_CONSENT_GRANTED);
    }

    public GitHubReviewConsent.State getGitHubReviewConsent() {
        return GitHubReviewConsent.from(getBoolean("github.review.enabled", DEFAULT_GITHUB_REVIEW_ENABLED), getGitHubReviewConsentGranted());
    }

    public boolean getUpdatesEnabled() {
        return getUpdateConsent().enabled();
    }

    public boolean getUpdateConsentGranted() {
        return getBoolean("updates.consent.granted", DEFAULT_UPDATES_CONSENT_GRANTED);
    }

    public UpdateConsent.State getUpdateConsent() {
        return UpdateConsent.from(getBoolean("updates.enabled", DEFAULT_UPDATES_ENABLED), getUpdateConsentGranted());
    }

    public String getUpdateMetadataUrl() {
        return getString("updates.metadata.url", DEFAULT_UPDATES_METADATA_URL).trim();
    }

    public String getUpdateMetadataPublicKey() {
        return getString("updates.metadata.public.key", DEFAULT_UPDATES_METADATA_PUBLIC_KEY).trim();
    }

    public int getUpdateCheckTimeoutMs() {
        return getInt("updates.check.timeout.ms", DEFAULT_UPDATES_CHECK_TIMEOUT_MS);
    }

    public DebugFeatureSettings getDebugFeatureSettings() {
        DebugFeatureSettings defaults = DebugFeatureSettings.defaults();
        return new DebugFeatureSettings(getBoolean("debug.enabled", defaults.enabled()), getBoolean("debug.breakpoints.enabled", defaults.breakpoints()),
            getBoolean("debug.threads.enabled", defaults.threads()), getBoolean("debug.stacktrace.enabled", defaults.stackTrace()),
            getBoolean("debug.scopes.enabled", defaults.scopes()), getBoolean("debug.variables.enabled", defaults.variables()),
            getBoolean("debug.evaluate.enabled", defaults.evaluate()), getBoolean("debug.attach.enabled", defaults.attach()));
    }

    public boolean getDebugOpenSourceOnStop() {
        return getBoolean("debug.open.source.on.stop", DEFAULT_DEBUG_OPEN_SOURCE_ON_STOP);
    }

    DebugAdapterRegistry.Validation getDebugConfiguration() {
        return debugConfiguration;
    }

    public boolean isProjectConfigKeyAllowed(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (getProjectConfigAllowUnsafe()) {
            return true;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("theme")
            || normalized.equals("tab.size")
            || normalized.equals("line.numbers")
            || normalized.equals("show.current.line")
            || normalized.equals("expand.tab")
            || normalized.equals("auto.indent")
            || normalized.equals("highlight.search")
            || normalized.equals("scrolloff")
            || normalized.equals("textwidth")
            || normalized.equals("list")
            || normalized.equals("conceallevel")
            || normalized.equals("ruler.column")
            || normalized.equals("minimap")
            || normalized.startsWith("ui.")
            || normalized.startsWith("color.")
            || normalized.startsWith("font.");
    }

    public void set(String key, String value) {
        if (isSchemaVersionKey(key)) {
            return;
        }
        String normalizedKey;
        try {
            normalizedKey = normalizePersistedKey(key);
        } catch (IOException error) {
            return;
        }
        String normalizedValue = value == null ? "" : value;
        if (validateSettingValue(normalizedKey, normalizedValue) != null) {
            return;
        }
        config.put(normalizedKey, normalizedValue);
        settings.applyRuntime(normalizedKey, normalizedValue);
    }

    public void setAndPersist(String key, String value) throws IOException {
        if (isSchemaVersionKey(key)) {
            throw new IOException(ConfigSchema.VERSION_KEY + " is managed by Shed");
        }
        String normalizedKey = normalizePersistedKey(key);
        String normalizedValue = normalizePersistedValue(value == null ? "" : value);
        String validationError = validateSettingValue(normalizedKey, normalizedValue);
        if (validationError != null) {
            throw new IOException(validationError);
        }
        config.put(normalizedKey, normalizedValue);
        settings.applyRuntime(normalizedKey, normalizedValue);
        persistedConfig.put(normalizedKey, normalizedValue);
        writeConfigFile();
    }

    public void resetAndPersist(String key) throws IOException {
        if (isSchemaVersionKey(key)) {
            throw new IOException(ConfigSchema.VERSION_KEY + " is managed by Shed");
        }
        String normalizedKey = normalizePersistedKey(key);
        String defaultValue = settings.defaultValue(normalizedKey);
        if (defaultValue == null) {
            throw new IOException("no canonical default for " + normalizedKey);
        }
        config.put(normalizedKey, defaultValue);
        settings.applyRuntime(normalizedKey, defaultValue);
        persistedConfig.remove(normalizedKey);
        writeConfigFile();
    }

    public void resetKeybindingAndPersist(String scope, String lhs) throws IOException {
        String key = KeymapOverlay.normalizeKey("keybind." + (scope == null ? "" : scope) + "." + (lhs == null ? "" : lhs));
        config.remove(key);
        persistedConfig.remove(key);
        writeConfigFile();
    }

    public int persistCurrentConfig() throws IOException {
        persistedConfig.clear();
        List<String> keys = new ArrayList<>(config.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            if (ConfigSchema.VERSION_KEY.equals(key)) {
                continue;
            }
            String value = config.get(key);
            if (value == null) {
                continue;
            }
            String normalizedKey;
            String normalizedValue;
            try {
                normalizedKey = normalizePersistedKey(key);
                normalizedValue = normalizePersistedValue(value);
            } catch (IOException ignored) {
                continue;
            }
            if (ConfigSchema.VERSION_KEY.equals(normalizedKey)) {
                continue;
            }
            String defaultValue = defaultConfig.get(normalizedKey);
            if (defaultValue == null || !defaultValue.equals(normalizedValue)) {
                persistedConfig.put(normalizedKey, normalizedValue);
            }
        }
        writeConfigFile();
        return persistedConfig.size();
    }

    public void reload() {
        Map<String, String> configBeforeReload = new HashMap<>(config);
        Map<String, String> defaultsBeforeReload = new HashMap<>(defaultConfig);
        Map<String, String> persistedBeforeReload = new HashMap<>(persistedConfig);
        Map<String, String> projectBeforeReload = new HashMap<>(projectConfig);
        Map<String, String> projectPreviousBeforeReload = new HashMap<>(projectPreviousValues);
        Map<String, Object> settingsBeforeReload = settings.copyValues();
        File activeProjectBeforeReload = activeProjectConfigFile;
        DebugAdapterRegistry.Validation debugBeforeReload = debugConfiguration;
        reloadFallbackConfig = configBeforeReload;
        try {
            config.clear();
            projectConfig.clear();
            projectPreviousValues.clear();
            activeProjectConfigFile = null;
            loadDefaults();
            loadConfig();
        } finally {
            reloadFallbackConfig = null;
        }
        if (!configLoadFailed) {
            return;
        }
        config.clear();
        config.putAll(configBeforeReload);
        defaultConfig.clear();
        defaultConfig.putAll(defaultsBeforeReload);
        persistedConfig.clear();
        persistedConfig.putAll(persistedBeforeReload);
        projectConfig.clear();
        projectConfig.putAll(projectBeforeReload);
        projectPreviousValues.clear();
        projectPreviousValues.putAll(projectPreviousBeforeReload);
        settings.restoreValues(settingsBeforeReload);
        activeProjectConfigFile = activeProjectBeforeReload;
        debugConfiguration = debugBeforeReload;
        configLoadReport = configLoadReport.replace("Safe defaults are active.", "Last-known-good configuration remains active.");
    }

    public String applyProjectConfigForFile(File file) {
        if (!getProjectConfigEnabled()) {
            if (activeProjectConfigFile == null) {
                return "";
            }
            restoreProjectOverrides();
            projectConfig.clear();
            projectPreviousValues.clear();
            activeProjectConfigFile = null;
            return "Project config disabled";
        }

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

        if (getProjectConfigRequireTrustedFile() && !isTrustedProjectConfigFile(localConfig)) {
            return "Project config blocked (untrusted file): " + localConfig.getAbsolutePath();
        }

        try {
            Map<String, String> parsed = parseConfigFile(localConfig);
            Map<String, String> allowed = new LinkedHashMap<>();
            int skipped = 0;
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                if (isProjectConfigKeyAllowed(entry.getKey())) {
                    allowed.put(entry.getKey(), entry.getValue());
                } else {
                    skipped++;
                }
            }
            if (parsed.isEmpty()) {
                activeProjectConfigFile = localConfig;
                return "Project config loaded: " + localConfig.getAbsolutePath() + " (no overrides)";
            }
            for (Map.Entry<String, String> entry : allowed.entrySet()) {
                String key = entry.getKey();
                if (!projectPreviousValues.containsKey(key)) {
                    projectPreviousValues.put(key, config.get(key));
                }
                projectConfig.put(key, entry.getValue());
                config.put(key, entry.getValue());
                settings.applyRuntime(key, entry.getValue());
            }
            activeProjectConfigFile = localConfig;
            if (allowed.isEmpty()) {
                return "Project config loaded: " + localConfig.getAbsolutePath() + " (all overrides blocked)";
            }
            if (skipped > 0) {
                return "Project config loaded: " + localConfig.getAbsolutePath()
                    + " (" + skipped + " key" + (skipped == 1 ? "" : "s") + " blocked)";
            }
            return "Project config loaded: " + localConfig.getAbsolutePath();
        } catch (IOException e) {
            return "Project config load failed: " + e.getMessage();
        }
    }

    public String getActiveProjectConfigPath() {
        return activeProjectConfigFile == null ? null : activeProjectConfigFile.getAbsolutePath();
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        return settings.booleanValue(key, defaultValue);
    }

    private int getInt(String key, int defaultValue) {
        return settings.intValue(key, defaultValue);
    }

    private long getLong(String key, long defaultValue) {
        return settings.longValue(key, defaultValue);
    }

    private double getDouble(String key, double defaultValue) {
        return settings.doubleValue(key, defaultValue);
    }

    private String getString(String key, String defaultValue) {
        return settings.stringValue(key, defaultValue);
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

    public FormatterPolicy getFormatterPolicy(String extension) {
        return FormatterPolicy.resolve(this, extension);
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
        String normalizedKeySpec;
        try {
            normalizedKeySpec = KeymapOverlay.parseKey("keybind.normal." + keySpec).lhs();
        } catch (IllegalArgumentException error) {
            return null;
        }
        String modeSpecific = config.get("keybind." + normalizedMode + "." + normalizedKeySpec);
        if (modeSpecific != null) {
            return modeSpecific.trim();
        }
        String global = config.get("keybind.global." + normalizedKeySpec);
        if (global != null) {
            return global.trim();
        }
        return null;
    }

    // Get config file path
    public String getConfigPath() {
        return configPath;
    }

    public boolean isSchemaVersionKey(String key) {
        return ConfigSchema.VERSION_KEY.equals(key == null ? "" : key.trim());
    }

    public String validateSettingValue(String key, String value) {
        String normalizedKey = key == null ? "" : key.trim();
        String normalizedValue = value == null ? "" : value;
        String typedError = settings.validateRuntime(normalizedKey, normalizedValue);
        if (typedError != null) {
            return typedError;
        }
        String formatterError = FormatterPolicy.validateConfig(normalizedKey, normalizedValue);
        if (formatterError != null) return formatterError;
        return KeymapOverlay.isKeybindKey(normalizedKey) ? KeymapOverlay.validate(normalizedKey, normalizedValue) : null;
    }

    Set<String> typedSettingKeys() {
        return settings.keys();
    }

    List<TypedSettings.Descriptor> typedSettingDescriptors() {
        return settings.descriptors();
    }

    List<TypedSettings.Descriptor> searchTypedSettings(String query) {
        return settings.search(query);
    }

    List<KeymapOverlay.Binding> effectiveKeybindings() {
        return KeymapOverlay.effectiveBindings(config, getKeymapProfile());
    }

    String effectiveKeybindingsText(String query) {
        return KeymapOverlay.formatBindings(effectiveKeybindings(), query);
    }

    String typedSettingsReference() {
        StringBuilder reference = new StringBuilder("Shed typed settings reference\n");
        for (TypedSettings.Descriptor descriptor : settings.descriptors()) {
            reference.append("\n").append(descriptor.key()).append("\n")
                .append("Description: ").append(descriptor.description()).append("\n")
                .append("Allowed: ").append(descriptor.allowedValues()).append("\n")
                .append("Default: ").append(descriptor.defaultValue()).append("\n")
                .append("Behavior: ").append(descriptor.applyBehavior()).append("\n");
        }
        return reference.toString();
    }

    public boolean hasConfigLoadFailure() {
        return configLoadFailed;
    }

    public String getConfigLoadReport() {
        return configLoadReport;
    }

    public String getShedDirectoryPath() {
        return shedDirectoryPath;
    }

    public String getPluginsDirectoryPath() {
        return Path.of(shedDirectoryPath).resolve(SHED_PLUGINS_NAME).toString();
    }

    private String defaultSnippetsDirectoryPath() {
        return Path.of(shedDirectoryPath, "snippets").toString();
    }

    // Check if config file exists
    public boolean configExists() {
        return new File(configPath).exists();
    }

    public String defaultConfigTemplate() {
        StringBuilder template = new StringBuilder("# Shed configuration (TOML)\n")
            .append("# Core defaults are listed below; see docs/CONFIG.md for descriptions and dynamic namespaces.\n")
            .append("# Remove or change any key; unspecified keys use built-in defaults.\n\n")
            .append(ConfigSchema.VERSION_KEY).append(" = ").append(ConfigSchema.VERSION).append("\n\n");
        List<String> keys = new ArrayList<>(defaultConfig.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            template.append(tomlKey(key)).append(" = ").append(tomlValue(key, defaultConfig.get(key))).append("\n");
        }
        return template.append("\n# Per-project override: .shed.toml\n")
            .append("# Command alias example: \"command.alias.ww\" = \"w\"\n")
            .append("# Keybind example: \"keybind.normal.H\" = \"^\"\n")
            .append("# LSP example: \"lsp.py.command\" = \"pyright-langserver\"\n")
            .toString();
    }

    public void materializeDefaultConfig(boolean overwrite) throws IOException {
        Path path = Path.of(configPath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (overwrite) {
            Files.writeString(path, defaultConfigTemplate(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return;
        }
        try {
            Files.writeString(path, defaultConfigTemplate(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw new IOException("configuration exists; use :config! defaults to overwrite", error);
        }
    }

    private String defaultSessionDirectoryPath() {
        return Path.of(shedDirectoryPath).resolve(SHED_SESSIONS_NAME).toString();
    }

    private String defaultLandingSourcePath() {
        return Path.of(shedDirectoryPath).resolve("landing.md").toString();
    }

    private String defaultLandingRemoteCachePath() {
        return Path.of(shedDirectoryPath).resolve("landing.remote.md").toString();
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
        syncTypedSettings();
    }

    private void syncTypedSettings() {
        settings.reset();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (settings.knows(entry.getKey())) {
                settings.applyRuntime(entry.getKey(), entry.getValue());
            }
        }
    }

    private File findProjectConfig(File file) {
        if (file == null) {
            return null;
        }
        File cursor = file.isDirectory() ? file : file.getParentFile();
        while (cursor != null) {
            File candidate = new File(cursor, PROJECT_CONFIG_NAME);
            if (candidate.isFile()) {
                return candidate;
            }
            cursor = cursor.getParentFile();
        }
        return null;
    }

    private Map<String, String> parseConfigFile(File file) throws IOException {
        List<String> errors = new ArrayList<>();
        Map<String, Object> typed = parseTomlConfig(file.toPath(), errors);
        if (!errors.isEmpty()) {
            throw new IOException(String.join("; ", errors));
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : typed.entrySet()) {
            parsed.put(entry.getKey(), settings.stringify(entry.getValue()));
        }
        return parsed;
    }

    private String normalizePersistedKey(String rawKey) throws IOException {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) {
            throw new IOException("config key required");
        }
        if (key.indexOf('\0') >= 0 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0 || key.indexOf('=') >= 0) {
            throw new IOException("invalid config key: " + key);
        }
        if (KeymapOverlay.isKeybindKey(key)) {
            try {
                return KeymapOverlay.normalizeKey(key);
            } catch (IllegalArgumentException error) {
                throw new IOException(error.getMessage(), error);
            }
        }
        return key;
    }

    private String normalizePersistedValue(String rawValue) throws IOException {
        String value = rawValue == null ? "" : rawValue;
        if (value.indexOf('\0') >= 0) {
            throw new IOException("config value contains null byte");
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private boolean isTrustedProjectConfigFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        Path path = file.toPath();
        if (!supportsPosixAttributes(path)) {
            return true;
        }
        String expectedUser = System.getProperty("user.name");
        try {
            UserPrincipal owner = Files.getOwner(path);
            if (owner != null && expectedUser != null && !expectedUser.isBlank()) {
                if (!matchesLocalUsername(owner.getName(), expectedUser)) {
                    return false;
                }
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
        }
        try {
            java.util.Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                return false;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
        }
        return true;
    }

    private boolean supportsPosixAttributes(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    private boolean matchesLocalUsername(String ownerName, String expectedUser) {
        if (ownerName == null || expectedUser == null) {
            return false;
        }
        String normalizedOwner = ownerName.trim().toLowerCase(Locale.ROOT);
        String normalizedExpected = expectedUser.trim().toLowerCase(Locale.ROOT);
        return normalizedOwner.equals(normalizedExpected)
            || normalizedOwner.endsWith("\\" + normalizedExpected)
            || normalizedOwner.endsWith("/" + normalizedExpected);
    }

    private void writeConfigFile() throws IOException {
        File configFile = new File(configPath);
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Shed configuration (TOML)");
        lines.add("# Auto-generated by :set! and :config save");
        lines.add("");
        lines.add(ConfigSchema.VERSION_KEY + " = " + ConfigSchema.VERSION);
        lines.add("");
        List<String> keys = new ArrayList<>(persistedConfig.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            String value = persistedConfig.get(key);
            if (value == null) {
                continue;
            }
            lines.add(tomlKey(key) + " = " + tomlValue(key, value));
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

    private String tomlKey(String key) {
        return tomlString(key);
    }

    private String tomlValue(String key, String value) {
        String defaultValue = defaultConfig.get(key);
        if (key != null && key.startsWith("formatter.") && key.endsWith(".format.on.save")
            && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
            return value.toLowerCase(Locale.ROOT);
        }
        if (defaultValue != null && ("true".equals(defaultValue) || "false".equals(defaultValue))
            && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
            return value.toLowerCase(Locale.ROOT);
        }
        if (defaultValue != null && defaultValue.matches("-?\\d+") && value.matches("-?\\d+")) {
            return value;
        }
        if (defaultValue != null && defaultValue.matches("-?\\d+\\.\\d+") && value.matches("-?\\d+\\.\\d+")) {
            return value;
        }
        return tomlString(value);
    }

    private String tomlString(String value) {
        String source = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(source.length() + 2).append('"');
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            switch (current) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (current < 0x20 || current == 0x7f) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private Color getUiColor(String key, Color fallback) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return decodeColor(value, fallback);
    }

    private ThemePalette activeTheme() {
        String raw = getString("theme", DEFAULT_THEME);
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
