package shed;

import shed.api.RemoteCommandRequest;
import shed.api.RemoteCommandResult;
import shed.api.RemoteWorkspace;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes an explicitly selected test root in its connected remote workspace. */
final class RemoteTestExecution {
    private static final String REPORT_DIRECTORY = ".shed-remote-test-reports";
    private static final long MAX_REPORT_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_REPORT_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final int MAX_REPORT_FILES = 10_000;

    record Plan(RemoteWorkspace workspace, Path connectionRoot, Path workspaceRoot, String remoteRoot,
                String relativeWorkingDirectory, TestService.Command command, List<String> remoteCommand,
                List<Fetch> fetches, String remoteReportDirectory) {
        Plan {
            remoteCommand = remoteCommand == null ? List.of() : List.copyOf(remoteCommand);
            fetches = fetches == null ? List.of() : List.copyOf(fetches);
            remoteReportDirectory = remoteReportDirectory == null ? "" : remoteReportDirectory;
        }

        boolean needsRemoteReportDirectory() {
            return !remoteReportDirectory.isBlank();
        }
    }

    record Fetch(String remotePath, Path destinationDirectory) {
        Fetch {
            remotePath = requireRelative(remotePath, "remote report path");
            destinationDirectory = destinationDirectory == null ? null : destinationDirectory.toAbsolutePath().normalize();
            if (destinationDirectory == null) throw new IllegalArgumentException("report destination is required");
        }
    }

    record Result(TestService.Command command, CommandResult result, List<String> diagnostics) {
        Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private RemoteTestExecution() {
    }

    static Plan prepare(RemoteWorkspaceTaskTargets.Target target, Path workspaceRoot, TestService.Command command, Path reportCache) throws IOException {
        if (target == null || target.workspace() == null || target.localRoot() == null) throw new IOException("remote test workspace is unavailable");
        if (workspaceRoot == null || command == null || !command.executable()) throw new IOException("remote test command is unavailable");
        Path connectionRoot = target.localRoot().toAbsolutePath().normalize();
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (!root.startsWith(connectionRoot) || !Files.isDirectory(root)) throw new IOException("test root is outside the connected remote workspace");
        String remoteRoot = requireRemoteRoot(target.workspace().executionRoot());
        String relativeWorkingDirectory = relative(connectionRoot.relativize(root));
        Path cache = reportCache == null ? null : reportCache.toAbsolutePath().normalize();
        if (cache != null && !Files.isDirectory(cache)) throw new IOException("remote test report cache is unavailable");

        boolean usesCache = command.reports().stream().anyMatch(report -> isUnder(report, cache));
        String remoteReportDirectory = usesCache ? REPORT_DIRECTORY + "/" + UUID.randomUUID() : "";
        String remoteWorkspaceRoot = join(remoteRoot, relativeWorkingDirectory);
        String remoteCache = remoteReportDirectory.isBlank() ? "" : join(remoteWorkspaceRoot, remoteReportDirectory);
        List<String> cacheMappedCommand = command.argv().stream().map(argument -> mapArgument(argument, connectionRoot, remoteRoot, cache, remoteCache)).toList();
        List<String> remoteCommand = mapWorkspaceCommand(cacheMappedCommand, connectionRoot, remoteRoot);
        List<Fetch> fetches = new ArrayList<>();
        for (int index = 0; index < command.reports().size(); index++) {
            Path report = command.reports().get(index);
            if (report == null) continue;
            if (isUnder(report, cache)) {
                Path normalized = report.toAbsolutePath().normalize();
                String suffix = relative(cache.relativize(normalized));
                String remotePath = joinRelative(relativeWorkingDirectory, joinRelative(remoteReportDirectory, suffix));
                Path destination = suffix.isEmpty() ? cache : normalized.getParent();
                fetches.add(new Fetch(remotePath, destination));
            } else if (report.isAbsolute()) {
                Path normalized = report.toAbsolutePath().normalize();
                if (!normalized.startsWith(connectionRoot)) throw new IOException("remote test report escapes the workspace or report cache");
                if (cache == null) throw new IOException("remote test report cache is unavailable");
                fetches.add(new Fetch(relative(connectionRoot.relativize(normalized)), cache.resolve("workspace-report-" + index)));
            } else {
                if (cache == null) throw new IOException("remote test report cache is unavailable");
                fetches.add(new Fetch(joinRelative(relativeWorkingDirectory, relative(report)), cache.resolve("workspace-report-" + index)));
            }
        }
        return new Plan(target.workspace(), connectionRoot, root, remoteRoot, relativeWorkingDirectory, command, remoteCommand, fetches, remoteReportDirectory);
    }

