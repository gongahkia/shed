package shed;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Built-in, reviewed downloads. Never refreshed over the network. */
final class ManagedLanguageDistributionCatalog {
    enum ArchiveFormat { TAR_GZ }

    record Distribution(ManagedLanguageSupportTrust.CatalogArtifact artifact, String archiveFileName,
                        ArchiveFormat archiveFormat, Map<ManagedLanguageSupportTrust.Platform, String> launchPaths,
                        String verificationNotice) {
        Distribution {
            if (artifact == null || archiveFileName == null || archiveFileName.isBlank() || archiveFileName.contains("/")
                || archiveFormat == null || launchPaths == null || launchPaths.isEmpty()) {
                throw new IllegalArgumentException("invalid managed language distribution");
            }
            launchPaths = Map.copyOf(launchPaths);
            verificationNotice = verificationNotice == null ? "" : verificationNotice;
        }

        String launchPath(ManagedLanguageSupportTrust.Platform platform) {
            return launchPaths.get(platform);
        }
    }

    private static final ManagedLanguageSupportTrust.ArtifactCoordinate JDTLS_1_60_0 =
        new ManagedLanguageSupportTrust.ArtifactCoordinate("java.eclipse-jdtls", "1.60.0");
    private static final ManagedLanguageSupportTrust.CatalogArtifact JDTLS_ARCHIVE =
        new ManagedLanguageSupportTrust.CatalogArtifact(JDTLS_1_60_0,
            URI.create("https://download.eclipse.org/jdtls/milestones/1.60.0/jdt-language-server-1.60.0-202606262232.tar.gz"),
            "e94c303d8198f977930803582738771fd18c52c5492878410bf222b1aa81ef1d",
            "eclipse-download-sha256", "official-sha256-file",
            Set.of(ManagedLanguageSupportTrust.Platform.MACOS, ManagedLanguageSupportTrust.Platform.WINDOWS,
                ManagedLanguageSupportTrust.Platform.LINUX));
    private static final Distribution JDTLS = new Distribution(JDTLS_ARCHIVE,
        "jdt-language-server-1.60.0-202606262232.tar.gz", ArchiveFormat.TAR_GZ,
        Map.of(ManagedLanguageSupportTrust.Platform.MACOS, "bin/jdtls",
            ManagedLanguageSupportTrust.Platform.LINUX, "bin/jdtls",
            ManagedLanguageSupportTrust.Platform.WINDOWS, "bin/jdtls.bat"),
        "The Eclipse download publishes an official SHA-256 file; it does not publish a detached archive signature. Shed pins and verifies that SHA-256 before extraction.");

    private static final Map<String, Distribution> DISTRIBUTIONS = Map.of("java", JDTLS);

    private ManagedLanguageDistributionCatalog() {
    }

    static ManagedLanguageSupportTrust trust() {
        return new ManagedLanguageSupportTrust(List.of(JDTLS_ARCHIVE), Set.of());
    }

    static Distribution forEntry(ManagedLanguageCatalog.Entry entry) {
        return entry == null ? null : DISTRIBUTIONS.get(entry.languageId());
    }
}
