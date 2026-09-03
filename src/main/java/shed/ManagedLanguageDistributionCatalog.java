package shed;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Built-in, reviewed downloads. Never refreshed over the network. */
final class ManagedLanguageDistributionCatalog {
    enum ArchiveFormat { TAR_GZ }
    enum InstallerKind { PINNED_ARCHIVE, NPM }

    record Distribution(InstallerKind installerKind, ManagedLanguageSupportTrust.CatalogArtifact artifact,
                        String archiveFileName, ArchiveFormat archiveFormat, List<String> npmPackages,
                        Map<ManagedLanguageSupportTrust.Platform, String> launchPaths, List<String> launchArguments,
                        String verificationNotice) {
        Distribution {
            if (installerKind == null || launchPaths == null || launchPaths.isEmpty()) {
                throw new IllegalArgumentException("invalid managed language distribution");
            }
            if (installerKind == InstallerKind.PINNED_ARCHIVE && (artifact == null || archiveFileName == null
                || archiveFileName.isBlank() || archiveFileName.contains("/") || archiveFormat == null)) {
                throw new IllegalArgumentException("pinned archive metadata is required");
            }
            if (installerKind == InstallerKind.NPM && (artifact != null || archiveFileName != null || archiveFormat != null
                || npmPackages == null || npmPackages.isEmpty())) {
                throw new IllegalArgumentException("npm distribution metadata is required");
            }
            launchPaths = Map.copyOf(launchPaths);
            npmPackages = npmPackages == null ? List.of() : List.copyOf(npmPackages);
            launchArguments = launchArguments == null ? List.of() : List.copyOf(launchArguments);
            verificationNotice = verificationNotice == null ? "" : verificationNotice;
        }

        String launchPath(ManagedLanguageSupportTrust.Platform platform) {
            return launchPaths.get(platform);
        }

        boolean usesPinnedArchive() { return installerKind == InstallerKind.PINNED_ARCHIVE; }

        boolean usesNpm() { return installerKind == InstallerKind.NPM; }
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
    private static final Distribution JDTLS = new Distribution(InstallerKind.PINNED_ARCHIVE, JDTLS_ARCHIVE,
        "jdt-language-server-1.60.0-202606262232.tar.gz", ArchiveFormat.TAR_GZ, List.of(),
        Map.of(ManagedLanguageSupportTrust.Platform.MACOS, "bin/jdtls",
            ManagedLanguageSupportTrust.Platform.LINUX, "bin/jdtls",
            ManagedLanguageSupportTrust.Platform.WINDOWS, "bin/jdtls.bat"),
        List.of(),
        "The Eclipse download publishes an official SHA-256 file; it does not publish a detached archive signature. Shed pins and verifies that SHA-256 before extraction.");

    private static final Map<ManagedLanguageSupportTrust.Platform, String> NPM_LAUNCHERS = Map.of(
        ManagedLanguageSupportTrust.Platform.MACOS, "node_modules/.bin/%s",
        ManagedLanguageSupportTrust.Platform.LINUX, "node_modules/.bin/%s",
        ManagedLanguageSupportTrust.Platform.WINDOWS, "node_modules/.bin/%s.cmd"
    );
    private static final Distribution PYRIGHT = npm("pyright-langserver", List.of("pyright@1.1.411"), List.of("--stdio"),
        "npm resolves the exact requested Pyright version and records package-integrity values in its local lockfile. This is not an independently published archive checksum. npm lifecycle scripts, audit, funding, and update-notifier requests are disabled.");
    private static final Distribution TYPESCRIPT_JAVASCRIPT = npm("typescript-language-server",
        List.of("typescript-language-server@5.3.0", "typescript@6.0.3"), List.of("--stdio"),
        "npm resolves the exact requested TypeScript Language Server and TypeScript versions and records package-integrity values in its local lockfile. This is not an independently published archive checksum. npm lifecycle scripts, audit, funding, and update-notifier requests are disabled.");
    private static final Distribution JSON = npm("vscode-json-language-server",
        List.of("@zed-industries/vscode-langservers-extracted@4.10.8"), List.of("--stdio"),
        "npm resolves the exact requested Zed-maintained VS Code language-server bundle and records package-integrity values in its local lockfile. This is not an independently published archive checksum. npm lifecycle scripts, audit, funding, and update-notifier requests are disabled.");
    private static final Distribution HTML = npm("vscode-html-language-server",
        List.of("@zed-industries/vscode-langservers-extracted@4.10.8"), List.of("--stdio"),
        "npm resolves the exact requested Zed-maintained VS Code language-server bundle and records package-integrity values in its local lockfile. This is not an independently published archive checksum. npm lifecycle scripts, audit, funding, and update-notifier requests are disabled.");
    private static final Distribution CSS = npm("vscode-css-language-server",
        List.of("@zed-industries/vscode-langservers-extracted@4.10.8"), List.of("--stdio"),
        "npm resolves the exact requested Zed-maintained VS Code language-server bundle and records package-integrity values in its local lockfile. This is not an independently published archive checksum. npm lifecycle scripts, audit, funding, and update-notifier requests are disabled.");
    private static final Distribution MARKDOWN = npm("remark-language-server", List.of("remark-language-server@3.0.0"),
        List.of("--stdio"), "npm resolves the exact requested remark-language-server version and records package-integrity values in its local lockfile. This is not an independently published archive checksum. npm lifecycle scripts, audit, funding, and update-notifier requests are disabled.");

    private static final Map<String, Distribution> DISTRIBUTIONS = Map.of(
        "java", JDTLS,
        "python", PYRIGHT,
        "typescript-javascript", TYPESCRIPT_JAVASCRIPT,
        "json", JSON,
        "html", HTML,
        "css", CSS,
        "markdown", MARKDOWN
    );

    private ManagedLanguageDistributionCatalog() {
    }

    static ManagedLanguageSupportTrust trust() {
        return new ManagedLanguageSupportTrust(List.of(JDTLS_ARCHIVE), Set.of());
    }

    static Distribution forEntry(ManagedLanguageCatalog.Entry entry) {
        return entry == null ? null : DISTRIBUTIONS.get(entry.languageId());
    }

    private static Distribution npm(String launcher, List<String> packages, List<String> arguments, String notice) {
        Map<ManagedLanguageSupportTrust.Platform, String> launchPaths = Map.of(
            ManagedLanguageSupportTrust.Platform.MACOS, NPM_LAUNCHERS.get(ManagedLanguageSupportTrust.Platform.MACOS).formatted(launcher),
            ManagedLanguageSupportTrust.Platform.LINUX, NPM_LAUNCHERS.get(ManagedLanguageSupportTrust.Platform.LINUX).formatted(launcher),
            ManagedLanguageSupportTrust.Platform.WINDOWS, NPM_LAUNCHERS.get(ManagedLanguageSupportTrust.Platform.WINDOWS).formatted(launcher)
        );
        return new Distribution(InstallerKind.NPM, null, null, null, packages, launchPaths, arguments, notice);
    }
}
