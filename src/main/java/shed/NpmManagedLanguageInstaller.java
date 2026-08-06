package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Installs exact npm packages only after the caller supplies a GUI approval capability. */
final class NpmManagedLanguageInstaller {
    interface Cancellation {
        boolean isCancelled();
        void onCancel(Runnable action);
    }

    interface CommandRunner {
        CommandResult run(List<String> command, Path directory, Map<String, String> environment,
            Cancellation cancellation) throws IOException;
    }

    record CommandResult(int exitCode, String output, boolean cancelled) {
    }

    record Result(boolean installed, String detail, Path command) {
    }

    private static final long INSTALL_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final ManagedLanguageSupportTrust trust;
    private final Path shedDirectory;
    private final CommandRunner runner;

    NpmManagedLanguageInstaller(ManagedLanguageSupportTrust trust, Path shedDirectory) {
        this(trust, shedDirectory, NpmManagedLanguageInstaller::runNpm);
    }

    NpmManagedLanguageInstaller(ManagedLanguageSupportTrust trust, Path shedDirectory, CommandRunner runner) {
        this.trust = Objects.requireNonNull(trust, "trust");
        this.shedDirectory = Objects.requireNonNull(shedDirectory, "Shed directory");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    Result install(ManagedLanguageDistributionCatalog.Distribution distribution, ManagedLanguageCatalog.Entry entry,
        ManagedLanguageSupportTrust.Platform platform, Cancellation cancellation) {
        if (distribution == null || entry == null || !distribution.usesNpm()) {
            return new Result(false, "npm language-service install is unavailable", null);
        }
        if (cancelled(cancellation)) return new Result(false, "install cancelled before npm started", null);
        Path versionDirectory = trust.cacheDirectory(shedDirectory, entry.installMetadata().coordinate());
        Path runtime = versionDirectory.resolve("runtime").normalize();
        if (!runtime.startsWith(versionDirectory)) return new Result(false, "managed npm runtime escapes its cache", null);
        Path staged = null;
        Path backup = null;
        boolean replaced = false;
        try {
            Files.createDirectories(versionDirectory);
            staged = Files.createTempDirectory(versionDirectory, ".shed-npm-");
            writePackageManifest(staged, distribution.npmPackages());
            List<String> command = npmCommand(distribution.npmPackages());
            Map<String, String> environment = Map.of(
                "NPM_CONFIG_AUDIT", "false",
                "NPM_CONFIG_FUND", "false",
                "NPM_CONFIG_UPDATE_NOTIFIER", "false",
                "NPM_CONFIG_IGNORE_SCRIPTS", "true",
                "NPM_CONFIG_CACHE", staged.resolve(".npm-cache").toString()
            );
            CommandResult npm = runner.run(command, staged, environment, cancellation);
            if (npm.cancelled() || cancelled(cancellation)) return new Result(false, "install cancelled", null);
            if (npm.exitCode() != 0) return new Result(false, "npm install failed" + outputDetail(npm.output()), null);
            Path stagedCommand = launchPath(staged, distribution, platform);
            if (!Files.isRegularFile(stagedCommand)) {
                return new Result(false, "npm did not install the expected language-server launcher", null);
            }
            if (Files.exists(runtime)) {
                backup = versionDirectory.resolve(".shed-npm-backup-" + UUID.randomUUID()).normalize();
                move(runtime, backup);
            }
            move(staged, runtime);
            replaced = true;
            Path installedCommand = launchPath(runtime, distribution, platform);
            if (!Files.isRegularFile(installedCommand)) return new Result(false, "installed npm launcher is unavailable", null);
            return new Result(true, "Installed " + entry.displayName() + " with npm (scripts disabled)", installedCommand);
        } catch (IOException error) {
            return new Result(false, error.getMessage() == null ? "npm install failed" : error.getMessage(), null);
        } finally {
            try {
                if (staged != null && Files.exists(staged)) deleteTree(staged);
                if (replaced && backup != null && Files.exists(backup)) deleteTree(backup);
                if (!replaced && backup != null && Files.exists(backup) && !Files.exists(runtime)) move(backup, runtime);
            } catch (IOException ignored) {
                // Keep a verified prior runtime if cleanup cannot finish.
            }
        }
    }

    private static List<String> npmCommand(List<String> packages) {
        List<String> command = new ArrayList<>(List.of("npm", "install", "--omit=dev", "--ignore-scripts",
            "--no-audit", "--no-fund", "--save-exact"));
        command.addAll(packages);
        return List.copyOf(command);
    }

    private static void writePackageManifest(Path directory, List<String> packages) throws IOException {
        StringBuilder dependencies = new StringBuilder();
        for (int index = 0; index < packages.size(); index++) {
            String spec = packages.get(index);
            int at = spec.lastIndexOf('@');
            if (at <= 0 || at == spec.length() - 1) throw new IOException("npm package spec must be name@exact-version");
            if (index > 0) dependencies.append(',');
            dependencies.append('\n').append("    ").append(MiniJson.stringify(spec.substring(0, at))).append(": ")
                .append(MiniJson.stringify(spec.substring(at + 1)));
        }
        String manifest = "{\n  \"private\": true,\n  \"dependencies\": {" + dependencies + "\n  }\n}\n";
        Files.writeString(directory.resolve("package.json"), manifest, StandardCharsets.UTF_8);
    }

    private static Path launchPath(Path runtime, ManagedLanguageDistributionCatalog.Distribution distribution,
        ManagedLanguageSupportTrust.Platform platform) throws IOException {
        String launcher = distribution.launchPath(platform);
        if (launcher == null || launcher.isBlank()) throw new IOException("npm distribution has no launcher for this platform");
        Path path = runtime.resolve(launcher).normalize();
        if (!path.startsWith(runtime)) throw new IOException("npm launcher escapes managed cache");
        return path;
    }

    private static CommandResult runNpm(List<String> command, Path directory, Map<String, String> environment,
        Cancellation cancellation) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (cancellation != null) cancellation.onCancel(process::destroyForcibly);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INSTALL_TIMEOUT_SECONDS);
        try (InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                if (cancelled(cancellation)) {
                    process.destroyForcibly();
                    return new CommandResult(-1, output.toString(StandardCharsets.UTF_8), true);
                }
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    return new CommandResult(-1, output.toString(StandardCharsets.UTF_8), false);
                }
                while (stream.available() > 0) {
                    int read = stream.read(buffer, 0, Math.min(buffer.length, MAX_OUTPUT_BYTES - output.size()));
                    if (read < 0) break;
                    if (read > 0) output.write(buffer, 0, read);
                    if (output.size() >= MAX_OUTPUT_BYTES) break;
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    while (output.size() < MAX_OUTPUT_BYTES) {
                        int read = stream.read(buffer, 0, Math.min(buffer.length, MAX_OUTPUT_BYTES - output.size()));
                        if (read < 0) break;
                        output.write(buffer, 0, read);
                    }
                    return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8), false);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(-1, output.toString(StandardCharsets.UTF_8), true);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean cancelled(Cancellation cancellation) {
        return cancellation != null && cancellation.isCancelled();
    }

    private static String outputDetail(String output) {
        String text = output == null ? "" : output.trim();
        return text.isBlank() ? "" : ": " + text.substring(0, Math.min(500, text.length()));
    }
}
