package shed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class BracketColorServiceTest {

    private BracketColorService service;

    @BeforeEach
    void setUp() {
        service = new BracketColorService();
    }

    @Test
    void computeBracketColors_simplePair() {
        List<BracketColorService.ColoredBracket> result = service.computeBracketColors("(a)");
        assertEquals(2, result.size());
        assertEquals('(', result.get(0).bracket);
        assertEquals(')', result.get(1).bracket);
        assertEquals(0, result.get(0).depth);
        assertEquals(0, result.get(1).depth);
    }

    @Test
    void computeBracketColors_nestedPairs() {
        List<BracketColorService.ColoredBracket> result = service.computeBracketColors("((a))");
        assertEquals(4, result.size());
        assertEquals(0, result.get(0).depth); // outer (
        assertEquals(1, result.get(1).depth); // inner (
        assertEquals(1, result.get(2).depth); // inner )
        assertEquals(0, result.get(3).depth); // outer )
    }

    @Test
    void computeBracketColors_mixedBrackets() {
        List<BracketColorService.ColoredBracket> result = service.computeBracketColors("({[]})");
        assertEquals(6, result.size());
    }

    @Test
    void computeBracketColors_ignoresStrings() {
        List<BracketColorService.ColoredBracket> result = service.computeBracketColors("\"(not a bracket)\"");
        assertTrue(result.isEmpty());
    }

    @Test
    void computeBracketColors_ignoresComments() {
        List<BracketColorService.ColoredBracket> result = service.computeBracketColors("// (comment)\n(real)");
        assertEquals(2, result.size());
    }

    @Test
    void computeBracketColors_emptyInput() {
        assertTrue(service.computeBracketColors("").isEmpty());
        assertTrue(service.computeBracketColors(null).isEmpty());
    }

    @Test
    void findMatchingPairs_findsPairs() {
        List<BracketColorService.BracketPair> pairs = service.findMatchingPairs("(a[b]c)");
        assertEquals(2, pairs.size());
    }

    @Test
    void findMatchingPairs_unmatchedIgnored() {
        List<BracketColorService.BracketPair> pairs = service.findMatchingPairs("(a");
        assertTrue(pairs.isEmpty());
    }

    @Test
    void coloredBracket_hasColor() {
        BracketColorService.ColoredBracket bracket = new BracketColorService.ColoredBracket(0, 0, '(');
        assertNotNull(bracket.color());
    }

    @Test
    void bracketPair_hasColor() {
        BracketColorService.BracketPair pair = new BracketColorService.BracketPair(0, 1, 0, '(');
        assertNotNull(pair.color());
    }

    @Test
    void differentDepths_differentColors() {
        BracketColorService.ColoredBracket depth0 = new BracketColorService.ColoredBracket(0, 0, '(');
        BracketColorService.ColoredBracket depth1 = new BracketColorService.ColoredBracket(0, 1, '(');
        assertNotEquals(depth0.color(), depth1.color());
    }
}
