package shed;

import java.util.Objects;

/** Half-open UTF-16 offsets into one captured snapshot, never file-byte or visual columns. */
record MarkdownSourceRange(int start, int end) {
    MarkdownSourceRange {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source range");
    }

    int length() { return end - start; }

    MarkdownSourceRange validate(VersionedTextSnapshot source) {
        Objects.requireNonNull(source);
        if (end > source.length()) throw new IllegalArgumentException("Range exceeds captured source");
        checkBoundary(source, start);
        checkBoundary(source, end);
        return this;
    }

    String text(VersionedTextSnapshot source) {
        validate(source);
        return source.substring(start, length());
    }

    static MarkdownSourceRange at(VersionedTextSnapshot source, int line, int character) {
        Objects.requireNonNull(source);
        if (line < 0 || line >= source.lineCount() || character < 0
                || character > source.lineEndOffset(line) - source.lineStartOffset(line)) {
            throw new IllegalArgumentException("Position exceeds captured source");
        }
        int offset = source.lineStartOffset(line) + character;
        return new MarkdownSourceRange(offset, offset).validate(source);
    }

    private static void checkBoundary(VersionedTextSnapshot source, int offset) {
        if (offset > 0 && offset < source.length() && Character.isHighSurrogate(source.charAt(offset - 1))
                && Character.isLowSurrogate(source.charAt(offset))) {
            throw new IllegalArgumentException("Range splits a surrogate pair");
        }
    }
}
