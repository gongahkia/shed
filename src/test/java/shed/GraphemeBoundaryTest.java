package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GraphemeBoundaryTest {
    @Test
    void keepsCombiningEmojiAndFlagClustersIntact() {
        String combining = "e\u0301";
        String emoji = "👩🏽";
        String zwj = "👩‍💻";
        String flags = "🇸🇬🇺🇸";

        assertEquals(combining.length(), GraphemeBoundary.next(combining, 0));
        assertEquals(0, GraphemeBoundary.previous(combining, combining.length()));
        assertEquals(emoji.length(), GraphemeBoundary.next(emoji, 0));
        assertEquals(zwj.length(), GraphemeBoundary.next(zwj, 0));
        assertEquals(4, GraphemeBoundary.next(flags, 0));
        assertEquals(flags.length(), GraphemeBoundary.next(flags, 4));
        assertFalse(GraphemeBoundary.isBoundary(zwj, 1));
        assertFalse(GraphemeBoundary.isBoundary(zwj, 3));
    }

    @Test
    void preservesCrLfAndUnpairedSurrogatesAsWholeUnits() {
        String lines = "\r\nx";
        String malformed = "a\uD83Db";

        assertEquals(2, GraphemeBoundary.next(lines, 0));
        assertEquals(1, GraphemeBoundary.next(malformed, 0));
        assertEquals(2, GraphemeBoundary.next(malformed, 1));
        assertTrue(GraphemeBoundary.isBoundary(malformed, 2));
        assertEquals(1, GraphemeBoundary.floor(malformed, 1));
        assertEquals(2, GraphemeBoundary.ceiling(malformed, 2));
    }

    @Test
    void normalizesInteriorOffsetsByDirection() {
        String text = "e\u0301x";

        assertEquals(0, GraphemeBoundary.previous(text, 1));
        assertEquals(2, GraphemeBoundary.next(text, 1));
        assertEquals(0, GraphemeBoundary.floor(text, 1));
        assertEquals(2, GraphemeBoundary.ceiling(text, 1));
    }
}
