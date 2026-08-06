package shed;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ManagedLanguageSupportService {
    private final LanguageServerDetector detector;
    private final ManagedLanguageSupportTrust trust;
    private final ManagedLanguageArtifactStore artifactStore;
    private final Path shedDirectory;
    private final ManagedLanguageSupportTrust.Platform platform;
    private final ManagedLanguageInstaller.ArtifactFetcher artifactFetcher;
    private final Map<String, LanguageServerDetector.Result> detections = new ConcurrentHashMap<>();

    ManagedLanguageSupportService(LanguageServerDetector detector, ManagedLanguageSupportTrust trust, Path shedDirectory,
        ManagedLanguageSupportTrust.Platform platform) {
        this(detector, trust, shedDirectory, platform, null);
    }

    ManagedLanguageSupportService(LanguageServerDetector detector, ManagedLanguageSupportTrust trust, Path shedDirectory,
        ManagedLanguageSupportTrust.Platform platform, ManagedLanguageInstaller.ArtifactFetcher artifactFetcher) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.shedDirectory = Objects.requireNonNull(shedDirectory, "Shed directory");
        this.artifactStore = new ManagedLanguageArtifactStore(trust, this.shedDirectory);
        this.platform = platform;
        this.artifactFetcher = artifactFetcher;
    }

    ManagedLanguageCatalog.Entry entryFor(String extensionOrLanguage) {
        if (extensionOrLanguage == null || extensionOrLanguage.isBlank()) return null;
        String normalized = extensionOrLanguage.trim().replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
        ManagedLanguageCatalog.Entry byExtension = ManagedLanguageCatalog.forExtension(normalized);
        if (byExtension != null) return byExtension;
        return ManagedLanguageCatalog.entries().stream().filter(entry -> entry.languageId().equals(normalized)).findFirst().orElse(null);
    }

    LanguageServerDetector.Result detect(ManagedLanguageCatalog.Entry entry) {
        if (entry == null) return new LanguageServerDetector.Result(null, null, "", "", "", "language server is not in the catalog");
        LanguageServerDetector.Result result = detector.detect(entry, platform);
        detections.put(entry.languageId(), result);
        return result;
    }

    ManagedLanguageArtifactStore.Result remove(ManagedLanguageCatalog.Entry entry) {
        return entry == null ? new ManagedLanguageArtifactStore.Result(ManagedLanguageArtifactStore.Outcome.REJECTED,
            "language server is not in the catalog", null) : artifactStore.remove(entry.installMetadata().coordinate().toolId());
    }

    ManagedLanguageDistributionCatalog.Distribution distributionFor(ManagedLanguageCatalog.Entry entry) {
        ManagedLanguageDistributionCatalog.Distribution distribution = ManagedLanguageDistributionCatalog.forEntry(entry);
        if (distribution == null || distribution.launchPath(platform) == null) return null;
        if (distribution.usesPinnedArchive()) {
            return distribution.artifact().coordinate().equals(entry.installMetadata().coordinate()) ? distribution : null;
        }
        return distribution.usesNpm() ? distribution : null;
    }

    InstallResult install(ManagedLanguageCatalog.Entry entry, ManagedLanguageInstallApproval approval, AsyncJobService.JobToken token) {
        if (approval == null || !approval.consumeFor(entry)) {
            return new InstallResult(false, "A fresh approval in the Language Services panel is required before installing.", null, List.of());
        }
        ManagedLanguageDistributionCatalog.Distribution distribution = distributionFor(entry);
        if (distribution == null) return new InstallResult(false, "No verified managed download is available for "
            + (entry == null ? "this language" : entry.displayName()), null, List.of());
        if (distribution.usesNpm()) {
            NpmManagedLanguageInstaller.Result installed = new NpmManagedLanguageInstaller(trust, shedDirectory).install(distribution, entry,
                platform,
                new NpmManagedLanguageInstaller.Cancellation() {
                    @Override public boolean isCancelled() { return token != null && token.isCancelled(); }
                    @Override public void onCancel(Runnable action) { if (token != null) token.onCancel(action); }
                });
            return new InstallResult(installed.installed(), installed.detail(), installed.command(), distribution.launchArguments());
        }
        ManagedLanguageInstaller installer = new ManagedLanguageInstaller(trust,
            artifactFetcher == null ? artifact -> openOfficialArtifact(artifact, token) : artifactFetcher,
            shedDirectory);
        ManagedLanguageInstaller.Review review = new ManagedLanguageInstaller.Review(distribution.artifact(), entry.installMetadata(),
            null, distribution.archiveFileName());
        ManagedLanguageInstaller.Result downloaded = installer.install(review, platform, true, () -> token != null && token.isCancelled());
        if (!downloaded.installed()) return new InstallResult(false, downloaded.detail(), null, List.of());
        try {
            Path command = replaceRuntime(distribution, downloaded.installedPath(), platform, token);
            command.toFile().setExecutable(true, true);
            return new InstallResult(true, "Installed and verified " + entry.displayName(), command, distribution.launchArguments());
        } catch (IOException error) {
            return new InstallResult(false, error.getMessage() == null ? "managed archive extraction failed" : error.getMessage(), null, List.of());
        }
    }

    record InstallResult(boolean installed, String detail, Path command, List<String> arguments) {
        InstallResult {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }
    }

    String overview() {
        StringBuilder text = new StringBuilder("Managed LSP Support\n");
        text.append("=".repeat(40)).append("\n\n");
        text.append("This view performs no detection, download, update, or network request.\n");
        text.append("Run :lsp manage detect <ext> for an explicit local-only version probe.\n\n");
        for (ManagedLanguageCatalog.Entry entry : ManagedLanguageCatalog.entries()) appendEntry(text, entry);
        text.append("Actions\n");
        text.append("  :lsp manage detect <ext>  explicit local probe; runs in a background job\n");
        text.append("  :lsp manage retry <ext>   repeat an explicit local probe\n");
        text.append("  :lsp manage              open the managed language-services panel\n");
        text.append("  :lsp manage install <ext> open an explicit managed-install review\n");
        text.append("  :lsp manage update <ext>  open an explicit managed-update review\n");
        text.append("  :lsp manage remove <ext>  remove only Shed-managed cache content\n");
        text.append("  :lsp manage manual <ext> show user-managed config.toml settings\n");
        return text.toString();
    }

    String detectionReport(LanguageServerDetector.Result result) {
        if (result == null || result.entry() == null || result.status() == null) return "LSP detection failed: language server is not in the catalog";
        StringBuilder text = new StringBuilder();
        text.append(result.entry().displayName()).append(" local detection\n\n");
        text.append("Status: ").append(result.status().availability()).append("\n");
        text.append("Detail: ").append(result.status().detail()).append("\n");
        text.append("Executable: ").append(result.executable()).append("\n");
        if (!result.serverVersion().isBlank()) text.append("Server version: ").append(result.serverVersion()).append("\n");
        if (!result.runtimeVersion().isBlank()) text.append("Runtime version: ").append(result.runtimeVersion()).append("\n");
        if (!result.failure().isBlank()) text.append("Probe result: ").append(result.failure()).append("\n");
        text.append("Next step: ").append(result.status().remediation()).append("\n");
        return text.toString();
    }

    String managedAvailability(ManagedLanguageCatalog.Entry entry, String operation) {
        if (entry == null) return "LSP " + operation + " failed: language server is not in the catalog";
        ManagedLanguageCatalog.Status status = entry.assessManagedInstall(trust, platform, false);
        StringBuilder text = new StringBuilder(entry.displayName()).append(" managed ").append(operation).append("\n\n");
        text.append("Status: ").append(status.availability()).append("\n");
        text.append("Detail: ").append(status.detail()).append("\n");
        text.append("No download or update was started.\n\n");
        text.append(manualInstructions(entry));
        return text.toString();
    }

    String manualInstructions(ManagedLanguageCatalog.Entry entry) {
        if (entry == null) return "Manual configuration unavailable: language server is not in the catalog";
        String extension = primaryExtension(entry);
        String[] command = new LspService().builtinCommand(extension);
        String executable = command == null ? entry.commandFor(platform) : command[0];
        String args = command == null || command.length < 2 ? "" : String.join(" ", java.util.Arrays.copyOfRange(command, 1, command.length));
        StringBuilder text = new StringBuilder(entry.displayName()).append(" manual-tool alternative\n\n");
        text.append("Install/select the user-managed executable: ").append(executable).append("\n");
        text.append("Add to ~/.shed/config.toml:\n");
        text.append("  \"lsp.").append(extension).append(".command\" = \"").append(executable).append("\"\n");
        if (!args.isBlank()) text.append("  \"lsp.").append(extension).append(".args\" = \"").append(args).append("\"\n");
        text.append("Then run: :lsp restart ").append(extension).append("\n");
        return text.toString();
    }

    static ManagedLanguageSupportTrust.Platform platformFor(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) return ManagedLanguageSupportTrust.Platform.MACOS;
        if (normalized.contains("win")) return ManagedLanguageSupportTrust.Platform.WINDOWS;
        if (normalized.contains("nux") || normalized.contains("nix") || normalized.contains("aix") || normalized.contains("bsd")) {
            return ManagedLanguageSupportTrust.Platform.LINUX;
        }
        return null;
    }

    private void appendEntry(StringBuilder text, ManagedLanguageCatalog.Entry entry) {
        text.append(entry.displayName()).append(" (").append(String.join(", ", extensions(entry))).append(")\n");
        LanguageServerDetector.Result detection = detections.get(entry.languageId());
        if (detection == null) {
            text.append("  Local status: not checked\n");
        } else if (detection.status() == null) {
            text.append("  Local status: ").append(detection.failure()).append("\n");
        } else {
            text.append("  Local status: ").append(detection.status().availability()).append(" — ").append(detection.status().detail()).append("\n");
            text.append("  Next step: ").append(detection.status().remediation()).append("\n");
        }
        ManagedLanguageDistributionCatalog.Distribution distribution = distributionFor(entry);
        if (distribution == null) {
            ManagedLanguageCatalog.Status managed = entry.assessManagedInstall(trust, platform, false);
            text.append("  Managed status: ").append(managed.availability()).append(" — ").append(managed.detail()).append("\n");
            text.append("  Managed download: unavailable; use the manual setup\n");
        } else if (distribution.usesPinnedArchive()) {
            ManagedLanguageCatalog.Status managed = entry.assessManagedInstall(trust, platform, false);
            text.append("  Managed status: ").append(managed.availability()).append(" — ").append(managed.detail()).append("\n");
            text.append("  Managed download: available after explicit review\n");
        } else {
            text.append("  Managed status: GUI approval required — exact npm packages install only after review\n");
            text.append("  Managed download: available after explicit review; npm scripts are disabled\n");
        }
        text.append("  Manual: :lsp manage manual ").append(primaryExtension(entry)).append("\n\n");
    }

    private static InputStream openOfficialArtifact(ManagedLanguageSupportTrust.CatalogArtifact artifact,
                                                     AsyncJobService.JobToken token) throws IOException {
        URLConnection connection = artifact.source().toURL().openConnection();
        if (!(connection instanceof HttpURLConnection http)) throw new IOException("managed download must use HTTPS");
        http.setInstanceFollowRedirects(false);
        http.setConnectTimeout(10_000);
        http.setReadTimeout(30_000);
        http.connect();
        if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
            http.disconnect();
            throw new IOException("managed download failed with HTTP " + http.getResponseCode());
        }
        if (token != null) token.onCancel(http::disconnect);
        return http.getInputStream();
    }

    private static Path replaceRuntime(ManagedLanguageDistributionCatalog.Distribution distribution, Path archive,
                                       ManagedLanguageSupportTrust.Platform platform, AsyncJobService.JobToken token) throws IOException {
        Path versionDirectory = archive.getParent().toAbsolutePath().normalize();
        Path runtime = versionDirectory.resolve("runtime").normalize();
        if (!runtime.startsWith(versionDirectory)) throw new IOException("managed runtime escapes managed cache");
        Path staged = Files.createTempDirectory(versionDirectory, ".shed-runtime-");
        Path backup = null;
        boolean replaced = false;
        try {
            TarGzExtractor.extract(archive, staged, () -> token != null && token.isCancelled());
            String launchPath = distribution.launchPath(platform);
            if (launchPath == null || launchPath.isBlank()) throw new IOException("managed distribution has no launcher for this platform");
            Path stagedCommand = staged.resolve(launchPath).normalize();
            if (!stagedCommand.startsWith(staged) || !Files.isRegularFile(stagedCommand)) {
                throw new IOException("managed archive did not contain its language-server launcher");
            }
            if (Files.exists(runtime)) {
                backup = versionDirectory.resolve(".shed-runtime-backup-" + UUID.randomUUID()).normalize();
                move(runtime, backup);
            }
            move(staged, runtime);
            replaced = true;
            return runtime.resolve(launchPath).normalize();
        } catch (IOException error) {
            if (backup != null && Files.exists(backup) && !Files.exists(runtime)) move(backup, runtime);
            throw error;
        } finally {
            if (Files.exists(staged)) deleteManagedTree(staged);
            if (replaced && backup != null && Files.exists(backup)) deleteManagedTree(backup);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        if (source.equals(target)) return;
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target);
        }
    }

    private static void deleteManagedTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<String> extensions(ManagedLanguageCatalog.Entry entry) {
        return entry.extensions().stream().sorted(Comparator.naturalOrder()).map(extension -> "." + extension).toList();
    }

    private static String primaryExtension(ManagedLanguageCatalog.Entry entry) {
        return entry.extensions().stream().sorted().findFirst().orElse(entry.languageId());
    }
}
