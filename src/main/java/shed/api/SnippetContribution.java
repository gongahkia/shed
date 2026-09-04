package shed.api;

/** One bounded TextMate-subset snippet supplied by a local Java extension. */
public record SnippetContribution(String id, String languageId, String trigger, String body, String description) {
    private static final int MAX_BODY_LENGTH = 64 * 1024;

    public SnippetContribution {
        id = identifier(id, "snippet id");
        languageId = identifier(languageId, "language id");
        trigger = trigger == null ? "" : trigger.trim();
        if (!trigger.matches("[A-Za-z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException("snippet trigger is invalid");
        }
        if (body == null || body.isEmpty() || body.length() > MAX_BODY_LENGTH || body.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("snippet body must be non-empty, NUL-free, and at most 64 KiB");
        }
        description = description == null || description.isBlank() ? id : description.trim();
        if (description.length() > 240 || description.indexOf('\0') >= 0 || description.indexOf('\n') >= 0 || description.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("snippet description must be single-line and at most 240 characters");
        }
    }

    private static String identifier(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) throw new IllegalArgumentException(label + " is invalid");
        return normalized;
    }
}
