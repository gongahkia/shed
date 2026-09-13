package shed;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
    void editorPaintUsesGrayscaleAntialiasingWithIntegerLayoutMetrics() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            EditorUiController.applyEditorTextRenderingHints(graphics);
            assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                graphics.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING));
            assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
                graphics.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS));
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void editorScrollbarsCanBeHiddenWithoutDisablingScrolling() {
        javax.swing.JScrollPane scrollPane = new javax.swing.JScrollPane();
        EditorUiController.applyEditorScrollBarVisibility(scrollPane, false);
        assertEquals(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_NEVER, scrollPane.getVerticalScrollBarPolicy());
        assertEquals(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.getHorizontalScrollBarPolicy());
        EditorUiController.applyEditorScrollBarVisibility(scrollPane, true);
        assertEquals(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, scrollPane.getVerticalScrollBarPolicy());
        assertEquals(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED, scrollPane.getHorizontalScrollBarPolicy());
    }

    @Test
    void minimapSamplingCapsPaintWorkToPanelHeight() {
        assertEquals(1, MinimapPanel.sampleStepForLineCount(100, 400));
        assertEquals(25, MinimapPanel.sampleStepForLineCount(10_000, 400));
        assertTrue(MinimapPanel.scaleForLineCount(10_000, 400) < 1.0);
    }
}
