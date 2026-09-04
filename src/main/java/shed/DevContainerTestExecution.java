package shed;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Maps an explicit test run into an already-running Dev Container without making the app cache visible there. */
final class DevContainerTestExecution {
    private static final String REPORT_DIRECTORY = ".shed-devcontainer-test-reports";
    private static final String REPORT_PREFIX = "run-";

    record Plan(TestService.Command command, List<String> invocation) {
        Plan {
            invocation = invocation == null ? List.of() : List.copyOf(invocation);
        }
    }

    private DevContainerTestExecution() {
    }

    /** Creates an app-owned, mounted report directory for one explicit test run. */
    static Path createReportCache(Path workspace) throws IOException {
        Path root = workspaceRoot(workspace);
        Path base = reportBase(root);
        if (Files.exists(base, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(base)) {
            throw new IOException("Dev Container report cache path is a symbolic link");
        }
        Files.createDirectories(base);
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Dev Container report cache is not a directory");
        }
        return Files.createTempDirectory(base, REPORT_PREFIX);
    }

    static Plan prepare(Path workspace, String remoteRoot, TestService.Command command) throws IOException {
        Path root = workspaceRoot(workspace);
        if (command == null || !command.executable()) throw new IOException("Dev Container test command is unavailable");
        List<String> mapped = RemoteTestExecution.mapWorkspaceCommand(command.argv(), root, remoteRoot);
        return new Plan(command, DevContainerRuntime.testInvocation(root, mapped));
    }

    /** Keeps only cache reports that are safe for Shed's parsers to consume. */
    static TestService.Command validatedCommand(TestService.Command command, Path cache, List<String> diagnostics) {
        if (command == null || cache == null) return command;
        Path normalizedCache = cache.toAbsolutePath().normalize();
        List<Path> reports = new ArrayList<>();
        for (Path report : command.reports()) {
            if (!isUnder(report, normalizedCache)) {
                reports.add(report);
                continue;
            }
            if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
                reports.add(report);
                continue;
            }
            try {
                RemoteTestExecution.validateReportPath(report, normalizedCache);
                reports.add(report);
            } catch (IOException error) {
                if (diagnostics != null) diagnostics.add("Dev Container test report rejected: " + concise(error));
            }
        }
        return new TestService.Command(command.argv(), reports);
    }

    /** Deletes only the exact per-run directory created above, without following symbolic links. */
    static String cleanupReportCache(Path workspace, Path cache) {
        try {
            Path root = workspaceRoot(workspace);
            Path base = reportBase(root);
            Path candidate = cache == null ? null : cache.toAbsolutePath().normalize();
            if (candidate == null || !base.equals(candidate.getParent()) || !candidate.getFileName().toString().startsWith(REPORT_PREFIX)) {
                throw new IOException("report cache is not a generated Dev Container test directory");
            }
            if (Files.exists(base, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Dev Container report cache root changed during the test run");
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) deleteTree(candidate);
            Files.deleteIfExists(base);
            return "";
        } catch (IOException error) {
            return "Dev Container test report cleanup failed: " + concise(error);
        }
    }

    private static Path workspaceRoot(Path workspace) throws IOException {
        if (workspace == null) throw new IOException("Dev Container test workspace is required");
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Dev Container test workspace is unavailable");
        return root;
    }

    private static Path reportBase(Path root) throws IOException {
        Path base = root.resolve(REPORT_DIRECTORY).normalize();
        if (!base.startsWith(root)) throw new IOException("Dev Container report cache escapes the workspace");
        return base;
    }

    private static boolean isUnder(Path candidate, Path root) {
        return candidate != null && candidate.isAbsolute() && candidate.toAbsolutePath().normalize().startsWith(root);
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
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

    private static String concise(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
