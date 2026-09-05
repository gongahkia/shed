package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ConfigManagerTest {
    @TempDir
    Path tempDir;

    private String originalHome;
    private String originalReducedMotionProperty;

    @BeforeEach
    void saveHome() {
        originalHome = System.getProperty("user.home");
        originalReducedMotionProperty = System.getProperty("prefers.reduced.motion");
    }

    @AfterEach
    void restoreHome() {
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
        }
        if (originalReducedMotionProperty == null) {
            System.clearProperty("prefers.reduced.motion");
        } else {
            System.setProperty("prefers.reduced.motion", originalReducedMotionProperty);
        }
    }

    @Test
    void loadsDefaultsWhenConfigMissing() {
        Path home = tempDir.resolve("home-default");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(4, config.getTabSize());
        assertEquals("Monospaced", config.getFontFamily());
        assertEquals(16, config.getFontSize());
        assertEquals("", config.getUiFontFamily());
        assertEquals(0, config.getUiFontSize());
        assertEquals("Monospaced", config.getTerminalFontFamily());
        assertEquals(14, config.getTerminalFontSize());
        assertEquals("system", config.getTerminalDefaultProfile());
        assertEquals(KeymapProfile.VIM, config.getKeymapProfile());
        assertEquals(LineNumberMode.ABSOLUTE, config.getLineNumberMode());
        assertTrue(config.getHighlightSearch());
        assertFalse(config.getSessionRestoreOnStart());
        assertFalse(config.getTerminalSessionRestoreEnabled());
        assertTrue(config.getMarkdownPreviewScrollSync());
        assertTrue(config.getDebugOpenSourceOnStop());
        assertTrue(config.getLandingWelcomeEnabled());
        assertEquals(home.resolve(".shed/landing.md").toString(), config.getLandingSource());
        assertEquals(home.resolve(".shed/landing.remote.md").toString(), config.getLandingRemoteCachePath());
        assertEquals(5000, config.getLandingRemoteTimeoutMs());
        TypedSettings.Descriptor terminalRestore = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("terminal.session.restore")).findFirst().orElseThrow();
        assertEquals("Terminal", terminalRestore.category());
        assertEquals("Live: checked when saving or loading a session", terminalRestore.applyBehavior());
        TypedSettings.Descriptor terminalProfile = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("terminal.default.profile")).findFirst().orElseThrow();
        assertEquals("system | builtin:<id> | <extension-id>:<id>", terminalProfile.allowedValues());
        assertEquals("Live: used when the next default terminal opens", terminalProfile.applyBehavior());
        assertFalse(config.getWorkspaceIndexEnabled());
        assertTrue(config.getGitAutoRefreshEnabled());
        assertEquals(1500, config.getGitAutoRefreshIntervalMs());
        assertFalse(config.getBackupPolicy().enabled());
        assertEquals(BackupPolicy.BackupMode.IDLE, config.getBackupPolicy().mode());
        assertEquals("default", config.getSessionAutoloadName());
        assertEquals(15000, config.getProcessTimeoutMs());
        assertEquals(UndoHistoryPolicy.defaults(), config.getUndoHistoryPolicy());
        assertEquals(new MultiSelectionPolicy(false, MultiSelectionPolicy.DEFAULT_MAX_CURSORS), config.getMultiSelectionPolicy());
        assertFalse(config.hasConfigLoadFailure());
        assertTrue(config.getConfigLoadReport().startsWith("Configuration not found: "));
    }

    @Test
    void persistsMarkdownPreviewScrollSyncPreference() throws IOException {
        Path home = tempDir.resolve("home-markdown-preview-sync");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("markdown.preview.scroll.sync", "false");

        assertFalse(config.getMarkdownPreviewScrollSync());
        TypedSettings.Descriptor descriptor = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("markdown.preview.scroll.sync")).findFirst().orElseThrow();
        assertEquals("Markdown Preview", descriptor.category());
        assertEquals("Live: applies to open Markdown previews", descriptor.applyBehavior());
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"markdown.preview.scroll.sync\" = false"));
    }

    @Test
    void defaultTemplateIsReloadableToml() throws IOException {
        Path home = tempDir.resolve("home-template");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        Path configPath = Path.of(config.getConfigPath());
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, config.defaultConfigTemplate());

        config.reload();

        assertFalse(config.hasConfigLoadFailure());
        assertEquals(4, config.getTabSize());
        assertEquals("one-dark-pro", config.getThemeId());
    }

    @Test
    void validatesUndoHistoryLimits() {
        Path home = tempDir.resolve("home-undo-policy");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.set("undo.history.max.entries", "12");
        config.set("undo.history.max.bytes", "4096");

        assertEquals(new UndoHistoryPolicy(12, 4096), config.getUndoHistoryPolicy());
        assertEquals("undo.history.max.entries must be between 1 and " + UndoHistoryPolicy.MAX_ENTRIES,
            config.validateSettingValue("undo.history.max.entries", "0"));
        assertEquals("undo.history.max.bytes must be between 1 and " + UndoHistoryPolicy.MAX_BYTES,
            config.validateSettingValue("undo.history.max.bytes", "0"));
    }

    @Test
    void configuresOptionalBackupModes() {
        Path home = tempDir.resolve("home-backup-policy");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.set("backup.enabled", "true");
        config.set("backup.mode", "save-only");

        assertTrue(config.getBackupPolicy().enabled());
        assertEquals(BackupPolicy.BackupMode.SAVE_ONLY, config.getBackupPolicy().mode());
        assertEquals("backup.mode must be idle or save-only", config.validateSettingValue("backup.mode", "always"));
    }

    @Test
    void configuresLandingSource() {
        Path home = tempDir.resolve("home-landing");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.set("landing.source", "notes/start.md");
        config.set("landing.welcome.enabled", "false");
        config.set("landing.remote.cache.path", "cache/remote.md");
        config.set("landing.remote.timeout.ms", "6000");

        assertEquals("notes/start.md", config.getLandingSource());
        assertFalse(config.getLandingWelcomeEnabled());
        assertEquals("cache/remote.md", config.getLandingRemoteCachePath());
        assertEquals(6000, config.getLandingRemoteTimeoutMs());
        assertEquals("landing.remote.timeout.ms must be between 1000 and 30000",
            config.validateSettingValue("landing.remote.timeout.ms", "999"));
        assertFalse(config.isProjectConfigKeyAllowed("landing.source"));
        assertFalse(config.isProjectConfigKeyAllowed("landing.welcome.enabled"));
    }

    @Test
    void configuresUiBufferAndTerminalFonts() {
        Path home = tempDir.resolve("home-fonts");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.set("font.family", "Fira Code");
        config.set("font.size", "15");
        config.set("ui.font.family", "SF Pro Text");
        config.set("ui.font.size", "13");
        config.set("terminal.font.family", "JetBrains Mono");
        config.set("terminal.font.size", "12");
        config.set("terminal.default.profile", "builtin:bash");

        assertEquals("Fira Code", config.getFontFamily());
        assertEquals(15, config.getFontSize());
        assertEquals("SF Pro Text", config.getUiFontFamily());
        assertEquals(13, config.getUiFontSize());
        assertEquals("JetBrains Mono", config.getTerminalFontFamily());
        assertEquals(12, config.getTerminalFontSize());
        assertEquals("builtin:bash", config.getTerminalDefaultProfile());
        assertEquals("ui.font.size must be non-negative", config.validateSettingValue("ui.font.size", "-1"));
        assertEquals("terminal.font.size must be at least 1", config.validateSettingValue("terminal.font.size", "0"));
        assertEquals("terminal.default.profile must be system, builtin:<id>, or an extension profile id",
            config.validateSettingValue("terminal.default.profile", "/bin/bash"));
        assertEquals("terminal.default.profile must be system, builtin:<id>, or an extension profile id",
            config.validateSettingValue("terminal.default.profile", "builtin:"));
    }

    @Test
    void configuresPlainKeymapProfile() throws IOException {
        Path home = tempDir.resolve("home-plain-keymap");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("keymap.profile", "plain");

        assertEquals(KeymapProfile.PLAIN, config.getKeymapProfile());
        config.setAndPersist("keymap.profile", "emacs");
        assertEquals(KeymapProfile.EMACS, config.getKeymapProfile());
        assertEquals("keymap.profile must be vim, plain, or emacs", config.validateSettingValue("keymap.profile", "editor"));
        TypedSettings.Descriptor descriptor = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("keymap.profile")).findFirst().orElseThrow();
        assertEquals("vim | plain | emacs", descriptor.allowedValues());
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"keymap.profile\" = \"emacs\""));
    }

    @Test
    void persistsAndResetsValidatedKeymapOverlays() throws IOException {
        Path home = tempDir.resolve("home-keymap-overlay");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("keybind.NORMAL.<C-S>", "<esc>:w<enter>");
        config.setAndPersist("keybind.normal.x", "a");

        assertEquals("<esc>:w<enter>", config.getKeybinding("normal", "<c-s>"));
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"keybind.normal.<c-s>\" = \"<esc>:w<enter>\""));
        IOException invalid = assertThrows(IOException.class, () -> config.setAndPersist("keybind.normal.x", "<f1>"));
        assertEquals("keybinding rhs token has unsupported token <f1>", invalid.getMessage());
        assertEquals("a", config.getKeybinding("normal", "x"));

        config.resetKeybindingAndPersist("normal", "<c-s>");

        assertNull(config.getKeybinding("normal", "<c-s>"));
        assertFalse(Files.readString(Path.of(config.getConfigPath())).contains("keybind.normal.<c-s>"));
    }

    @Test
    void configuresExperimentalMultiSelectionPolicy() throws IOException {
        Path home = tempDir.resolve("home-multi-selection");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("multi.selection.enabled", "true");
        config.setAndPersist("multi.selection.max.cursors", "24");

        assertEquals(new MultiSelectionPolicy(true, 24), config.getMultiSelectionPolicy());
        assertEquals("multi.selection.max.cursors must be between " + MultiSelectionPolicy.MIN_MAX_CURSORS + " and "
            + MultiSelectionPolicy.MAX_MAX_CURSORS, config.validateSettingValue("multi.selection.max.cursors", "1"));
        List<String> matching = config.searchTypedSettings("multi selection").stream().map(TypedSettings.Descriptor::key).toList();
        assertTrue(matching.containsAll(List.of("multi.selection.enabled", "multi.selection.max.cursors")));
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"multi.selection.enabled\" = true"));
    }

    @Test
    void configuresLspFeaturesAndExposesTheirRestartBehavior() throws IOException {
        Path home = tempDir.resolve("home-lsp-features");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(LspFeatureSettings.defaults(), config.getLspFeatureSettings());
        assertTrue(config.getLspCompletionAutoShow());
        assertEquals(90, config.getLspCompletionDelayMs());
        assertTrue(config.getLspCompletionTriggerCharacters());
        assertTrue(config.getLspCompletionFuzzyMatching());
        assertTrue(config.getLspCompletionLocalWords());
        assertTrue(config.getLspCompletionCommitCharacters());
        assertFalse(config.getLspFormatOnSaveEnabled());
        config.setAndPersist("lsp.completion.delay.ms", "120");
        assertEquals(120, config.getLspCompletionDelayMs());
        assertEquals("lsp.completion.delay.ms must be between 0 and 1000", config.validateSettingValue("lsp.completion.delay.ms", "1001"));
        config.setAndPersist("lsp.completion.enabled", "false");
        config.setAndPersist("lsp.snippets.enabled", "true");
        config.setAndPersist("lsp.signature.help.enabled", "false");
        config.setAndPersist("lsp.hover.enabled", "false");
        config.setAndPersist("lsp.semantic.tokens.enabled", "false");
        config.setAndPersist("lsp.inlay.hints.enabled", "false");
        config.setAndPersist("lsp.definition.enabled", "false");
        config.setAndPersist("lsp.type.definition.enabled", "false");
        config.setAndPersist("lsp.implementation.enabled", "false");
        config.setAndPersist("lsp.call.hierarchy.enabled", "false");
        config.setAndPersist("lsp.type.hierarchy.enabled", "false");
        config.setAndPersist("lsp.references.enabled", "false");
        config.setAndPersist("lsp.rename.enabled", "false");
        config.setAndPersist("lsp.code.actions.enabled", "false");
        config.setAndPersist("lsp.command.execution.enabled", "false");
        config.setAndPersist("lsp.formatting.enabled", "false");
        config.setAndPersist("lsp.format.on.save.enabled", "true");
        assertTrue(config.getLspFormatOnSaveEnabled());

        LspFeatureSettings features = config.getLspFeatureSettings();
        assertEquals(new LspFeatureSettings(false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false), features);
        assertFalse(features.capabilityEnablement().get(LspCapability.COMPLETION));
        assertFalse(features.capabilityEnablement().get(LspCapability.SIGNATURE_HELP));
        assertFalse(features.capabilityEnablement().get(LspCapability.HOVER));
        assertFalse(features.capabilityEnablement().get(LspCapability.DEFINITION));
        assertFalse(features.capabilityEnablement().get(LspCapability.TYPE_DEFINITION));
        assertFalse(features.capabilityEnablement().get(LspCapability.IMPLEMENTATION));
        assertFalse(features.capabilityEnablement().get(LspCapability.CALL_HIERARCHY));
        assertFalse(features.capabilityEnablement().get(LspCapability.TYPE_HIERARCHY));
        assertFalse(features.capabilityEnablement().get(LspCapability.REFERENCES));
        assertFalse(features.capabilityEnablement().get(LspCapability.RENAME));
        assertFalse(features.capabilityEnablement().get(LspCapability.CODE_ACTION));
        assertFalse(features.capabilityEnablement().get(LspCapability.EXECUTE_COMMAND));
        assertFalse(features.capabilityEnablement().get(LspCapability.FORMATTING));
        assertFalse(features.capabilityEnablement().get(LspCapability.SEMANTIC_TOKENS));
        assertFalse(features.capabilityEnablement().get(LspCapability.INLAY_HINTS));
        TypedSettings.Descriptor descriptor = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("lsp.command.execution.enabled"))
            .findFirst()
            .orElseThrow();
        assertEquals("Language Server", descriptor.category());
        assertEquals("Restart: takes effect when an LSP server is started or restarted", descriptor.applyBehavior());
        TypedSettings.Descriptor autoShow = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("lsp.completion.auto.show")).findFirst().orElseThrow();
        assertEquals("Live: used by the next completion request", autoShow.applyBehavior());
        TypedSettings.Descriptor formatOnSave = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("lsp.format.on.save.enabled")).findFirst().orElseThrow();
        assertEquals("Live: used by the next save request", formatOnSave.applyBehavior());
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"lsp.formatting.enabled\" = false"));
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"lsp.format.on.save.enabled\" = true"));
    }

    @Test
    void persistsPerLanguageFormatterPolicies() throws IOException {
        Path home = tempDir.resolve("home-formatter-policy");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("formatter.py.mode", "external");
        config.setAndPersist("formatter.py.command", "ruff");
        config.setAndPersist("formatter.py.args", "format --stdin-filename '${file}'");
        config.setAndPersist("formatter.py.format.on.save", "true");

        FormatterPolicy policy = config.getFormatterPolicy(".py");
        assertEquals(FormatterPolicy.Mode.EXTERNAL, policy.mode());
        assertEquals("ruff", policy.command());
        assertEquals(List.of("format", "--stdin-filename", "${file}"), policy.args());
        assertTrue(policy.formatOnSave());
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"formatter.py.format.on.save\" = true"));
        assertEquals(FormatterPolicy.Mode.EXTERNAL, new ConfigManager().getFormatterPolicy("py").mode());
    }

    @Test
    void configuresDebugFeaturesAndRejectsInvalidDebugTomlWithSourceLocation() throws IOException {
        Path home = tempDir.resolve("home-debug");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(DebugFeatureSettings.defaults(), config.getDebugFeatureSettings());
        config.setAndPersist("debug.variables.enabled", "false");
        assertFalse(config.getDebugFeatureSettings().variables());
        TypedSettings.Descriptor descriptor = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("debug.variables.enabled")).findFirst().orElseThrow();
        assertEquals("Debug", descriptor.category());
        assertEquals("Live: checked when explicit debug-session planning begins", descriptor.applyBehavior());

        Path configPath = Path.of(config.getConfigPath());
        Files.writeString(configPath, "schema_version = 1\n\"debug.configuration.main.request\" = \"run\"\n");
        config.reload();

        assertTrue(config.hasConfigLoadFailure());
        assertTrue(config.getConfigLoadReport().contains("line 2, column"));
        assertTrue(config.getConfigLoadReport().contains("debug.configuration.main.request must be launch or attach"));
        assertTrue(config.getDebugConfiguration().configurations().isEmpty());
    }

    @Test
    void configuresGitWorkbenchSurfacesIndependently() throws IOException {
        Path home = tempDir.resolve("home-git-history");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertTrue(config.getGitChangesEnabled());
        assertTrue(config.getGitDiffsEnabled());
        assertTrue(config.getGitStagingEnabled());
        assertTrue(config.getGitHistoryEnabled());
        assertTrue(config.getGitRemoteActionsEnabled());
        assertTrue(config.getGitPanelPresentationEnabled());
        config.setAndPersist("git.changes.enabled", "false");
        config.setAndPersist("git.diffs.enabled", "false");
        config.setAndPersist("git.staging.enabled", "false");
        config.setAndPersist("git.history.enabled", "false");
        config.setAndPersist("git.remote.actions.enabled", "false");
        config.setAndPersist("git.panel.presentation.enabled", "false");

        assertFalse(config.getGitChangesEnabled());
        assertFalse(config.getGitDiffsEnabled());
        assertFalse(config.getGitStagingEnabled());
        assertFalse(config.getGitHistoryEnabled());
        assertFalse(config.getGitRemoteActionsEnabled());
        assertFalse(config.getGitPanelPresentationEnabled());
        List<String> keys = config.searchTypedSettings("remote actions").stream().map(TypedSettings.Descriptor::key).toList();
        assertEquals(List.of("git.remote.actions.enabled"), keys);
        List<String> surfaces = List.of("git.changes.enabled", "git.diffs.enabled", "git.staging.enabled", "git.conflict.resolution.enabled",
            "git.history.enabled", "git.remote.actions.enabled", "git.panel.presentation.enabled");
        for (String key : surfaces) {
            TypedSettings.Descriptor descriptor = config.typedSettingDescriptors().stream()
                .filter(setting -> setting.key().equals(key)).findFirst().orElseThrow();
            assertEquals("Git", descriptor.category());
        }
        assertEquals("Live: used by subsequent staging and unstaging commands", config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("git.staging.enabled")).findFirst().orElseThrow().applyBehavior());
    }

    @Test
    void keepsGitHubReviewDisabledUntilExplicitlyEnabled() throws IOException {
        Path home = tempDir.resolve("home-github-review");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertFalse(config.getGitHubReviewEnabled());
        config.setAndPersist("github.review.enabled", "true");
        assertFalse(config.getGitHubReviewEnabled());
        config.setAndPersist("github.review.consent.granted", "true");
        assertTrue(config.getGitHubReviewEnabled());
        TypedSettings.Descriptor descriptor = config.typedSettingDescriptors().stream()
            .filter(setting -> setting.key().equals("github.review.enabled")).findFirst().orElseThrow();
        assertEquals("GitHub", descriptor.category());
    }

    @Test
    void materializesStableDefaultTemplateOnlyWhenConfirmed() throws IOException {
        Path home = tempDir.resolve("home-materialize");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        Path configPath = Path.of(config.getConfigPath());

        config.materializeDefaultConfig(false);
        String expected = config.defaultConfigTemplate();
        assertEquals(expected, Files.readString(configPath));
        assertTrue(expected.contains("# Core defaults are listed below"));
        for (String key : config.typedSettingKeys()) {
            assertTrue(expected.contains("\"" + key + "\" ="), "missing materialized setting " + key);
        }
        config.reload();
        assertFalse(config.hasConfigLoadFailure());

        IOException existing = assertThrows(IOException.class, () -> config.materializeDefaultConfig(false));
        assertEquals("configuration exists; use :config! defaults to overwrite", existing.getMessage());
        assertEquals(expected, Files.readString(configPath));

        Files.writeString(configPath, "schema_version = 1\n\"tab.size\" = 8\n");
        config.materializeDefaultConfig(true);
        assertEquals(expected, Files.readString(configPath));
        config.materializeDefaultConfig(true);
        assertEquals(expected, Files.readString(configPath));
    }

    @Test
    void configurationReferenceDocumentsEveryTypedSetting() throws IOException {
        Path home = tempDir.resolve("home-doc-catalog");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        String reference = Files.readString(Path.of("docs/CONFIG.md"));

        for (String key : config.typedSettingKeys()) {
            assertTrue(reference.contains("| `" + key + "` |"), "missing configuration reference for " + key);
        }
    }

    @Test
    void typedSettingDescriptorsStayInSyncWithPersistedToml() throws IOException {
        Path home = tempDir.resolve("home-inspector-model");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        TypedSettings.Descriptor initial = config.typedSettingDescriptors().stream()
            .filter(descriptor -> descriptor.key().equals("tab.size"))
            .findFirst()
            .orElseThrow();
        assertEquals("Editor", initial.category());
        assertEquals("integer", initial.type());
        assertEquals("4", initial.defaultValue());
        assertEquals("4", initial.currentValue());
        assertEquals("Tab width in spaces", initial.description());
        assertEquals("integer 1..16", initial.allowedValues());
        assertEquals("Live: applied immediately and on config reload", initial.applyBehavior());

        config.setAndPersist("tab.size", "6");
        TypedSettings.Descriptor updated = config.typedSettingDescriptors().stream()
            .filter(descriptor -> descriptor.key().equals("tab.size"))
            .findFirst()
            .orElseThrow();
        assertEquals("6", updated.currentValue());
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"tab.size\" = 6"));
        assertEquals(List.of("tab.size"), config.searchTypedSettings("tab width").stream()
            .map(TypedSettings.Descriptor::key).toList());
    }

    @Test
    void typedSettingsReferenceUsesDescriptorMetadata() {
        ConfigManager config = new ConfigManager();
        String reference = config.typedSettingsReference();

        for (TypedSettings.Descriptor descriptor : config.typedSettingDescriptors()) {
            assertTrue(reference.contains("\n" + descriptor.key() + "\n"));
            assertTrue(reference.contains("Description: " + descriptor.description()));
            assertTrue(reference.contains("Allowed: " + descriptor.allowedValues()));
            assertTrue(reference.contains("Default: " + descriptor.defaultValue()));
            assertTrue(reference.contains("Behavior: " + descriptor.applyBehavior()));
        }
    }

    @Test
    void configuresRecoveryRetentionAndCleanExitPolicy() throws IOException {
        Path home = tempDir.resolve("home-recovery-policy");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("recovery.retention.max.entries", "2");
        config.setAndPersist("recovery.retention.max.content.bytes", "1024");
        config.setAndPersist("recovery.cleanup.on.clean.exit", "false");

        assertEquals(new RecoveryJournal.RetentionPolicy(2, 1024), config.getRecoveryRetentionPolicy());
        assertFalse(config.getRecoveryCleanupOnCleanExit());
        assertEquals("recovery.retention.max.entries must be between 1 and " + RecoveryJournal.MAX_ENTRIES,
            config.validateSettingValue("recovery.retention.max.entries", "33"));
    }

    @Test
    void configuresBackupPolicy() throws IOException {
        Path home = tempDir.resolve("home-backup-policy");
        Path backupDirectory = tempDir.resolve("backup-store");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("backup.enabled", "false");
        config.setAndPersist("backup.directory", backupDirectory.toString());
        config.setAndPersist("backup.retention.count", "2");

        assertEquals(new BackupPolicy(false, backupDirectory.toString(), 2), config.getBackupPolicy());
        assertEquals("backup.retention.count must be between 1 and " + BackupPolicy.MAX_RETENTION_COUNT,
            config.validateSettingValue("backup.retention.count", "101"));
    }

    @Test
    void configuresProjectReplaceSafetyPolicy() throws IOException {
        Path home = tempDir.resolve("home-project-replace-policy");
        Path backupDirectory = tempDir.resolve("project-replace-backups");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertFalse(config.getProjectReplacePolicy().enabled());
        assertTrue(config.getProjectReplacePolicy().confirmRequired());
        config.setAndPersist("project.replace.enabled", "true");
        config.setAndPersist("project.replace.confirm.required", "false");
        config.setAndPersist("project.replace.backup.directory", backupDirectory.toString());
        config.setAndPersist("project.replace.scope", "current-file");

        assertEquals(new ProjectReplacePolicy(true, true, false, true, backupDirectory.toString(), "current-file"),
            config.getProjectReplacePolicy());
        assertEquals("project.replace.scope must be workspace or current-file",
            config.validateSettingValue("project.replace.scope", "selection"));
    }

    @Test
    void resetAndPersistRestoresOneTypedDefaultWithoutDisturbingOtherOverrides() throws IOException {
        Path home = tempDir.resolve("home-reset-setting");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("tab.size", "6");
        config.setAndPersist("theme", "gruvbox");
        config.resetAndPersist("tab.size");

        assertEquals(4, config.getTabSize());
        assertEquals("gruvbox", config.get("theme"));
        String file = Files.readString(Path.of(config.getConfigPath()));
        assertFalse(file.contains("\"tab.size\""));
        assertTrue(file.contains("\"theme\" = \"gruvbox\""));
        assertThrows(IOException.class, () -> config.resetAndPersist("color.background"));

        ConfigManager reloaded = new ConfigManager();
        assertEquals(4, reloaded.getTabSize());
        assertEquals("gruvbox", reloaded.get("theme"));
    }

    @Test
    void rejectsMissingSchemaVersion() throws IOException {
        Path home = tempDir.resolve("home-schema-missing");
        Path configPath = home.resolve(".shed/config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "\"tab.size\" = 8\n");
        System.setProperty("user.home", home.toString());

        ConfigManager config = new ConfigManager();

        assertTrue(config.hasConfigLoadFailure());
        assertEquals(4, config.getTabSize());
        assertTrue(config.getConfigLoadReport().contains("schema_version is required at the TOML root (expected 1)"));
    }

    @Test
    void rejectsInvalidAndUnsupportedSchemaVersions() throws IOException {
        Path home = tempDir.resolve("home-schema-invalid");
        Path configPath = home.resolve(".shed/config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "schema_version = \"1\"\n");
        System.setProperty("user.home", home.toString());

        ConfigManager config = new ConfigManager();
        assertTrue(config.hasConfigLoadFailure());
        assertTrue(config.getConfigLoadReport().contains("schema_version must be integer 1"));

        Files.writeString(configPath, "schema_version = 2\n");
        config.reload();
        assertTrue(config.hasConfigLoadFailure());
        assertTrue(config.getConfigLoadReport().contains("unsupported schema_version 2 (supported: 1)"));
    }

    @Test
    void rejectsInvalidTypedTomlSettingsWithDiagnostics() throws IOException {
        Path home = tempDir.resolve("home-typed-invalid");
        Path configPath = home.resolve(".shed/config.toml");
        String source = "schema_version = 1\n"
            + "\"tab.size\" = \"8\"\n"
            + "\"minimap.width\" = 20\n"
            + "\"line.numbers\" = \"diagonal\"\n";
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, source);
        System.setProperty("user.home", home.toString());

        ConfigManager config = new ConfigManager();

        assertTrue(config.hasConfigLoadFailure());
        assertEquals(4, config.getTabSize());
        assertEquals(84, config.getMinimapWidth());
        assertEquals(LineNumberMode.ABSOLUTE, config.getLineNumberMode());
        assertEquals(source, Files.readString(configPath));
        assertTrue(config.getConfigLoadReport().contains("line 2, column"));
        assertTrue(config.getConfigLoadReport().contains("tab.size must be TOML integer"));
        assertTrue(config.getConfigLoadReport().contains("tab.size must be TOML integer (active fallback: 4)"));
        assertTrue(config.getConfigLoadReport().contains("minimap.width must be at least 40"));
        assertTrue(config.getConfigLoadReport().contains("minimap.width must be at least 40 (active fallback: 84)"));
        assertTrue(config.getConfigLoadReport().contains("line.numbers must be none, absolute, relative, relativeabsolute, or hybrid"));
    }

    @Test
    void rejectsPartiallyInvalidConfigWithoutChangingIt() throws IOException {
        Path home = tempDir.resolve("home-invalid");
        Path shedDir = home.resolve(".shed");
        Path configPath = shedDir.resolve("config.toml");
        String source = "schema_version = 1\ntab.size = 8\ninvalid line\nfont.size = 18\n";
        Files.createDirectories(shedDir);
        Files.writeString(configPath, source);
        System.setProperty("user.home", home.toString());

        ConfigManager config = new ConfigManager();

        assertTrue(config.hasConfigLoadFailure());
        assertEquals(4, config.getTabSize());
        assertEquals(16, config.getFontSize());
        assertEquals(source, Files.readString(configPath));
        assertTrue(config.getConfigLoadReport().contains("Configuration recovery: " + configPath));
        assertTrue(config.getConfigLoadReport().contains("line 3"));
        assertTrue(config.getConfigLoadReport().contains("line 3, column"));
        assertTrue(config.getConfigLoadReport().contains("Remediation:"));
    }

    @Test
    void recoversFromUnreadableConfigPath() throws IOException {
        Path home = tempDir.resolve("home-unreadable");
        Path configPath = home.resolve(".shed/config.toml");
        Files.createDirectories(configPath);
        System.setProperty("user.home", home.toString());

        ConfigManager config = new ConfigManager();

        assertTrue(config.hasConfigLoadFailure());
        assertEquals(4, config.getTabSize());
        assertTrue(config.getConfigLoadReport().contains("Configuration recovery: " + configPath));
        assertTrue(config.getConfigLoadReport().contains("read failed:"));
        assertTrue(Files.isDirectory(configPath));
    }

    @Test
    void parsesConfigOverrides() throws IOException {
        Path home = tempDir.resolve("home-custom");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Path configPath = shedDir.resolve("config.toml");
        Files.writeString(configPath,
            "schema_version = 1\n"
                + "\"tab.size\" = 8\n"
                + "\"line.numbers\" = \"relative\"\n"
                + "\"highlight.search\" = false\n"
                + "\"command.alias.ww\" = \"w\"\n"
                + "\"session.restore.on.start\" = true\n"
                + "\"session.autoload\" = \"work\"\n"
                + "\"process.timeout.ms\" = 5000\n"
                + "\"shell.command.enabled\" = false\n");

        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(8, config.getTabSize());
        assertEquals(LineNumberMode.RELATIVE, config.getLineNumberMode());
        assertFalse(config.getHighlightSearch());
        assertEquals("w", config.resolveCommandAlias("ww"));
        assertTrue(config.getSessionRestoreOnStart());
        assertEquals("work", config.getSessionAutoloadName());
        assertEquals(5000, config.getProcessTimeoutMs());
        assertFalse(config.getShellCommandEnabled());
        assertFalse(config.hasConfigLoadFailure());
    }

    @Test
    void liveReloadKeepsLastKnownGoodConfigurationAfterInvalidEdit() throws IOException {
        Path home = tempDir.resolve("home-live-reload");
        Path configPath = home.resolve(".shed/config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "schema_version = 1\n\"tab.size\" = 8\n");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        ConfigLiveReloadService liveReload = new ConfigLiveReloadService(config);

        assertFalse(liveReload.reloadIfChanged());
        Files.writeString(configPath, "schema_version = 1\n\"tab.size\" = 12\n");
        assertTrue(liveReload.reloadIfChanged());
        assertEquals(12, config.getTabSize());
        assertFalse(config.hasConfigLoadFailure());

        Files.writeString(configPath, "schema_version = 1\n\"tab.size\" = \"invalid\"\n");
        assertTrue(liveReload.reloadIfChanged());
        assertEquals(12, config.getTabSize());
        assertTrue(config.hasConfigLoadFailure());
        assertTrue(config.getConfigLoadReport().contains("Last-known-good configuration remains active."));
        assertTrue(config.getConfigLoadReport().contains("active fallback: 12"));

        Files.writeString(configPath, "schema_version = 1\n\"tab.size\" = 6\n");
        assertTrue(liveReload.reloadIfChanged());
        assertEquals(6, config.getTabSize());
        assertFalse(config.hasConfigLoadFailure());
    }

    @Test
    void readsOnlyTomlGlobalConfig() throws IOException {
        Path home = tempDir.resolve("home-toml-only");
        List<Path> nonTomlPaths = List.of(
            home.resolve(".shed/shedrc"),
            home.resolve(".shedrc"),
            home.resolve(".config/shed/shedrc")
        );
        for (Path path : nonTomlPaths) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "tab.size=6\n");
        }

        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(4, config.getTabSize());
        assertEquals(home.resolve(".shed/config.toml").toString(), config.getConfigPath());
        for (Path path : nonTomlPaths) {
            assertEquals("tab.size=6\n", Files.readString(path));
        }

        Path tomlPath = Path.of(config.getConfigPath());
        Files.writeString(tomlPath, "schema_version = 1\n\"tab.size\" = 8\n");
        assertEquals(8, new ConfigManager().getTabSize());
    }

    @Test
    void pluginsDirectoryPathDerivedFromShedDir() {
        Path home = tempDir.resolve("home-plugins");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        String expected = home.resolve(".shed/plugins").toString();
        assertEquals(expected, config.getPluginsDirectoryPath());
    }

    @Test
    void supportsAdditionalBuiltInThemes() {
        Path home = tempDir.resolve("home-theme-extended");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals("vesper", config.setTheme("vesper"));
        assertEquals("vesper", config.getThemeId());
        assertEquals("nightfox", config.setTheme("nightfox"));
        assertEquals("nightfox", config.getThemeId());
    }

    @Test
    void unknownThemeDoesNotOverrideCurrentTheme() {
        Path home = tempDir.resolve("home-theme-unknown");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals("vesper", config.setTheme("vesper"));
        assertNull(config.setTheme("not-a-real-theme"));
        assertEquals("vesper", config.getThemeId());
    }

    @Test
    void getConfiguredLspServersReturnsEmpty() {
        Path home = tempDir.resolve("home-nolsp");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        Map<String, String> servers = config.getConfiguredLspServers();
        assertNotNull(servers);
        assertTrue(servers.isEmpty());
    }

    @Test
    void getConfiguredLspServersParsesToml() throws IOException {
        Path home = tempDir.resolve("home-lsp");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Files.writeString(shedDir.resolve("config.toml"),
            "schema_version = 1\n"
            + "\"lsp.py.command\" = \"pyright-langserver\"\n"
            + "\"lsp.py.args\" = \"--stdio\"\n"
            + "\"lsp.rs.command\" = \"rust-analyzer\"\n");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        Map<String, String> servers = config.getConfiguredLspServers();
        assertEquals(2, servers.size());
        assertTrue(servers.get("py").contains("pyright-langserver"));
        assertTrue(servers.get("py").contains("--stdio"));
        assertEquals("rust-analyzer", servers.get("rs"));
    }

    @Test
    void lspArgumentsPreserveQuotedValuesAndExposeMalformedValues() throws IOException {
        Path home = tempDir.resolve("home-lsp-arguments");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Files.writeString(shedDir.resolve("config.toml"),
            "schema_version = 1\n"
            + "\"lsp.ps1.command\" = \"pwsh\"\n"
            + "\"lsp.ps1.args\" = \"-NoProfile -Command \\\"& '/opt/PowerShell Editor Services/Start-EditorServices.ps1' -Stdio\\\"\"\n");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertArrayEquals(new String[] {"-NoProfile", "-Command",
            "& '/opt/PowerShell Editor Services/Start-EditorServices.ps1' -Stdio"}, config.getLspArgs("ps1"));

        config.set("lsp.ps1.args", "'unterminated");
        assertThrows(IllegalArgumentException.class, () -> config.getLspArgs("ps1"));
        assertTrue(config.getConfiguredLspServers().get("ps1").contains("invalid arguments"));
    }

    @Test
    void getUserCommandsReturnsConfiguredCommands() throws IOException {
        Path home = tempDir.resolve("home-usercmd");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Files.writeString(shedDir.resolve("config.toml"),
            "schema_version = 1\n"
            + "\"command.user.build\" = \"make -j4\"\n"
            + "\"command.user.test\" = \"./test.sh\"\n");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        Map<String, String> cmds = config.getUserCommands();
        assertEquals(2, cmds.size());
        assertEquals("make -j4", cmds.get("build"));
        assertEquals("./test.sh", cmds.get("test"));
    }

    @Test
    void limelightSettingsAreValidatedAndApplied() {
        Path home = tempDir.resolve("home-limelight-settings");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(0.5, config.getLimelightCoefficient(), 0.0001);
        assertEquals(0, config.getLimelightParagraphSpan());

        config.set("limelight.coefficient", "0.65");
        config.set("limelight.paragraph.span", "2");

        assertEquals(0.65, config.getLimelightCoefficient(), 0.0001);
        assertEquals(2, config.getLimelightParagraphSpan());
    }

    @Test
    void setAndPersistWritesConfigFile() throws IOException {
        Path home = tempDir.resolve("home-persist-single");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("limelight.coefficient", "0.65");
        config.setAndPersist("limelight.paragraph.span", "2");

        String file = Files.readString(Path.of(config.getConfigPath()));
        assertTrue(file.contains("schema_version = 1"));
        assertTrue(file.contains("\"limelight.coefficient\" = 0.65"));
        assertTrue(file.contains("\"limelight.paragraph.span\" = 2"));
        ConfigManager reloaded = new ConfigManager();
        assertEquals(0.65, reloaded.getLimelightCoefficient(), 0.0001);
        assertEquals(2, reloaded.getLimelightParagraphSpan());
    }

    @Test
    void setAndPersistRejectsInvalidKeysAndValues() {
        Path home = tempDir.resolve("home-persist-invalid");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertThrows(IOException.class, () -> config.setAndPersist("bad=key", "value"));
        assertThrows(IOException.class, () -> config.setAndPersist("ui.test", "bad\0value"));
        assertThrows(IOException.class, () -> config.setAndPersist("schema_version", "2"));
        IOException tabError = assertThrows(IOException.class, () -> config.setAndPersist("tab.size", "0"));
        assertEquals("tab.size must be between 1 and 16", tabError.getMessage());
    }

    @Test
    void setAndPersistNormalizesMultilineValues() throws IOException {
        Path home = tempDir.resolve("home-persist-multiline");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("command.user.sample", "echo one\ntwo\rthree");

        String file = Files.readString(Path.of(config.getConfigPath()));
        assertTrue(file.contains("\"command.user.sample\" = \"echo one two three\""));
    }

    @Test
    void persistCurrentConfigWritesRuntimeOverrides() throws IOException {
        Path home = tempDir.resolve("home-persist-runtime");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.set("limelight.coefficient", "0.8");
        config.set("limelight.paragraph.span", "1");
        int persisted = config.persistCurrentConfig();

        assertTrue(persisted >= 2);
        String file = Files.readString(Path.of(config.getConfigPath()));
        assertTrue(file.contains("schema_version = 1"));
        assertTrue(file.contains("\"limelight.coefficient\" = 0.8"));
        assertTrue(file.contains("\"limelight.paragraph.span\" = 1"));
    }

    @Test
    void persistCurrentConfigSkipsInvalidRuntimeKeys() throws IOException {
        Path home = tempDir.resolve("home-persist-invalid-runtime-key");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.set("limelight.coefficient", "0.8");
        config.set("bad\nkey", "boom");
        int persisted = config.persistCurrentConfig();

        assertTrue(persisted >= 1);
        String file = Files.readString(Path.of(config.getConfigPath()));
        assertTrue(file.contains("\"limelight.coefficient\" = 0.8"));
        assertFalse(file.contains("bad"));
    }

    @Test
    void persistCurrentConfigWithoutOverridesWritesNoRuntimeKeys() throws IOException {
        Path home = tempDir.resolve("home-persist-default-only");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        int persisted = config.persistCurrentConfig();
        assertEquals(0, persisted);

        String file = Files.readString(Path.of(config.getConfigPath()));
        assertFalse(file.contains("theme ="));
        assertFalse(file.contains("tab.size ="));
    }

    @Test
    void blankSessionAutoloadFallsBackToDefault() throws IOException {
        Path home = tempDir.resolve("home-session-autoload-blank");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Files.writeString(shedDir.resolve("config.toml"), "schema_version = 1\n\"session.autoload\" = \"\"\n");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals("default", config.getSessionAutoloadName());
    }

    @Test
    void projectLocalConfigLoadsAndClearsOnFileSwitch() throws IOException {
        Path home = tempDir.resolve("home-project-local");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        Path projectA = tempDir.resolve("project-a");
        Path projectB = tempDir.resolve("project-b");
        Files.createDirectories(projectA.resolve("src"));
        Files.createDirectories(projectB.resolve("src"));
        Files.writeString(projectA.resolve(".shed.toml"), "schema_version = 1\n\"theme\" = \"dracula\"\n");

        File fileA = projectA.resolve("src/App.java").toFile();
        Files.writeString(fileA.toPath(), "class App {}\n");
        File fileB = projectB.resolve("src/Other.java").toFile();
        Files.writeString(fileB.toPath(), "class Other {}\n");

        String loaded = config.applyProjectConfigForFile(fileA);
        assertTrue(loaded.contains("Project config loaded"));
        assertEquals("dracula", config.getThemeId());
        assertNotNull(config.getActiveProjectConfigPath());

        String cleared = config.applyProjectConfigForFile(fileB);
        assertTrue(cleared.contains("Project config cleared"));
        assertEquals("one-dark-pro", config.getThemeId());
        assertNull(config.getActiveProjectConfigPath());
    }

    @Test
    void projectLocalConfigBlocksUnsafeKeysByDefault() throws IOException {
        Path home = tempDir.resolve("home-project-local-safe");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        Path project = tempDir.resolve("project-safe");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(".shed.toml"),
            "schema_version = 1\n"
                + "command.user.pwn = \"echo hacked\"\n"
                + "keybind.normal.q = \":q!\"\n");
        File file = project.resolve("src/Main.java").toFile();
        Files.writeString(file.toPath(), "class Main {}\n");

        String loaded = config.applyProjectConfigForFile(file);
        assertTrue(loaded.contains("blocked"));
        assertTrue(config.getUserCommands().isEmpty());
        assertNull(config.getKeybinding("normal", "q"));
    }

    @Test
    void projectLocalConfigAllowsUnsafeKeysWhenEnabled() throws IOException {
        Path home = tempDir.resolve("home-project-local-unsafe");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        config.set("project.config.allow.unsafe", "true");

        Path project = tempDir.resolve("project-unsafe");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(".shed.toml"),
            "schema_version = 1\n"
                + "command.user.local = \"echo ok\"\n"
                + "keybind.normal.q = \":q!\"\n");
        File file = project.resolve("src/Main.java").toFile();
        Files.writeString(file.toPath(), "class Main {}\n");

        String loaded = config.applyProjectConfigForFile(file);
        assertTrue(loaded.contains("Project config loaded"));
        assertEquals("echo ok", config.getUserCommands().get("local"));
        assertEquals(":q!", config.getKeybinding("normal", "q"));
    }

    @Test
    void resolvesUnsafeProjectDebugConfigurationsPerWorkspaceWithoutLeakingAcrossRoots() throws IOException {
        Path home = tempDir.resolve("home-project-debug-workspaces");
        Path configPath = home.resolve(".shed/config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
            schema_version = 1
            "project.config.allow.unsafe" = true
            "project.config.require.trusted.file" = false
            "debug.adapter.shared.command" = "shared-adapter"
            "debug.adapter.shared.capabilities" = "launch"
            """);
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        Path first = tempDir.resolve("project-debug-first");
        Path second = tempDir.resolve("project-debug-second");
        Files.createDirectories(first.resolve("src"));
        Files.createDirectories(second.resolve("src"));
        Files.writeString(first.resolve(".shed.toml"), """
            schema_version = 1
            "debug.enabled" = true
            "debug.open.source.on.stop" = false
            "debug.configuration.first.adapter" = "shared"
            "debug.configuration.first.request" = "launch"
            "debug.configuration.first.program" = "${file}"
            """);
        Files.writeString(second.resolve(".shed.toml"), """
            schema_version = 1
            "debug.configuration.second.adapter" = "shared"
            "debug.configuration.second.request" = "launch"
            "debug.configuration.second.program" = "${file}"
            """);
        File firstFile = first.resolve("src/Main.java").toFile();
        Files.writeString(firstFile.toPath(), "class Main {}\n");

        assertTrue(config.applyProjectConfigForFile(firstFile).contains("Project config loaded"));
        DebugAdapterRegistry.Validation firstValidation = config.getDebugConfigurationForWorkspace(first);
        DebugAdapterRegistry.Validation secondValidation = config.getDebugConfigurationForWorkspace(second);

        assertTrue(firstValidation.valid());
        assertTrue(secondValidation.valid());
        assertTrue(firstValidation.configurations().containsKey("first"));
        assertFalse(firstValidation.configurations().containsKey("second"));
        assertTrue(secondValidation.configurations().containsKey("second"));
        assertFalse(secondValidation.configurations().containsKey("first"));
        assertTrue(config.getDebugConfiguration().configurations().isEmpty());
        assertTrue(config.getDebugFeatureSettingsForWorkspace(first).enabled());
        assertFalse(config.getDebugOpenSourceOnStopForWorkspace(first));
        assertFalse(config.getDebugFeatureSettingsForWorkspace(second).enabled());
    }

    @Test
    void blocksProjectDebugConfigurationsUntilUnsafeProjectKeysAreEnabled() throws IOException {
        Path home = tempDir.resolve("home-project-debug-safe");
        Path configPath = home.resolve(".shed/config.toml");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, """
            schema_version = 1
            "project.config.require.trusted.file" = false
            "debug.adapter.shared.command" = "shared-adapter"
            "debug.adapter.shared.capabilities" = "launch"
            """);
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        Path project = tempDir.resolve("project-debug-safe");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".shed.toml"), """
            schema_version = 1
            "debug.configuration.project.adapter" = "shared"
            "debug.configuration.project.request" = "launch"
            "debug.configuration.project.program" = "${file}"
            """);

        DebugAdapterRegistry.Validation validation = config.getDebugConfigurationForWorkspace(project);

        assertTrue(validation.valid());
        assertTrue(validation.configurations().isEmpty());
    }

    @Test
    void projectLocalConfigCanBeDisabledAtRuntime() throws IOException {
        Path home = tempDir.resolve("home-project-local-disabled");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        Path project = tempDir.resolve("project-disabled");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(".shed.toml"), "schema_version = 1\ntheme = \"dracula\"\n");
        File file = project.resolve("src/Main.java").toFile();
        Files.writeString(file.toPath(), "class Main {}\n");

        assertTrue(config.applyProjectConfigForFile(file).contains("Project config loaded"));
        assertEquals("dracula", config.getThemeId());

        config.set("project.config.enabled", "false");
        assertTrue(config.applyProjectConfigForFile(file).contains("Project config disabled"));
        assertEquals("one-dark-pro", config.getThemeId());
    }
}
