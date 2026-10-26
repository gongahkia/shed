package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AgentQuestionSchemaTest {
    @Test void validatesPrimitiveAndChoiceFieldsWithoutChangingTheirValues() {
        var schema = parse("""
            {"type":"object","required":["name","count","enabled","pick","many"],"properties":{
              "name":{"type":"string","minLength":1,"maxLength":2},
              "count":{"type":"integer","minimum":1,"maximum":10,"default":2},
              "enabled":{"type":"boolean","default":false},
              "pick":{"type":"string","oneOf":[{"const":"a","title":"Alpha"},{"const":"b","title":"Beta"}]},
              "many":{"type":"array","items":{"anyOf":[{"const":"a","title":"Alpha"},{"const":"b","title":"Beta"}]},"minItems":1,"maxItems":2},
              "optional":{"type":"number"}}}
            """);
        var values = Map.<String, Object>of("name", "😀", "count", new BigDecimal("2.0"), "enabled", false, "pick", "b", "many", List.of("a", "b"));
        assertEquals(values, schema.validate(values)); assertEquals("Alpha", schema.fields().get(3).choices().getFirst().title());
        assertThrows(AcpException.class, () -> schema.validate(Map.of()));
        for (var invalid : List.of(Map.entry("name", "long"), Map.entry("count", 1.5), Map.entry("enabled", "true"), Map.entry("pick", "z"), Map.entry("many", List.of("a", "a")))) {
            var copy = new java.util.LinkedHashMap<>(values); copy.put(invalid.getKey(), invalid.getValue()); assertThrows(AcpException.class, () -> schema.validate(copy));
        }
        assertThrows(UnsupportedOperationException.class, () -> schema.validate(values).put("extra", true));
    }

    @Test void unsupportedSchemasAndInvalidDefaultsFailBeforeRendering() {
        for (String property : List.of("{\"type\":\"object\"}", "{\"type\":\"string\",\"format\":\"password\"}",
            "{\"type\":\"string\",\"enum\":[\"a\",\"a\"]}", "{\"type\":\"integer\",\"default\":1.5}",
            "{\"type\":\"string\",\"$ref\":\"https://example.com/schema\"}", "{\"type\":\"array\",\"items\":{\"type\":\"object\"}}"))
            assertThrows(AcpException.class, () -> parse("{\"properties\":{\"field\":" + property + "}}"));
        assertThrows(AcpException.class, () -> parse("{\"required\":[\"missing\"]}"));
        var schema = parse("{\"properties\":{\"n\":{\"type\":\"number\"}}}");
        assertThrows(AcpException.class, () -> schema.validate(Map.of("n", Double.NaN)));
        assertThrows(AcpException.class, () -> schema.validate(Map.of("unknown", 1)));
    }

    @Test @Timeout(5) void patternsAreBoundedAndFormatsAreCheckedWithoutNetworkAccess() {
        var expensive = parse("{\"properties\":{\"value\":{\"type\":\"string\",\"pattern\":\"(a+)+$\"}}}");
        assertThrows(AcpException.class, () -> expensive.validate(Map.of("value", "a".repeat(10000) + "!")));
        for (var example : Map.of("email", "a@example.com", "uri", "https://example.com/profile", "date", "2026-10-26", "date-time", "2026-10-26T10:00:00Z").entrySet()) {
            var schema = parse("{\"properties\":{\"value\":{\"type\":\"string\",\"format\":\"" + example.getKey() + "\"}}}");
            assertEquals(example.getValue(), schema.validate(Map.of("value", example.getValue())).get("value"));
            assertThrows(AcpException.class, () -> schema.validate(Map.of("value", "not valid")));
        }
    }
    private static AgentQuestionSchema parse(String json) { return AgentQuestionSchema.parse(MiniJson.parseStrict(json)); }
}
