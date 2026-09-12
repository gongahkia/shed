package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class UiPerformanceGuardTest {
    @Test
    void syntaxHighlightGuardOnlySkipsReadOnlyLargeFiles() {
        assertTrue(SyntaxUiController.shouldSkipSyntaxHighlighting(1, 1, true));
        assertTrue(!SyntaxUiController.shouldSkipSyntaxHighlighting(SyntaxUiController.MAX_FULL_SYNTAX_CHARS + 1, 1, false));
        assertTrue(!SyntaxUiController.shouldSkipSyntaxHighlighting(1, SyntaxUiController.MAX_FULL_SYNTAX_LINES + 1, false));
    }

    @Test
    void syntaxHighlightingSkipsPlainDiagnosticScratchBuffers() {
        assertFalse(SyntaxUiController.shouldHighlightSyntax(FileBuffer.createPlainScratch("[config recovery]", "font.family: \"Missing\"")));
        assertTrue(SyntaxUiController.shouldHighlightSyntax(FileBuffer.createScratch("[scratch]", "class Demo {}")));
    }

    @Test
    void minimapSamplingCapsPaintWorkToPanelHeight() {
        assertEquals(1, MinimapPanel.sampleStepForLineCount(100, 400));
        assertEquals(25, MinimapPanel.sampleStepForLineCount(10_000, 400));
        assertTrue(MinimapPanel.scaleForLineCount(10_000, 400) < 1.0);
    }
}
