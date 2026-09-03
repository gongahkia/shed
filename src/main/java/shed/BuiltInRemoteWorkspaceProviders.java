package shed;

import shed.api.RemoteWorkspace;
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
import java.util.List;
import java.util.Locale;

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
                ProcessResult result = execute(List.of("git", "clone", "--", gitUrl(uri), root.toString()), root.getParent());
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

        @Override public void synchronize() throws Exception {
            ProcessResult fetch = execute(List.of("git", "fetch", "--all", "--prune"), root);
            if (fetch.exitCode() != 0) throw new IOException("git fetch failed: " + fetch.detail());
            ProcessResult pull = execute(List.of("git", "pull", "--ff-only"), root);
            if (pull.exitCode() != 0) throw new IOException("git pull failed: " + pull.detail());
        }

        @Override public void synchronizeToRemote() throws Exception {
            ProcessResult push = execute(List.of("git", "push"), root);
            if (push.exitCode() != 0) throw new IOException("git push failed: " + push.detail());
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

        @Override public void synchronize() throws Exception {
            ProcessResult result = execute(rsync(false), root.getParent());
            if (result.exitCode() != 0) throw new IOException("remote pull failed: " + result.detail());
        }

        @Override public void synchronizeToRemote() throws Exception {
            ProcessResult result = execute(rsync(true), root.getParent());
            if (result.exitCode() != 0) throw new IOException("remote push failed: " + result.detail());
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
            return new DirectWorkspace("WSL " + uri, root);
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

        @Override public void synchronize() throws Exception {
            ProcessResult result = execute(List.of("docker", "cp", uri.getHost() + ":" + uri.getPath() + "/.", root.toString()), root.getParent());
            if (result.exitCode() != 0) throw new IOException("container pull failed: " + result.detail());
        }

        @Override public void synchronizeToRemote() throws Exception {
            ProcessResult result = execute(List.of("docker", "cp", root.toString() + "/.", uri.getHost() + ":" + uri.getPath()), root.getParent());
            if (result.exitCode() != 0) throw new IOException("container push failed: " + result.detail());
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
        @Override public void synchronize() { }
        @Override public void synchronizeToRemote() { }
        @Override public void close() { }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
        String detail() {
            String value = stdout == null || stdout.isBlank() ? stderr : stdout;
            return value == null || value.isBlank() ? "exit " + exitCode : value.strip();
        }
    }

    private static ProcessResult execute(List<String> command, Path directory) throws IOException {
        Path output = Files.createTempFile("shed-remote-command-", ".log");
        try {
            Process process = new ProcessBuilder(command).directory(directory == null ? null : directory.toFile())
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
