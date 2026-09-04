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
        assertTrue(List.of(service.keywordsFor(FileType.KOTLIN)).contains("fun"));
        assertTrue(List.of(service.keywordsFor(FileType.CSHARP)).contains("namespace"));
        assertTrue(List.of(service.keywordsFor(FileType.PHP)).contains("function"));
        assertTrue(List.of(service.keywordsFor(FileType.RUBY)).contains("def"));
        assertTrue(List.of(service.keywordsFor(FileType.SWIFT)).contains("func"));
        assertTrue(List.of(service.lineCommentPrefixesFor(FileType.PHP)).contains("#"));
    }
}
