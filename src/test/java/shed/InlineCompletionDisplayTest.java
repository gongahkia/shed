package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.Test;

class InlineCompletionDisplayTest {
    @Test void multilineInsertionReservesSpaceAndRestoresDocumentGeometryWithoutEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var source = area("prefix suffix\nnext line\nlast line", false, 400);
                int anchor = 7; String ghost = "first\n\tsecond\nthird";
                var originalSize = source.getPreferredSize(); var changes = new AtomicInteger();
                source.getDocument().addDocumentListener(new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) { changes.incrementAndGet(); }
                    public void removeUpdate(DocumentEvent e) { changes.incrementAndGet(); }
                    public void changedUpdate(DocumentEvent e) { changes.incrementAndGet(); }
                });
                var display = new InlineCompletionDisplay(source); display.show(anchor, ghost);
                var accepted = area("prefix " + ghost + "suffix\nnext line\nlast line", false, 400);
                assertEquals(accepted.getPreferredSize(), source.getPreferredSize());
                assertCoordinates(source, accepted, anchor, ghost.length());
                assertTrue(source.getPreferredSize().height > originalSize.height);
                assertEquals("prefix suffix\nnext line\nlast line", source.getText()); assertEquals(0, changes.get());
                assertSameInk(source, accepted);
                display.clear(); assertEquals(originalSize, source.getPreferredSize()); assertEquals(0, changes.get());
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    @Test void wrappingTabsAndResizingMatchTheAcceptedTextLayout() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                String original = "a\tbc suffix\nnext\tline", ghost = "X\tY and several words\n\tmore text "; int anchor = 4;
                for (boolean words : new boolean[] {false, true}) {
                    var source = area(original, true, 140); source.setWrapStyleWord(words);
                    var display = new InlineCompletionDisplay(source); display.show(anchor, ghost);
                    var accepted = area(original.substring(0, anchor) + ghost + original.substring(anchor), true, 140);
                    accepted.setWrapStyleWord(words);
                    for (int width : new int[] {140, 100, 240}) {
                        source.setSize(width, 600); accepted.setSize(width, 600);
                        assertCoordinates(source, accepted, anchor, ghost.length());
                        assertEquals(accepted.getPreferredSize().height, source.getPreferredSize().height);
                        assertSameInk(source, accepted);
                    }
                    display.clear();
                }
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private static JTextArea area(String text, boolean wrap, int width) {
        var area = new JTextArea(text); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setForeground(Color.BLACK); area.setBackground(Color.WHITE); area.setTabSize(4);
        area.setLineWrap(wrap); area.setSize(width, 600); return area;
    }
    private static void assertCoordinates(JTextArea source, JTextArea accepted, int anchor, int inserted) throws Exception {
        for (int offset = 0; offset <= source.getDocument().getLength(); offset++) {
            int projected = offset <= anchor ? offset : offset + inserted;
            assertEquals(accepted.modelToView2D(projected), source.modelToView2D(offset), "offset " + offset);
        }
    }
    private static void assertSameInk(JTextArea preview, JTextArea accepted) {
        var actual = render(preview); var expected = render(accepted); int ink = 0, ghost = 0;
        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++) {
            boolean a = actual.getRGB(x, y) != Color.WHITE.getRGB(), b = expected.getRGB(x, y) != Color.WHITE.getRGB();
            assertEquals(b, a, "ink at " + x + "," + y);
            if (a) { ink++; if (actual.getRGB(x,y) != expected.getRGB(x,y)) ghost++; }
        }
        assertTrue(ink > 0); assertTrue(ghost > 0, "suggested text must have its own foreground");
    }
    private static BufferedImage render(JTextArea area) {
        var image = new BufferedImage(area.getWidth(), area.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); area.paint(graphics); graphics.dispose(); return image;
    }
}
