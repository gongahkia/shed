package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ManagedLanguageArtifactStoreTest {
    @TempDir Path tempDir;

    @Test
    void rejectsTamperedStaleAndIncompleteArtifactsWithoutALaunchPath() throws Exception {
        Map<String, byte[]> content = Map.of("1.0.0", "verified".getBytes(StandardCharsets.UTF_8));
        ManagedLanguageSupportTrust trust = trust(content);
        ManagedLanguageInstaller installer = installer(trust, content);
        install(installer, trust, content, "1.0.0");
        ManagedLanguageArtifactStore store = new ManagedLanguageArtifactStore(trust, tempDir.resolve(".shed"));
        ManagedLanguageSupportTrust.ArtifactCoordinate coordinate = coordinate("1.0.0");
        Path artifact = store.resolveForLaunch(coordinate, ManagedLanguageSupportTrust.Platform.LINUX).launchPath();

        assertEquals(artifact, store.resolveActive("test.tool", ManagedLanguageSupportTrust.Platform.LINUX).launchPath());
        Files.writeString(artifact, "tampered");
        assertRejected(store.resolveForLaunch(coordinate, ManagedLanguageSupportTrust.Platform.LINUX), "tampered");
        assertRejected(store.resolveActive("test.tool", ManagedLanguageSupportTrust.Platform.LINUX), "tampered");

        Files.writeString(artifact, "verified");
        Path receipt = artifact.getParent().resolve(ManagedLanguageArtifactStore.RECEIPT_FILE_NAME);
        Files.writeString(receipt, Files.readString(receipt).replace("\"version\":\"1.0.0\"", "\"version\":\"0.9.0\""));
        assertRejected(store.resolveForLaunch(coordinate, ManagedLanguageSupportTrust.Platform.LINUX), "stale");
        assertRejected(store.resolveActive("test.tool", ManagedLanguageSupportTrust.Platform.LINUX), "stale");

        Files.delete(receipt);
        assertRejected(store.resolveForLaunch(coordinate, ManagedLanguageSupportTrust.Platform.LINUX), "incomplete");
        assertRejected(store.resolveActive("test.tool", ManagedLanguageSupportTrust.Platform.LINUX), "incomplete");
    }

    @Test
    void keepsOnlyActiveAndPreviousVerifiedVersionsAndRollsBackExplicitly() throws Exception {
        Map<String, byte[]> content = Map.of(
            "1.0.0", "one".getBytes(StandardCharsets.UTF_8),
            "2.0.0", "two".getBytes(StandardCharsets.UTF_8),
            "3.0.0", "three".getBytes(StandardCharsets.UTF_8)
        );
        ManagedLanguageSupportTrust trust = trust(content);
        ManagedLanguageInstaller installer = installer(trust, content);
        install(installer, trust, content, "1.0.0");
        install(installer, trust, content, "2.0.0");
        install(installer, trust, content, "3.0.0");
        ManagedLanguageArtifactStore store = new ManagedLanguageArtifactStore(trust, tempDir.resolve(".shed"));
        Path toolDirectory = tempDir.resolve(".shed/managed-languages/test.tool");

        assertFalse(Files.exists(toolDirectory.resolve("1.0.0")));
        assertTrue(Files.isDirectory(toolDirectory.resolve("2.0.0")));
        assertTrue(Files.isDirectory(toolDirectory.resolve("3.0.0")));
        assertEquals("three", Files.readString(store.resolveActive("test.tool", ManagedLanguageSupportTrust.Platform.LINUX).launchPath()));

        ManagedLanguageArtifactStore.Result rollback = store.rollback("test.tool", ManagedLanguageSupportTrust.Platform.LINUX);

        assertEquals(ManagedLanguageArtifactStore.Outcome.ROLLED_BACK, rollback.outcome());
        assertEquals("two", Files.readString(store.resolveActive("test.tool", ManagedLanguageSupportTrust.Platform.LINUX).launchPath()));
    }

    private void assertRejected(ManagedLanguageArtifactStore.Result result, String detail) {
        assertEquals(ManagedLanguageArtifactStore.Outcome.REJECTED, result.outcome());
        assertFalse(result.launchable());
        assertEquals(null, result.launchPath());
        assertTrue(result.detail().contains(detail));
    }

    private ManagedLanguageInstaller installer(ManagedLanguageSupportTrust trust, Map<String, byte[]> content) {
        return new ManagedLanguageInstaller(trust, artifact -> new ByteArrayInputStream(content.get(artifact.coordinate().version())), tempDir.resolve(".shed"));
    }

    private void install(ManagedLanguageInstaller installer, ManagedLanguageSupportTrust trust, Map<String, byte[]> content, String version) {
        byte[] bytes = content.get(version);
        ManagedLanguageSupportTrust.CatalogArtifact artifact = trust.assess(ManagedLanguageSupportTrust.Ownership.SHED_MANAGED,
            coordinate(version), ManagedLanguageSupportTrust.Platform.LINUX, true).artifact();
        ManagedLanguageCatalog.InstallMetadata metadata = new ManagedLanguageCatalog.InstallMetadata(coordinate(version), URI.create("https://example.invalid/project"),
            URI.create("https://example.invalid/license"), "MIT License", "Node.js", "16", ManagedLanguageCatalog.RuntimeRequirementKind.MINIMUM_VERSION,
            ManagedLanguageCatalog.RuntimeVersionScheme.STANDARD, Set.of(ManagedLanguageSupportTrust.Platform.LINUX));
        ManagedLanguageInstaller.Result result = installer.install(new ManagedLanguageInstaller.Review(artifact, metadata, (long) bytes.length, "tool.tar.gz"),
            ManagedLanguageSupportTrust.Platform.LINUX, true, () -> false);
        assertTrue(result.installed(), result.detail());
    }

    private ManagedLanguageSupportTrust trust(Map<String, byte[]> content) {
        return new ManagedLanguageSupportTrust(content.entrySet().stream().map(entry -> new ManagedLanguageSupportTrust.CatalogArtifact(
            coordinate(entry.getKey()), URI.create("https://downloads.example.invalid/tool-" + entry.getKey() + ".tar.gz"), sha256(entry.getValue()),
            "shed-test-key", "detached-signature", Set.of(ManagedLanguageSupportTrust.Platform.LINUX))).toList(), Set.of());
    }

    private ManagedLanguageSupportTrust.ArtifactCoordinate coordinate(String version) {
        return new ManagedLanguageSupportTrust.ArtifactCoordinate("test.tool", version);
    }

    private String sha256(byte[] content) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
