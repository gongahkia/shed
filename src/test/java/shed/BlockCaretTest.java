package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

class BlockCaretTest {
    @Test
    void paintsASolidCaretInsteadOfXorInvertingTheUnderlyingGlyph() throws Exception {
        JTextArea area = new JTextArea("A");
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        area.setBackground(new Color(0x28, 0x2C, 0x34));
        area.setForeground(Color.WHITE);
        area.setCaretColor(new Color(0x61, 0xAF, 0xEF));
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            BlockCaret.paintBlockCaret(graphics, area, new Rectangle(4, 4, 10, 19), 10, 0);
            assertEquals(area.getCaretColor().getRGB(), image.getRGB(13, 5));
        } finally {
            graphics.dispose();
        }
    }
}
