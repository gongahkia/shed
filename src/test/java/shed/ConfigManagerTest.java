package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(LineNumberMode.ABSOLUTE, config.getLineNumberMode());
        assertTrue(config.getHighlightSearch());
        assertFalse(config.getSessionRestoreOnStart());
        assertFalse(config.getWorkspaceIndexEnabled());
        assertEquals("default", config.getSessionAutoloadName());
        assertEquals(15000, config.getProcessTimeoutMs());
        assertEquals(UndoHistoryPolicy.defaults(), config.getUndoHistoryPolicy());
        assertEquals(new MultiSelectionPolicy(false, MultiSelectionPolicy.DEFAULT_MAX_CURSORS), config.getMultiSelectionPolicy());
        assertFalse(config.hasConfigLoadFailure());
        assertTrue(config.getConfigLoadReport().startsWith("Configuration not found: "));
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
        config.setAndPersist("lsp.completion.enabled", "false");
        config.setAndPersist("lsp.snippets.enabled", "true");
        config.setAndPersist("lsp.signature.help.enabled", "false");
        config.setAndPersist("lsp.hover.enabled", "false");
        config.setAndPersist("lsp.semantic.tokens.enabled", "false");
        config.setAndPersist("lsp.inlay.hints.enabled", "false");
        config.setAndPersist("lsp.definition.enabled", "false");
        config.setAndPersist("lsp.references.enabled", "false");
        config.setAndPersist("lsp.rename.enabled", "false");
        config.setAndPersist("lsp.code.actions.enabled", "false");
        config.setAndPersist("lsp.command.execution.enabled", "false");
        config.setAndPersist("lsp.formatting.enabled", "false");

        LspFeatureSettings features = config.getLspFeatureSettings();
        assertEquals(new LspFeatureSettings(false, true, false, false, false, false, false, false, false, false, false, false), features);
        assertFalse(features.capabilityEnablement().get(LspCapability.COMPLETION));
        assertFalse(features.capabilityEnablement().get(LspCapability.SIGNATURE_HELP));
        assertFalse(features.capabilityEnablement().get(LspCapability.HOVER));
        assertFalse(features.capabilityEnablement().get(LspCapability.DEFINITION));
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
        assertTrue(Files.readString(Path.of(config.getConfigPath())).contains("\"lsp.formatting.enabled\" = false"));
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
            + "\"ui.dramatic.sound.volume\" = 101\n"
            + "\"line.numbers\" = \"diagonal\"\n";
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, source);
        System.setProperty("user.home", home.toString());

        ConfigManager config = new ConfigManager();

        assertTrue(config.hasConfigLoadFailure());
        assertEquals(4, config.getTabSize());
        assertEquals(75, config.getDramaticSoundVolume());
        assertEquals(LineNumberMode.ABSOLUTE, config.getLineNumberMode());
        assertEquals(source, Files.readString(configPath));
        assertTrue(config.getConfigLoadReport().contains("line 2, column"));
        assertTrue(config.getConfigLoadReport().contains("tab.size must be TOML integer"));
        assertTrue(config.getConfigLoadReport().contains("tab.size must be TOML integer (active fallback: 4)"));
        assertTrue(config.getConfigLoadReport().contains("ui.dramatic.sound.volume must be between 0 and 100"));
        assertTrue(config.getConfigLoadReport().contains("ui.dramatic.sound.volume must be between 0 and 100 (active fallback: 75)"));
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
    void dramaticDefaultsAndRuntimeTogglePathsWork() {
        Path home = tempDir.resolve("home-dramatic-defaults");
        System.setProperty("user.home", home.toString());
        System.clearProperty("prefers.reduced.motion");
        ConfigManager config = new ConfigManager();

        assertFalse(config.getDramaticUiEnabled());
        assertEquals("default", config.getDramaticSoundPack());
        assertEquals(75, config.getDramaticSoundVolume());
        assertTrue(config.getDramaticPerformanceGuardrailsEnabled());

        config.set("ui.dramatic", "true");
        config.set("ui.dramatic.sound", "true");
        config.set("ui.dramatic.sound.pack", "cinema");
        config.set("ui.dramatic.sound.volume", "90");
        config.set("ui.dramatic.performance.cpu.threshold", "0.65");

        assertTrue(config.getDramaticUiEnabled());
        assertTrue(config.getDramaticSoundEnabled());
        assertEquals("cinema", config.getDramaticSoundPack());
        assertEquals(90, config.getDramaticSoundVolume());
        assertEquals(0.65, config.getDramaticPerformanceCpuThreshold(), 0.0001);
    }

    @Test
    void dramaticOverridesAndReducedMotionSyncAreParsed() throws IOException {
        Path home = tempDir.resolve("home-dramatic-override");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Files.writeString(shedDir.resolve("config.toml"),
            "schema_version = 1\n"
            + "\"ui.dramatic\" = true\n"
            + "\"ui.dramatic.sound\" = true\n"
            + "\"ui.dramatic.sound.pack\" = \"soft\"\n"
            + "\"ui.dramatic.sound.volume\" = 40\n"
            + "\"ui.dramatic.reduced.motion\" = false\n"
            + "\"ui.dramatic.reduced.motion.sync\" = true\n"
            + "\"ui.dramatic.performance.guardrails\" = true\n"
            + "\"ui.dramatic.performance.line.threshold\" = 30000\n");
        System.setProperty("user.home", home.toString());
        System.setProperty("prefers.reduced.motion", "true");

        ConfigManager config = new ConfigManager();
        assertTrue(config.getDramaticUiEnabled());
        assertTrue(config.getDramaticSoundEnabled());
        assertEquals("soft", config.getDramaticSoundPack());
        assertEquals(40, config.getDramaticSoundVolume());
        assertTrue(config.getDramaticReducedMotionEnabled());
        assertEquals(30000, config.getDramaticPerformanceLineThreshold());
    }

    @Test
    void setAndPersistWritesConfigFile() throws IOException {
        Path home = tempDir.resolve("home-persist-single");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.setAndPersist("ui.dramatic", "true");
        config.setAndPersist("ui.dramatic.sound.pack", "cinema");

        String file = Files.readString(Path.of(config.getConfigPath()));
        assertTrue(file.contains("schema_version = 1"));
        assertTrue(file.contains("\"ui.dramatic\" = true"));
        assertTrue(file.contains("\"ui.dramatic.sound.pack\" = \"cinema\""));
        ConfigManager reloaded = new ConfigManager();
        assertTrue(reloaded.getDramaticUiEnabled());
        assertEquals("cinema", reloaded.getDramaticSoundPack());
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

        config.set("ui.dramatic", "true");
        config.set("ui.dramatic.sound", "true");
        config.set("ui.dramatic.sound.volume", "88");
        int persisted = config.persistCurrentConfig();

        assertTrue(persisted >= 3);
        String file = Files.readString(Path.of(config.getConfigPath()));
        assertTrue(file.contains("schema_version = 1"));
        assertTrue(file.contains("\"ui.dramatic\" = true"));
        assertTrue(file.contains("\"ui.dramatic.sound\" = true"));
        assertTrue(file.contains("\"ui.dramatic.sound.volume\" = 88"));
    }

    @Test
    void persistCurrentConfigSkipsInvalidRuntimeKeys() throws IOException {
        Path home = tempDir.resolve("home-persist-invalid-runtime-key");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        config.set("ui.dramatic", "true");
        config.set("bad\nkey", "boom");
        int persisted = config.persistCurrentConfig();

        assertTrue(persisted >= 1);
        String file = Files.readString(Path.of(config.getConfigPath()));
        assertTrue(file.contains("\"ui.dramatic\" = true"));
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
        Files.writeString(projectA.resolve(".shed.toml"), "schema_version = 1\n\"ui.dramatic\" = true\n\"ui.dramatic.sound.pack\" = \"cinema\"\n");

        File fileA = projectA.resolve("src/App.java").toFile();
        Files.writeString(fileA.toPath(), "class App {}\n");
        File fileB = projectB.resolve("src/Other.java").toFile();
        Files.writeString(fileB.toPath(), "class Other {}\n");

        String loaded = config.applyProjectConfigForFile(fileA);
        assertTrue(loaded.contains("Project config loaded"));
        assertEquals("cinema", config.getDramaticSoundPack());
        assertNotNull(config.getActiveProjectConfigPath());

        String cleared = config.applyProjectConfigForFile(fileB);
        assertTrue(cleared.contains("Project config cleared"));
        assertEquals("default", config.getDramaticSoundPack());
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
                + "ui.dramatic = true\n"
                + "command.user.pwn = \"echo hacked\"\n"
                + "keybind.normal.q = \":q!\"\n");
        File file = project.resolve("src/Main.java").toFile();
        Files.writeString(file.toPath(), "class Main {}\n");

        String loaded = config.applyProjectConfigForFile(file);
        assertTrue(loaded.contains("blocked"));
        assertTrue(config.getDramaticUiEnabled());
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
    void projectLocalConfigCanBeDisabledAtRuntime() throws IOException {
        Path home = tempDir.resolve("home-project-local-disabled");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        Path project = tempDir.resolve("project-disabled");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(".shed.toml"), "schema_version = 1\nui.dramatic = true\n");
        File file = project.resolve("src/Main.java").toFile();
        Files.writeString(file.toPath(), "class Main {}\n");

        assertTrue(config.applyProjectConfigForFile(file).contains("Project config loaded"));
        assertTrue(config.getDramaticUiEnabled());

        config.set("project.config.enabled", "false");
        assertTrue(config.applyProjectConfigForFile(file).contains("Project config disabled"));
        assertFalse(config.getDramaticUiEnabled());
    }
}
