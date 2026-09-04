package shed;

import shed.api.RemoteWorkspace;
import shed.api.RemoteCommandResult;
import shed.api.RemoteWorkspaceProvider;
import shed.api.RemoteWorkspaceRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit remote-workspace operations. Connections are local mirrors unless a provider says otherwise. */
final class RemoteWorkspaceController {
    private record Connection(String id, String provider, URI uri, RemoteWorkspace workspace) { }

    private final Texteditor editor;
    private final Map<String, Connection> connections = new LinkedHashMap<>();

    RemoteWorkspaceController(Texteditor editor) {
        this.editor = editor;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value) || "status".equalsIgnoreCase(value)) return showStatus();
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return "Remote command invalid: " + error.getMessage();
        }
        String operation = tokens.getFirst().toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "open", "connect" -> connect(tokens.size() == 2 ? tokens.get(1) : "");
            case "pull", "refresh" -> synchronize(tokens.size() == 2 ? tokens.get(1) : "", false);
            case "push" -> synchronize(tokens.size() == 2 ? tokens.get(1) : "", true);
            case "exec" -> execute(tokens);
            case "terminal", "term" -> openTerminal(tokens);
            case "close", "disconnect" -> close(tokens.size() == 2 ? tokens.get(1) : "");
            case "providers" -> showProviders();
            default -> "Usage: :remote [list|providers|open <uri>|pull <id>|push <id>|exec <id> <command...>|terminal <id> [command...]|close <id>]";
        };
    }

    private String connect(String rawUri) {
        URI uri;
        try {
            uri = URI.create(rawUri);
        } catch (IllegalArgumentException error) {
            return "Remote URI is invalid";
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) return "Remote URI requires a scheme";
        RemoteWorkspaceProvider provider = providerFor(uri);
        if (provider == null) return "No remote workspace provider supports: " + uri.getScheme();
        int job = editor.asyncJobService.submit("remote connect: " + uri, token -> {
            Path storage = Path.of(editor.configManager.getShedDirectoryPath());
            RemoteWorkspace workspace = provider.connect(new RemoteWorkspaceRequest(uri, storage));
            Path root = workspace.localRoot() == null ? null : workspace.localRoot().toAbsolutePath().normalize();
            if (root == null || !Files.isDirectory(root)) {
                try { workspace.close(); } catch (Exception ignored) { }
                throw new IllegalStateException("remote provider did not return an existing local workspace directory");
            }
            return new Connection(connectionId(uri), provider.id(), uri, workspace);
        }, (snapshot, result, error) -> {
            if (error != null || result == null) {
                editor.showMessage("Remote connect failed: " + detail(error == null ? snapshot.getErrorMessage() : error.getMessage()));
                return;
            }
            Connection prior;
            synchronized (connections) { prior = connections.put(result.id(), result); }
            editor.remoteWorkspaceTaskTargets.register(result.id(), result.workspace());
            if (prior != null) {
                try { prior.workspace().close(); } catch (Exception ignored) { }
            }
            editor.workspaceController.add(result.workspace().localRoot().toString(), true);
            editor.showMessage("Remote workspace connected: " + result.id());
        });
        return "Remote connection requested (job " + job + ").";
    }

    private String synchronize(String id, boolean push) {
        Connection connection = connection(id);
        if (connection == null) return "Remote workspace not connected: " + id;
        int job = editor.asyncJobService.submit("remote " + (push ? "push" : "pull") + ": " + connection.id(), token -> {
            if (push) connection.workspace().synchronizeToRemote();
            else connection.workspace().synchronize();
            return connection;
        }, (snapshot, result, error) -> {
            if (error != null) editor.showMessage("Remote " + (push ? "push" : "pull") + " failed: " + detail(error.getMessage()));
            else editor.showMessage("Remote " + (push ? "push" : "pull") + " complete: " + connection.id());
        });
        return "Remote " + (push ? "push" : "pull") + " requested (job " + job + ").";
    }

    private String close(String id) {
        Connection connection;
        synchronized (connections) { connection = connections.remove(normalizeId(id)); }
        if (connection == null) return "Remote workspace not connected: " + id;
        if (editor.lspController != null) editor.lspController.stopServersForWorkspace(connection.workspace().localRoot());
        editor.remoteWorkspaceTaskTargets.unregister(connection.id());
        try {
            connection.workspace().close();
            editor.workspaceController.remove(connection.workspace().localRoot().toString());
            return "Remote workspace closed: " + connection.id();
        } catch (Exception error) {
            synchronized (connections) { connections.put(connection.id(), connection); }
            editor.remoteWorkspaceTaskTargets.register(connection.id(), connection.workspace());
            return "Remote workspace close failed: " + detail(error.getMessage());
        }
    }

    private String execute(List<String> tokens) {
        if (tokens.size() < 3) return "Usage: :remote exec <id> <command...>";
        Connection connection = connection(tokens.get(1));
        if (connection == null) return "Remote workspace not connected: " + tokens.get(1);
        List<String> command = List.copyOf(tokens.subList(2, tokens.size()));
        int job = editor.asyncJobService.submit("remote exec: " + connection.id(), token -> connection.workspace().execute(command),
            (snapshot, result, error) -> showExecutionResult(connection, result, error));
        return "Remote command requested (job " + job + ").";
    }

    private String openTerminal(List<String> tokens) {
        if (tokens.size() < 2) return "Usage: :remote terminal <id> [command...]";
        Connection connection = connection(tokens.get(1));
        if (connection == null) return "Remote workspace not connected: " + tokens.get(1);
        try {
            List<String> command = tokens.size() == 2 ? List.of() : List.copyOf(tokens.subList(2, tokens.size()));
            List<String> invocation = connection.workspace().terminalCommand(new shed.api.RemoteTerminalRequest("", command));
            if (invocation == null || invocation.isEmpty()) return "Remote terminal unavailable: provider returned no command";
            return editor.terminalController.openDirect("Remote " + connection.id(), connection.workspace().localRoot().toFile(), invocation);
        } catch (Exception error) {
            return "Remote terminal unavailable: " + detail(error.getMessage());
        }
    }

    private void showExecutionResult(Connection connection, RemoteCommandResult result, Exception error) {
        if (error != null) {
            editor.showMessage("Remote command failed: " + detail(error.getMessage()));
            return;
        }
        if (result == null) {
            editor.showMessage("Remote command failed: no result");
            return;
        }
        String output = capOutput(result.output());
        String content = "Remote command: " + connection.id() + "\nExit: " + result.exitCode() + "\n\n" + output;
        editor.showScratchBuffer("[remote exec " + connection.id() + "]", content);
        editor.showMessage(result.exitCode() == 0 ? "Remote command complete: " + connection.id()
            : "Remote command exited " + result.exitCode() + ": " + connection.id());
    }

    private String showStatus() {
        StringBuilder output = new StringBuilder("Remote Workspaces\n\n");
        List<Connection> values;
        synchronized (connections) { values = connections.values().stream().sorted(Comparator.comparing(Connection::id)).toList(); }
        if (values.isEmpty()) output.append("No remote workspaces connected.\n");
        for (Connection connection : values) {
            output.append(connection.id()).append("  ").append(connection.provider()).append("\n  ").append(connection.uri())
                .append("\n  local: ").append(connection.workspace().localRoot()).append("\n");
        }
        output.append("\nConnections use a local working tree. Pull and push are explicit operations; remote URI passwords are rejected.\n");
        editor.showScratchBuffer("[remote workspaces]", output.toString());
        return "Showing remote workspaces";
    }

    private String showProviders() {
        StringBuilder output = new StringBuilder("Remote Workspace Providers\n\n");
        for (RemoteWorkspaceProvider provider : providers()) output.append("  ").append(provider.id()).append("  ").append(provider.displayName()).append("\n");
        output.append("\nBuilt in: git/Git-over-HTTPS-or-SSH, ssh mirror, Docker container mirror, and WSL on Windows. Extensions can register more URI schemes.\n");
        editor.showScratchBuffer("[remote providers]", output.toString());
        return "Showing remote workspace providers";
    }

    void closeAll() {
        List<Connection> values;
        synchronized (connections) {
            values = new ArrayList<>(connections.values());
            connections.clear();
        }
        for (Connection connection : values) {
            if (editor.lspController != null) editor.lspController.stopServersForWorkspace(connection.workspace().localRoot());
            editor.remoteWorkspaceTaskTargets.unregister(connection.id());
            try { connection.workspace().close(); } catch (Exception ignored) { }
        }
    }

    RemoteLspEndpoint languageServerEndpoint(Path localWorkspace, List<String> serverCommand) throws IOException {
        Connection connection = connectionForLocalPath(localWorkspace);
        if (connection == null) return null;
        String remoteRoot = connection.workspace().languageServerRoot();
        if (remoteRoot == null || remoteRoot.isBlank()) return null;
        try {
            return new RemoteLspEndpoint(connection.workspace().localRoot(), remoteRoot, connection.workspace().languageServerCommand(serverCommand));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Remote language server is unavailable: " + detail(error.getMessage()), error);
        }
    }

    String remoteLanguageServerUri(Path localFile) {
        Connection connection = connectionForLocalPath(localFile);
        if (connection == null) return null;
        String remoteRoot = connection.workspace().languageServerRoot();
        if (remoteRoot == null || remoteRoot.isBlank()) return null;
        try {
            return new RemoteLspEndpoint(connection.workspace().localRoot(), remoteRoot, List.of()).uriFor(localFile);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    Path localPathForRemoteLanguageServerUri(String uri) {
        List<Connection> values;
        synchronized (connections) { values = List.copyOf(connections.values()); }
        for (Connection connection : values) {
            String remoteRoot = connection.workspace().languageServerRoot();
            if (remoteRoot == null || remoteRoot.isBlank()) continue;
            try {
                Path path = new RemoteLspEndpoint(connection.workspace().localRoot(), remoteRoot, List.of()).localPathFor(uri);
                if (path != null) return path;
            } catch (IllegalArgumentException ignored) {
                // A contributed provider cannot make unrelated LSP locations unopenable.
            }
        }
        return null;
    }

    private RemoteWorkspaceProvider providerFor(URI uri) {
        for (RemoteWorkspaceProvider provider : providers()) {
            try {
                if (provider.supports(uri)) return provider;
            } catch (RuntimeException ignored) {
                // A broken provider cannot claim the URI.
            }
        }
        return null;
    }

    private List<RemoteWorkspaceProvider> providers() {
        List<RemoteWorkspaceProvider> result = new ArrayList<>();
        if (editor.extensionManager != null) {
            for (ExtensionRegistry.Owned<RemoteWorkspaceProvider> provider : editor.extensionManager.remoteWorkspaceProviders()) result.add(provider.value());
        }
        result.addAll(BuiltInRemoteWorkspaceProviders.all());
        return List.copyOf(result);
    }

    private Connection connection(String id) {
        synchronized (connections) { return connections.get(normalizeId(id)); }
    }

    private Connection connectionForLocalPath(Path candidate) {
        Path path = candidate == null ? null : candidate.toAbsolutePath().normalize();
        if (path == null) return null;
        Connection selected = null;
        synchronized (connections) {
            for (Connection connection : connections.values()) {
                Path root = connection.workspace().localRoot();
                if (root == null) continue;
                Path normalized = root.toAbsolutePath().normalize();
                if (path.startsWith(normalized) && (selected == null || normalized.getNameCount() > selected.workspace().localRoot().getNameCount())) {
                    selected = connection;
                }
            }
        }
        return selected;
    }

    private static String connectionId(URI uri) {
        String value = uri.getScheme().toLowerCase(Locale.ROOT) + "-" + Integer.toUnsignedString(uri.normalize().toString().hashCode(), 36);
        return normalizeId(value);
    }

    private static String normalizeId(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String detail(String value) { return value == null || value.isBlank() ? "unknown error" : value.replace('\n', ' ').replace('\r', ' '); }
    private static String capOutput(String value) {
        String output = value == null ? "" : value;
        int maximum = 1024 * 1024;
        return output.length() <= maximum ? output : output.substring(0, maximum) + "\n[shed: output truncated]\n";
    }
}
