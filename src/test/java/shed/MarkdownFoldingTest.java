package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class MarkdownFoldingTest {
    @Test
    void everyEntryPointIsUnavailableWithoutAccessingEditorOrSourceRanges() {
        // No Swing host is necessary: disabled folding must not inspect or mutate
        // a document, even through the former low-level reconstruction helpers.
        MarkdownController controller = new MarkdownController(null);
        String message = "Markdown folding is temporarily unavailable";
        assertEquals(message, controller.toggleFoldAtCursor());
        assertEquals(message, controller.foldHeading(-1, null, null));
        assertEquals(message, controller.unfoldHeading(-1, null));
        assertEquals(message, controller.foldAll());
        assertEquals(message, controller.unfoldAll());
        assertEquals(message, controller.globalFoldCycle());
    }
}
