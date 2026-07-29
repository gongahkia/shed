package shed;

record MultiSelection(int start, int end) {
    MultiSelection {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid multi-selection range");
        }
    }

    static MultiSelection caret(int offset) {
        return new MultiSelection(offset, offset);
    }

    boolean collapsed() {
        return start == end;
    }
}
