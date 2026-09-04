package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LandingPageSourceTest {
    @TempDir
    Path tempDir;
    private String originalHome;

    @BeforeEach
    void saveHome() {
        originalHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void createsConfiguredLocalLandingFile() throws Exception {
        Path home = tempDir.resolve("home");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        config.set("landing.source", "pages/start.md");

        LandingPageSource.Resolved source = LandingPageSource.resolve(config);
        Path landing = LandingPageSource.ensureLocalFile(source, "hello\n").toPath();

        assertFalse(source.isRemote());
        assertEquals(home.resolve("pages/start.md"), landing);
        assertEquals("hello\n", Files.readString(landing));
    }

    @Test
    void mapsHttpsSourceToLocalCache() throws Exception {
        Path home = tempDir.resolve("home-remote");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        config.set("landing.source", "https://example.com/start.md");
        config.set("landing.remote.cache.path", "cache/start.md");

        LandingPageSource.Resolved source = LandingPageSource.resolve(config);
        Path cache = LandingPageSource.ensureLocalFile(source, "loading\n").toPath();

        assertTrue(source.isRemote());
        assertEquals("https://example.com/start.md", source.remoteUri().toString());
        assertEquals(home.resolve("cache/start.md"), cache);
    }

    @Test
    void usesNativeWelcomeOnlyForMissingOrUntouchedDefaultLandingFile() throws Exception {
        Path home = tempDir.resolve("home-welcome");
        System.setProperty("user.home", home.toString());
        ConfigManager config = new ConfigManager();
        String legacyContent = "shed 2.0\nwelcome\n";

        assertTrue(LandingPageSource.resolveStartupTarget(config, legacyContent).showNativeWelcome());

        Path defaultLanding = home.resolve(".shed/landing.md");
        Files.createDirectories(defaultLanding.getParent());
        Files.writeString(defaultLanding, legacyContent);
        assertTrue(LandingPageSource.resolveStartupTarget(config, legacyContent).showNativeWelcome());

        Files.writeString(defaultLanding, """
            shed 1.4
            swing modal editor

            :help        view help
            :e <file>    open a file
            :recent      show recent files
            :ls          list open buffers

            edit and save this local landing file to customize it.
            """);
        assertTrue(LandingPageSource.resolveStartupTarget(config, legacyContent).showNativeWelcome());

        Files.writeString(defaultLanding, "my own start page\n");
        assertFalse(LandingPageSource.resolveStartupTarget(config, legacyContent).showNativeWelcome());

        config.set("landing.source", "pages/start.md");
        assertFalse(LandingPageSource.resolveStartupTarget(config, legacyContent).showNativeWelcome());
        config.set("landing.welcome.enabled", "false");
        assertFalse(LandingPageSource.resolveStartupTarget(config, legacyContent).showNativeWelcome());
    }

    @Test
    void rejectsInsecureRemoteSource() {
        ConfigManager config = new ConfigManager();
        config.set("landing.source", "http://example.com/start.md");

        IOException error = assertThrows(IOException.class, () -> LandingPageSource.resolve(config));

        assertEquals("remote landing source must use HTTPS", error.getMessage());
    }
}
