package shed;

record MultiSelectionPolicy(boolean enabled, int maxCursors) {
    static final int DEFAULT_MAX_CURSORS = 16;
    static final int MIN_MAX_CURSORS = 2;
    static final int MAX_MAX_CURSORS = 256;

    MultiSelectionPolicy {
        if (maxCursors < MIN_MAX_CURSORS || maxCursors > MAX_MAX_CURSORS) {
            throw new IllegalArgumentException("invalid multi-selection cursor limit");
        }
    }
}
