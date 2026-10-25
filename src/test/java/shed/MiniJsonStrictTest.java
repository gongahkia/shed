package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonStrictTest {
    @Test void preservesAllControlCharactersAndMalformedSurrogateCodeUnitsOverUtf8Transport() {
        StringBuilder text = new StringBuilder("quotes\" slash\\ emoji 👩‍💻 ");
        for (char c = 0; c < 32; c++) text.append(c);
        text.append('\ud800').append('x').append('\udc00');
        String encoded = MiniJson.stringify(Map.of("text", text.toString()));
        String transported = new String(encoded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals(text.toString(), MiniJson.asObject(MiniJson.parseStrict(transported)).get("text"));
        assertTrue(encoded.contains("\\u0000")); assertTrue(encoded.contains("\\ud800"));
    }

    @Test void acceptsExactNumbersAndNestedValues() {
        Map<String, Object> value = MiniJson.asObject(MiniJson.parseStrict(" {\"id\":9223372036854775807,\"a\":[true,false,null,1.25e2]} \n"));
        assertEquals(Long.MAX_VALUE, value.get("id"));
        assertEquals(new BigDecimal("1.25e2"), MiniJson.asArray(value.get("a")).get(3));
        assertNull(MiniJson.parseStrict("null"));
    }

    @Test void malformedAmbiguousAndUnboundedInputsFailWithoutPartialResults() {
        for (String input : List.of("", "{", "[", "[1,]", "{\"a\":1,}", "{a:1}", "{\"a\" 1}",
            "{\"a\":1 \"b\":2}", "{\"a\":null,\"a\":2}", "true false", "truth", "01", "+1", "1.", "1e", "-", "NaN",
            "\"bad\\x\"", "\"bad\\u00\"", "\"bad\\u00zz\"", "\"unterminated", "\"raw\nnewline\"", "\u00a0null"))
            assertThrows(IllegalArgumentException.class, () -> MiniJson.parseStrict(input), input);
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseStrict("[".repeat(66) + "0" + "]".repeat(66)));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseStrict(" ".repeat(1024 * 1024 + 1)));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.stringify(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.stringify(Float.POSITIVE_INFINITY));
    }
}
