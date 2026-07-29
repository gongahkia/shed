package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

public class GraphemeEditRangeTest {
    @Test
    void deletionAndSelectionExpandToWholeClusters() {
        assertWholeCluster("e\u0301");
        assertWholeCluster("👩🏽");
        assertWholeCluster("👩‍💻");
        assertWholeCluster("🇸🇬");
        assertWholeCluster("\r\n");
        assertWholeCluster("\uD83D");
    }

    @Test
    void interiorOffsetsResolveByOperationDirection() {
        String text = "a👩‍💻b";
        int interior = 3;

        GraphemeEditRange.Range backward = GraphemeEditRange.previous(text, interior);
        GraphemeEditRange.Range forward = GraphemeEditRange.next(text, interior);

        assertEquals("a", text.substring(backward.start(), backward.end()));
        assertEquals("b", text.substring(forward.start(), forward.end()));
    }

    private void assertWholeCluster(String cluster) {
        String text = "a" + cluster + "b";
        int start = 1;
        int end = start + cluster.length();
        int interior = start + Math.min(1, cluster.length() - 1);

        GraphemeEditRange.Range selection = GraphemeEditRange.selection(text, interior, end);
        GraphemeEditRange.Range forward = GraphemeEditRange.next(text, start);
        GraphemeEditRange.Range backward = GraphemeEditRange.previous(text, end);

        assertEquals(cluster, text.substring(selection.start(), selection.end()));
        assertEquals(cluster, text.substring(forward.start(), forward.end()));
        assertEquals(cluster, text.substring(backward.start(), backward.end()));
        assertDeletedTextIsIntact(text, forward);
        assertDeletedTextIsIntact(text, backward);
    }

    private void assertDeletedTextIsIntact(String text, GraphemeEditRange.Range range) {
        String result = text.substring(0, range.start()) + text.substring(range.end());
        assertEquals("ab", result);
        assertFalse(result.contains("\uFFFD"));
    }
}
