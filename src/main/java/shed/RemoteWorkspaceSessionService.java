package shed;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tracks user-activated remote execution sessions without making remote placement implicit. */
final class RemoteWorkspaceSessionService {
    private final Map<Path, Connection> connections = new LinkedHashMap<>();

    synchronized void activate(String id, RemoteWorkspace workspace) {
        Connection connection = new Connection(id, workspace);
        connections.entrySet().removeIf(entry -> entry.getValue().id().equals(connection.id()));
        connections.put(WorkspaceRootResolver.normalizedIdentity(connection.localRoot()), connection);
    }

    synchronized boolean deactivate(String id) {
        String normalized = normalizeId(id);
        return connections.entrySet().removeIf(entry -> entry.getValue().id().equals(normalized));
    }

    /** Returns the deepest user-activated remote workspace containing the candidate path. */
    synchronized Connection connectionFor(Path candidate) {
        Path normalized = normalizedPath(candidate);
        if (normalized == null) return null;
        Path identity = WorkspaceRootResolver.normalizedIdentity(normalized);
        return connections.entrySet().stream()
            .filter(entry -> identity.startsWith(entry.getKey()))
            .max(Comparator.comparingInt(entry -> entry.getKey().getNameCount()))
            .map(Map.Entry::getValue)
            .orElse(null);
    }

    synchronized boolean isActive(String id) {
        String normalized = normalizeId(id);
        return connections.values().stream().anyMatch(connection -> connection.id().equals(normalized));
    }

    synchronized List<Connection> activeConnections() {
        return connections.values().stream().sorted(Comparator.comparing(Connection::id)).toList();
    }

    synchronized void close() {
        connections.clear();
    }

    record Connection(String id, RemoteWorkspace workspace, Path localRoot) {
        Connection(String id, RemoteWorkspace workspace) {
            this(normalizeId(id), workspace, normalizedPath(workspace == null ? null : workspace.localRoot()));
        }

        Connection {
            if (id.isBlank() || workspace == null || localRoot == null) {
                throw new IllegalArgumentException("remote workspace session is incomplete");
            }
        }

        List<String> terminalInvocation(Path workingDirectory, List<String> command) throws Exception {
            return workspace.terminalCommand(new RemoteTerminalRequest(relativeDirectory(workingDirectory), command));
        }

        TerminalLinkResolver.SourcePathMapper sourcePathMapper(Path terminalWorkingDirectory) {
            String remoteRoot = workspace.languageServerRoot();
            if (remoteRoot == null || remoteRoot.isBlank()) return null;
            try {
                RemoteLspEndpoint endpoint = new RemoteLspEndpoint(localRoot, remoteRoot, List.of());
                Path startDirectory = WorkspaceRootResolver.pathWithinRoot(terminalWorkingDirectory, localRoot);
                if (startDirectory == null) return null;
                return (sourcePath, ignoredWorkingDirectory) -> mapTerminalSource(endpoint, startDirectory, sourcePath);
            } catch (IllegalArgumentException error) {
                return null;
            }
        }

        private Path mapTerminalSource(RemoteLspEndpoint endpoint, Path startDirectory, String sourcePath) {
            if (sourcePath == null || sourcePath.isBlank() || sourcePath.indexOf('\0') >= 0) return null;
            try {
                if (sourcePath.startsWith("/")) return endpoint.localPathForRemotePath(sourcePath);
                Path reported = Path.of(sourcePath);
                if (reported.isAbsolute()) return null;
                Path candidate = startDirectory.resolve(reported).normalize();
                return candidate.startsWith(localRoot) ? candidate : null;
            } catch (IllegalArgumentException error) {
                return null;
            }
        }

        private String relativeDirectory(Path workingDirectory) {
            Path normalized = WorkspaceRootResolver.pathWithinRoot(workingDirectory, localRoot);
            if (normalized == null) {
                throw new IllegalArgumentException("remote terminal directory is outside the active workspace");
            }
            return localRoot.relativize(normalized).toString().replace('\\', '/');
        }
    }

    private static Path normalizedPath(Path value) {
        if (value == null) return null;
        try {
            return value.toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
