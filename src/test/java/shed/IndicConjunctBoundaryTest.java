package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndicConjunctBoundaryTest {
    private static String resource(String name) throws Exception {
        try (var input = IndicConjunctBoundaryTest.class.getResourceAsStream("/unicode/15.1.0/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void lookupExactlyMatchesPinnedIndicPropertiesForEveryCodePoint() throws Exception {
        byte[] expected = new byte[Character.MAX_CODE_POINT + 1];
        for (String line : resource("IndicConjunctBreak.txt").split("\n")) {
            String[] fields = line.split("#", 2)[0].trim().split(";");
            if (fields.length != 3) continue;
            String[] range = fields[0].trim().split("\\.\\.");
            int first = Integer.parseInt(range[0], 16), last = Integer.parseInt(range[range.length - 1], 16);
            byte property = switch (fields[2].trim()) { case "Consonant" -> 1; case "Linker" -> 2; case "Extend" -> 3; default -> throw new AssertionError(line); };
            java.util.Arrays.fill(expected, first, last + 1, property);
        }
        for (int cp = 0; cp < expected.length; cp++) {
            if (IndicConjunctBreak.isConsonant(cp) != (expected[cp] == 1)
                || IndicConjunctBreak.isLinker(cp) != (expected[cp] == 2)
                || IndicConjunctBreak.isExtend(cp) != (expected[cp] == 3)) fail("InCB mismatch at U+" + Integer.toHexString(cp));
        }
    }

    @Test void allPinnedGb9cCasesHaveExactNavigationSelectionAndDeletionRanges() throws Exception {
        int tested = 0;
        for (String line : resource("GraphemeBreakTest.txt").split("\n")) {
            if (!line.contains("[9.3]")) continue;
            StringBuilder text = new StringBuilder(); List<Integer> expected = new ArrayList<>();
            for (String part : line.split("#", 2)[0].trim().split("\\s+")) {
                if (part.equals("÷")) expected.add(text.length());
                else if (!part.equals("×")) text.appendCodePoint(Integer.parseInt(part, 16));
            }
            assertBoundaries(text.toString(), expected); tested++;
        }
        assertEquals(7, tested);
    }

    @Test void preservesConjunctsAcrossScriptsWithoutJoiningNonLinkingContexts() {
        for (String cluster : List.of("क्ष", "क्‍ष", "क़्‍ष", "क््त्र", "ক্ষ", "ક્ષ", "କ୍ଷ", "క్ష", "ക്ഷ"))
            assertBoundaries("[" + cluster + "]", List.of(0, 1, 1 + cluster.length(), 2 + cluster.length()));
        assertBoundaries("कष", List.of(0, 1, 2));
        assertBoundaries("a्ष", List.of(0, 2, 3));
        assertBoundaries("क्‌ष", List.of(0, 3, 4)); // ZWNJ ends the InCB linking context
        assertBoundaries("क्\nष", List.of(0, 2, 3, 4));
    }

    private static void assertBoundaries(String text, List<Integer> boundaries) {
        for (int i = 0; i < boundaries.size() - 1; i++) {
            int start = boundaries.get(i), end = boundaries.get(i + 1);
            assertEquals(end, GraphemeBoundary.next(text, start), text);
            assertEquals(start, GraphemeBoundary.previous(text, end), text);
            assertEquals(new GraphemeEditRange.Range(start, end), GraphemeEditRange.next(text, start));
            assertEquals(new GraphemeEditRange.Range(start, end), GraphemeEditRange.previous(text, end));
            for (int offset = start + 1; offset < end; offset++) {
                assertFalse(GraphemeBoundary.isBoundary(text, offset), text);
                assertEquals(start, GraphemeBoundary.floor(text, offset));
                assertEquals(end, GraphemeBoundary.ceiling(text, offset));
                assertEquals(new GraphemeEditRange.Range(start, end), GraphemeEditRange.selection(text, offset, end));
                assertEquals(new GraphemeEditRange.Range(start, end), GraphemeEditRange.selection(text, end, offset));
            }
        }
    }
}
