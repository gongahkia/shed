package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class LspCompletionApplicationTest {
    @Test
    void appliesSnippetTextAndTracksPlaceholders() {
        LspClient.CompletionItem item = new LspClient.CompletionItem("fn", "", null, "", "fn ${1:name}($2) { $0 }", true, List.of());

        LspCompletionApplication.Result result = LspCompletionApplication.apply("fn", 2, "fn", item);

        assertEquals("fn name() {  }", result.text());
        assertEquals(3, result.placeholders().size());
        assertEquals(new SnippetExpansion.Placeholder(1, 3, 7), result.placeholders().get(0));
        assertFalse(result.fallback());
    }

    @Test
    void appliesCompletionAndAdditionalTextEditsFromOriginalDocument() {
        List<LspClient.CompletionTextEdit> edits = List.of(
            new LspClient.CompletionTextEdit(0, 0, 0, 3, "bar"),
            new LspClient.CompletionTextEdit(1, 0, 1, 3, "qux")
        );
        LspClient.CompletionItem item = new LspClient.CompletionItem("bar", "", null, "", "bar", false, edits);

        LspCompletionApplication.Result result = LspCompletionApplication.apply("foo\nbaz", 3, "foo", item);

        assertEquals("bar\nqux", result.text());
        assertFalse(result.fallback());
    }

    @Test
    void fallsBackToLabelForInvalidServerEditOrSnippet() {
        LspClient.CompletionItem invalidRange = new LspClient.CompletionItem("safe", "", null, "", "ignored", false,
            List.of(new LspClient.CompletionTextEdit(8, 0, 8, 1, "bad")));
        LspClient.CompletionItem invalidSnippet = new LspClient.CompletionItem("safe", "", null, "", "${1:unterminated", true, List.of());

        LspCompletionApplication.Result rangeResult = LspCompletionApplication.apply("old", 3, "old", invalidRange);
        LspCompletionApplication.Result snippetResult = LspCompletionApplication.apply("old", 3, "old", invalidSnippet);

        assertEquals("safe", rangeResult.text());
        assertTrue(rangeResult.fallback());
        assertEquals("safe", snippetResult.text());
        assertTrue(snippetResult.fallback());
    }
}
