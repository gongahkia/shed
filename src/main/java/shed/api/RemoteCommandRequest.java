package shed.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * A direct-argv command scoped to a connected remote workspace.
 *
 * <p>The working directory is relative to that workspace's root. This avoids
 * leaking a local mirror path into a provider's remote command implementation.</p>
 */
public record RemoteCommandRequest(List<String> command, String relativeWorkingDirectory, Map<String, String> environment) {
    public RemoteCommandRequest {
        command = List.copyOf(command == null ? List.of() : command);
        if (command.isEmpty()) throw new IllegalArgumentException("remote command is required");
        for (int index = 0; index < command.size(); index++) {
            String argument = command.get(index);
            if (argument == null || hasControl(argument) || (index == 0 && argument.isBlank())) {
                throw new IllegalArgumentException("remote command contains an invalid argument");
            }
        }
        relativeWorkingDirectory = normalizeDirectory(relativeWorkingDirectory);
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : (environment == null ? Map.<String, String>of() : environment).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("remote environment name is invalid");
            }
            if (value == null || hasControl(value)) {
                throw new IllegalArgumentException("remote environment value is invalid");
            }
            values.put(key, value);
        }
        environment = Collections.unmodifiableMap(values);
    }

    private static String normalizeDirectory(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        if (normalized.isEmpty() || ".".equals(normalized)) return "";
        if (normalized.startsWith("/") || hasControl(normalized)) {
            throw new IllegalArgumentException("remote working directory must be relative");
        }
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("remote working directory must not traverse the workspace");
            }
        }
        return normalized;
    }

    private static boolean hasControl(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }
}
