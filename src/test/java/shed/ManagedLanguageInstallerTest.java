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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ManagedLanguageInstallerTest {
    @TempDir Path tempDir;

    @Test
    void reviewExposesSourceVersionSizeAndLicenseBeforeAnyFetch() {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        ManagedLanguageInstaller.Review review = review(content, 8L);

        assertTrue(review.summary().contains("source: https://downloads.example.invalid/tool.tar.gz"));
        assertTrue(review.summary().contains("version: 1.0.0"));
        assertTrue(review.summary().contains("size: 8 bytes"));
        assertTrue(review.summary().contains("license: MIT License"));
    }

    @Test
    void deniesFetchUntilExplicitConsentAndInstallsVerifiedBytes() throws Exception {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        int[] fetches = {0};
        ManagedLanguageInstaller installer = installer(content, fetches);
        ManagedLanguageInstaller.Review review = review(content, (long) content.length);

        ManagedLanguageInstaller.Result denied = installer.install(review, ManagedLanguageSupportTrust.Platform.LINUX, false, () -> false);

        assertEquals(ManagedLanguageInstaller.Outcome.CONSENT_REQUIRED, denied.outcome());
        assertEquals(0, fetches[0]);
        ManagedLanguageInstaller.Result installed = installer.install(review, ManagedLanguageSupportTrust.Platform.LINUX, true, () -> false);
        assertTrue(installed.installed());
        assertEquals(1, fetches[0]);
        assertEquals(content.length, Files.size(installed.installedPath()));
        assertEquals("artifact", Files.readString(installed.installedPath()));
    }

    @Test
    void cancellationAndIntegrityFailureLeaveNoManagedArtifact() throws Exception {
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        ManagedLanguageInstaller cancelled = installer(content, new int[] {0});
        ManagedLanguageInstaller rejected = installer("tampered".getBytes(StandardCharsets.UTF_8), new int[] {0});
        ManagedLanguageInstaller.Review review = review(content, (long) content.length);

        ManagedLanguageInstaller.Result cancelledResult = cancelled.install(review, ManagedLanguageSupportTrust.Platform.LINUX, true, () -> true);
        ManagedLanguageInstaller.Result rejectedResult = rejected.install(review, ManagedLanguageSupportTrust.Platform.LINUX, true, () -> false);

        assertEquals(ManagedLanguageInstaller.Outcome.CANCELLED, cancelledResult.outcome());
        assertEquals(ManagedLanguageInstaller.Outcome.REJECTED, rejectedResult.outcome());
        assertFalse(Files.exists(tempDir.resolve(".shed/managed-languages/test.tool/1.0.0/tool.tar.gz")));
    }

    private ManagedLanguageInstaller installer(byte[] content, int[] fetches) {
        return new ManagedLanguageInstaller(trust(content), artifact -> {
            fetches[0]++;
            return new ByteArrayInputStream(content);
        }, tempDir.resolve(".shed"));
    }

    private ManagedLanguageInstaller.Review review(byte[] content, Long size) {
        ManagedLanguageCatalog.InstallMetadata metadata = new ManagedLanguageCatalog.InstallMetadata(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("test.tool", "1.0.0"), URI.create("https://example.invalid/project"),
            URI.create("https://example.invalid/license"), "MIT License", "Node.js", "16",
            ManagedLanguageCatalog.RuntimeRequirementKind.MINIMUM_VERSION, ManagedLanguageCatalog.RuntimeVersionScheme.STANDARD,
            Set.of(ManagedLanguageSupportTrust.Platform.LINUX));
        return new ManagedLanguageInstaller.Review(trust(content).assess(ManagedLanguageSupportTrust.Ownership.SHED_MANAGED,
            metadata.coordinate(), ManagedLanguageSupportTrust.Platform.LINUX, true).artifact(), metadata, size, "tool.tar.gz");
    }

    private ManagedLanguageSupportTrust trust(byte[] content) {
        ManagedLanguageSupportTrust.CatalogArtifact artifact = new ManagedLanguageSupportTrust.CatalogArtifact(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("test.tool", "1.0.0"), URI.create("https://downloads.example.invalid/tool.tar.gz"),
            sha256(content), "shed-test-key", "detached-signature", Set.of(ManagedLanguageSupportTrust.Platform.LINUX));
        return new ManagedLanguageSupportTrust(List.of(artifact), Set.of());
    }

    private String sha256(byte[] content) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
