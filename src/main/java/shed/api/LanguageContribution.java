package shed.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Declarative language identity and optional Language Server launch details. */
public record LanguageContribution(
    String id,
    String displayName,
    Set<String> fileExtensions,
    List<String> serverCommand,
    List<String> serverArguments
) {
    public LanguageContribution {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("language id must contain only letters, numbers, '.', '_' or '-'");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("language display name is required");
        }
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (String extension : fileExtensions == null ? Set.<String>of() : fileExtensions) {
            String normalized = extension == null ? "" : extension.trim().replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9][a-z0-9+_-]*")) {
                throw new IllegalArgumentException("invalid file extension: " + extension);
            }
            extensions.add(normalized);
        }
        if (extensions.isEmpty()) {
            throw new IllegalArgumentException("at least one file extension is required");
        }
        fileExtensions = Set.copyOf(extensions);
        serverCommand = validateArguments(serverCommand, "server command");
        serverArguments = validateArguments(serverArguments, "server argument");
    }

    private static List<String> validateArguments(List<String> values, String label) {
        List<String> normalized = values == null ? List.of() : List.copyOf(values);
        for (String value : normalized) {
            if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(label + " must contain non-empty single-line values");
            }
        }
        return normalized;
    }
}
