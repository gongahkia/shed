package shed;

/** Preserves a peer's structured JSON-RPC failure without parsing its display message. */
final class AcpException extends RuntimeException {
    private final long code;
    private final Object data;
    AcpException(long code, String message) { this(code, message, null); }
    AcpException(long code, String message, Object data) { super(message); this.code = code; this.data = data; }
    long code() { return code; }
    Object data() { return data; }
}
