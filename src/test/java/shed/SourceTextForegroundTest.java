package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import org.junit.jupiter.api.Test;

/** Real source UI/view paints, without a desktop window or native process. */
class SourceTextForegroundTest {
    private static final Color BLUE = new Color(0x315FBA), RED = new Color(0xC45555);

    private static JTextArea area(String text, boolean wrap, boolean words, boolean source) {
        JTextArea area = new JTextArea(text) {
            @Override protected void paintComponent(Graphics graphics) {
                EditorUiController.applyEditorTextRenderingHints((Graphics2D) graphics);
                super.paintComponent(graphics);
            }
        };
        area.putClientProperty(SourcePlainView.SOURCE_PROPERTY, source);
        area.setUI(new WorkbenchStyle.TextAreaUI());
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 19));
        area.setForeground(BLUE);area.setBackground(Color.WHITE);area.setTabSize(4);
        area.setSelectedTextColor(Color.WHITE);area.setSelectionColor(new Color(0x547368));
        area.setLineWrap(wrap);area.setWrapStyleWord(words);area.setSize(230,200);
        area.getCaret().setVisible(false);
        return area;
    }

    private static BufferedImage paint(JTextArea area, int scale) {
        BufferedImage image = new BufferedImage(area.getWidth()*scale,area.getHeight()*scale,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { graphics.scale(scale,scale);area.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    private static void samePixels(BufferedImage expected, BufferedImage actual) {
        assertArrayEquals(expected.getRGB(0,0,expected.getWidth(),expected.getHeight(),null,0,expected.getWidth()),
            actual.getRGB(0,0,actual.getWidth(),actual.getHeight(),null,0,actual.getWidth()),
            "source glyphs must match a single stock text paint");
    }

    @Test void overlappingLexicalAndSemanticTokensPaintEachGlyphOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String text="import FutureTask;\nvalue = 42;";
            JTextArea expected=area(text,false,false,false),actual=area(text,false,false,true);
            actual.setForeground(Color.BLACK);
            SourceTextForeground.install(actual,()->List.of(new SyntaxSpan(0,text.length(),RED)),
                ()->List.of(new SyntaxSpan(0,text.length(),BLUE)));
            assertTrue(SourcePlainView.paintsForeground(actual));
            for(int scale:new int[]{1,2})samePixels(paint(expected,scale),paint(actual,scale));
        });
    }

    @Test void coloredRunsRetainTabsWrappingSelectionHighlightsAndDocumentGeometry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                String text="alpha\tcafé value = 42;\nmore repeated words here";
                for(boolean wrap:new boolean[]{false,true})for(boolean words:new boolean[]{false,true}){
                    JTextArea expected=area(text,wrap,words,false),actual=area(text,wrap,words,true);
                    var events=new AtomicInteger();actual.getDocument().addDocumentListener(new DocumentListener(){
                        public void insertUpdate(DocumentEvent event){events.incrementAndGet();}
                        public void removeUpdate(DocumentEvent event){events.incrementAndGet();}
                        public void changedUpdate(DocumentEvent event){events.incrementAndGet();}
                    });
                    SourceTextForeground.install(actual,()->List.of(new SyntaxSpan(0,5,BLUE),new SyntaxSpan(6,10,BLUE),
                        new SyntaxSpan(11,16,BLUE),new SyntaxSpan(19,21,BLUE)),List::of);
                    assertTrue(SourcePlainView.paintsForeground(actual));
                    for(JTextArea area:new JTextArea[]{expected,actual}){
                        area.getHighlighter().addHighlight(11,16,new DefaultHighlighter.DefaultHighlightPainter(new Color(0xFFE5A5)));
                        area.select(18,21);area.getCaret().setSelectionVisible(true);
                    }
                    for(int scale:new int[]{1,2}){
                        try{samePixels(paint(expected,scale),paint(actual,scale));}
                        catch(AssertionError error){throw new AssertionError("wrap="+wrap+", words="+words+", scale="+scale,error);}
                    }
                    assertEquals(expected.getPreferredSize(),actual.getPreferredSize());
                    for(int i=0;i<=text.length();i++)assertEquals(expected.modelToView2D(i),actual.modelToView2D(i));
                    assertEquals(text,actual.getText());assertEquals(0,events.get());
                }
            } catch(Exception error){throw new AssertionError(error);}
        });
    }

    @Test void completionDismissalRestoresTheColoredSourceView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                String text="FutureTask value;";
                for(boolean wrap:new boolean[]{false,true}){
                    JTextArea expected=area(text,wrap,true,false),actual=area(text,wrap,true,true);
                    SourceTextForeground.install(actual,()->List.of(new SyntaxSpan(0,text.length(),BLUE)),List::of);
                    var completion=new InlineCompletionDisplay(actual);completion.show(11,"suggested ");completion.clear();
                    assertTrue(SourcePlainView.paintsForeground(actual));
                    samePixels(paint(expected,1),paint(actual,1));assertEquals(text,actual.getText());
                }
            } catch(Exception error){throw new AssertionError(error);}
        });
    }
}
