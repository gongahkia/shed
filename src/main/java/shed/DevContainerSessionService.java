package shed;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks explicitly connected local Dev Container workspaces for this application process only. */
final class DevContainerSessionService {
    private final Map<Path, Connection> connections = new LinkedHashMap<>();

    synchronized void connect(Path workspace, String remoteWorkingDirectory) {
        Connection connection = new Connection(workspace, remoteWorkingDirectory);
        connections.put(connection.workspace(), connection);
    }

    synchronized boolean disconnect(Path workspace) {
        Path normalized = normalizedWorkspace(workspace);
        return normalized != null && connections.remove(normalized) != null;
    }

    /** Returns the deepest explicitly connected workspace containing the candidate path. */
    synchronized Connection connectionFor(Path candidate) {
        Path normalized = normalizedWorkspace(candidate);
        if (normalized == null) return null;
        return connections.values().stream()
            .filter(connection -> normalized.startsWith(connection.workspace()))
            .max(Comparator.comparingInt(connection -> connection.workspace().getNameCount()))
            .orElse(null);
    }

    synchronized boolean isConnected(Path workspace) {
        Path normalized = normalizedWorkspace(workspace);
        return normalized != null && connections.containsKey(normalized);
    }

    private static Path normalizedWorkspace(Path workspace) {
        if (workspace == null) return null;
        try {
            return workspace.toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return null;
        }
    }

    record Connection(Path workspace, String remoteWorkingDirectory) {
        Connection {
            workspace = normalizedWorkspace(workspace);
            if (workspace == null) throw new IllegalArgumentException("Dev Container workspace is required");
            remoteWorkingDirectory = DevContainerWorkspace.requireAbsolutePosixPath(remoteWorkingDirectory);
        }

        TerminalLinkResolver.SourcePathMapper sourcePathMapper() {
            return (sourcePath, workingDirectory) -> mapSourcePath(sourcePath, workingDirectory, workspace, remoteWorkingDirectory);
        }

        private static Path mapSourcePath(String sourcePath, Path workingDirectory, Path workspace, String remoteRoot) {
            if (sourcePath == null || sourcePath.isBlank() || sourcePath.indexOf('\0') >= 0) return null;
            String normalizedSource = sourcePath.replace('\\', '/');
            if (normalizedSource.startsWith("/")) {
                String relative = relativeRemotePath(normalizedSource, remoteRoot);
                return relative == null ? null : resolveInsideWorkspace(workspace, relative);
            }
            if (workingDirectory == null) return null;
            try {
                Path directory = workingDirectory.toAbsolutePath().normalize();
                if (!directory.startsWith(workspace)) return null;
                return resolveInsideWorkspace(directory, sourcePath);
            } catch (RuntimeException error) {
                return null;
            }
        }

        private static String relativeRemotePath(String sourcePath, String remoteRoot) {
            if ("/".equals(remoteRoot)) return sourcePath.substring(1);
            if (!sourcePath.startsWith(remoteRoot + "/")) return null;
            return sourcePath.substring(remoteRoot.length() + 1);
        }

        private static Path resolveInsideWorkspace(Path root, String relative) {
            try {
                Path candidate = root.resolve(relative).normalize();
                return candidate.startsWith(root) ? candidate : null;
            } catch (RuntimeException error) {
                return null;
            }
        }
    }
}
