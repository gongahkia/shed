package shed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class LanguageServerDetector {
    interface ExecutableResolver {
        Path resolve(String command);
    }

    interface CommandRunner {
        CommandResult run(List<String> command) throws IOException, InterruptedException;
    }

    interface LatestStableValidator {
        Boolean isSupported(ManagedLanguageCatalog.Entry entry, String runtimeVersion);
    }

    record CommandResult(int exitCode, String output, String failure) {
        boolean succeeded() {
            return exitCode == 0 && (failure == null || failure.isBlank());
        }
    }

    record Result(ManagedLanguageCatalog.Entry entry, ManagedLanguageCatalog.Status status, String executable,
        String serverVersion, String runtimeVersion, String failure) {
        boolean usable() {
            return status != null && status.usable();
        }
    }

    private final ExecutableResolver resolver;
    private final CommandRunner runner;
    private final LatestStableValidator latestStableValidator;

    LanguageServerDetector(ExecutableResolver resolver, CommandRunner runner, LatestStableValidator latestStableValidator) {
        this.resolver = resolver == null ? LanguageServerDetector::resolveOnPath : resolver;
        this.runner = runner == null ? LanguageServerDetector::runReadOnly : runner;
        this.latestStableValidator = latestStableValidator == null ? (entry, version) -> null : latestStableValidator;
    }

    Result detect(ManagedLanguageCatalog.Entry entry, ManagedLanguageSupportTrust.Platform platform) {
        if (entry == null) {
            return new Result(null, null, "", "", "", "catalog entry is required");
        }
        String command = entry.commandFor(platform);
        Path executable = resolver.resolve(command);
        if (executable == null) {
            ManagedLanguageCatalog.Status status = entry.assessUserManaged(platform, null);
            return new Result(entry, status, command, "", "", "executable not found: " + command);
        }
        String executablePath = executable.toString();
        CommandResult server = run(List.of(executablePath, "--version"));
        if (!server.succeeded()) {
            ManagedLanguageCatalog.Status status = entry.assessUserManaged(platform,
                new ManagedLanguageCatalog.ToolDetection(executablePath, ""));
            return new Result(entry, status, executablePath, "", "", commandFailure(server));
        }
        String serverVersion = normalized(server.output());
        if (entry.installMetadata().runtimeRequirementKind() == ManagedLanguageCatalog.RuntimeRequirementKind.NONE) {
            ManagedLanguageCatalog.Status status = entry.assessUserManaged(platform,
                new ManagedLanguageCatalog.ToolDetection(executablePath, ""));
            return new Result(entry, status, executablePath, serverVersion, "", "");
        }
        CommandResult runtime = run(runtimeCommand(entry, executablePath));
        if (!runtime.succeeded()) {
            ManagedLanguageCatalog.Status status = entry.assessUserManaged(platform,
                new ManagedLanguageCatalog.ToolDetection(executablePath, ""));
            return new Result(entry, status, executablePath, serverVersion, "", commandFailure(runtime));
        }
        String runtimeVersion = normalized(runtime.output());
        Boolean supported = entry.installMetadata().runtimeRequirementKind() == ManagedLanguageCatalog.RuntimeRequirementKind.LATEST_STABLE
            ? latestStableValidator.isSupported(entry, runtimeVersion) : null;
        ManagedLanguageCatalog.Status status = entry.assessUserManaged(platform,
            new ManagedLanguageCatalog.ToolDetection(executablePath, runtimeVersion, supported));
        return new Result(entry, status, executablePath, serverVersion, runtimeVersion, "");
    }

    private CommandResult run(List<String> command) {
        try {
            return runner.run(command);
        } catch (IOException e) {
            return new CommandResult(-1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "version probe interrupted");
        }
    }

    private static List<String> runtimeCommand(ManagedLanguageCatalog.Entry entry, String executable) {
        String runtime = entry.installMetadata().runtimeName().toLowerCase(Locale.ROOT);
        return switch (runtime) {
            case "java" -> List.of("java", "--version");
            case "node.js" -> List.of("node", "--version");
            case "dotnet sdk" -> List.of("dotnet", "--version");
            case "go" -> List.of("go", "version");
            case "rust" -> List.of("rustc", "--version");
            default -> List.of(executable, "--version");
        };
    }

    private static Path resolveOnPath(String command) {
        if (command == null || command.isBlank()) return null;
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory, command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static CommandResult runReadOnly(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (InputStream stream = process.getInputStream()) {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return new CommandResult(-1, new String(stream.readNBytes(8192), StandardCharsets.UTF_8), "version probe timed out");
            }
            return new CommandResult(process.exitValue(), new String(stream.readNBytes(8192), StandardCharsets.UTF_8), "");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String commandFailure(CommandResult result) {
        if (result.failure() != null && !result.failure().isBlank()) return result.failure();
        return "version probe failed with exit code " + result.exitCode();
    }
}
