package shed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyntaxHighlightServiceTest {
    @Test
    void includesJavaKeywordsAndScopes() {
        SyntaxHighlightService service = new SyntaxHighlightService();
        assertTrue(List.of(service.keywordsFor(FileType.JAVA)).contains("record"));
        assertTrue(service.scopeRulesFor(FileType.JAVA).stream().anyMatch(rule -> rule.scope.equals("annotation")));
    }
}
