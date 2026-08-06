package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class VersionedTextSnapshotTest {
    @Test
    void tracksLinesPositionsAndReplacementAcrossChunkBoundaries() {
        String source = "alpha\nbeta\ngamma\n" + "x".repeat(9000) + "\nomega";
        VersionedTextSnapshot snapshot = VersionedTextSnapshot.of(source);

        assertEquals(source, snapshot.text());
        assertEquals(5, snapshot.lineCount());
        assertEquals(new VersionedTextSnapshot.Position(1, 2), snapshot.positionAt(8));
        assertEquals(11, snapshot.lineStartOffset(2));
        assertEquals(16, snapshot.lineEndOffset(2));
        assertEquals("gamma", snapshot.line(2));

        VersionedTextSnapshot updated = snapshot.replace(6, 4, "B\nB");
        assertEquals("alpha\nB\nB\ngamma\n" + "x".repeat(9000) + "\nomega", updated.text());
        assertEquals(snapshot.version() + 1, updated.version());
        assertEquals(new VersionedTextSnapshot.Position(3, 0), updated.positionAt(10));
    }

    @Test
    void agreesWithStringReferenceAfterRandomizedEdits() {
        Random random = new Random(7364519L);
        String reference = "first\nsecond\nthird";
        VersionedTextSnapshot snapshot = VersionedTextSnapshot.of(reference);

        for (int iteration = 0; iteration < 800; iteration++) {
            int offset = random.nextInt(reference.length() + 1);
            int removedLength = random.nextInt(reference.length() - offset + 1);
            String inserted = randomText(random);
            reference = reference.substring(0, offset) + inserted + reference.substring(offset + removedLength);
            snapshot = snapshot.replace(offset, removedLength, inserted);

            assertEquals(reference, snapshot.text(), "iteration=" + iteration);
            assertEquals(lineCount(reference), snapshot.lineCount(), "iteration=" + iteration);
            for (int probe = 0; probe <= reference.length(); probe += Math.max(1, reference.length() / 11)) {
                assertEquals(positionAt(reference, probe), snapshot.positionAt(probe), "position iteration=" + iteration);
            }
            for (int line = 0; line < snapshot.lineCount(); line++) {
                int start = lineStart(reference, line);
                int end = lineEnd(reference, line);
                assertEquals(start, snapshot.lineStartOffset(line), "line start iteration=" + iteration);
                assertEquals(end, snapshot.lineEndOffset(line), "line end iteration=" + iteration);
                assertEquals(end, snapshot.offsetAt(line, Integer.MAX_VALUE), "line end position iteration=" + iteration);
            }
        }
    }

    private static String randomText(Random random) {
        int length = random.nextInt(14);
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            int kind = random.nextInt(8);
            result.append(kind == 0 ? '\n' : (char) ('a' + kind));
        }
        return result.toString();
    }

    private static int lineCount(String value) {
        return (int) value.chars().filter(character -> character == '\n').count() + 1;
    }

    private static VersionedTextSnapshot.Position positionAt(String value, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            if (value.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return new VersionedTextSnapshot.Position(line, offset - lineStart);
    }

    private static int lineStart(String value, int line) {
        if (line == 0) return 0;
        int seen = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n' && ++seen == line) return index + 1;
        }
        return value.length();
    }

    private static int lineEnd(String value, int line) {
        int start = lineStart(value, line);
        int end = value.indexOf('\n', start);
        return end < 0 ? value.length() : end;
    }
}
