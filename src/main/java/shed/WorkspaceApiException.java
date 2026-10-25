package shed;

import java.util.Map;

/** Stable failures shared by workspace adapters. No transport relies on display-string parsing. */
public final class WorkspaceApiException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST, UNSUPPORTED, NOT_FOUND, OUTSIDE_WORKSPACE, PERMISSION_DENIED,
        PROTECTED_PATH, EXCLUDED_PATH, STALE_VERSION, CONFLICT, CANCELLED,
        DEFERRED_REQUIRES_FOREGROUND, LIMIT_EXCEEDED, IO_ERROR, NOT_CONNECTED
    }

    private final Code code;
    private final Map<String, Object> details;

    public WorkspaceApiException(Code code, String message) { this(code, message, Map.of()); }

    public WorkspaceApiException(Code code, String message, Map<String, Object> details) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code);
        this.details = Map.copyOf(details);
    }

    public Code code() { return code; }
    public Map<String, Object> details() { return details; }
    public Map<String, Object> toMap() {
        return Map.of("code", code.name(), "message", getMessage(), "details", details);
    }
}
