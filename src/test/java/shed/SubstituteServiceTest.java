package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class SubstituteServiceTest {
    @Test
    void replacesFirstOccurrenceOnly() {
        SubstituteService service = new SubstituteService();
        SubstituteService.Result result = service.replaceLiteral("a b a", "a", "x", false);

        assertEquals("x b a", result.getUpdatedText());
        assertEquals(1, result.getMatchCount());
        assertEquals(0, result.getFirstMatchOffset());
    }

    @Test
    void replacesAllOccurrences() {
        SubstituteService service = new SubstituteService();
        SubstituteService.Result result = service.replaceLiteral("foo foo foo", "foo", "bar", true);

        assertEquals("bar bar bar", result.getUpdatedText());
        assertEquals(3, result.getMatchCount());
        assertEquals(0, result.getFirstMatchOffset());
    }

    @Test
    void returnsOriginalWhenPatternMissing() {
        SubstituteService service = new SubstituteService();
        SubstituteService.Result result = service.replaceLiteral("alpha", "z", "x", true);

        assertEquals("alpha", result.getUpdatedText());
        assertEquals(0, result.getMatchCount());
        assertEquals(-1, result.getFirstMatchOffset());
    }
}
