package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertEquals("default", config.getSessionAutoloadName());
        assertEquals(15000, config.getProcessTimeoutMs());
    }

    @Test
    void parsesConfigOverrides() throws IOException {
        Path home = tempDir.resolve("home-custom");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Path configPath = shedDir.resolve("shedrc");
        Files.writeString(configPath,
            "tab.size=8\n"
                + "line.numbers=relative\n"
                + "highlight.search=false\n"
                + "command.alias.ww=w\n"
                + "session.restore.on.start=true\n"
                + "session.autoload=work\n"
                + "process.timeout.ms=5000\n");

        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(8, config.getTabSize());
        assertEquals(LineNumberMode.RELATIVE, config.getLineNumberMode());
        assertFalse(config.getHighlightSearch());
        assertEquals("w", config.resolveCommandAlias("ww"));
        assertTrue(config.getSessionRestoreOnStart());
        assertEquals("work", config.getSessionAutoloadName());
        assertEquals(5000, config.getProcessTimeoutMs());
    }

    @Test
    void migratesLegacyRootConfigIntoShedDirectory() throws IOException {
        Path home = tempDir.resolve("home-legacy");
        Files.createDirectories(home);
        Files.writeString(home.resolve(".shedrc"), "tab.size=6\n");

        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();

        assertEquals(6, config.getTabSize());
        assertTrue(Files.exists(home.resolve(".shed/shedrc")));
        assertEquals(home.resolve(".shed/shedrc").toString(), config.getConfigPath());
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
    void getConfiguredLspServersReturnsEmpty() {
        Path home = tempDir.resolve("home-nolsp");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        Map<String, String> servers = config.getConfiguredLspServers();
        assertNotNull(servers);
        assertTrue(servers.isEmpty());
    }

    @Test
    void getConfiguredLspServersParsesShedrc() throws IOException {
        Path home = tempDir.resolve("home-lsp");
        Path shedDir = home.resolve(".shed");
        Files.createDirectories(shedDir);
        Files.writeString(shedDir.resolve("shedrc"),
            "lsp.py.command=pyright-langserver\n"
            + "lsp.py.args=--stdio\n"
            + "lsp.rs.command=rust-analyzer\n");
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
        Files.writeString(shedDir.resolve("shedrc"),
            "command.user.build=make -j4\n"
            + "command.user.test=./test.sh\n");
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
        Files.writeString(shedDir.resolve("shedrc"),
            "ui.dramatic=true\n"
            + "ui.dramatic.sound=true\n"
            + "ui.dramatic.sound.pack=soft\n"
            + "ui.dramatic.sound.volume=40\n"
            + "ui.dramatic.reduced.motion=false\n"
            + "ui.dramatic.reduced.motion.sync=true\n"
            + "ui.dramatic.performance.guardrails=true\n"
            + "ui.dramatic.performance.line.threshold=30000\n");
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
        assertTrue(file.contains("ui.dramatic=true"));
        assertTrue(file.contains("ui.dramatic.sound.pack=cinema"));
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
        assertTrue(file.contains("ui.dramatic=true"));
        assertTrue(file.contains("ui.dramatic.sound=true"));
        assertTrue(file.contains("ui.dramatic.sound.volume=88"));
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
        Files.writeString(projectA.resolve(".shedrc.local"), "ui.dramatic=true\nui.dramatic.sound.pack=cinema\n");

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
        Files.writeString(project.resolve(".shedrc.local"),
            "ui.dramatic=true\n"
                + "command.user.pwn=echo hacked\n"
                + "keybind.normal.q=:q!\n");
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
        Files.writeString(project.resolve(".shedrc.local"),
            "command.user.local=echo ok\n"
                + "keybind.normal.q=:q!\n");
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
        Files.writeString(project.resolve(".shedrc.local"), "ui.dramatic=true\n");
        File file = project.resolve("src/Main.java").toFile();
        Files.writeString(file.toPath(), "class Main {}\n");

        assertTrue(config.applyProjectConfigForFile(file).contains("Project config loaded"));
        assertTrue(config.getDramaticUiEnabled());

        config.set("project.config.enabled", "false");
        assertTrue(config.applyProjectConfigForFile(file).contains("Project config disabled"));
        assertFalse(config.getDramaticUiEnabled());
    }
}
