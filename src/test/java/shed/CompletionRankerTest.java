package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

public class CompletionRankerTest {
    @Test
    void filtersFuzzilyAndUsesServerSortTextForTies() {
        List<LspClient.CompletionItem> items = LspClient.parseCompletionItems(MiniJson.parse("["
            + "{\"label\":\"config\",\"filterText\":\"config\",\"sortText\":\"b\"},"
            + "{\"label\":\"create\",\"filterText\":\"create\",\"sortText\":\"a\"},"
            + "{\"label\":\"context\",\"filterText\":\"context\",\"sortText\":\"c\"}]"));

        List<LspClient.CompletionItem> ranked = new CompletionRanker().rank("ct", items, true, 12);

        assertEquals(List.of("context"), ranked.stream().map(LspClient.CompletionItem::getLabel).toList());
        assertEquals("create", new CompletionRanker().rank("c", items, true, 12).get(0).getLabel());
    }
}
