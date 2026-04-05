package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @BeforeEach
    void saveHome() {
        originalHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreHome() {
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
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
}
