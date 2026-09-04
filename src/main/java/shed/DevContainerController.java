package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Explicit local Dev Container CLI integration; it never installs or starts a container implicitly. */
final class DevContainerController {
    private final Texteditor editor;
    private final DevContainerSessionService sessions;
    private final Set<Path> pendingConnections = new HashSet<>();

    DevContainerController(Texteditor editor) {
        this.editor = editor;
        this.sessions = editor.devContainerSessions;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "status".equalsIgnoreCase(value) || "config".equalsIgnoreCase(value)) return showConfiguration();
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return "Container command invalid: " + error.getMessage();
        }
        String operation = tokens.getFirst().toLowerCase(java.util.Locale.ROOT);
        return switch (operation) {
            case "up", "start" -> submit("up", List.of("devcontainer", "up", "--workspace-folder", workspace().toString()));
            case "connect", "reopen" -> connect();
            case "disconnect" -> disconnect();
            case "exec", "run" -> execute(tokens.subList(1, tokens.size()));
            case "terminal", "shell" -> openTerminal(tokens.subList(1, tokens.size()));
            case "open" -> openMirror(tokens.subList(1, tokens.size()));
            default -> "Usage: :container [status|up|connect|disconnect|exec <command...>|terminal [command...]|open <container> <absolute-path>]";
        };
    }

    private String showConfiguration() {
        Path configuration = configuration();
        if (configuration == null) return "No .devcontainer/devcontainer.json in the active workspace";
        try {
            String text = Files.readString(configuration, StandardCharsets.UTF_8);
            DevContainerSessionService.Connection connection = sessions == null ? null : sessions.connectionFor(workspace());
            String output = "Dev Container\n\nWorkspace: " + workspace() + "\nConfiguration: " + configuration
                + "\nConnection: " + (connection == null ? "not connected" : "connected for this application session")
                + (connection == null ? "\nRun :container connect to route normal terminals and tasks through this container.\n"
                    : "\nContainer workspace: " + connection.remoteWorkingDirectory() + "\n")
                + "\nDev Container actions are explicit and require an installed devcontainer CLI.\n"
                + "\n:container up\n:container connect\n:container disconnect\n:container exec <command...>\n:container terminal [command...]\n"
                + "\nConfiguration:\n\n" + text;
            editor.showScratchBuffer("[dev container]", output);
            return "Showing Dev Container configuration";
        } catch (IOException error) {
            return "Could not read Dev Container configuration: " + concise(error);
        }
    }

    private String connect() {
        Path root = workspace().toAbsolutePath().normalize();
        if (configuration() == null) return "No .devcontainer/devcontainer.json in the active workspace";
        if (sessions != null && sessions.isConnected(root)) return "Dev Container is already connected for this workspace";
        if (!pendingConnections.add(root)) return "Dev Container connection is already requested";
        int job = editor.asyncJobService.submit("dev container connect", token -> connect(root, token),
            (snapshot, result, error) -> completeConnect(root, result, error));
        return "Dev Container connection requested (job " + job + ").";
    }

    private ConnectResult connect(Path root, AsyncJobService.JobToken token) throws Exception {
        String startup = run(List.of("devcontainer", "up", "--workspace-folder", root.toString()), root, token);
        String remoteRoot = DevContainerWorkspace.remoteWorkingDirectory(run(
            List.of("devcontainer", "exec", "--workspace-folder", root.toString(), "pwd"), root, token));
        return new ConnectResult(root, remoteRoot, startup);
    }

    private void completeConnect(Path root, ConnectResult result, Exception error) {
        pendingConnections.remove(root);
        if (error != null || result == null || sessions == null) {
            String detail = error == null ? "Dev Container connection returned no result" : concise(error);
            editor.showScratchBuffer("[dev container connect]", detail + "\n");
            editor.showMessage("Dev Container connection failed");
            return;
        }
        try {
            sessions.connect(result.workspace(), result.remoteWorkingDirectory());
            String output = result.startupOutput().isBlank() ? "(no startup output)\n" : result.startupOutput();
            editor.showScratchBuffer("[dev container connect]", "Connected workspace: " + result.workspace()
                + "\nContainer workspace: " + result.remoteWorkingDirectory() + "\n\n" + output);
            editor.showMessage("Dev Container connected; new terminals and tasks use it");
        } catch (IllegalArgumentException failure) {
            editor.showScratchBuffer("[dev container connect]", "Connection rejected: " + concise(failure) + "\n");
            editor.showMessage("Dev Container connection failed");
        }
    }

    private String disconnect() {
        Path root = workspace().toAbsolutePath().normalize();
        if (sessions == null || !sessions.disconnect(root)) return "Dev Container is not connected for this workspace";
        return "Dev Container disconnected; the container remains running";
    }

    private String execute(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) return "Usage: :container exec <command...>";
        List<String> command = prefix("exec");
        command.addAll(arguments);
        return submit("exec", command);
    }

    private String openTerminal(List<String> arguments) {
        List<String> command = prefix("exec");
        command.addAll(arguments == null || arguments.isEmpty() ? List.of("/bin/sh") : arguments);
        return editor.terminalController.openDirect("Dev Container", workspace().toFile(), command);
    }

    private String openMirror(List<String> arguments) {
        if (arguments == null || arguments.size() != 2 || !arguments.get(1).startsWith("/")) {
            return "Usage: :container open <container> <absolute-path>";
        }
        String container = arguments.getFirst();
        if (!container.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return "Container name is invalid";
        return editor.handleRemoteWorkspaceCommand("open container://" + container + arguments.get(1));
    }

    private String submit(String operation, List<String> command) {
        if (configuration() == null) return "No .devcontainer/devcontainer.json in the active workspace";
        Path root = workspace();
        int job = editor.asyncJobService.submit("dev container " + operation, token -> run(command, root, token),
            (snapshot, output, error) -> {
                String result = error == null ? output : "Dev Container " + operation + " failed: " + concise(error);
                editor.showScratchBuffer("[dev container " + operation + "]", result == null || result.isBlank() ? "(no output)\n" : result);
                editor.showMessage(error == null ? "Dev Container " + operation + " completed" : "Dev Container " + operation + " failed");
            });
        return "Dev Container " + operation + " requested (job " + job + ").";
    }

    private List<String> prefix(String subcommand) {
        return new ArrayList<>(List.of("devcontainer", subcommand, "--workspace-folder", workspace().toString()));
    }

    private Path workspace() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (editor.workspaceController != null && buffer != null && buffer.getFile() != null) {
            Path root = editor.workspaceController.rootFor(buffer.getFile().toPath());
            if (root != null) return root;
        }
        Path active = editor.workspaceController == null ? null : editor.workspaceController.activeRoot();
        if (active != null) return active;
        if (buffer != null && buffer.getFile() != null && buffer.getFile().getParentFile() != null) {
            return buffer.getFile().getParentFile().toPath().toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private Path configuration() {
        Path candidate = workspace().resolve(".devcontainer").resolve("devcontainer.json").normalize();
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    boolean hasConfiguration(Path workspace) {
        return DevContainerRuntime.hasConfiguration(workspace);
    }

    RemoteLspEndpoint languageServerEndpoint(Path workspace, List<String> serverCommand) throws IOException {
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (root == null || !hasConfiguration(root)) throw new IOException("Dev Container language server requires .devcontainer/devcontainer.json");
        String remoteRoot = remoteWorkingDirectory(root);
        return new RemoteLspEndpoint(root, remoteRoot, languageServerInvocation(root, serverCommand));
    }

    /** Builds an explicit stdio DAP bridge after verifying the running container's mounted workspace root. */
    RemoteDebugEndpoint debugAdapterEndpoint(Path workspace, List<String> adapterCommand) throws IOException {
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (root == null || !hasConfiguration(root)) throw new IOException("Dev Container debug adapter requires .devcontainer/devcontainer.json");
        String remoteRoot = remoteWorkingDirectory(root);
        return new RemoteDebugEndpoint(root, remoteRoot, debugAdapterInvocation(root, adapterCommand));
    }

    /** Probes the already-running container only for an explicit operation that needs its mounted workspace path. */
    String remoteWorkingDirectory(Path workspace) throws IOException {
        return DevContainerRuntime.remoteWorkingDirectory(workspace);
    }

    /** Inspection must not start the CLI or probe the container. */
    boolean supportsDebugAdapter(Path workspace, List<String> adapterCommand) {
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (root == null || !hasConfiguration(root)) return false;
        try {
            debugAdapterInvocation(root, adapterCommand);
            return true;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    static List<String> languageServerInvocation(Path workspace, List<String> serverCommand) throws IOException {
        return DevContainerRuntime.languageServerInvocation(workspace, serverCommand);
    }

    static List<String> debugAdapterInvocation(Path workspace, List<String> adapterCommand) throws IOException {
        return DevContainerRuntime.debugAdapterInvocation(workspace, adapterCommand);
    }

    static List<String> testInvocation(Path workspace, List<String> testCommand) throws IOException {
        return DevContainerRuntime.testInvocation(workspace, testCommand);
    }

    private static String run(List<String> command, Path root, AsyncJobService.JobToken token) throws Exception {
        Path output = Files.createTempFile("shed-devcontainer-", ".log");
        try {
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            token.onCancel(process::destroyForcibly);
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Dev Container command timed out after 15 minutes");
            }
            String text = readCapped(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? "devcontainer exited " + process.exitValue() : text.strip());
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Dev Container command interrupted", error);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    static String readCapped(Path path) throws IOException {
        return DevContainerRuntime.readCapped(path);
    }

    private static String concise(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private record ConnectResult(Path workspace, String remoteWorkingDirectory, String startupOutput) {
    }
}
