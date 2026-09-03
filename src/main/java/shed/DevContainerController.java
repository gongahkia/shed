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

/** Explicit local Dev Container CLI integration; it never installs or starts a container implicitly. */
final class DevContainerController {
    private static final int OUTPUT_LIMIT_BYTES = 128 * 1024;
    private final Texteditor editor;

    DevContainerController(Texteditor editor) {
        this.editor = editor;
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
            case "exec", "run" -> execute(tokens.subList(1, tokens.size()));
            case "terminal", "shell" -> openTerminal(tokens.subList(1, tokens.size()));
            case "open" -> openMirror(tokens.subList(1, tokens.size()));
            default -> "Usage: :container [status|up|exec <command...>|terminal [command...]|open <container> <absolute-path>]";
        };
    }

    private String showConfiguration() {
        Path configuration = configuration();
        if (configuration == null) return "No .devcontainer/devcontainer.json in the active workspace";
        try {
            String text = Files.readString(configuration, StandardCharsets.UTF_8);
            String output = "Dev Container\n\nWorkspace: " + workspace() + "\nConfiguration: " + configuration
                + "\n\nDev Container actions are explicit and require an installed devcontainer CLI.\n"
                + "\n:container up\n:container exec <command...>\n:container terminal [command...]\n"
                + "\nConfiguration:\n\n" + text;
            editor.showScratchBuffer("[dev container]", output);
            return "Showing Dev Container configuration";
        } catch (IOException error) {
            return "Could not read Dev Container configuration: " + concise(error);
        }
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
        Path active = editor.workspaceController.activeRoot();
        if (active != null) return active;
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer != null && buffer.getFile() != null && buffer.getFile().getParentFile() != null) {
            return buffer.getFile().getParentFile().toPath().toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private Path configuration() {
        Path candidate = workspace().resolve(".devcontainer").resolve("devcontainer.json").normalize();
        return Files.isRegularFile(candidate) ? candidate : null;
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

    private static String concise(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
