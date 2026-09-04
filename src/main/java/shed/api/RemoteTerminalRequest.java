package shed.api;

import java.util.List;

/** An explicitly requested interactive terminal scoped to a connected workspace. */
public record RemoteTerminalRequest(String relativeWorkingDirectory, List<String> command) {
    public RemoteTerminalRequest {
        relativeWorkingDirectory = normalizeDirectory(relativeWorkingDirectory);
        command = List.copyOf(command == null ? List.of() : command);
        for (int index = 0; index < command.size(); index++) {
            String argument = command.get(index);
            if (argument == null || hasControl(argument) || (index == 0 && argument.isBlank())) {
                throw new IllegalArgumentException("remote terminal command contains an invalid argument");
            }
        }
    }

    private static String normalizeDirectory(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        if (normalized.isEmpty() || ".".equals(normalized)) return "";
        if (normalized.startsWith("/") || hasControl(normalized)) {
            throw new IllegalArgumentException("remote terminal directory must be relative");
        }
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("remote terminal directory must not traverse the workspace");
            }
        }
        return normalized;
    }

    private static boolean hasControl(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }
}
