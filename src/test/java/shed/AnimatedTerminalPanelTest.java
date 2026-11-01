package shed;

import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import java.awt.Font;
import java.awt.image.BufferedImage;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimatedTerminalPanelTest {
    @Test
    void terminalCursorMovesVisuallyAndHonorsVisibilityShapesAndReducedMotion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var settings = new PtyTerminalPane.ShedTerminalSettingsProvider(null, new Font(Font.MONOSPACED, Font.PLAIN, 14));
            StyleState style = new StyleState(); style.setDefaultStyle(settings.getDefaultStyle());
            TerminalTextBuffer buffer = new TerminalTextBuffer(80, 24, style);
            AnimatedTerminalPanel panel = new AnimatedTerminalPanel(settings, buffer, style) {
                @Override public boolean isShowing() { return true; }
                @Override public boolean isFocusOwner() { return true; }
            };
            try {
                panel.init(new JScrollBar()); panel.setSize(panel.getPreferredSize());
                panel.setCursorShape(CursorShape.STEADY_BLOCK);
                panel.setCursor(0, 1);
                BufferedImage start = paint(panel);
                panel.setCursor(20, 1);
                BufferedImage moving = paint(panel);
                assertTrue(litPixels(moving, 0, 30) > 0, "Smear begins at the old cursor");
                assertEquals(0, litPixels(moving, 130, moving.getWidth()), "Cursor should glide toward its new column");
                panel.putClientProperty("shed.caretAnimation", false);
                BufferedImage settled = paint(panel);
                assertEquals(0, litPixels(settled, 0, 30));
                assertTrue(litPixels(settled, 130, settled.getWidth()) > 0);
                panel.setCursorVisible(false);
                assertEquals(0, litPixels(paint(panel), 0, settled.getWidth()));
                panel.setCursorVisible(true);
                panel.setCursor(0, 1);
                for (CursorShape shape : new CursorShape[]{CursorShape.STEADY_BLOCK, CursorShape.STEADY_UNDERLINE, CursorShape.STEADY_VERTICAL_BAR}) {
                    panel.setCursorShape(shape);
                    assertTrue(litPixels(paint(panel), 0, 30) > 0, shape.name());
                }
                assertEquals("", buffer.getScreenLines().trim(), "Drawing must not change terminal contents");
                assertTrue(litPixels(start, 0, 30) > 0);
            } finally { panel.dispose(); }
        });
    }

    private static BufferedImage paint(AnimatedTerminalPanel panel) {
        var image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try { panel.paintComponent(g); } finally { g.dispose(); }
        return image;
    }

    private static int litPixels(BufferedImage image, int left, int right) {
        int count = 0;
        for (int x = left; x < right; x++) for (int y = 0; y < image.getHeight(); y++) {
            if ((image.getRGB(x, y) & 0xffffff) != 0) count++;
        }
        return count;
    }
}
