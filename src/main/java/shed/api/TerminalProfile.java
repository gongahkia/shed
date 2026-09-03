package shed.api;

import java.util.List;

/** A named direct-argv terminal profile. */
public record TerminalProfile(String id, String displayName, List<String> command) {
    public TerminalProfile {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("terminal profile id is invalid");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("terminal profile display name is required");
        }
        command = command == null ? List.of() : List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("terminal profile command is required");
        }
        for (String value : command) {
            if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("terminal profile command contains an invalid value");
            }
        }
    }
}