    static Result execute(Plan plan) throws Exception {
        if (plan == null || plan.workspace() == null) throw new IOException("remote test plan is unavailable");
        List<String> diagnostics = new ArrayList<>();
        RemoteCommandResult remoteResult;
        List<Path> reports = new ArrayList<>();
        try {
            if (plan.needsRemoteReportDirectory()) {
                RemoteCommandResult prepared = plan.workspace().execute(new RemoteCommandRequest(List.of("mkdir", "-p", plan.remoteReportDirectory()),
                    plan.relativeWorkingDirectory(), Map.of()));
                if (prepared == null || prepared.exitCode() != 0) throw new IOException("remote test report directory could not be created");
            }
            remoteResult = plan.workspace().execute(new RemoteCommandRequest(plan.remoteCommand(), plan.relativeWorkingDirectory(), Map.of()));
            if (remoteResult == null) throw new IOException("remote test command returned no result");
            for (Fetch fetch : plan.fetches()) {
                try {
                    Files.createDirectories(fetch.destinationDirectory());
                    Path fetched = plan.workspace().fetchWorkspacePath(fetch.remotePath(), fetch.destinationDirectory());
                    reports.add(validateFetchedPath(fetched, fetch.destinationDirectory()));
                } catch (Exception error) {
                    diagnostics.add("Remote test report unavailable: " + concise(error));
                }
            }
        } finally {
            if (plan.needsRemoteReportDirectory()) {
                try {
                    RemoteCommandResult removed = plan.workspace().execute(new RemoteCommandRequest(List.of("rm", "-rf", plan.remoteReportDirectory()),
                        plan.relativeWorkingDirectory(), Map.of()));
                    if (removed == null || removed.exitCode() != 0) diagnostics.add("Remote test report cleanup failed");
                } catch (Exception error) {
                    diagnostics.add("Remote test report cleanup failed: " + concise(error));
                }
            }
        }
        String output = localizeOutput(remoteResult.output(), plan.remoteRoot(), plan.connectionRoot());
        if (output.contains("[shed: output truncated]")) diagnostics.add("Remote test output was truncated; stdout-derived results may be incomplete.");
        return new Result(new TestService.Command(plan.command().argv(), reports), new CommandResult(remoteResult.exitCode(), output, ""), diagnostics);
    }

    private static Path validateFetchedPath(Path fetched, Path destinationDirectory) throws IOException {
        Path destination = destinationDirectory.toAbsolutePath().normalize();
        Path result = fetched == null ? null : fetched.toAbsolutePath().normalize();
        if (result == null || !result.startsWith(destination) || !Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("remote provider returned an invalid report path");
        }
        validateReportPath(result, destination);
        return result;
    }

    /** Maps only a complete workspace-path prefix, including values such as {@code --file=/host/project/a}. */
    static List<String> mapWorkspaceCommand(List<String> command, Path localRoot, String remoteRoot) throws IOException {
        if (command == null || command.isEmpty()) throw new IOException("remote test command is unavailable");
        if (localRoot == null) throw new IOException("local workspace root is unavailable");
        String remote = requireRemoteRoot(remoteRoot);
        Path local = localRoot.toAbsolutePath().normalize();
        List<String> mapped = new ArrayList<>();
        for (String argument : command) {
            if (argument == null || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new IOException("remote test command is invalid");
            }
            mapped.add(replacePathPrefix(argument, local, remote));
        }
        return List.copyOf(mapped);
    }

