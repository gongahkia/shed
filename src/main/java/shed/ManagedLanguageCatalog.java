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

    enum RuntimeVersionScheme {
        STANDARD,
        JAVA_LEGACY
    }

    enum RuntimeRequirementKind {
        NONE,
        MINIMUM_VERSION,
        LATEST_STABLE
    }

    record ToolDetection(String executable, String runtimeVersion, Boolean runtimeSupported) {
        ToolDetection(String executable, String runtimeVersion) {
            this(executable, runtimeVersion, null);
        }

        boolean executableFound() {
            return executable != null && !executable.isBlank();
        }
    }

    record RuntimeVersion(int major, int minor, int patch) implements Comparable<RuntimeVersion> {
        RuntimeVersion {
            if (major < 0 || minor < 0 || patch < 0) {
                throw new IllegalArgumentException("runtime version parts must be non-negative");
            }
        }

        @Override
        public int compareTo(RuntimeVersion other) {
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }
            int minorComparison = Integer.compare(minor, other.minor);
            return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    record InstallMetadata(
        ManagedLanguageSupportTrust.ArtifactCoordinate coordinate,
        URI projectUrl,
        URI licenseUrl,
        String licenseName,
        String runtimeName,
        String minimumRuntimeVersion,
        RuntimeRequirementKind runtimeRequirementKind,
        RuntimeVersionScheme runtimeVersionScheme,
        Set<ManagedLanguageSupportTrust.Platform> supportedPlatforms
    ) {
        InstallMetadata {
            coordinate = Objects.requireNonNull(coordinate, "coordinate");
            projectUrl = Objects.requireNonNull(projectUrl, "project url");
            licenseUrl = Objects.requireNonNull(licenseUrl, "license url");
            licenseName = Objects.requireNonNull(licenseName, "license name");
            runtimeName = requireText(runtimeName, "runtime name");
            minimumRuntimeVersion = requireText(minimumRuntimeVersion, "minimum runtime version");
            runtimeRequirementKind = Objects.requireNonNull(runtimeRequirementKind, "runtime requirement kind");
            runtimeVersionScheme = Objects.requireNonNull(runtimeVersionScheme, "runtime version scheme");
            if (runtimeRequirementKind == RuntimeRequirementKind.MINIMUM_VERSION
                && parseRuntimeVersion(minimumRuntimeVersion, runtimeVersionScheme) == null) {
                throw new IllegalArgumentException("minimum runtime version is invalid");
            }
            supportedPlatforms = supportedPlatforms == null ? Set.of() : Set.copyOf(supportedPlatforms);
        }

        RuntimeVersion minimumRuntime() {
            return runtimeRequirementKind == RuntimeRequirementKind.MINIMUM_VERSION
                ? parseRuntimeVersion(minimumRuntimeVersion, runtimeVersionScheme) : null;
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
            if (installMetadata.runtimeRequirementKind() == RuntimeRequirementKind.NONE) {
                return status(Availability.AVAILABLE, displayName + " executable is available",
                    "Use :lsp restart " + defaultExtension() + " after changing its command.");
            }
            if (installMetadata.runtimeRequirementKind() == RuntimeRequirementKind.LATEST_STABLE) {
                return assessLatestStableRuntime(detection);
            }
            RuntimeVersion version = parseRuntimeVersion(detection.runtimeVersion(), installMetadata.runtimeVersionScheme());
            if (version == null) {
                return status(Availability.RUNTIME_VERSION_UNKNOWN, installMetadata.runtimeName() + " runtime version could not be validated",
                    "Use " + runtimeRequirement() + " for " + displayName + ", then restart the LSP client.");
            }
            if (version.compareTo(installMetadata.minimumRuntime()) < 0) {
                return status(Availability.RUNTIME_VERSION_UNSUPPORTED,
                    displayName + " requires " + runtimeRequirement() + " (detected " + installMetadata.runtimeName() + " " + version + ")",
                    "Install or select " + runtimeRequirement() + ", then restart the LSP client.");
            }
            return status(Availability.AVAILABLE, displayName + " is available with " + installMetadata.runtimeName() + " " + version,
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
            if (installMetadata.runtimeRequirementKind() == RuntimeRequirementKind.NONE) return "no versioned runtime";
            return installMetadata.runtimeRequirementKind() == RuntimeRequirementKind.LATEST_STABLE
                ? installMetadata.runtimeName() + " latest stable" : installMetadata.runtimeName() + " " + installMetadata.minimumRuntimeVersion() + "+";
        }

        private Status assessLatestStableRuntime(ToolDetection detection) {
            if (detection.runtimeSupported() == null) {
                return status(Availability.RUNTIME_VERSION_UNKNOWN,
                    installMetadata.runtimeName() + " stable-toolchain compatibility could not be validated",
                    "Use the latest stable " + installMetadata.runtimeName() + " toolchain with required components, then restart the LSP client.");
            }
            if (!detection.runtimeSupported()) {
                return status(Availability.RUNTIME_VERSION_UNSUPPORTED,
                    displayName + " requires the " + runtimeRequirement(),
                    "Update to the latest stable " + installMetadata.runtimeName() + " toolchain and install required components, then restart the LSP client.");
            }
            String version = detection.runtimeVersion() == null || detection.runtimeVersion().isBlank()
                ? installMetadata.runtimeName() + " stable" : detection.runtimeVersion().trim();
            return status(Availability.AVAILABLE, displayName + " is available with " + version,
                "Use :lsp restart " + defaultExtension() + " after changing its command or runtime.");
        }

        private Status status(Availability availability, String detail, String remediation) {
            return new Status(availability, detail, remediation, null);
        }
    }

    private static final Pattern STANDARD_RUNTIME_VERSION = Pattern.compile(
        "(?:^|[^0-9])(\\d{1,3})(?:[._](\\d{1,3}))?(?:[._](\\d{1,3}))?(?=[^0-9]|$)"
    );
    private static final Pattern JAVA_LEGACY_RUNTIME_VERSION = Pattern.compile(
        "(?:^|[^0-9])(?:1\\.)?(\\d{1,3})(?:[._](\\d{1,3}))?(?:[._](\\d{1,3}))?(?=[^0-9]|$)"
    );
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
            new ManagedLanguageSupportTrust.ArtifactCoordinate("java.eclipse-jdtls", "1.60.0"),
            URI.create("https://github.com/eclipse-jdtls/eclipse.jdt.ls"),
            URI.create("https://www.eclipse.org/legal/epl-2.0/"),
            "Eclipse Public License 2.0",
            "Java",
            "21",
            RuntimeRequirementKind.MINIMUM_VERSION,
            RuntimeVersionScheme.JAVA_LEGACY,
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
            "14",
            RuntimeRequirementKind.MINIMUM_VERSION,
            RuntimeVersionScheme.STANDARD,
            DESKTOP_PLATFORMS
        )
    );
    private static final Entry TYPESCRIPT_JAVASCRIPT = new Entry(
        "typescript-javascript",
        Set.of("js", "jsx", "ts", "tsx"),
        "TypeScript Language Server",
        "typescript-language-server",
        "typescript-language-server.cmd",
        new InstallMetadata(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("typescript.typescript-language-server", "5.3.0"),
            URI.create("https://github.com/typescript-language-server/typescript-language-server"),
            URI.create("https://github.com/typescript-language-server/typescript-language-server/blob/master/LICENSE"),
            "Apache License 2.0",
            "Node.js",
            "20",
            RuntimeRequirementKind.MINIMUM_VERSION,
            RuntimeVersionScheme.STANDARD,
            DESKTOP_PLATFORMS
        )
    );
    private static final Entry GO = new Entry(
        "go",
        Set.of("go"),
        "gopls",
        "gopls",
        "gopls.exe",
        new InstallMetadata(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("go.gopls", "0.23.0"),
            URI.create("https://go.dev/gopls/"),
            URI.create("https://github.com/golang/tools/blob/master/LICENSE"),
            "BSD 3-Clause License",
            "Go",
            "1.21",
            RuntimeRequirementKind.MINIMUM_VERSION,
            RuntimeVersionScheme.STANDARD,
            DESKTOP_PLATFORMS
        )
    );
    private static final Entry RUST = new Entry(
        "rust",
        Set.of("rs"),
        "rust-analyzer",
        "rust-analyzer",
        "rust-analyzer.exe",
        new InstallMetadata(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("rust.rust-analyzer", "2026-07-27"),
            URI.create("https://rust-analyzer.github.io/book/installation.html"),
            URI.create("https://github.com/rust-lang/rust-analyzer/blob/master/LICENSE-MIT"),
            "MIT OR Apache-2.0",
            "Rust",
            "stable",
            RuntimeRequirementKind.LATEST_STABLE,
            RuntimeVersionScheme.STANDARD,
            DESKTOP_PLATFORMS
        )
    );
    private static final Entry C_CPP = new Entry(
        "c-cpp",
        Set.of("c", "cc", "cpp", "cxx", "h", "hpp", "hxx"),
        "clangd",
        "clangd",
        "clangd.exe",
        new InstallMetadata(
            new ManagedLanguageSupportTrust.ArtifactCoordinate("c-cpp.clangd", "22.1.8"),
            URI.create("https://clangd.llvm.org/installation.html"),
            URI.create("https://github.com/llvm/llvm-project/blob/main/LICENSE.TXT"),
            "Apache License 2.0 with LLVM Exceptions",
            "clangd",
            "7",
            RuntimeRequirementKind.MINIMUM_VERSION,
            RuntimeVersionScheme.STANDARD,
            DESKTOP_PLATFORMS
        )
    );
    private static final Entry JSON = new Entry(
        "json", Set.of("json", "jsonc"), "VS Code JSON Language Server",
        "vscode-json-language-server", "vscode-json-language-server.cmd",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("json.zed-vscode-langservers-extracted", "4.10.8"),
            URI.create("https://www.npmjs.com/package/@zed-industries/vscode-langservers-extracted"),
            URI.create("https://github.com/microsoft/vscode/blob/main/LICENSE.txt"), "MIT License", "Node.js", "none",
            RuntimeRequirementKind.NONE, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry HTML = new Entry(
        "html", Set.of("html", "htm", "xhtml"), "VS Code HTML Language Server",
        "vscode-html-language-server", "vscode-html-language-server.cmd",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("html.zed-vscode-langservers-extracted", "4.10.8"),
            URI.create("https://www.npmjs.com/package/@zed-industries/vscode-langservers-extracted"),
            URI.create("https://github.com/microsoft/vscode/blob/main/LICENSE.txt"), "MIT License", "Node.js", "none",
            RuntimeRequirementKind.NONE, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry CSS = new Entry(
        "css", Set.of("css", "scss", "less"), "VS Code CSS Language Server",
        "vscode-css-language-server", "vscode-css-language-server.cmd",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("css.zed-vscode-langservers-extracted", "4.10.8"),
            URI.create("https://www.npmjs.com/package/@zed-industries/vscode-langservers-extracted"),
            URI.create("https://github.com/microsoft/vscode/blob/main/LICENSE.txt"), "MIT License", "Node.js", "none",
            RuntimeRequirementKind.NONE, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry MARKDOWN = new Entry(
        "markdown", Set.of("md", "markdown"), "remark-language-server",
        "remark-language-server", "remark-language-server.cmd",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("markdown.remark-language-server", "3.0.0"),
            URI.create("https://github.com/remarkjs/remark-language-server"),
            URI.create("https://github.com/remarkjs/remark-language-server/blob/main/license"), "MIT License", "Node.js", "16",
            RuntimeRequirementKind.MINIMUM_VERSION, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry KOTLIN = new Entry(
        "kotlin", Set.of("kt", "kts"), "Kotlin LSP",
        "kotlin-lsp", "kotlin-lsp",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("kotlin.kotlin-lsp", "user-managed"),
            URI.create("https://github.com/Kotlin/kotlin-lsp"), URI.create("https://github.com/Kotlin/kotlin-lsp/blob/main/LICENSE.txt"),
            "Kotlin LSP terms", "Kotlin / Java toolchain", "user-managed", RuntimeRequirementKind.NONE,
            RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry CSHARP = new Entry(
        "csharp", Set.of("cs", "csx"), "csharp-ls",
        "csharp-ls", "csharp-ls",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("csharp.csharp-ls", "user-managed"),
            URI.create("https://github.com/razzmatazz/csharp-language-server"),
            URI.create("https://github.com/razzmatazz/csharp-language-server/blob/master/LICENSE"), "MIT License", "dotnet SDK", "10",
            RuntimeRequirementKind.MINIMUM_VERSION, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry PHP = new Entry(
        "php", Set.of("php", "phtml", "php3", "php4", "php5", "phps"), "Intelephense",
        "intelephense", "intelephense.cmd",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("php.intelephense", "user-managed"),
            URI.create("https://intelephense.com/docs"), URI.create("https://intelephense.com/eula"), "Intelephense EULA", "Node.js", "current LTS",
            RuntimeRequirementKind.NONE, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry RUBY = new Entry(
        "ruby", Set.of("rb", "rake", "gemspec"), "Ruby LSP",
        "ruby-lsp", "ruby-lsp",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("ruby.ruby-lsp", "user-managed"),
            URI.create("https://github.com/Shopify/ruby-lsp"), URI.create("https://github.com/Shopify/ruby-lsp/blob/main/LICENSE.txt"), "MIT License",
            "project Ruby and Bundler environment", "user-managed", RuntimeRequirementKind.NONE, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final Entry SWIFT = new Entry(
        "swift", Set.of("swift"), "SourceKit-LSP",
        "sourcekit-lsp", "sourcekit-lsp",
        new InstallMetadata(new ManagedLanguageSupportTrust.ArtifactCoordinate("swift.sourcekit-lsp", "toolchain"),
            URI.create("https://github.com/swiftlang/sourcekit-lsp"), URI.create("https://github.com/swiftlang/sourcekit-lsp/blob/main/LICENSE.txt"),
            "Apache License 2.0", "Swift toolchain", "user-managed", RuntimeRequirementKind.NONE, RuntimeVersionScheme.STANDARD, DESKTOP_PLATFORMS)
    );
    private static final List<Entry> CORE = List.of(JAVA, PYTHON, TYPESCRIPT_JAVASCRIPT, GO, RUST, C_CPP, JSON, HTML, CSS, MARKDOWN,
        KOTLIN, CSHARP, PHP, RUBY, SWIFT);

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

    static Entry typescriptJavascript() {
        return TYPESCRIPT_JAVASCRIPT;
    }

    static Entry html() {
        return HTML;
    }

    static Entry css() {
        return CSS;
    }

    static Entry go() {
        return GO;
    }

    static Entry rust() {
        return RUST;
    }

    static Entry cCpp() {
        return C_CPP;
    }

    static Entry json() { return JSON; }

    static Entry markdown() { return MARKDOWN; }

    static Entry kotlin() { return KOTLIN; }

    static Entry csharp() { return CSHARP; }

    static Entry php() { return PHP; }

    static Entry ruby() { return RUBY; }

    static Entry swift() { return SWIFT; }

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
        RuntimeVersion parsed = parseRuntimeVersion(version);
        return parsed == null ? null : parsed.major();
    }

    static RuntimeVersion parseRuntimeVersion(String version) {
        return parseRuntimeVersion(version, RuntimeVersionScheme.STANDARD);
    }

    static RuntimeVersion parseRuntimeVersion(String version, RuntimeVersionScheme scheme) {
        if (version == null || version.isBlank()) {
            return null;
        }
        Pattern pattern = scheme == RuntimeVersionScheme.JAVA_LEGACY ? JAVA_LEGACY_RUNTIME_VERSION : STANDARD_RUNTIME_VERSION;
        Matcher matcher = pattern.matcher(version.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return new RuntimeVersion(major, minor, patch);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Integer javaMajor(String version) {
        RuntimeVersion parsed = parseRuntimeVersion(version, RuntimeVersionScheme.JAVA_LEGACY);
        return parsed == null ? null : parsed.major();
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
