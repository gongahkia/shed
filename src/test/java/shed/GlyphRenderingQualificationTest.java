package shed;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.font.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.StyleContext;
import org.junit.jupiter.api.Test;

/** Characterization: a passing source/capture check is not a passing glyph-coverage qualification. */
class GlyphRenderingQualificationTest {
    static final String B0_SOURCE = "# Source alpha\nUnicode: e\u0301, क्ष, 👩\u200d💻, 中文\n".repeat(10);
    record Fixture(String id, String text, String referenceFamily) { }
    static final List<Fixture> CORPUS = List.of(
        new Fixture("ascii", "Source alpha 0123 !?", "Monospaced"),
        new Fixture("latin", "café Ångström naïve", "Dialog"),
        new Fixture("combining", "e\u0301 a\u0308 n\u0303", "Dialog"),
        new Fixture("punctuation-math", "‘text’ — … ∑ ∫ ≠ ≤ ∞", "Apple Symbols"),
        new Fixture("cjk", "中文日本語", "PingFang SC"),
        new Fixture("kana", "ひらがな カタカナ", "Hiragino Sans"),
        new Fixture("hangul", "한글", "Apple SD Gothic Neo"),
        new Fixture("indic-gb9c", "क्ष क्\u200dष", "Devanagari Sangam MN"),
        new Fixture("bengali", "ক্ষ", "Bangla Sangam MN"),
        new Fixture("arabic", "العربية", "Geeza Pro"),
        new Fixture("hebrew", "עברית", "Arial Hebrew"),
        new Fixture("bidi", "abc العربية עברית 123", "Dialog"),
        new Fixture("supplementary", "𝄞 𐐀", "Apple Symbols"),
        new Fixture("emoji", "😀 👩🏽", "Apple Color Emoji"),
        new Fixture("zwj", "👩\u200d💻 👨\u200d👩\u200d👧\u200d👦", "Apple Color Emoji"),
        new Fixture("variation", "✈\ufe0f ✈\ufe0e", "Apple Color Emoji"),
        new Fixture("mixed", "Unicode: e\u0301, क्ष, 👩\u200d💻, 中文", "Dialog"),
        new Fixture("unsupported-negative-control", "\udbff\udfff", "Dialog"));

    static BufferedImage image(JComponent component, int scale) {
        BufferedImage image = new BufferedImage(component.getWidth() * scale, component.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setClip(0, 0, image.getWidth(), image.getHeight()); g.scale(scale, scale);
            EditorUiController.applyEditorTextRenderingHints(g); component.paint(g);
        } finally { g.dispose(); }
        return image;
    }

