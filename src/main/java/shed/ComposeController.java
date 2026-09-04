package shed;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit local Docker Compose bridge; it neither discovers nor changes stacks in the background. */
final class ComposeController {
    private static final List<String> CONFIGURATION_NAMES = List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");
    private static final int CONFIGURATION_LIMIT_BYTES = 512 * 1024;
    private final Texteditor editor;

    ComposeController(Texteditor editor) {
        this.editor = editor;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        Path root = workspace();
        Path configuration = configuration(root);
        if (value.isEmpty() || "status".equalsIgnoreCase(value) || "config".equalsIgnoreCase(value)) return showConfiguration(root, configuration);
        if (configuration == null) return "No local Compose configuration in: " + root;
        List<String> tokens;
        try {
            tokens = ShellCommand.directCommand(value);
        } catch (IllegalArgumentException error) {
            return "Compose command invalid: " + error.getMessage();
        }
        String operation = tokens.getFirst().toLowerCase(Locale.ROOT);
        try {
            return switch (operation) {
                case "up", "start" -> submit("up", configuration, appendServices(List.of("up", "-d"), tokens.subList(1, tokens.size())));
                case "build" -> submit("build", configuration, appendServices(List.of("build"), tokens.subList(1, tokens.size())));
                case "ps" -> tokens.size() == 1 ? submit("ps", configuration, List.of("ps")) : "Usage: :compose ps";
                case "services" -> tokens.size() == 1 ? submit("services", configuration, List.of("config", "--services")) : "Usage: :compose services";
                case "logs" -> submit("logs", configuration, appendServices(List.of("logs", "--tail", "200"), tokens.subList(1, tokens.size())));
                case "exec" -> execute(configuration, tokens.subList(1, tokens.size()));
                case "terminal", "shell" -> openTerminal(configuration, tokens.subList(1, tokens.size()));
                case "down", "stop" -> tokens.size() == 1
                    ? submit("down", configuration, List.of("down"))
                    : "Usage: :compose down";
                case "redeploy" -> redeploy(configuration, tokens.subList(1, tokens.size()));
                default -> "Usage: :compose [status|up [service...]|build [service...]|ps|logs [service...]|exec <service> <command...>|terminal <service> [command...]|redeploy <service>|down]";
            };
        } catch (IllegalArgumentException error) {
            return "Compose command invalid: " + error.getMessage();
        }
    }

