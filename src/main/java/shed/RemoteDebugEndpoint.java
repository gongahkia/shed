package shed;

import java.nio.file.Path;
import java.util.List;

/** Maps a local remote-workspace mirror to paths carried by a remote DAP adapter. */
final class RemoteDebugEndpoint {
    private final Path localRoot;
    private final String remoteRoot;
    private final List<String> command;

    RemoteDebugEndpoint(Path localRoot, String remoteRoot, List<String> command) {
        this.localRoot = localRoot == null ? null : localRoot.toAbsolutePath().normalize();
        this.remoteRoot = normalizedRoot(remoteRoot);
        this.command = command == null ? List.of() : List.copyOf(command);
        if (this.localRoot == null || this.command.isEmpty()) throw new IllegalArgumentException("remote debug endpoint is incomplete");
        for (String argument : this.command) {
            if (argument == null || argument.isBlank() || containsControl(argument)) throw new IllegalArgumentException("remote debug command is invalid");
        }
    }

    List<String> command() { return command; }

    String remotePathFor(Path localPath) {
        Path value = localPath == null ? null : localPath.toAbsolutePath().normalize();
        if (value == null || !value.startsWith(localRoot)) return null;
        String suffix = localRoot.relativize(value).toString().replace('\\', '/');
        if (suffix.isEmpty()) return remoteRoot;
        return hasTraversal(suffix) ? null : remoteRoot + "/" + suffix;
    }

    Path localPathFor(String remotePath) {
        String value = remotePath == null ? "" : remotePath.replace('\\', '/');
        if (!value.equals(remoteRoot) && !value.startsWith(remoteRoot + "/")) return null;
        String relative = value.equals(remoteRoot) ? "" : value.substring(remoteRoot.length() + 1);
        if (hasTraversal(relative)) return null;
        try {
            Path candidate = localRoot.resolve(relative).normalize();
            return candidate.startsWith(localRoot) ? candidate : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String normalizedRoot(String value) {
        String root = value == null ? "" : value.trim().replace('\\', '/');
        while (root.length() > 1 && root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (!root.startsWith("/") || containsControl(root) || hasTraversal(root)) throw new IllegalArgumentException("remote debug root is invalid");
        return root;
    }

    private static boolean containsControl(String value) { return value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0; }
    private static boolean hasTraversal(String value) { for (String part : value.split("/")) if ("..".equals(part)) return true; return false; }
}
