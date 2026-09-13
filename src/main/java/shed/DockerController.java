package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Explicit local Docker Engine actions. Every command is direct argv and runs as an observable async job. */
final class DockerController {
    private static final int DEFAULT_LOG_LINES = 500;
    private static final int MAX_LOG_LINES = 10_000;
    private final Texteditor editor;

    DockerController(Texteditor editor) {
        this.editor = editor;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value) || "ps".equalsIgnoreCase(value) || "status".equalsIgnoreCase(value)) {
            return submit("list", List.of("docker", "container", "ls", "--all", "--format", "table {{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"));
        }
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return "Docker command invalid: " + error.getMessage();
        }
        String operation = tokens.getFirst().toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "inspect" -> inspect(tokens);
            case "start", "stop", "restart" -> lifecycle(operation, tokens);
            case "logs", "log" -> logs(tokens);
            case "exec", "run" -> execute(tokens);
            case "terminal", "shell" -> terminal(tokens);
            case "open", "connect" -> openWorkspace(tokens);
            default -> usage();
        };
    }

    private String inspect(List<String> tokens) {
        if (tokens.size() != 2 || !container(tokens.get(1))) return "Usage: :docker inspect <container>";
        return submit("inspect", List.of("docker", "container", "inspect", tokens.get(1)));
    }

    private String lifecycle(String operation, List<String> tokens) {
        if (tokens.size() != 2 || !container(tokens.get(1))) return "Usage: :docker " + operation + " <container>";
        return submit(operation, List.of("docker", "container", operation, tokens.get(1)));
    }

    private String logs(List<String> tokens) {
        if (tokens.size() < 2 || tokens.size() > 3 || !container(tokens.get(1))) return "Usage: :docker logs <container> [lines]";
        int lines = DEFAULT_LOG_LINES;
        if (tokens.size() == 3) {
            try {
                lines = Integer.parseInt(tokens.get(2));
            } catch (NumberFormatException error) {
                return "Docker log line count must be between 1 and " + MAX_LOG_LINES;
            }
            if (lines < 1 || lines > MAX_LOG_LINES) return "Docker log line count must be between 1 and " + MAX_LOG_LINES;
        }
        return submit("logs", List.of("docker", "container", "logs", "--tail", Integer.toString(lines), tokens.get(1)));
    }

    private String execute(List<String> tokens) {
        if (tokens.size() < 3 || !container(tokens.get(1))) return "Usage: :docker exec <container> <command...>";
        List<String> command = new ArrayList<>(List.of("docker", "container", "exec", tokens.get(1)));
        command.addAll(tokens.subList(2, tokens.size()));
        return submit("exec", command);
    }

    private String terminal(List<String> tokens) {
        if (tokens.size() < 2 || !container(tokens.get(1))) return "Usage: :docker terminal <container> [command...]";
        List<String> command = new ArrayList<>(List.of("docker", "container", "exec", "-it", tokens.get(1)));
        command.addAll(tokens.size() == 2 ? List.of("/bin/sh") : tokens.subList(2, tokens.size()));
        return editor.terminalController.openDirect("Docker " + tokens.get(1), workspace().toFile(), command);
    }

    private String openWorkspace(List<String> tokens) {
        if (tokens.size() != 3 || !container(tokens.get(1)) || !tokens.get(2).startsWith("/")) {
            return "Usage: :docker open <container> <absolute-container-path>";
        }
        return editor.handleRemoteWorkspaceCommand("open container://" + tokens.get(1) + tokens.get(2));
    }

    private String submit(String operation, List<String> command) {
        int job = editor.asyncJobService.submit("docker " + operation, token -> run(command, token),
            (snapshot, output, error) -> complete(operation, output, error));
        return "Docker " + operation + " requested (job " + job + ").";
    }

    private void complete(String operation, String output, Exception error) {
        String heading = "Docker " + operation;
        String content = error == null ? output : heading + " failed: " + concise(error);
        editor.showScratchBuffer("[docker " + operation + "]", content == null || content.isBlank() ? "(no output)\n" : content);
        editor.showMessage(error == null ? heading + " completed" : heading + " failed");
    }

    private static String run(List<String> command, AsyncJobService.JobToken token) throws Exception {
        Path output = Files.createTempFile("shed-docker-", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            token.onCancel(process::destroyForcibly);
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Docker command timed out after 15 minutes");
            }
            String text = DevContainerRuntime.readCapped(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? "docker exited " + process.exitValue() : text.strip());
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker command interrupted", error);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private Path workspace() {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (editor.workspaceController != null && buffer != null && buffer.getFile() != null) {
            Path root = editor.workspaceController.rootFor(buffer.getFile().toPath());
            if (root != null) return root;
        }
        Path active = editor.workspaceController == null ? null : editor.workspaceController.activeRoot();
        if (active != null) return active;
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static boolean container(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*");
    }

    private static String concise(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String usage() {
        return "Usage: :docker [list|inspect <container>|start|stop|restart <container>|logs <container> [lines]|exec <container> <command...>|terminal <container> [command...]|open <container> <absolute-container-path>]";
    }
}
