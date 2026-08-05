package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SnippetExpansionTest {
    @Test
    void expandsTabStopsDefaultsChoicesAndVariables() {
        SnippetExpansion.Result result = SnippetExpansion.parse("${1:name} = ${2|one,two|}; $TM_FILENAME $0", Map.of("TM_FILENAME", "Demo.java"));

        assertNotNull(result);
        assertEquals("name = one; Demo.java ", result.text());
        assertEquals(3, result.placeholders().size());
        assertEquals(new SnippetExpansion.Placeholder(1, 0, 4), result.placeholders().get(0));
        assertEquals(new SnippetExpansion.Placeholder(2, 7, 10), result.placeholders().get(1));
        assertEquals(new SnippetExpansion.Placeholder(0, 22, 22), result.placeholders().get(2));
    }
}
