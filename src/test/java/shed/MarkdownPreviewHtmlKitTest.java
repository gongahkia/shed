package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkdownPreviewHtmlKitTest {
    @TempDir Path temporary;

    private static HTMLDocument read(HTMLEditorKit kit, String html) throws Exception {
        HTMLDocument document = (HTMLDocument) kit.createDefaultDocument();
        kit.read(new StringReader(html), document, 0);
        return document;
    }

    private static String structure(Document document) {
        StringBuilder result = new StringBuilder();
        ElementIterator iterator = new ElementIterator(document);
        for (Element e; (e = iterator.next()) != null;) {
            TreeMap<String, String> attributes = new TreeMap<>();
            var names = e.getAttributes().getAttributeNames();
            while (names.hasMoreElements()) {
                Object key = names.nextElement();
                Object value = e.getAttributes().getAttribute(key);
                if (value instanceof javax.swing.ButtonModel model) {
                    // Independently constructed checkbox models have different object identities.
                    value = model.getClass().getName() + ":" + model.isSelected() + ":" + model.isEnabled();
                }
                if (key != StyleConstants.ResolveAttribute) attributes.put(key.toString(), String.valueOf(value));
            }
            result.append(e.getName()).append(':').append(e.getStartOffset()).append(':')
                .append(e.getEndOffset()).append(attributes).append('\n');
        }
        return result.toString();
    }

    private static void equalDocuments(HTMLDocument stock, HTMLDocument repaired) throws Exception {
        assertEquals(stock.getText(0, stock.getLength()), repaired.getText(0, repaired.getLength()));
        assertEquals(structure(stock), structure(repaired));
    }

    @ParameterizedTest @ValueSource(ints = {1, 32, 512})
    void multilineCodePreservesExactTextAndStructure(int lines) throws Exception {
        String code = "\t  <tag> & \"quote\" α😀 e\u0301 日本語 " + "x".repeat(80) + "\n";
        String literal = code.repeat(lines);
        String html = "<html><body><pre><code>" + literal.replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;") + "</code></pre></body></html>";
        HTMLDocument repaired = read(new MarkdownPreviewHtmlKit(), html);
        equalDocuments(read(new HTMLEditorKit(), html), repaired);
        ElementIterator iterator = new ElementIterator(repaired);
        boolean found = false;
        for (Element e; (e = iterator.next()) != null;) {
            if (e.getAttributes().getAttribute(StyleConstants.NameAttribute) == HTML.Tag.PRE) {
                // Include Swing's terminal sentinel: PRE can end one beyond getLength().
                assertEquals(literal, repaired.getText(e.getStartOffset(), e.getEndOffset() - e.getStartOffset()));
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test void singleLineEmptyCodeAndEofConventionsMatchSwing() throws Exception {
        for (String code : new String[]{"", " ", "x".repeat(16384), "a\n\n b\n", "a\r\nb\rc\n"}) {
            String html = "<html><body><pre><code>" + code + "</code></pre><p>after</p></body></html>";
            equalDocuments(read(new HTMLEditorKit(), html), read(new MarkdownPreviewHtmlKit(), html));
        }
    }

    @Test void readerRetainsFactorySettingsAndBaseUrl() throws Exception {
        MarkdownPreviewHtmlKit kit = new MarkdownPreviewHtmlKit();
        HTMLDocument document = (HTMLDocument) kit.createDefaultDocument();
        HTMLDocument other = (HTMLDocument) kit.createDefaultDocument();
        assertNotSame(document.getStyleSheet(), other.getStyleSheet());
        assertEquals(100, document.getTokenThreshold());
        assertEquals(4, document.getAsynchronousLoadPriority());
        var url = temporary.resolve("input.md").toUri().toURL();
        document.putProperty(Document.StreamDescriptionProperty, url);
        kit.read(new StringReader("<p><a href='relative.md'>relative</a></p>"), document, 0);
        assertEquals(url, document.getBase());
        assertTrue(structure(document).contains("relative.md"));
    }

    @Test void renderedMarkdownGeometryPixelsSelectionAndNavigationMatchStock() throws Exception {
        Path source = Files.writeString(temporary.resolve("input.md"), "# Original\n");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", temporary.resolve("local.png").toFile());
        try (MarkdownPreviewAssets assets = new MarkdownPreviewAssets()) {
            String html = MarkdownPreviewRenderer.render("# Heading\n\n**bold** and *emphasis* [anchor](#heading)\n\n"
                + "- item\n- [x] task\n\n> quote\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n"
                + "```text\n\t  <tag> & α😀\n\n  spaces\n```\n\n![local](local.png)\n\n$x^2$\n\n"
                + "<script>unsafe()</script>\n", "input.md", new Font(Font.DIALOG, 0, 13),
                Color.WHITE, Color.BLACK, assets, source.toFile());
            assertFalse(html.contains("<script>"));
            assertTrue(html.contains("alt=\"math\""));
            assertTrue(html.contains(temporary.resolve("local.png").toUri().toASCIIString()));
            FutureTask<Void> check = new FutureTask<>(() -> {
                for (int width : new int[]{400, 640}) {
                    JEditorPane stock = pane(new HTMLEditorKit(), html);
                    JEditorPane repaired = pane(new MarkdownPreviewHtmlKit(), html);
                    equalDocuments((HTMLDocument) stock.getDocument(), (HTMLDocument) repaired.getDocument());
                    stock.setSize(width, 480); repaired.setSize(width, 480);
                    Dimension size = stock.getPreferredSize();
                    assertEquals(size, repaired.getPreferredSize());
                    stock.setSize(width, size.height); repaired.setSize(width, size.height);
                    for (int position : new int[]{0, stock.getDocument().getLength() / 2, stock.getDocument().getLength() - 1}) {
                        assertEquals(stock.modelToView2D(position), repaired.modelToView2D(position));
                        var point = stock.modelToView2D(position).getBounds().getLocation();
                        assertEquals(stock.viewToModel2D(point), repaired.viewToModel2D(point));
                    }
                    stock.select(0, stock.getDocument().getLength()); repaired.select(0, repaired.getDocument().getLength());
                    assertEquals(stock.getSelectedText(), repaired.getSelectedText());
                    stock.select(0, 0); repaired.select(0, 0);
                    assertArrayEquals(pixels(stock, width), pixels(repaired, width));
                    stock.scrollToReference("heading"); repaired.scrollToReference("heading");
                    assertEquals(stock.getVisibleRect(), repaired.getVisibleRect());
                    // Explicit repeated setText uses the same document/reader route as preview refresh.
                    stock.setText(html); repaired.setText(html);
                    equalDocuments((HTMLDocument) stock.getDocument(), (HTMLDocument) repaired.getDocument());
                }
                return null;
            });
            SwingUtilities.invokeAndWait(check); check.get();
        }
    }

    private static JEditorPane pane(HTMLEditorKit kit, String html) {
        JEditorPane pane = new JEditorPane();
        pane.setEditorKit(kit); pane.setEditable(false);
        pane.setFont(new Font(Font.DIALOG, 0, 13));
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        // Deterministic existing image data; avoid comparing asynchronous image-load races.
        var cache = new java.util.Hashtable<java.net.URL, java.awt.Image>();
        var matches = java.util.regex.Pattern.compile("<img[^>]+src=\"([^\"]+)\"").matcher(html);
        while (matches.find()) {
            try { var url = java.net.URI.create(matches.group(1)).toURL(); cache.put(url, ImageIO.read(url)); }
            catch (Exception error) { throw new AssertionError(error); }
        }
        pane.getDocument().putProperty("imageCache", cache);
        pane.setText(html);
        return pane;
    }

    private static int[] pixels(JEditorPane pane, int width) {
        BufferedImage image = new BufferedImage(width, 480, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { pane.paint(graphics); } finally { graphics.dispose(); }
        return image.getRGB(0, 0, width, 480, null, 0, width);
    }
}
