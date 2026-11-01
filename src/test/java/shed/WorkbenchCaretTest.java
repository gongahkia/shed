package shed;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchCaretTest {
    @TempDir Path home;

    @Test
    void sharedDelegatesCoverNewInputsAndSurviveThemeUpdates() throws Exception {
        String previousHome = System.getProperty("user.home");
        var defaults = new java.util.HashMap<>(UIManager.getDefaults());
        try {
            System.setProperty("user.home", home.toString());
            SwingUtilities.invokeAndWait(() -> {
                WorkbenchStyle.install(new ConfigManager());
                JComboBox<String> combo = new JComboBox<>(new String[]{"value"});
                combo.setEditable(true);
                JSpinner spinner = new JSpinner();
                JTextComponent[] inputs = {new JTextField("settings search"), new JTextField("command palette"),
                    new JTextArea("prompt"), new JPasswordField("secret"), new JFormattedTextField("12"),
                    new JTextPane(), new JEditorPane(), (JTextComponent) combo.getEditor().getEditorComponent(),
                    ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField()};
                for (JTextComponent input : inputs) {
                    assertInstanceOf(BlockCaret.class, input.getCaret(), input.getClass().getName());
                    input.setText("example"); input.setCaretPosition(2); input.moveCaretPosition(5);
                    input.updateUI();
                    assertInstanceOf(BlockCaret.class, input.getCaret());
                    assertEquals(2, input.getCaret().getMark());
                    assertEquals(5, input.getCaretPosition());
                    input.setCaret(new javax.swing.text.DefaultCaret());
                }
                JTextArea buffer = new JTextArea("buffer");
                BlockCaret explicit = new BlockCaret(); explicit.setBlinkRate(0);
                buffer.setCaret(explicit); buffer.updateUI();
                assertSame(explicit, buffer.getCaret());
                assertEquals(0, explicit.getBlinkRate());
                buffer.setCaret(new javax.swing.text.DefaultCaret());
            });
        } finally {
            System.setProperty("user.home", previousHome);
            SwingUtilities.invokeAndWait(() -> {
                for (Object key : new java.util.ArrayList<>(UIManager.getDefaults().keySet())) {
                    if (!defaults.containsKey(key)) UIManager.getDefaults().remove(key);
                }
                defaults.forEach(UIManager::put);
            });
        }
    }

    @Test
    void passwordCaretOnlyDrawsTheEchoCharacter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                JPasswordField password = new JPasswordField("A"); password.setEchoChar('*');
                password.setBackground(Color.BLACK); password.setCaretColor(Color.BLUE);
                var first = draw(password);
                password.setText("B");
                var second = draw(password);
                assertArrayEquals(first, second, "Caret must never reveal a password glyph");
                password.setEchoChar('#');
                assertFalse(java.util.Arrays.equals(first, draw(password)), "Echo character should be respected");
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private static int[] draw(JPasswordField password) throws Exception {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try { BlockCaret.paintBlockCaret(g, password, new Rectangle(4, 4, 16, 24), 16, 0); }
        finally { g.dispose(); }
        return image.getRGB(0, 0, 40, 40, null, 0, 40);
    }
}