    static Path configuration(Path workspace) {
        if (workspace == null) return null;
        Path root = workspace.toAbsolutePath().normalize();
        for (String name : CONFIGURATION_NAMES) {
            Path candidate = root.resolve(name).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    static List<String> invocation(Path configuration, List<String> action) {
        if (configuration == null || action == null || action.isEmpty()) throw new IllegalArgumentException("Compose configuration and action are required");
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-f", configuration.toAbsolutePath().normalize().toString()));
        command.addAll(action);
        return List.copyOf(command);
    }

    private String showConfiguration(Path root, Path configuration) {
        StringBuilder output = new StringBuilder("Docker Compose\n\nWorkspace: ").append(root).append("\n");
        if (configuration == null) {
            output.append("\nNo compose.yaml, compose.yml, docker-compose.yaml, or docker-compose.yml at this workspace root.\n");
        } else {
            output.append("Configuration: ").append(configuration).append("\n\n");
            try {
                if (Files.size(configuration) > CONFIGURATION_LIMIT_BYTES) {
                    output.append("Configuration is larger than 512 KiB and is not shown.\n");
                } else {
                    output.append(Files.readString(configuration, StandardCharsets.UTF_8));
                }
            } catch (IOException error) {
                output.append("Could not read configuration: ").append(concise(error)).append("\n");
            }
        }
        output.append("\nCompose actions are explicit and use the installed Docker CLI.\n")
            .append(":compose up [service...]\n:compose build [service...]\n:compose ps\n:compose logs [service...]\n")
            .append(":compose exec <service> <command...>\n:compose terminal <service> [command...]\n")
            .append(":compose redeploy <service>\n:compose down\n");
        editor.showScratchBuffer("[docker compose]", output.toString());
        return configuration == null ? "No Compose configuration" : "Showing Compose configuration";
    }

    private String execute(Path configuration, List<String> arguments) {
        if (arguments.size() < 2 || !service(arguments.getFirst())) return "Usage: :compose exec <service> <command...>";
        List<String> action = new ArrayList<>(List.of("exec", "-T", arguments.getFirst()));
        action.addAll(arguments.subList(1, arguments.size()));
        return submit("exec " + arguments.getFirst(), configuration, action);
    }

    private String openTerminal(Path configuration, List<String> arguments) {
        if (arguments.isEmpty() || !service(arguments.getFirst())) return "Usage: :compose terminal <service> [command...]";
        List<String> action = new ArrayList<>(List.of("exec", arguments.getFirst()));
        action.addAll(arguments.size() == 1 ? List.of("/bin/sh") : arguments.subList(1, arguments.size()));
        return editor.terminalController.openDirect("Compose " + arguments.getFirst(), workspace().toFile(), invocation(configuration, action));
    }

    private String redeploy(Path configuration, List<String> arguments) {
        if (arguments.size() != 1 || !service(arguments.getFirst())) return "Usage: :compose redeploy <service>";
        String service = arguments.getFirst();
        Path root = workspace();
        int job = editor.asyncJobService.submit("compose redeploy " + service, token -> {
            CommandResult build = editor.runExternalCommand(invocation(configuration, List.of("build", service)), root.toFile(), null, token,
                editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true);
            if (build.exitCode != 0 || token.isCancelled()) return build;
            return editor.runExternalCommand(invocation(configuration, List.of("up", "--no-deps", "-d", service)), root.toFile(), null, token,
                editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true);
        }, (snapshot, result, error) -> complete("redeploy " + service, snapshot, result, error));
        return "Compose redeploy requested (job " + job + ").";
    }

    private String submit(String operation, Path configuration, List<String> action) {
        Path root = workspace();
        int job = editor.asyncJobService.submit("compose " + operation, token -> editor.runExternalCommand(invocation(configuration, action), root.toFile(), null,
            token, editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), true),
            (snapshot, result, error) -> complete(operation, snapshot, result, error));
        return "Compose " + operation + " requested (job " + job + ").";
    }

    private void complete(String operation, AsyncJobService.JobSnapshot snapshot, CommandResult result, Exception error) {
        if (editor.closingDown) return;
        if (snapshot != null && snapshot.getStatus() == AsyncJobService.Status.CANCELLED) {
            editor.showMessage("Compose " + operation + " cancelled");
            return;
        }
        String output = output(result);
        if (!output.isBlank()) editor.showScratchBuffer("[compose " + operation + "]", output + "\n");
        if (error != null || result == null || result.exitCode != 0) {
            String detail = error != null ? concise(error) : result == null ? "unknown error" : output.isBlank() ? "exit " + result.exitCode : output.strip();
            editor.showMessage("Compose " + operation + " failed: " + detail);
        } else {
            editor.showMessage("Compose " + operation + " completed");
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
        if (buffer != null && buffer.getFile() != null && buffer.getFile().getParentFile() != null) {
            return buffer.getFile().getParentFile().toPath().toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static List<String> appendServices(List<String> base, List<String> services) {
        List<String> result = new ArrayList<>(base);
        for (String service : services) {
            if (!service(service)) throw new IllegalArgumentException("Compose service name is invalid: " + service);
            result.add(service);
        }
        return List.copyOf(result);
    }

    private static boolean service(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*");
    }

    private static String output(CommandResult result) {
        if (result == null) return "";
        if (result.stderr.isBlank() || result.stderr.equals(result.stdout)) return result.stdout;
        return result.stdout.isBlank() ? result.stderr : result.stdout + "\n" + result.stderr;
    }

    private static String concise(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error == null ? "unknown error" : error.getClass().getSimpleName()
            : message.replace('\n', ' ').replace('\r', ' ');
    }
}
