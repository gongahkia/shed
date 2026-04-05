package shed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

class FuzzyMatchServiceTest {

    private FuzzyMatchService service;

    @BeforeEach
    void setUp() {
        service = new FuzzyMatchService();
    }

    @Test
    void score_exactMatchHighest() {
        int exact = service.score("write", "write");
        int prefix = service.score("write", "writeall");
        int fuzzy = service.score("write", "w_r_i_t_e");
        assertTrue(exact > prefix);
        assertTrue(prefix > 0);
    }

    @Test
    void score_prefixMatchHigh() {
        int prefixScore = service.score("wr", "write");
        int nonPrefix = service.score("wr", "tower");
        assertTrue(prefixScore > nonPrefix);
    }

    @Test
    void score_noMatchReturnsZero() {
        assertEquals(0, service.score("xyz", "abc"));
        assertEquals(0, service.score("zzz", "write"));
    }

    @Test
    void score_emptyQueryReturnsOne() {
        assertEquals(1, service.score("", "anything"));
    }

    @Test
    void score_nullsReturnZero() {
        assertEquals(0, service.score(null, "test"));
        assertEquals(0, service.score("test", null));
    }

    @Test
    void match_returnsOrderedResults() {
        List<String> candidates = Arrays.asList("write", "writeall", "wq", "quit", "wall");
        List<FuzzyMatchService.Match> results = service.match("w", candidates, 10);
        assertFalse(results.isEmpty());
        // All w-starting commands should match
        assertTrue(results.size() >= 4);
    }

    @Test
    void matchStrings_respectsLimit() {
        List<String> candidates = Arrays.asList("a1", "a2", "a3", "a4", "a5");
        List<String> results = service.matchStrings("a", candidates, 2);
        assertEquals(2, results.size());
    }

    @Test
    void match_fuzzyFindsSubsequences() {
        List<String> candidates = Arrays.asList("bracketcolor", "buffers", "bnext");
        List<String> results = service.matchStrings("brc", candidates, 5);
        assertTrue(results.contains("bracketcolor"));
    }

    @Test
    void score_caseInsensitive() {
        int lower = service.score("write", "WRITE");
        assertTrue(lower > 0);
    }

    @Test
    void match_emptyListReturnsEmpty() {
        List<String> results = service.matchStrings("test", List.of(), 5);
        assertTrue(results.isEmpty());
    }
}
