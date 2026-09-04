package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Narrow, direct-argv boundary for explicit processes in an already-running Dev Container. */
final class DevContainerRuntime {
    private static final int OUTPUT_LIMIT_BYTES = 128 * 1024;

    private DevContainerRuntime() {
    }

    static boolean hasConfiguration(Path workspace) {
        if (workspace == null) return false;
        return Files.isRegularFile(workspace.toAbsolutePath().normalize().resolve(".devcontainer").resolve("devcontainer.json"));
    }

    static String remoteWorkingDirectory(Path workspace) throws IOException {
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (root == null || !hasConfiguration(root)) throw new IOException("Dev Container workspace requires .devcontainer/devcontainer.json");
        return DevContainerWorkspace.remoteWorkingDirectory(probeWorkspaceRoot(root));
    }

    static List<String> languageServerInvocation(Path workspace, List<String> command) throws IOException {
        return processInvocation(workspace, command, "language server");
    }

    static List<String> debugAdapterInvocation(Path workspace, List<String> command) throws IOException {
        return processInvocation(workspace, command, "debug adapter");
    }

    static List<String> testInvocation(Path workspace, List<String> command) throws IOException {
        return processInvocation(workspace, command, "test command");
    }

    static List<String> terminalInvocation(Path workspace, List<String> command) throws IOException {
        return processInvocation(workspace, command, "terminal");
    }

    static String readCapped(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int accepted = Math.min(read, OUTPUT_LIMIT_BYTES - total);
                if (accepted > 0) output.write(buffer, 0, accepted);
                total += read;
                if (total > OUTPUT_LIMIT_BYTES) break;
            }
            String value = output.toString(StandardCharsets.UTF_8);
            return total > OUTPUT_LIMIT_BYTES ? value + "\n[shed: output truncated]\n" : value;
        }
    }

    private static List<String> processInvocation(Path workspace, List<String> processCommand, String purpose) throws IOException {
        if (workspace == null) throw new IOException("Dev Container workspace is required");
        List<String> command = new ArrayList<>(List.of("devcontainer", "exec", "--workspace-folder", workspace.toAbsolutePath().normalize().toString()));
        if (processCommand == null || processCommand.isEmpty() || processCommand.getFirst() == null || processCommand.getFirst().isBlank()) {
            throw new IOException("Dev Container " + purpose + " command is required");
        }
        for (String argument : processCommand) {
            if (argument == null || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new IOException("Dev Container " + purpose + " command is invalid");
            }
        }
        command.addAll(processCommand);
        return List.copyOf(command);
    }

    private static String probeWorkspaceRoot(Path workspace) throws IOException {
        Path output = Files.createTempFile("shed-devcontainer-probe-", ".log");
        try {
            Process process = new ProcessBuilder("devcontainer", "exec", "--workspace-folder", workspace.toString(), "pwd")
                .directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Dev Container workspace probe timed out after 5 seconds");
            }
            String text = readCapped(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? "devcontainer exited " + process.exitValue() : text.strip());
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Dev Container workspace probe interrupted", error);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
