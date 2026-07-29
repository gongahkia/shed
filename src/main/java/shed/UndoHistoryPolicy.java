package shed;

public record UndoHistoryPolicy(int maxEntries, long maxBytes) {
    public static final int DEFAULT_MAX_ENTRIES = 500;
    public static final long DEFAULT_MAX_BYTES = 8L * 1024L * 1024L;
    public static final int MAX_ENTRIES = 100_000;
    public static final long MAX_BYTES = 1024L * 1024L * 1024L;

    public UndoHistoryPolicy {
        if (maxEntries < 1 || maxEntries > MAX_ENTRIES) {
            throw new IllegalArgumentException("maxEntries must be between 1 and " + MAX_ENTRIES);
        }
        if (maxBytes < 1 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("maxBytes must be between 1 and " + MAX_BYTES);
        }
    }

    public static UndoHistoryPolicy defaults() {
        return new UndoHistoryPolicy(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
    }
}
