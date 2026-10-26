package shed;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.beans.PropertyChangeEvent;
import javax.swing.JTextArea;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;

/** A virtual insertion: Swing lays out a separate display document; the source never changes. */
final class InlineCompletionDisplay extends BasicTextAreaUI {
    private final JTextArea source;
    private JTextArea display;
    private int anchor, length;

    InlineCompletionDisplay(JTextArea source) {
        this.source = source;
        install();
    }

    private void install() {
        if (source.getUI() == this) return;
        Caret caret = source.getCaret(); int dot = caret.getDot(), mark = caret.getMark();
        Highlighter highlighter = source.getHighlighter();
        source.setUI(this);
        source.setHighlighter(highlighter); source.setCaret(caret);
        caret.setDot(mark); caret.moveDot(dot);
    }

    void show(int offset, String suggestion) throws BadLocationException {
        install();
        Document document = source.getDocument();
        String original = document.getText(0, document.getLength());
        anchor = offset; length = suggestion.length();
        display = new JTextArea(original.substring(0, offset) + suggestion + original.substring(offset));
        display.setUI(new GhostUI());
        configure(); modelChanged(); source.revalidate(); source.repaint();
    }

    void clear() {
        if (display == null) return;
        display = null; length = 0; modelChanged(); source.revalidate(); source.repaint();
    }

    boolean showing() { return display != null; }

    private void configure() {
        if (display == null) return;
        display.setFont(source.getFont()); display.setTabSize(source.getTabSize());
        display.setLineWrap(source.getLineWrap()); display.setWrapStyleWord(source.getWrapStyleWord());
        display.setForeground(source.getForeground()); display.setBackground(source.getBackground());
    }

    @Override protected void propertyChange(PropertyChangeEvent event) {
        configure(); super.propertyChange(event);
    }

    @Override public View create(Element element) {
        return display == null ? super.create(element) : new Projection(element, display.getUI().getRootView(display));
    }

    private int projected(int offset) { return offset <= anchor ? offset : offset + length; }
    private int original(int offset) { return offset <= anchor ? offset : offset < anchor + length ? anchor : offset - length; }

    private final class Projection extends View {
        private final View delegate;
        Projection(Element sourceElement, View delegate) { super(sourceElement); this.delegate = delegate; }
        @Override public float getPreferredSpan(int axis) { return delegate.getPreferredSpan(axis); }
        @Override public float getMinimumSpan(int axis) { return delegate.getMinimumSpan(axis); }
        @Override public float getMaximumSpan(int axis) { return delegate.getMaximumSpan(axis); }
        @Override public void setSize(float width, float height) { delegate.setSize(width, height); }
        @Override public void paint(Graphics graphics, Shape allocation) { delegate.paint(graphics, allocation); }
        @Override public Shape modelToView(int position, Shape allocation, Position.Bias bias) throws BadLocationException {
            return delegate.modelToView(projected(position), allocation, bias);
        }
        @Override public int viewToModel(float x, float y, Shape allocation, Position.Bias[] bias) {
            return original(delegate.viewToModel(x, y, allocation, bias));
        }
    }

    private Color ghost() {
        Color foreground = source.getForeground(), background = source.getBackground();
        return new Color((foreground.getRed() + background.getRed()) / 2,
            (foreground.getGreen() + background.getGreen()) / 2, (foreground.getBlue() + background.getBlue()) / 2);
    }

    private float draw(Graphics2D graphics, float x, float y, int start, int end, TabExpander tabs) throws BadLocationException {
        // Segment at the ghost boundaries, retaining absolute offsets and Swing's real tab stops.
        int position = start;
        while (position < end) {
            boolean suggested = position >= anchor && position < anchor + length;
            int limit = position < anchor ? Math.min(end, anchor) : suggested ? Math.min(end, anchor + length) : end;
            Segment segment = new Segment(); display.getDocument().getText(position, limit - position, segment);
            graphics.setColor(suggested ? ghost() : source.getForeground());
            x = Utilities.drawTabbedText(segment, x, y, graphics, tabs, position);
            position = limit;
        }
        return x;
    }

    private final class GhostUI extends BasicTextAreaUI {
        @Override public View create(Element element) {
            return display.getLineWrap() ? new WrappedGhost(element, display.getWrapStyleWord()) : new PlainGhost(element);
        }
    }
    private final class PlainGhost extends PlainView {
        PlainGhost(Element element) { super(element); }
        @Override protected float drawUnselectedText(Graphics2D graphics, float x, float y, int start, int end) throws BadLocationException {
            return draw(graphics, x, y, start, end, this);
        }
    }
    private final class WrappedGhost extends WrappedPlainView {
        WrappedGhost(Element element, boolean words) { super(element, words); }
        @Override protected float drawUnselectedText(Graphics2D graphics, float x, float y, int start, int end) throws BadLocationException {
            return draw(graphics, x, y, start, end, this);
        }
    }
}
