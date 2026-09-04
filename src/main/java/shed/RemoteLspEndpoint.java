package shed;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

/** Maps one local remote-workspace mirror to the remote file URIs used by its LSP process. */
final class RemoteLspEndpoint {
    private final Path localRoot;
    private final String remoteRoot;
    private final List<String> command;
    private final String rootUri;

    RemoteLspEndpoint(Path localRoot, String remoteRoot, List<String> command) {
        this.localRoot = localRoot == null ? null : localRoot.toAbsolutePath().normalize();
        this.remoteRoot = normalizeRemoteRoot(remoteRoot);
        this.command = command == null ? List.of() : List.copyOf(command);
        if (this.localRoot == null) {
            throw new IllegalArgumentException("remote language server endpoint is incomplete");
        }
        for (String argument : this.command) {
            if (argument == null || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("remote language server command is invalid");
            }
        }
        rootUri = fileUri(this.remoteRoot);
    }

    Path localRoot() { return localRoot; }
    List<String> command() { return command; }
    String rootUri() { return rootUri; }

    String uriFor(Path localFile) {
        Path path = localFile == null ? null : localFile.toAbsolutePath().normalize();
        if (path == null || !path.startsWith(localRoot)) return null;
        Path relative = localRoot.relativize(path);
        String value = relative.toString().replace('\\', '/');
        if (value.isEmpty()) return rootUri;
        if (value.startsWith("/") || containsTraversal(value)) return null;
        return fileUri(remoteRoot + "/" + value);
    }

    Path localPathFor(String uri) {
        if (uri == null || uri.isBlank()) return null;
        try {
            URI value = new URI(uri);
            if (!"file".equalsIgnoreCase(value.getScheme()) || value.getAuthority() != null || value.getPath() == null) return null;
            return localPathForRemotePath(value.getPath());
        } catch (URISyntaxException | IllegalArgumentException error) {
            return null;
        }
    }

    /** Maps only an absolute remote path beneath this endpoint's root into its local mirror. */
    Path localPathForRemotePath(String remotePath) {
        if (remotePath == null || !remotePath.startsWith("/") || containsTraversal(remotePath)) return null;
        if (!remotePath.equals(remoteRoot) && !remotePath.startsWith(remoteRoot + "/")) return null;
        String relative = remotePath.equals(remoteRoot) ? "" : remotePath.substring(remoteRoot.length() + 1);
        if (containsTraversal(relative)) return null;
        try {
            Path candidate = localRoot.resolve(relative).normalize();
            return candidate.startsWith(localRoot) ? candidate : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    TerminalLinkResolver.SourcePathMapper terminalSourcePathMapper() {
        return (sourcePath, ignoredWorkingDirectory) -> {
            try {
                Path raw = Path.of(sourcePath);
                String remotePath = raw.isAbsolute() ? raw.normalize().toString() : remoteRoot + "/" + raw.normalize();
                return localPathForRemotePath(remotePath);
            } catch (IllegalArgumentException error) {
                return null;
            }
        };
    }

    private static String normalizeRemoteRoot(String value) {
        String root = value == null ? "" : value.trim();
        while (root.length() > 1 && root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (!root.startsWith("/") || root.indexOf('\0') >= 0 || root.indexOf('\n') >= 0 || root.indexOf('\r') >= 0 || containsTraversal(root)) {
            throw new IllegalArgumentException("remote language server root is invalid");
        }
        return root;
    }

    private static boolean containsTraversal(String value) {
        for (String part : value.split("/")) if ("..".equals(part)) return true;
        return false;
    }

    private static String fileUri(String path) {
        try {
            return new URI("file", "", path, null).toString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("remote language server path is invalid", error);
        }
    }
}