    /** Validates a report tree before an app parser consumes container or provider-produced files. */
    static void validateReportPath(Path report, Path allowedRoot) throws IOException {
        Path destination = allowedRoot == null ? null : allowedRoot.toAbsolutePath().normalize();
        Path result = report == null ? null : report.toAbsolutePath().normalize();
        if (destination == null || result == null || !result.startsWith(destination) || !Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("report path is unavailable or outside its declared cache");
        }
        if (Files.isSymbolicLink(result)) throw new IOException("report contains a symbolic link");
        long[] total = {0};
        int[] files = {0};
        Files.walkFileTree(result, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) throw new IOException("remote report contains a symbolic link");
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) throw new IOException("remote report contains an unsafe file");
                if (++files[0] > MAX_REPORT_FILES) throw new IOException("remote report contains too many files");
                if (attributes.size() > MAX_REPORT_FILE_BYTES) throw new IOException("remote report file exceeds 8 MiB");
                total[0] += attributes.size();
                if (total[0] > MAX_REPORT_TOTAL_BYTES) throw new IOException("remote report exceeds 32 MiB");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String mapArgument(String argument, Path connectionRoot, String remoteRoot, Path cache, String remoteCache) {
        String mapped = replacePathPrefix(argument, cache, remoteCache);
        return replacePathPrefix(mapped, connectionRoot, remoteRoot);
    }

    private static String replacePathPrefix(String value, Path localRoot, String remoteRoot) {
        if (value == null || localRoot == null || remoteRoot == null || remoteRoot.isBlank()) return value;
        String local = localRoot.toAbsolutePath().normalize().toString();
        if (local.isBlank()) return value;
        int offset = value.startsWith(local) ? 0 : value.indexOf("=" + local) >= 0 ? value.indexOf("=" + local) + 1 : -1;
        if (offset < 0 || !pathBoundary(value, offset + local.length())) return value;
        String suffix = value.substring(offset + local.length()).replace('\\', '/');
        return value.substring(0, offset) + join(remoteRoot, suffix);
    }

    private static boolean pathBoundary(String value, int index) {
        return index >= value.length() || value.charAt(index) == '/' || value.charAt(index) == '\\';
    }

    private static boolean isUnder(Path path, Path root) {
        if (path == null || root == null || !path.isAbsolute()) return false;
        return path.toAbsolutePath().normalize().startsWith(root);
    }

    private static String relative(Path value) {
        if (value == null || value.getNameCount() == 0) return "";
        String result = value.toString().replace('\\', '/');
        return requireRelative(result, "workspace-relative path");
    }

    private static String join(String root, String suffix) {
        String base = root == null ? "" : root.replace('\\', '/').replaceAll("/+$", "");
        String child = suffix == null ? "" : suffix.replace('\\', '/').replaceFirst("^/+", "");
        return child.isEmpty() ? base : base + "/" + child;
    }

    private static String joinRelative(String parent, String child) {
        String left = parent == null ? "" : parent.trim();
        String right = child == null ? "" : child.trim();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + "/" + right;
    }

    private static String requireRemoteRoot(String value) throws IOException {
        String root = value == null ? "" : value.trim().replace('\\', '/').replaceAll("/+$", "");
        if (root.isBlank() || root.indexOf('\0') >= 0 || root.indexOf('\n') >= 0 || root.indexOf('\r') >= 0) throw new IOException("remote workspace has no usable execution root");
        return root;
    }

    private static String requireRelative(String value, String label) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        if (normalized.isEmpty()) return "";
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be workspace-relative");
        }
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private static String localizeOutput(String output, String remoteRoot, Path localRoot) {
        String value = output == null ? "" : output;
        return remoteRoot == null || remoteRoot.isBlank() || localRoot == null ? value : value.replace(remoteRoot, localRoot.toString());
    }

    private static String concise(Exception error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) return error == null ? "unknown error" : error.getClass().getSimpleName();
        return error.getMessage().replace('\n', ' ').replace('\r', ' ');
    }
}
