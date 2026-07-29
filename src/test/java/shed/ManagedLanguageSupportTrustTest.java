package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ManagedLanguageSupportTrustTest {
    private static final ManagedLanguageSupportTrust.ArtifactCoordinate JDTLS =
        new ManagedLanguageSupportTrust.ArtifactCoordinate("java.jdtls", "1.43.0");

    @Test
    void userManagedToolsNeedNoCatalogConsentOrManagedNetwork() {
        ManagedLanguageSupportTrust trust = trustWith(validArtifact());

        ManagedLanguageSupportTrust.Assessment assessment = trust.assess(
            ManagedLanguageSupportTrust.Ownership.USER_MANAGED, null, null, false);

        assertEquals(ManagedLanguageSupportTrust.Decision.ALLOWED, assessment.decision());
        assertFalse(assessment.permitsManagedNetwork());
        assertFalse(assessment.permitsManagedCacheWrite());
        assertEquals(null, assessment.artifact());
    }

    @Test
    void managedToolRequiresExplicitConsentBeforeNetworkOrCacheWrite() {
        ManagedLanguageSupportTrust trust = trustWith(validArtifact());

        ManagedLanguageSupportTrust.Assessment assessment = trust.assess(
            ManagedLanguageSupportTrust.Ownership.SHED_MANAGED, JDTLS,
            ManagedLanguageSupportTrust.Platform.MACOS, false);

        assertEquals(ManagedLanguageSupportTrust.Decision.CONSENT_REQUIRED, assessment.decision());
        assertFalse(assessment.permitsManagedNetwork());
        assertFalse(assessment.permitsManagedCacheWrite());
    }

    @Test
    void approvedSignedCatalogArtifactPermitsManagedInstall() {
        ManagedLanguageSupportTrust trust = trustWith(validArtifact());

        ManagedLanguageSupportTrust.Assessment assessment = trust.assess(
            ManagedLanguageSupportTrust.Ownership.SHED_MANAGED, JDTLS,
            ManagedLanguageSupportTrust.Platform.MACOS, true);

        assertEquals(ManagedLanguageSupportTrust.Decision.ALLOWED, assessment.decision());
        assertTrue(assessment.permitsManagedNetwork());
        assertTrue(assessment.permitsManagedCacheWrite());
    }

    @Test
    void rejectsUnknownUnsignedUnsupportedAndRevokedManagedArtifacts() {
        ManagedLanguageSupportTrust trust = trustWith(validArtifact(), Set.of(JDTLS));
        ManagedLanguageSupportTrust.ArtifactCoordinate unknown =
            new ManagedLanguageSupportTrust.ArtifactCoordinate("python.pyright", "1.1.0");
        ManagedLanguageSupportTrust.CatalogArtifact unsigned = new ManagedLanguageSupportTrust.CatalogArtifact(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("java.jdtls", "1.44.0"),
            URI.create("https://downloads.example.invalid/jdtls"), "a".repeat(64), "", "", Set.of(ManagedLanguageSupportTrust.Platform.MACOS));
        ManagedLanguageSupportTrust trustWithUnsigned = trustWith(unsigned);

        assertEquals(ManagedLanguageSupportTrust.Decision.REJECTED, trust.assess(
            ManagedLanguageSupportTrust.Ownership.SHED_MANAGED, unknown,
            ManagedLanguageSupportTrust.Platform.MACOS, true).decision());
        assertEquals(ManagedLanguageSupportTrust.Decision.REJECTED, trust.assess(
            ManagedLanguageSupportTrust.Ownership.SHED_MANAGED, JDTLS,
            ManagedLanguageSupportTrust.Platform.MACOS, true).decision());
        assertEquals(ManagedLanguageSupportTrust.Decision.REJECTED, trustWithUnsigned.assess(
            ManagedLanguageSupportTrust.Ownership.SHED_MANAGED, unsigned.coordinate(),
            ManagedLanguageSupportTrust.Platform.MACOS, true).decision());
        assertEquals(ManagedLanguageSupportTrust.Decision.REJECTED, trustWith(validArtifact()).assess(
            ManagedLanguageSupportTrust.Ownership.SHED_MANAGED, JDTLS,
            ManagedLanguageSupportTrust.Platform.WINDOWS, true).decision());
    }

    @Test
    void confinesManagedCachesToShedOwnedDirectory() {
        ManagedLanguageSupportTrust trust = trustWith(validArtifact());
        Path cache = trust.cacheDirectory(Path.of("target", "shed-home"), JDTLS);

        assertTrue(cache.startsWith(trust.cacheRoot(Path.of("target", "shed-home"))));
        assertTrue(cache.endsWith(Path.of("java.jdtls", "1.43.0")));
        assertThrows(IllegalArgumentException.class,
            () -> new ManagedLanguageSupportTrust.ArtifactCoordinate("../escape", "1.0"));
    }

    private ManagedLanguageSupportTrust trustWith(ManagedLanguageSupportTrust.CatalogArtifact artifact) {
        return trustWith(artifact, Set.of());
    }

    private ManagedLanguageSupportTrust trustWith(ManagedLanguageSupportTrust.CatalogArtifact artifact,
        Set<ManagedLanguageSupportTrust.ArtifactCoordinate> revoked) {
        return new ManagedLanguageSupportTrust(List.of(artifact), revoked);
    }

    private ManagedLanguageSupportTrust.CatalogArtifact validArtifact() {
        return new ManagedLanguageSupportTrust.CatalogArtifact(
            JDTLS,
            URI.create("https://downloads.example.invalid/jdtls"),
            "a".repeat(64),
            "shed-release-key-2026",
            "base64-detached-signature",
            Set.of(ManagedLanguageSupportTrust.Platform.MACOS, ManagedLanguageSupportTrust.Platform.LINUX)
        );
    }
}
