package shed;

import java.net.URI;
import java.util.List;
import java.util.Locale;
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
        String runtimeName,
        int minimumRuntimeMajor,
        Set<ManagedLanguageSupportTrust.Platform> supportedPlatforms
    ) {
        InstallMetadata {
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            projectUrl = Objects.requireNonNull(projectUrl, "project url");
            licenseUrl = Objects.requireNonNull(licenseUrl, "license url");
            licenseName = Objects.requireNonNull(licenseName, "license name");
            runtimeName = requireText(runtimeName, "runtime name");
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
            if (extensions.isEmpty()) {
                throw new IllegalArgumentException("at least one extension is required");
            }
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
                return status(Availability.UNSUPPORTED_PLATFORM, displayName + " is unsupported on " + platformName(platform),
                    "Configure a user-managed " + languageId + " language server for this platform.");
            }
            if (detection == null || !detection.executableFound()) {
                return status(Availability.EXECUTABLE_MISSING, displayName + " executable was not found",
                    "Install " + displayName + " and set lsp." + defaultExtension() + ".command, or review the managed install option.");
            }
            Integer major = runtimeMajor(detection.runtimeVersion());
            if (major == null) {
                return status(Availability.RUNTIME_VERSION_UNKNOWN, installMetadata.runtimeName() + " runtime version could not be validated",
                    "Use " + runtimeRequirement() + " for " + displayName + ", then restart the LSP client.");
            }
            if (major < installMetadata.minimumRuntimeMajor()) {
                return status(Availability.RUNTIME_VERSION_UNSUPPORTED,
                    displayName + " requires " + runtimeRequirement() + " (detected " + installMetadata.runtimeName() + " " + major + ")",
                    "Install or select " + runtimeRequirement() + ", then restart the LSP client.");
            }
            return status(Availability.AVAILABLE, displayName + " is available with " + installMetadata.runtimeName() + " " + major,
                "Use :lsp restart " + defaultExtension() + " after changing its command or runtime.");
        }

        Status assessManagedInstall(ManagedLanguageSupportTrust trust, ManagedLanguageSupportTrust.Platform platform,
            boolean explicitConsent) {
            if (trust == null) {
                return status(Availability.MANAGED_ARTIFACT_UNAVAILABLE, "managed language trust policy is unavailable",
                    "Install " + displayName + " manually and set lsp." + defaultExtension() + ".command.");
            }
            ManagedLanguageSupportTrust.Assessment assessment = trust.assess(
                ManagedLanguageSupportTrust.Ownership.SHED_MANAGED,
                installMetadata.coordinate(),
                platform,
                explicitConsent
            );
            if (assessment.decision() == ManagedLanguageSupportTrust.Decision.CONSENT_REQUIRED) {
                return new Status(Availability.MANAGED_CONSENT_REQUIRED, assessment.reason(),
                    "Review the " + installMetadata.licenseName() + " and explicitly approve this managed install.", assessment);
            }
            if (assessment.decision() == ManagedLanguageSupportTrust.Decision.REJECTED) {
                return new Status(Availability.MANAGED_ARTIFACT_UNAVAILABLE, assessment.reason(),
                    "Install " + displayName + " manually and set lsp." + defaultExtension() + ".command.", assessment);
            }
            return new Status(Availability.MANAGED_INSTALL_READY,
                displayName + " install is approved for " + runtimeRequirement(),
                "The installer may now fetch the cataloged artifact; no installer is available yet.", assessment);
        }

        private boolean supports(ManagedLanguageSupportTrust.Platform platform) {
            return platform != null && installMetadata.supportedPlatforms().contains(platform);
        }

        private String defaultExtension() {
            return extensions.iterator().next();
        }

        private String runtimeRequirement() {
            return installMetadata.runtimeName() + " " + installMetadata.minimumRuntimeMajor() + "+";
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
            "Java",
            21,
            DESKTOP_PLATFORMS
        )
    );
    private static final Entry PYTHON = new Entry(
        "python",
        Set.of("py"),
        "Pyright",
        "pyright-langserver",
        "pyright-langserver.cmd",
        new InstallMetadata(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("python.pyright", "1.1.411"),
            URI.create("https://github.com/microsoft/pyright"),
            URI.create("https://github.com/microsoft/pyright/blob/main/LICENSE.txt"),
            "MIT License",
            "Node.js",
            14,
            DESKTOP_PLATFORMS
        )
    );
    private static final List<Entry> CORE = List.of(JAVA, PYTHON);

    private ManagedLanguageCatalog() {
    }

    static List<Entry> entries() {
        return CORE;
    }

    static Entry java() {
        return JAVA;
    }

    static Entry python() {
        return PYTHON;
    }

    static Entry forExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String normalized = extension.startsWith(".") ? extension.substring(1) : extension;
        for (Entry entry : CORE) {
            if (entry.extensions().contains(normalized.toLowerCase(Locale.ROOT))) {
                return entry;
            }
        }
        return null;
    }

    static Integer runtimeMajor(String version) {
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

    static Integer javaMajor(String version) {
        return runtimeMajor(version);
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
