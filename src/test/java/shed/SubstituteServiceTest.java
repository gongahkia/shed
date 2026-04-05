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
    @Test
    void regexReplacesWithPattern() {
        SubstituteService service = new SubstituteService();
        SubstituteService.Result result = service.replaceRegex("foo123bar456", "\\d+", "N", true);
        assertEquals("fooNbarN", result.getUpdatedText());
        assertEquals(2, result.getMatchCount());
    }
    @Test
    void regexReplacesFirstOnly() {
        SubstituteService service = new SubstituteService();
        SubstituteService.Result result = service.replaceRegex("aaa bbb aaa", "a+", "X", false);
        assertEquals("X bbb aaa", result.getUpdatedText());
        assertEquals(1, result.getMatchCount());
    }
    @Test
    void regexFallsBackOnInvalidPattern() {
        SubstituteService service = new SubstituteService();
        SubstituteService.Result result = service.replaceRegex("a[b", "[", "X", true);
        assertEquals("aXb", result.getUpdatedText());
    }
    @Test
    void regexNoMatchReturnsOriginal() {
        SubstituteService service = new SubstituteService();
        SubstituteService.Result result = service.replaceRegex("hello", "\\d+", "N", true);
        assertEquals("hello", result.getUpdatedText());
        assertEquals(0, result.getMatchCount());
    }
}
