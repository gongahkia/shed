package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class NetworkConsentAuditTest {
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
    void githubRequestsRemainDisabledUntilEnabledAndConsented() throws IOException {
        System.setProperty("user.home", tempDir.resolve("home").toString());
        ConfigManager config = new ConfigManager();

        assertFalse(config.getGitHubReviewEnabled());
        assertFalse(config.getGitHubReviewConsentGranted());
        config.set("github.review.enabled", "true");
        assertFalse(config.getGitHubReviewEnabled());
        config.set("github.review.consent.granted", "true");
        assertTrue(config.getGitHubReviewEnabled());
    }

    @Test
    void updateRequestsRemainDisabledUntilEnabledAndConsented() throws IOException {
        System.setProperty("user.home", tempDir.resolve("home-updates").toString());
        ConfigManager config = new ConfigManager();

        assertFalse(config.getUpdatesEnabled());
        assertFalse(config.getUpdateConsentGranted());
        config.set("updates.enabled", "true");
        assertFalse(config.getUpdatesEnabled());
        config.set("updates.consent.granted", "true");
        assertTrue(config.getUpdatesEnabled());
        assertFalse(config.isProjectConfigKeyAllowed("updates.metadata.url"));
    }

    @Test
    void appOwnedOutboundPrimitivesRemainDocumented() throws IOException {
        assertEquals(Set.of("PluginManager.java"), sourcesContaining("openConnection("));
        assertEquals(Set.of("LandingPageRemoteTransport.java", "UpdateMetadataTransport.java"), sourcesContaining("HttpClient.newBuilder("));
        assertEquals(Set.of("DebugAdapterTransport.java"), sourcesContaining("new Socket("));
        assertEquals(Set.of("EditActionController.java", "MarkdownController.java", "UpdateController.java"), sourcesContaining(".browse("));
        assertEquals(Set.of(
            "DebugAdapterTransport.java", "JobQuickfixController.java", "LanguageServerDetector.java", "LspClient.java",
            "LuaEngine.java", "PaletteController.java", "SyntaxUiController.java", "WorkspaceIndexService.java"
        ), sourcesContaining("new ProcessBuilder("));
        assertEquals(Set.of("PtyTerminalPane.java"), sourcesContaining("PtyProcessBuilder"));

        String audit = Files.readString(Path.of("docs/NETWORK_PRIVACY.md"));
        for (String source : Set.of("PluginManager", "DebugAdapterTransport", "GitHub", "UpdateMetadataTransport", "LandingPageRemoteTransport", "ManagedLanguageCatalog", "browser", "child processes")) {
            assertTrue(audit.contains(source), "audit missing " + source);
        }
    }

    private static Set<String> sourcesContaining(String marker) throws IOException {
        try (Stream<Path> sourceFiles = Files.walk(Path.of("src/main/java/shed"))) {
            return sourceFiles
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> contains(path, marker))
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static boolean contains(Path path, String marker) {
        try {
            return Files.readString(path).contains(marker);
        } catch (IOException error) {
            throw new IllegalStateException("cannot inspect " + path, error);
        }
    }
}
