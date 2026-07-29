package shed;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ManagedLanguageCatalog {
    enum Availability {
        AVAILABLE,
        UNSUPPORTED_PLATFORM,
        EXECUTABLE_MISSING,
        RUNTIME_VERSION_UNKNOWN,
        RUNTIME_VERSION_UNSUPPORTED,
        MANAGED_CONSENT_REQUIRED,
        MANAGED_ARTIFACT_UNAVAILABLE,
        MANAGED_INSTALL_READY
    }

    record ToolDetection(String executable, String runtimeVersion) {
        boolean executableFound() {
            return executable != null && !executable.isBlank();
        }
    }

    record InstallMetadata(
        ManagedLanguageSupportTrust.ArtifactCoordinate coordinate,
        URI projectUrl,
        URI licenseUrl,
        String licenseName,
        int minimumRuntimeMajor,
        Set<ManagedLanguageSupportTrust.Platform> supportedPlatforms
    ) {
        InstallMetadata {
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            projectUrl = Objects.requireNonNull(projectUrl, "project url");
            licenseUrl = Objects.requireNonNull(licenseUrl, "license url");
            licenseName = Objects.requireNonNull(licenseName, "license name");
            supportedPlatforms = supportedPlatforms == null ? Set.of() : Set.copyOf(supportedPlatforms);
        }
    }

    record Status(Availability availability, String detail, String remediation,
        ManagedLanguageSupportTrust.Assessment trustAssessment) {
        boolean usable() {
            return availability == Availability.AVAILABLE;
        }

        boolean permitsManagedInstall() {
            return availability == Availability.MANAGED_INSTALL_READY
                && trustAssessment != null
                && trustAssessment.permitsManagedNetwork();
        }
    }

    record Entry(
        String languageId,
        Set<String> extensions,
        String displayName,
        String command,
        String windowsCommand,
        InstallMetadata installMetadata
    ) {
        Entry {
            languageId = requireText(languageId, "language id");
            extensions = extensions == null ? Set.of() : Set.copyOf(extensions);
            displayName = requireText(displayName, "display name");
            command = requireText(command, "command");
            windowsCommand = requireText(windowsCommand, "Windows command");
            installMetadata = Objects.requireNonNull(installMetadata, "install metadata");
        }

        String commandFor(ManagedLanguageSupportTrust.Platform platform) {
            return platform == ManagedLanguageSupportTrust.Platform.WINDOWS ? windowsCommand : command;
        }

        Status assessUserManaged(ManagedLanguageSupportTrust.Platform platform, ToolDetection detection) {
            if (!supports(platform)) {
                return status(Availability.UNSUPPORTED_PLATFORM, "Java language support is unsupported on " + platformName(platform),
                    "Configure a user-managed Java language server for this platform.");
            }
            if (detection == null || !detection.executableFound()) {
                return status(Availability.EXECUTABLE_MISSING, "Eclipse JDT LS executable was not found",
                    "Install Eclipse JDT LS and set lsp.java.command, or review the managed install option.");
            }
            Integer major = javaMajor(detection.runtimeVersion());
            if (major == null) {
                return status(Availability.RUNTIME_VERSION_UNKNOWN, "Java runtime version could not be validated",
                    "Use Java " + installMetadata.minimumRuntimeMajor() + "+ for Eclipse JDT LS, then restart the LSP client.");
            }
            if (major < installMetadata.minimumRuntimeMajor()) {
                return status(Availability.RUNTIME_VERSION_UNSUPPORTED,
                    "Eclipse JDT LS requires Java " + installMetadata.minimumRuntimeMajor() + "+ (detected Java " + major + ")",
                    "Install or select Java " + installMetadata.minimumRuntimeMajor() + "+, then restart the LSP client.");
            }
            return status(Availability.AVAILABLE, "Eclipse JDT LS is available with Java " + major,
                "Use :lsp restart java after changing its command or runtime.");
        }

        Status assessManagedInstall(ManagedLanguageSupportTrust trust, ManagedLanguageSupportTrust.Platform platform,
            boolean explicitConsent) {
            if (trust == null) {
                return status(Availability.MANAGED_ARTIFACT_UNAVAILABLE, "managed language trust policy is unavailable",
                    "Install Eclipse JDT LS manually and set lsp.java.command.");
            }
            ManagedLanguageSupportTrust.Assessment assessment = trust.assess(
                ManagedLanguageSupportTrust.Ownership.SHED_MANAGED,
                installMetadata.coordinate(),
                platform,
                explicitConsent
            );
            if (assessment.decision() == ManagedLanguageSupportTrust.Decision.CONSENT_REQUIRED) {
                return new Status(Availability.MANAGED_CONSENT_REQUIRED, assessment.reason(),
                    "Review the Eclipse Public License 2.0 and explicitly approve this managed install.", assessment);
            }
            if (assessment.decision() == ManagedLanguageSupportTrust.Decision.REJECTED) {
                return new Status(Availability.MANAGED_ARTIFACT_UNAVAILABLE, assessment.reason(),
                    "Install Eclipse JDT LS manually and set lsp.java.command.", assessment);
            }
            return new Status(Availability.MANAGED_INSTALL_READY,
                "Eclipse JDT LS install is approved for Java " + installMetadata.minimumRuntimeMajor() + "+",
                "The installer may now fetch the cataloged artifact; no installer is available yet.", assessment);
        }

        private boolean supports(ManagedLanguageSupportTrust.Platform platform) {
            return platform != null && installMetadata.supportedPlatforms().contains(platform);
        }

        private Status status(Availability availability, String detail, String remediation) {
            return new Status(availability, detail, remediation, null);
        }
    }

    private static final Pattern JAVA_VERSION = Pattern.compile("(?:^|[^0-9])(?:1\\.)?(\\d{1,3})(?:[._+\\-]|$)");
    private static final Set<ManagedLanguageSupportTrust.Platform> DESKTOP_PLATFORMS = Set.of(
        ManagedLanguageSupportTrust.Platform.MACOS,
        ManagedLanguageSupportTrust.Platform.WINDOWS,
        ManagedLanguageSupportTrust.Platform.LINUX
    );
    private static final Entry JAVA = new Entry(
        "java",
        Set.of("java"),
        "Eclipse JDT LS",
        "jdtls",
        "jdtls.bat",
        new InstallMetadata(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("java.eclipse-jdtls", "1.50.0"),
            URI.create("https://github.com/eclipse-jdtls/eclipse.jdt.ls"),
            URI.create("https://www.eclipse.org/legal/epl-2.0/"),
            "Eclipse Public License 2.0",
            21,
            DESKTOP_PLATFORMS
        )
    );
    private static final List<Entry> CORE = List.of(JAVA);

    private ManagedLanguageCatalog() {
    }

    static List<Entry> entries() {
        return CORE;
    }

    static Entry java() {
        return JAVA;
    }

    static Entry forExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String normalized = extension.startsWith(".") ? extension.substring(1) : extension;
        for (Entry entry : CORE) {
            if (entry.extensions().contains(normalized.toLowerCase())) {
                return entry;
            }
        }
        return null;
    }

    static Integer javaMajor(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        Matcher matcher = JAVA_VERSION.matcher(version.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String platformName(ManagedLanguageSupportTrust.Platform platform) {
        return platform == null ? "an unknown platform" : platform.name().toLowerCase();
    }
}
