package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTMLDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotebookPanelTest {
    @TempDir Path home;

    @Test
    void liveAppearanceAndNewCellsPreserveEditsAndRefreshBothMarkdownSurfaces() throws Exception {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            ConfigManager config = new ConfigManager();
            SwingUtilities.invokeAndWait(() -> {
                NotebookDocument document = NotebookDocument.empty().withCells(List.of(
                    new NotebookDocument.Cell("markdown", "# Unsaved note", Map.of()),
                    new NotebookDocument.Cell("code", "print(1)", Map.of("outputs", List.of(
                        Map.of("output_type", "stream", "text", "1"),
                        Map.of("data", Map.of("text/markdown", "# Result")))))));
                AtomicReference<NotebookDocument> saved = new AtomicReference<>();
                NotebookPanel panel = new NotebookPanel(document, saved::set, null, null, null, null);
                Font codeFont = new Font(Font.MONOSPACED, Font.PLAIN, 19);
                Font uiFont = new Font(Font.DIALOG, Font.PLAIN, 15);
                List<Component> newlyStyled = new ArrayList<>();
                panel.refreshAppearance(config, codeFont, uiFont, newlyStyled::add);
                descendants(panel, JToggleButton.class).getFirst().doClick();
                JTextArea source = descendants(panel, JTextArea.class).stream()
                    .filter(area -> area.getText().equals("print(1)")).findFirst().orElseThrow();
                source.append("\nprint(2)");
                source.select(0, 5);
                javax.swing.text.Document sourceDocument = source.getDocument();

                config.setTheme("github-light");
                panel.refreshAppearance(config, codeFont, uiFont, newlyStyled::add);

                assertSame(sourceDocument, source.getDocument());
                assertEquals("print", source.getSelectedText());
                assertEquals(codeFont, source.getFont());
                assertEquals(config.getSelectionColor(), source.getSelectionColor());
                List<JEditorPane> previews = descendants(panel, JEditorPane.class);
                assertEquals(2, previews.size());
                for (JEditorPane preview : previews) {
                    assertEquals(config.getNormalColor(), preview.getBackground());
                    assertEquals(config.getEditorForeground(), preview.getForeground());
                    HTMLDocument html = (HTMLDocument) preview.getDocument();
                    String background = html.getStyleSheet().getRule("body").getAttribute(CSS.Attribute.BACKGROUND_COLOR).toString();
                    assertEquals(String.format("#%06x", config.getNormalColor().getRGB() & 0xffffff), background);
                }
                assertTrue(previews.getFirst().getText().contains("Unsaved note"));
                JEditorPane markdownOutput = previews.stream().filter(preview -> "Notebook Markdown Output"
                    .equals(preview.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                JScrollPane rendered = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, markdownOutput);
                JTextArea plainOutput = descendants(panel, JTextArea.class).stream().filter(area -> !area.isEditable()).findFirst().orElseThrow();
                JScrollPane plain = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, plainOutput);
                Container outputStack = rendered.getParent();
                outputStack.setSize(1000, 400);
                outputStack.doLayout();
                assertEquals(0, plain.getX(), "Plain output should start at the cell's leading edge");
                assertEquals(plain.getX(), rendered.getX(), "Mixed plain and Markdown outputs should share the leading edge");
                button(panel, "+ Code").doClick();
                assertEquals(1, newlyStyled.size());
                assertTrue(newlyStyled.getFirst() != panel);
                for (JTextArea area : descendants(panel, JTextArea.class)) assertEquals(codeFont, area.getFont());
                button(panel, "Save").doClick();
                assertEquals(3, saved.get().cells().size());
                assertEquals("print(1)\nprint(2)", saved.get().cells().get(1).source());
            });
        } finally {
            if (originalHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", originalHome);
        }
    }

    private static JButton button(Container root, String text) {
        return descendants(root, JButton.class).stream().filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
    }

    private static <T> List<T> descendants(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(descendants(container, type));
        }
        return result;
    }
}
