package shed;

import shed.api.RemoteWorkspace;
import shed.api.RemoteCommandResult;
import shed.api.RemoteCommandRequest;
import shed.api.RemoteTerminalRequest;
import shed.api.RemoteWorkspaceProvider;
import shed.api.RemoteWorkspaceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Built-in remote providers deliberately use local mirrors; no hidden remote code is installed. */
final class BuiltInRemoteWorkspaceProviders {
    private BuiltInRemoteWorkspaceProviders() {
    }

    static List<RemoteWorkspaceProvider> all() {
        return List.of(new GitProvider(), new SshProvider(), new ContainerProvider(), new WslProvider());
    }

    static Path workspaceStorage(Path shedDirectory, URI uri) throws IOException {
        Path base = shedDirectory.resolve("remote-workspaces").toAbsolutePath().normalize();
        if (Files.exists(base) && Files.isSymbolicLink(base)) throw new IOException("remote workspace storage is symbolic-link based");
        Files.createDirectories(base);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return base.resolve(scheme + "-" + sha256(uri.normalize().toString()).substring(0, 24)).normalize();
    }

    private static final class GitProvider implements RemoteWorkspaceProvider {
        @Override public String id() { return "git"; }
        @Override public String displayName() { return "Git repository"; }

        @Override public boolean supports(URI uri) {
            if (uri == null || uri.getScheme() == null || uri.getUserInfo() != null) return false;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return ("git".equals(scheme) || "git+https".equals(scheme) || "git+ssh".equals(scheme) || "https".equals(scheme) || "ssh".equals(scheme))
                && uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".git");
        }