    static Map<String, Object> encodedImage(String id, BufferedImage image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, "png", bytes));
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        GutterRenderingTest.assertPixelsEqual(image, decoded);
        return Map.of("id", id, "png_base64", Base64.getEncoder().encodeToString(bytes.toByteArray()), "png_roundtrip_exact", true);
    }

    @Test void recordCoverageShapingAndIndependentSwingPainting() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        var captures = new ArrayList<Map<String, Object>>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                long first = System.nanoTime(); Font bundled = BundledEditorFont.atSize(16); assertNotNull(bundled);
                var fonts = new LinkedHashMap<String, Font>(); fonts.put("bundled", bundled);
                fonts.put("registered-family", new Font(BundledEditorFont.FAMILY, Font.PLAIN, 16));
                fonts.put("public-style-context", new StyleContext().getFont(BundledEditorFont.FAMILY, Font.PLAIN, 16).deriveFont(16f));
                fonts.put("logical-monospace", new Font(Font.MONOSPACED, Font.PLAIN, 16));
                fonts.put("logical-dialog", new Font(Font.DIALOG, Font.PLAIN, 16));
                rows.add(Map.of("operation", "initial_font_setup", "ms", (System.nanoTime() - first) / 1e6));
                for (Fixture fixture : CORPUS) {
                    var options = new LinkedHashMap<>(fonts);
                    String reference = FontFamilyCatalog.resolve(fixture.referenceFamily());
                    if (reference != null) options.put("native-reference", new Font(reference, Font.PLAIN, 16));
                    for (var entry : options.entrySet()) {
                        Font font = entry.getValue();
                        BufferedImage canvas = new BufferedImage(600, 100, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = canvas.createGraphics();
                        try {
                            EditorUiController.applyEditorTextRenderingHints(g); g.setFont(font);
                            FontRenderContext context = g.getFontRenderContext();
                            int[] points = fixture.text().codePoints().toArray();
                            var coverage = new ArrayList<Map<String, Object>>(); int offset = 0;
                            for (int cp : points) {
                                coverage.add(Map.of("code_point", String.format("U+%04X", cp), "utf16_offset", offset,
                                    "can_display", font.canDisplay(cp), "default_ignorable_control", cp == 0x200d || cp == 0xfe0f || cp == 0xfe0e));
                                offset += Character.charCount(cp);
                            }
                            char[] chars = fixture.text().toCharArray();
                            GlyphVector vector = font.layoutGlyphVector(context, chars, 0, chars.length, Font.LAYOUT_LEFT_TO_RIGHT);
                            var glyphs = new ArrayList<Integer>();
                            for (int i = 0; i < vector.getNumGlyphs(); i++) glyphs.add(vector.getGlyphCode(i));
                            TextLayout layout = new TextLayout(fixture.text(), font, context);
                            g.setColor(new Color(0xFAFAFC)); g.fillRect(0, 0, 600, 100); g.setColor(new Color(0x30343C));
                            layout.draw(g, 4, 30);
                            JTextArea area = new JTextArea(fixture.text());
                            area.putClientProperty(SourcePlainView.SOURCE_PROPERTY, Boolean.TRUE);
                            area.setUI(new WorkbenchStyle.TextAreaUI()); area.setFont(font);
                            area.setBackground(new Color(0xFAFAFC)); area.setForeground(new Color(0x30343C));
                            area.setSize(600, 100); area.setCaretPosition(0); area.getCaret().setVisible(false);
                            BufferedImage swing = image(area, 1);
                            var row = new LinkedHashMap<String, Object>(); row.put("fixture", fixture.id());
                            row.put("source", fixture.text()); row.put("utf16_length", fixture.text().length());
                            row.put("mode", entry.getKey()); row.put("font_name", font.getFontName()); row.put("family", font.getFamily());
                            row.put("font_class", font.getClass().getName()); row.put("coverage", coverage);
                            row.put("reference_family_requested", fixture.referenceFamily()); row.put("reference_family_available", reference != null);
                            row.put("expected_behavior", fixture.id().equals("unsupported-negative-control")
                                ? "noncharacter negative control; missing glyph permitted"
                                : "assigned visible text; combining, ZWJ and variation controls may have no independent ink; shaping is not certified by scalar coverage");
                            row.put("source_utf8_sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(fixture.text().getBytes(StandardCharsets.UTF_8))));
                            row.put("missing_glyph_code", font.getMissingGlyphCode()); row.put("layout_glyph_codes", glyphs);
                            row.put("glyph_vector_direction", "diagnostic LTR; TextLayout and Swing separately resolve bidi text");
                            row.put("text_layout_advance", layout.getAdvance()); row.put("text_layout_bounds", layout.getBounds().toString());
                            row.put("font_metric_height", area.getFontMetrics(font).getHeight());
                            row.put("first_caret_bounds", area.modelToView2D(0).toString());
                            row.put("view_class", area.getUI().getRootView(area).getView(0).getClass().getName());
                            row.put("source_exact_after_paint", area.getText().equals(fixture.text())); rows.add(row);
                            assertEquals(fixture.text(), area.getText());
                            if (List.of("cjk", "indic-gb9c", "zwj", "mixed", "ascii").contains(fixture.id())) {
                                captures.add(encodedImage(fixture.id() + "-" + entry.getKey() + "-layout", canvas));
                                captures.add(encodedImage(fixture.id() + "-" + entry.getKey() + "-swing", swing));
                            }
                            area.setDocument(new javax.swing.text.PlainDocument());
                        } finally { g.dispose(); }
                    }
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
        System.out.println("GLYPH_QUALIFICATION " + MiniJson.stringify(Map.of("rows", rows, "captures", captures,
            "boundary", "in-process headless characterization, not universal glyph success or native capture acceptance",
            "jdk", System.getProperty("java.runtime.version"), "font_resource", BundledEditorFont.FAMILY)));
    }

    @Test void originalSourceAndGeometrySurviveScaledCaptureFontAndThemeRoundTrips() throws Exception {
        var captures = new ArrayList<Map<String, Object>>();
        var geometry = new ArrayList<Map<String, Object>>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                FileBuffer buffer = FileBuffer.createScratch("[B0 exact rendering]", B0_SOURCE);
                assertEquals(430, B0_SOURCE.length());
                Font bundled = BundledEditorFont.atSize(16); var config = new ConfigManager(); config.setTheme("shed-light");
                JTextArea left = new JTextArea(), right = new JTextArea();
                for (JTextArea area : List.of(left, right)) {
                    area.putClientProperty(SourcePlainView.SOURCE_PROPERTY, Boolean.TRUE);
                    area.setUI(new WorkbenchStyle.TextAreaUI()); area.setDocument(buffer.getDocument()); area.setFont(bundled);
                    area.setBackground(config.getNormalColor()); area.setForeground(config.getEditorForeground());
                    area.setSize(600, 500); area.select(4, 12); area.getCaret().setVisible(false);
                }
                var pane = new EditorPane(right, new LineNumberPanel(right), new JScrollPane(right), new SearchManager(right));
                pane.setBuffer(buffer);
                var snapshot = buffer.textSnapshot(); long revision = buffer.documentRevision(); boolean dirty = buffer.isModified();
                var undo = buffer.getUndoManager(); undo.discardAllEdits();
                BufferedImage before = image(left, 1);
                captures.add(encodedImage("b0-source-before-1x", before));
                captures.add(encodedImage("b0-source-2x", image(left, 2)));
                GutterRenderingTest.assertPixelsEqual(before, image(left, 1));
                for (int offset : new int[]{0, 4, 12, 28, B0_SOURCE.indexOf("👩"), 430}) {
                    var bounds = left.modelToView2D(offset); assertNotNull(bounds);
                    int mapped = left.viewToModel2D(new java.awt.geom.Point2D.Double(bounds.getX(), bounds.getCenterY()));
                    System.out.println("GLYPH_HIT_TEST " + MiniJson.stringify(Map.of("requested_utf16", offset,
                        "mapped_utf16", mapped, "source_length", B0_SOURCE.length(), "bounds", bounds.toString())));
                    assertTrue(mapped >= 0, "unavailable mapping at " + offset);
                    if (mapped > B0_SOURCE.length())
                        assertEquals(buffer.getDocument().getDefaultRootElement().getEndOffset(), mapped, "unexpected outlier");
                    if (offset == 4) assertEquals(4, mapped);
                    geometry.add(Map.of("utf16_offset", offset, "bounds", bounds.toString(), "raw_hit_test", mapped,
                        "raw_hit_test_is_source_offset", mapped <= B0_SOURCE.length()));
                }
                var originalBounds = left.modelToView2D(4);
                for (String theme : List.of("dracula", "shed-light")) {
                    config.setTheme(theme);
                    for (JTextArea area : List.of(left, right)) {
                        area.setForeground(config.getEditorForeground()); area.setBackground(config.getNormalColor());
                        area.setFont(new Font(Font.DIALOG, Font.PLAIN, 24)); image(area, 1);
                        area.setFont(bundled); image(area, 2);
                    }
                }
                assertEquals(originalBounds, left.modelToView2D(4));
                GutterRenderingTest.assertPixelsEqual(before, image(left, 1));
                GutterRenderingTest.assertPixelsEqual(image(left, 1), image(right, 1));
                captures.add(encodedImage("b0-source-restored-1x", image(left, 1)));
                assertSame(snapshot, buffer.textSnapshot()); assertEquals(revision, buffer.documentRevision());
                assertEquals(dirty, buffer.isModified()); assertSame(undo, buffer.getUndoManager()); assertFalse(undo.canUndo());
                assertEquals(4, left.getSelectionStart()); assertEquals(12, left.getSelectionEnd());
                assertEquals(B0_SOURCE, buffer.getContent());
                pane.disposeSourceView();
                left.append("\nexact edit"); undo.undo(); assertEquals(B0_SOURCE, left.getText());
                undo.redo(); assertEquals(B0_SOURCE + "\nexact edit", left.getText()); undo.undo();
                image(right, 1); assertEquals(0, right.getDocument().getLength());
                assertSame(buffer.getDocument(), left.getDocument()); assertEquals(B0_SOURCE, buffer.getContent());
                left.setDocument(new javax.swing.text.PlainDocument());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        System.out.println("GLYPH_CAPTURE " + MiniJson.stringify(Map.of("source", B0_SOURCE, "utf16_length", B0_SOURCE.length(),
            "first_bundled_unsupported_utf16_offset", BundledEditorFont.atSize(16).canDisplayUpTo(B0_SOURCE),
            "geometry", geometry, "captures", captures, "source_and_history_preserved", true,
            "boundary", "standalone source views; no live syntax overlay/main window or native capture replay")));
    }
}
