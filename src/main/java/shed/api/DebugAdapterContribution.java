package shed.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A debugger adapter an extension makes available for explicit configuration. */
public record DebugAdapterContribution(
    String id,
    String displayName,
    List<String> command,
    List<String> arguments,
    Set<String> capabilities
) {
    public DebugAdapterContribution {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("debug adapter id is invalid");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("debug adapter display name is required");
        }
        command = validate(command, "command", true);
        arguments = validate(arguments, "argument", false);
        LinkedHashSet<String> normalizedCapabilities = new LinkedHashSet<>();
        for (String capability : capabilities == null ? Set.<String>of() : capabilities) {
            String normalized = capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if (!normalized.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("invalid debug capability: " + capability);
            }
            normalizedCapabilities.add(normalized);
        }
        capabilities = Set.copyOf(normalizedCapabilities);
    }

    private static List<String> validate(List<String> values, String label, boolean required) {
        List<String> normalized = values == null ? List.of() : List.copyOf(values);
        if (required && normalized.isEmpty()) {
            throw new IllegalArgumentException("debug adapter command is required");
        }
        for (String value : normalized) {
            if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("debug adapter " + label + " must be a non-empty single-line value");
            }
        }
        return normalized;
    }
}