        @Override public RemoteWorkspace connect(RemoteWorkspaceRequest request) throws Exception {
            URI uri = request.uri();
            validateNoPassword(uri);
            Path root = workspaceStorage(request.storageDirectory(), uri);
            if (Files.exists(root) && Files.isSymbolicLink(root)) throw new IOException("remote workspace directory is symbolic-link based");
            if (!Files.isDirectory(root.resolve(".git"))) {
                if (Files.exists(root)) {
                    try (var entries = Files.list(root)) {
                        if (entries.findAny().isPresent()) throw new IOException("remote workspace directory is not an empty Git clone");
                    }
                }
                Files.createDirectories(root.getParent());
                ProcessResult result = executeProcess(List.of("git", "clone", "--", gitUrl(uri), root.toString()), root.getParent());
                if (result.exitCode() != 0) throw new IOException("git clone failed: " + result.detail());
            }
            return new GitWorkspace(uri, root);
        }
    }

    private static final class GitWorkspace implements RemoteWorkspace {
        private final URI uri;
        private final Path root;

        private GitWorkspace(URI uri, Path root) {
            this.uri = uri;
            this.root = root;
        }

        @Override public String displayName() { return "Git " + uri; }
        @Override public Path localRoot() { return root; }
        @Override public String executionRoot() { return root.toString(); }

        @Override public void synchronize() throws Exception {
            ProcessResult fetch = executeProcess(List.of("git", "fetch", "--all", "--prune"), root);
            if (fetch.exitCode() != 0) throw new IOException("git fetch failed: " + fetch.detail());
            ProcessResult pull = executeProcess(List.of("git", "pull", "--ff-only"), root);
            if (pull.exitCode() != 0) throw new IOException("git pull failed: " + pull.detail());
        }

        @Override public void synchronizeToRemote() throws Exception {
            ProcessResult push = executeProcess(List.of("git", "push"), root);
            if (push.exitCode() != 0) throw new IOException("git push failed: " + push.detail());
        }

        @Override public RemoteCommandResult execute(List<String> command) throws Exception {
            return execute(new RemoteCommandRequest(command, "", Map.of()));
        }

        @Override public RemoteCommandResult execute(RemoteCommandRequest request) throws Exception {
            return commandResult(executeProcess(requiredCommand(request.command()), localDirectory(root, request), request.environment()));
        }

        @Override public List<String> terminalCommand(RemoteTerminalRequest request) {
            RemoteTerminalRequest terminal = requiredTerminalRequest(request);
            return terminal.command().isEmpty() ? ShellCommand.interactiveCommand() : terminal.command();
        }

        @Override public void close() {
            // A mirror remains on disk until the user removes it explicitly.
        }
    }

    private static final class SshProvider implements RemoteWorkspaceProvider {
        @Override public String id() { return "ssh"; }
        @Override public String displayName() { return "SSH mirror"; }

        @Override public boolean supports(URI uri) {
            return uri != null && "ssh".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && safeRemotePath(uri.getPath());
        }

        @Override public RemoteWorkspace connect(RemoteWorkspaceRequest request) throws Exception {
            URI uri = request.uri();
            validateNoPassword(uri);
            if (!supports(uri)) throw new IOException("SSH URI must contain a host and absolute path without '..'");
            Path root = workspaceStorage(request.storageDirectory(), uri);
            if (Files.exists(root) && Files.isSymbolicLink(root)) throw new IOException("remote workspace directory is symbolic-link based");
            Files.createDirectories(root);
            SshWorkspace workspace = new SshWorkspace(uri, root);
            workspace.synchronize();
            return workspace;
        }
    }

    private static final class SshWorkspace implements RemoteWorkspace {
        private final URI uri;
        private final Path root;

        private SshWorkspace(URI uri, Path root) {
            this.uri = uri;
            this.root = root;
        }

        @Override public String displayName() { return "SSH " + uri; }
        @Override public Path localRoot() { return root; }
        @Override public String executionRoot() { return uri.getPath(); }

        @Override public void synchronize() throws Exception {
            ProcessResult result = executeProcess(rsync(false), root.getParent());
            if (result.exitCode() != 0) throw new IOException("remote pull failed: " + result.detail());
        }

        @Override public void synchronizeToRemote() throws Exception {
            ProcessResult result = executeProcess(rsync(true), root.getParent());
            if (result.exitCode() != 0) throw new IOException("remote push failed: " + result.detail());
        }

        @Override public RemoteCommandResult execute(List<String> command) throws Exception {
            return execute(new RemoteCommandRequest(command, "", Map.of()));
        }

        @Override public RemoteCommandResult execute(RemoteCommandRequest request) throws Exception {
            List<String> invocation = new ArrayList<>(List.of("ssh"));
            if (uri.getPort() > 0) {
                invocation.add("-p");
                invocation.add(Integer.toString(uri.getPort()));
            }
            invocation.add(safeSshTarget(uri));
            invocation.add(posixCommand(remoteDirectory(uri.getPath(), request.relativeWorkingDirectory()), requiredCommand(request.command()), request.environment()));
            return commandResult(executeProcess(invocation, root.getParent()));
        }

        @Override public List<String> terminalCommand(RemoteTerminalRequest request) throws Exception {
            RemoteTerminalRequest terminal = requiredTerminalRequest(request);
            List<String> invocation = new ArrayList<>(List.of("ssh", "-tt"));
            if (uri.getPort() > 0) {
                invocation.add("-p");
                invocation.add(Integer.toString(uri.getPort()));
            }
            invocation.add(safeSshTarget(uri));
            List<String> command = terminal.command().isEmpty() ? List.of("sh", "-l") : terminal.command();
            invocation.add(posixCommand(remoteDirectory(uri.getPath(), terminal.relativeWorkingDirectory()), command, Map.of()));
            return List.copyOf(invocation);
        }

        @Override public void close() {
            // No daemon is left running; mirror files remain available offline.
        }

        private List<String> rsync(boolean push) {
            List<String> command = new ArrayList<>(List.of("rsync", "-az", "--protect-args"));
            if (uri.getPort() > 0) {
                command.add("-e");
                command.add("ssh -p " + uri.getPort());
            }
            String source = remoteAddress(uri);
            if (push) {
                command.add(root.toString() + "/");
                command.add(source + "/");
            } else {
                command.add(source + "/");
                command.add(root.toString() + "/");
            }
            return List.copyOf(command);
        }
    }

    private static final class WslProvider implements RemoteWorkspaceProvider {
        @Override public String id() { return "wsl"; }
        @Override public String displayName() { return "Windows Subsystem for Linux"; }

        @Override public boolean supports(URI uri) {
            return uri != null && "wsl".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && safeRemotePath(uri.getPath());
        }

        @Override public RemoteWorkspace connect(RemoteWorkspaceRequest request) throws Exception {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                throw new IOException("WSL workspaces are available only on Windows");
            }
            URI uri = request.uri();
            Path root = Path.of("//wsl$/" + uri.getHost() + uri.getPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) throw new IOException("WSL path is unavailable: " + root);
            return new WslWorkspace(uri, root);
        }
    }

    private static final class ContainerProvider implements RemoteWorkspaceProvider {
        @Override public String id() { return "container"; }
        @Override public String displayName() { return "Docker container mirror"; }

        @Override public boolean supports(URI uri) {
            if (uri == null || uri.getScheme() == null || uri.getHost() == null || !safeRemotePath(uri.getPath())) return false;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return ("container".equals(scheme) || "docker".equals(scheme)) && uri.getHost().matches("[A-Za-z0-9][A-Za-z0-9_.-]*");
        }

        @Override public RemoteWorkspace connect(RemoteWorkspaceRequest request) throws Exception {
            URI uri = request.uri();
            if (!supports(uri)) throw new IOException("container URI must be container://<name>/<absolute-path>");
            Path root = workspaceStorage(request.storageDirectory(), uri);
            if (Files.exists(root) && Files.isSymbolicLink(root)) throw new IOException("remote workspace directory is symbolic-link based");
            Files.createDirectories(root);
            ContainerWorkspace workspace = new ContainerWorkspace(uri, root);
            workspace.synchronize();
            return workspace;
        }
    }

    private static final class ContainerWorkspace implements RemoteWorkspace {
        private final URI uri;
        private final Path root;

        private ContainerWorkspace(URI uri, Path root) {
            this.uri = uri;
            this.root = root;
        }

        @Override public String displayName() { return "Container " + uri; }
        @Override public Path localRoot() { return root; }
        @Override public String executionRoot() { return uri.getPath(); }

        @Override public void synchronize() throws Exception {
            ProcessResult result = executeProcess(List.of("docker", "cp", uri.getHost() + ":" + uri.getPath() + "/.", root.toString()), root.getParent());
            if (result.exitCode() != 0) throw new IOException("container pull failed: " + result.detail());
        }

        @Override public void synchronizeToRemote() throws Exception {
            ProcessResult result = executeProcess(List.of("docker", "cp", root.toString() + "/.", uri.getHost() + ":" + uri.getPath()), root.getParent());
            if (result.exitCode() != 0) throw new IOException("container push failed: " + result.detail());
        }

        @Override public RemoteCommandResult execute(List<String> command) throws Exception {
            return execute(new RemoteCommandRequest(command, "", Map.of()));
        }

        @Override public RemoteCommandResult execute(RemoteCommandRequest request) throws Exception {
            List<String> invocation = new ArrayList<>(List.of("docker", "exec", "--workdir", remoteDirectory(uri.getPath(), request.relativeWorkingDirectory())));
            for (Map.Entry<String, String> entry : request.environment().entrySet()) {
                invocation.add("--env");
                invocation.add(entry.getKey() + "=" + entry.getValue());
            }
            invocation.add(uri.getHost());
            invocation.addAll(requiredCommand(request.command()));
            return commandResult(executeProcess(invocation, root.getParent()));
        }

        @Override public List<String> terminalCommand(RemoteTerminalRequest request) throws Exception {
            RemoteTerminalRequest terminal = requiredTerminalRequest(request);
            List<String> invocation = new ArrayList<>(List.of("docker", "exec", "-it", "--workdir",
                remoteDirectory(uri.getPath(), terminal.relativeWorkingDirectory()), uri.getHost()));
            invocation.addAll(terminal.command().isEmpty() ? List.of("/bin/sh") : terminal.command());
            return List.copyOf(invocation);
        }

        @Override public void close() {
            // Docker copy does not leave an attached process or an implicit container lifecycle.
        }
    }

    private static final class DirectWorkspace implements RemoteWorkspace {
        private final String name;
        private final Path root;
        private DirectWorkspace(String name, Path root) { this.name = name; this.root = root; }
        @Override public String displayName() { return name; }
        @Override public Path localRoot() { return root; }
        @Override public String executionRoot() { return root.toString(); }
        @Override public void synchronize() { }
        @Override public void synchronizeToRemote() { }
        @Override public List<String> terminalCommand(RemoteTerminalRequest request) {
            RemoteTerminalRequest terminal = requiredTerminalRequest(request);
            return terminal.command().isEmpty() ? ShellCommand.interactiveCommand() : terminal.command();
        }
        @Override public void close() { }
    }

    private static final class WslWorkspace implements RemoteWorkspace {
        private final URI uri;
        private final Path root;

        private WslWorkspace(URI uri, Path root) {
            this.uri = uri;
            this.root = root;
        }

        @Override public String displayName() { return "WSL " + uri; }
        @Override public Path localRoot() { return root; }
        @Override public String executionRoot() { return uri.getPath(); }
        @Override public void synchronize() { }

        @Override public RemoteCommandResult execute(List<String> command) throws Exception {
            return execute(new RemoteCommandRequest(command, "", Map.of()));
        }

        @Override public RemoteCommandResult execute(RemoteCommandRequest request) throws Exception {
            List<String> invocation = new ArrayList<>(List.of("wsl.exe", "-d", uri.getHost(), "--cd", remoteDirectory(uri.getPath(), request.relativeWorkingDirectory()), "--"));
            if (!request.environment().isEmpty()) {
                invocation.add("env");
                for (Map.Entry<String, String> entry : request.environment().entrySet()) {
                    invocation.add(entry.getKey() + "=" + entry.getValue());
                }
            }
            invocation.addAll(requiredCommand(request.command()));
            return commandResult(executeProcess(invocation, root));
        }

        @Override public List<String> terminalCommand(RemoteTerminalRequest request) throws Exception {
            RemoteTerminalRequest terminal = requiredTerminalRequest(request);
            List<String> invocation = new ArrayList<>(List.of("wsl.exe", "-d", uri.getHost(), "--cd",
                remoteDirectory(uri.getPath(), terminal.relativeWorkingDirectory())));
            if (!terminal.command().isEmpty()) {
                invocation.add("--");
                invocation.addAll(terminal.command());
            }
            return List.copyOf(invocation);
        }

        @Override public void close() { }
    }

    private static List<String> requiredCommand(List<String> command) throws IOException {
        List<String> values = command == null ? List.of() : List.copyOf(command);
        if (values.isEmpty()) throw new IOException("remote command is required");
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value == null || (index == 0 && value.isBlank()) || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IOException("remote command contains an invalid argument");
            }
        }
        return values;
    }

    private static RemoteTerminalRequest requiredTerminalRequest(RemoteTerminalRequest request) {
        if (request == null) throw new IllegalArgumentException("remote terminal request is required");
        return request;
    }

    private static RemoteCommandResult commandResult(ProcessResult value) {
        return new RemoteCommandResult(value.exitCode(), value.stdout());
    }

    private static String safeSshTarget(URI uri) throws IOException {
        String host = uri == null ? null : uri.getHost();
        String user = uri == null ? null : uri.getUserInfo();
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) {
            throw new IOException("SSH command execution requires a DNS host name");
        }
        if (user != null && !user.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("SSH command execution requires a simple SSH user name");
        }
        return user == null || user.isBlank() ? host : user + "@" + host;
    }

    private static Path localDirectory(Path root, RemoteCommandRequest request) throws IOException {
        Path directory = root.resolve(request.relativeWorkingDirectory()).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
            throw new IOException("remote task directory is unavailable in the local workspace mirror");
        }
        return directory;
    }

    private static String remoteDirectory(String root, String relative) throws IOException {
        if (!safeRemotePath(root)) throw new IOException("remote command directory is invalid");
        if (relative == null || relative.isEmpty()) return root;
        String result = root.endsWith("/") ? root + relative : root + "/" + relative;
        if (!safeRemotePath(result)) throw new IOException("remote command directory is invalid");
        return result;
    }

    private static String posixCommand(String directory, List<String> command, Map<String, String> environment) throws IOException {
        if (!safeRemotePath(directory)) throw new IOException("remote command directory is invalid");
        StringBuilder result = new StringBuilder("cd -- ").append(posixQuote(directory)).append(" && ");
        if (!environment.isEmpty()) {
            result.append("env");
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                result.append(' ').append(entry.getKey()).append('=').append(posixQuote(entry.getValue()));
            }
            result.append(' ');
        }
        result.append("exec");
        for (String argument : command) result.append(' ').append(posixQuote(argument));
        return result.toString();
    }

    private static String posixQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
        String detail() {
            String value = stdout == null || stdout.isBlank() ? stderr : stdout;
            return value == null || value.isBlank() ? "exit " + exitCode : value.strip();
        }
    }

    private static ProcessResult executeProcess(List<String> command, Path directory) throws IOException {
        return executeProcess(command, directory, Map.of());
    }

    private static ProcessResult executeProcess(List<String> command, Path directory, Map<String, String> environment) throws IOException {
        Path output = Files.createTempFile("shed-remote-command-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory == null ? null : directory.toFile());
            if (environment != null && !environment.isEmpty()) builder.environment().putAll(new LinkedHashMap<>(environment));
            Process process = builder
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean completed = process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("remote command timed out");
            }
            return new ProcessResult(process.exitValue(), readCapped(Files.newInputStream(output)), "");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("remote command interrupted", error);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static String readCapped(InputStream input) throws IOException {
        try (InputStream value = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int total = 0, read; (read = value.read(buffer)) >= 0;) {
                int accepted = Math.min(read, 128 * 1024 - total);
                if (accepted > 0) output.write(buffer, 0, accepted);
                total += read;
                if (total > 128 * 1024) break;
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static boolean safeRemotePath(String value) {
        if (value == null || !value.startsWith("/") || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return false;
        for (String part : value.split("/")) if ("..".equals(part)) return false;
        return true;
    }

    private static void validateNoPassword(URI uri) throws IOException {
        if (uri == null || uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            throw new IOException("passwords in remote URIs are not accepted; use SSH keys or credential helpers");
        }
    }

    private static String remoteAddress(URI uri) {
        String user = uri.getUserInfo();
        return (user == null || user.isBlank() ? "" : user + "@") + uri.getHost() + ":" + uri.getPath();
    }

    private static String gitUrl(URI uri) throws IOException {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "git+https" -> "https" + uri.toString().substring("git+https".length());
            case "git+ssh" -> "ssh" + uri.toString().substring("git+ssh".length());
            case "git", "https", "ssh" -> uri.toString();
            default -> throw new IOException("unsupported Git remote scheme: " + scheme);
        };
    }

    private static String sha256(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte part : digest) result.append(String.format(Locale.ROOT, "%02x", part));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }
}
