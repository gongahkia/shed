package shed;

import java.awt.*;
import java.awt.event.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.beans.PropertyChangeListener;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.*;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.plaf.TextUI;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;
import static shed.MarkdownPresentationFrame.*;

/** Test/probe-only view adapter. No production registration or source mutation. */
final class MarkdownPresentationPrototype implements AutoCloseable {
    static final int CACHE_ROWS = 128, CACHE_UNITS = 16384;
    static final String LINK_COLOR = "shed.prototype.linkForeground";
    record Binding(MarkdownSemanticSnapshot.Source source, Document document) { }
    record Ticket(MarkdownSemanticSnapshot.Source source, Settings settings, long generation) { }
    record Metrics(int rows, int decorations, int cachedRows, int cachedUnits, long shaped, long invalidated, long staleRejected) { }
    private final JTextArea area;
    private Supplier<Binding> binding;
    private final TextUI originalUI;
    private final Caret originalCaret;
    private final PrototypeUI ui = new PrototypeUI();
    private final PropertyChangeListener properties;
    private final java.awt.event.ComponentListener sizeListener;
    private final javax.swing.event.CaretListener caretListener;
    private final java.awt.event.InputMethodListener composition;
    private MarkdownPresentationFrame frame;
    private long generation = 1, shaped, invalidated, staleRejected;
    private int lastWidth, cacheUnits;
    private boolean closed, composing;
    private final LinkedHashMap<Key, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private record Key(Row row, boolean active) { }
    private record Cached(String text, TextLayout layout) { }

    MarkdownPresentationPrototype(JTextArea area, Supplier<Binding> binding) {
        edt(); this.area = area; this.binding = binding; originalUI = area.getUI(); originalCaret = area.getCaret();
        int dot = area.getCaretPosition(), mark = area.getCaret().getMark();
        area.setUI(ui);
        // BlockCaret redraws one glyph in the uniform editor font; do not pretend it supports variable typography.
        var caret = new DefaultCaret(); caret.setBlinkRate(0); area.setCaret(caret); area.setCaretPosition(mark); area.moveCaretPosition(dot);
        lastWidth = width();
        properties = event -> {
            if (Set.of("font", "foreground", "background", "selectionColor", "lineWrap", "document", "componentOrientation", "UI", LINK_COLOR).contains(event.getPropertyName())) invalidate();
        };
        area.addPropertyChangeListener(properties);
        sizeListener = new ComponentAdapter() { @Override public void componentResized(ComponentEvent event) { if (width() != lastWidth) { lastWidth = width(); invalidate(); } } };
        area.addComponentListener(sizeListener);
        caretListener = event -> { clearCache(); area.repaint(); };
        area.addCaretListener(caretListener);
        composition = new InputMethodListener() {
            public void caretPositionChanged(InputMethodEvent event) { }
            public void inputMethodTextChanged(InputMethodEvent event) {
                composing = event.getText() != null && event.getCommittedCharacterCount() < event.getText().getEndIndex(); invalidate();
            }
        };
        area.addInputMethodListener(composition);
    }
    private int width() {
        int available = area.getParent() instanceof JViewport viewport ? viewport.getExtentSize().width : area.getWidth();
        return Math.max(32, available - area.getInsets().left - area.getInsets().right);
    }
    Settings settings() {
        Color fg = area.getForeground(), bg = area.getBackground();
        Color code = new Color((fg.getRed() + bg.getRed() * 11) / 12, (fg.getGreen() + bg.getGreen() * 11) / 12, (fg.getBlue() + bg.getBlue() * 11) / 12);
        Color link = area.getClientProperty(LINK_COLOR) instanceof Color color ? color : fg;
        return new Settings(area.getFont(), fg, bg, link, code, Math.min(16384, width()), area.getLineWrap(), true,
            area.getFontMetrics(area.getFont()).getFontRenderContext());
    }
    Ticket capture() {
        edt(); if (closed) throw new IllegalStateException("Prototype closed");
        Binding current = binding.get();
        if (area.getDocument() != current.document()) throw new IllegalStateException("Foreign document binding");
        lastWidth = width(); return new Ticket(current.source(), settings(), generation);
    }
    boolean install(MarkdownPresentationFrame result) {
        edt();
        Binding current = closed ? null : binding.get();
        if (closed || composing || area.getUI() != ui || area.getDocument() != current.document()
                || !result.stamp.matches(current.source(), generation) || !result.settings.equals(settings())) { staleRejected++; return false; }
        frame = result.state == State.READY ? result : null; clearCache(); area.revalidate(); area.repaint(); return result.state == State.READY;
    }
    private MarkdownPresentationFrame currentFrame() {
        if (closed || composing || frame == null) return null;
        Binding current = binding.get();
        if (area.getDocument() != current.document() || !frame.stamp.matches(current.source(), generation) || !frame.settings.equals(settings())) { invalidate(); return null; }
        return frame;
    }
    boolean styled() { edt(); return currentFrame() != null; }
    private void invalidate() { frame = null; generation++; invalidated++; clearCache(); area.revalidate(); area.repaint(); }
    private void clearCache() { cache.clear(); cacheUnits = 0; }
    Metrics metrics() { return new Metrics(frame == null ? 0 : frame.rows.size(), frame == null ? 0 : frame.styles.size(), cache.size(), cacheUnits, shaped, invalidated, staleRejected); }
    private Cached layout(MarkdownPresentationFrame value, Row row) {
        boolean active = area.getDocument().getDefaultRootElement().getElementIndex(area.getCaretPosition()) == row.line();
        Key key = new Key(row, active); Cached found = cache.get(key); if (found != null) return found;
        try {
            String text = area.getDocument().getText(row.start(), row.end() - row.start());
            TextLayout layout = new TextLayout(value.attributed(text.isEmpty() ? " " : text, row.start(), active).getIterator(), value.settings.frc());
            found = new Cached(text, layout); shaped++;
            cache.put(key, found); cacheUnits += text.length();
            var iterator = cache.entrySet().iterator();
            while ((cache.size() > CACHE_ROWS || cacheUnits > CACHE_UNITS) && iterator.hasNext()) { cacheUnits -= iterator.next().getValue().text().length(); iterator.remove(); }
            return found;
        } catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
    }
    private final class PrototypeUI extends BasicTextAreaUI {
        @Override public View create(Element element) { return new Bridge(element, SourcePlainView.adapt(super.create(element))); }
    }
    private final class Bridge extends View {
        private final View stock;
        private final Styled styled;
        Bridge(Element element, View stock) { super(element); this.stock = stock; styled = new Styled(element); }
        @Override public void setParent(View parent) { super.setParent(parent); stock.setParent(parent == null ? null : this); styled.setParent(parent == null ? null : this); }
        private View active() { return currentFrame() == null ? stock : styled; }
        @Override public int getViewCount() { return 1; }
        @Override public View getView(int index) { if (index != 0) throw new ArrayIndexOutOfBoundsException(index); return active(); }
        @Override public int getViewIndex(int position, Position.Bias bias) { return 0; }
        @Override public Shape getChildAllocation(int index, Shape allocation) { return allocation; }
        @Override public void setSize(float width, float height) { if (currentFrame() == null) stock.setSize(width, height); }
        @Override public float getPreferredSpan(int axis) { return active().getPreferredSpan(axis); }
        @Override public void paint(Graphics graphics, Shape allocation) { active().paint(graphics, allocation); }
        @Override public Shape modelToView(int position, Shape allocation, Position.Bias bias) throws BadLocationException { return active().modelToView(position, allocation, bias); }
        @Override public Shape modelToView(int start, Position.Bias first, int end, Position.Bias last, Shape allocation) throws BadLocationException { return active().modelToView(start, first, end, last, allocation); }
        @Override public int viewToModel(float x, float y, Shape allocation, Position.Bias[] bias) { return active().viewToModel(x, y, allocation, bias); }
        @Override public int getNextVisualPositionFrom(int position, Position.Bias bias, Shape allocation, int direction, Position.Bias[] result) throws BadLocationException { return active().getNextVisualPositionFrom(position, bias, allocation, direction, result); }
        @Override public void insertUpdate(DocumentEvent event, Shape allocation, ViewFactory factory) { invalidate(); stock.insertUpdate(event, allocation, factory); preferenceChanged(null, true, true); }
        @Override public void removeUpdate(DocumentEvent event, Shape allocation, ViewFactory factory) { invalidate(); stock.removeUpdate(event, allocation, factory); preferenceChanged(null, true, true); }
        @Override public void changedUpdate(DocumentEvent event, Shape allocation, ViewFactory factory) { invalidate(); stock.changedUpdate(event, allocation, factory); preferenceChanged(null, true, true); }
    }
    private final class Styled extends View {
        Styled(Element element) { super(element); }
        @Override public float getPreferredSpan(int axis) { var value = currentFrame(); return value == null ? 0 : axis == X_AXIS ? value.width : value.height; }
        @Override public void paint(Graphics graphics, Shape allocation) {
            var value = currentFrame(); if (value == null) return;
            var origin = allocation.getBounds(); var clip = graphics.getClipBounds(); if (clip == null) clip = origin;
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, value.settings.frc().getAntiAliasingHint());
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, value.settings.frc().getFractionalMetricsHint());
                int first = value.rowAtY(clip.y - origin.y);
                for (int index = first; index < value.rows.size(); index++) {
                    Row row = value.rows.get(index); float top = origin.y + row.y(); if (top > clip.y + clip.height) break;
                    if (row.object() != null) {
                        g.setColor(value.settings.code()); g.fill(new Rectangle2D.Float(origin.x + 4, top + 3, row.width() - 8, row.height() - 6));
                        g.setColor(value.settings.foreground()); g.setFont(value.settings.font()); g.drawString("media placeholder", origin.x + 10, top + row.height() / 2 + 4); continue;
                    }
                    Cached cached = layout(value, row); TextLayout glyphs = cached.layout();
                    int start = Math.max(row.start(), area.getSelectionStart()), end = Math.min(row.end(), area.getSelectionEnd());
                    if (start < end) {
                        AttributedString selected = value.attributed(cached.text(), row.start(), true);
                        selected.addAttribute(TextAttribute.FOREGROUND, area.getSelectedTextColor(), start - row.start(), end - row.start());
                        selected.addAttribute(TextAttribute.BACKGROUND, area.getSelectionColor(), start - row.start(), end - row.start());
                        glyphs = new TextLayout(selected.getIterator(), value.settings.frc());
                    }
                    if (!cached.text().isEmpty()) glyphs.draw(g, origin.x, top + row.ascent());
                }
            } finally { g.dispose(); }
        }
        @Override public Shape modelToView(int position, Shape allocation, Position.Bias bias) throws BadLocationException {
            if (position < 0 || position > getDocument().getLength()) throw new BadLocationException("Invalid source offset", position);
            var value = currentFrame(); if (value == null) return allocation;
            Row row = value.rowAtOffset(position, bias == Position.Bias.Backward); Cached cached = layout(value, row);
            int relative = Math.min(Math.max(0, position - row.start()), cached.text().length());
            float x = cached.text().isEmpty() ? 0 : cached.layout().getCaretInfo(TextHitInfo.leading(relative))[0];
            var origin = allocation.getBounds(); return new Rectangle2D.Float(origin.x + x, origin.y + row.y(), 1, row.height());
        }
        @Override public Shape modelToView(int start, Position.Bias first, int end, Position.Bias last, Shape allocation) throws BadLocationException {
            return modelToView(start, allocation, first).getBounds2D().createUnion(modelToView(end, allocation, last).getBounds2D());
        }
        @Override public int viewToModel(float x, float y, Shape allocation, Position.Bias[] bias) {
            bias[0] = Position.Bias.Forward; var value = currentFrame(); if (value == null) return 0;
            var origin = allocation.getBounds(); Row row = value.rows.get(value.rowAtY(y - origin.y));
            if (row.object() != null) return row.object().start();
            return hit(value, row, x - origin.x);
        }
        private int hit(MarkdownPresentationFrame value, Row row, float x) {
            Cached cached = layout(value, row); if (cached.text().isEmpty()) return row.start();
            int offset = Math.min(cached.text().length(), Math.max(0, cached.layout().hitTestChar(x, 0).getInsertionIndex()));
            if (offset > 0 && offset < cached.text().length() && Character.isLowSurrogate(cached.text().charAt(offset)) && Character.isHighSurrogate(cached.text().charAt(offset - 1))) offset--;
            return row.start() + offset;
        }
        @Override public int getNextVisualPositionFrom(int position, Position.Bias bias, Shape allocation, int direction, Position.Bias[] result) throws BadLocationException {
            result[0] = Position.Bias.Forward; var value = currentFrame(); if (value == null || position < 0) return 0;
            Row row = value.rowAtOffset(position, bias == Position.Bias.Backward);
            if (direction == SwingConstants.NORTH || direction == SwingConstants.SOUTH) {
                int index = Collections.binarySearch(value.textRows, row, Comparator.comparingDouble(Row::y));
                index = Math.max(0, Math.min(value.textRows.size() - 1, index + (direction == SwingConstants.NORTH ? -1 : 1)));
                Point magic = area.getCaret().getMagicCaretPosition();
                float x = (float) modelToView(position, allocation, bias).getBounds2D().getX() - allocation.getBounds().x;
                return hit(value, value.textRows.get(index), magic == null ? x : magic.x - allocation.getBounds().x);
            }
            Element logical = getDocument().getDefaultRootElement().getElement(row.line());
            int start = logical.getStartOffset(), end = Math.min(getDocument().getLength(), logical.getEndOffset());
            String text = getDocument().getText(start, end - start);
            BreakIterator breaks = BreakIterator.getCharacterInstance(Locale.ROOT); breaks.setText(text);
            int next = direction == SwingConstants.WEST ? breaks.preceding(position - start) : breaks.following(position - start);
            if (next != BreakIterator.DONE) return start + next;
            return Math.min(getDocument().getLength(), Math.max(0, direction == SwingConstants.WEST ? start - 1 : end));
        }
    }
    @Override public void close() {
        edt(); if (closed) return; closed = true; frame = null; clearCache();
        area.removePropertyChangeListener(properties); area.removeComponentListener(sizeListener); area.removeCaretListener(caretListener); area.removeInputMethodListener(composition);
        int dot = area.getCaretPosition(), mark = area.getCaret().getMark();
        if (area.getUI() == ui) { area.setUI(originalUI); area.setCaret(originalCaret); area.setCaretPosition(mark); area.moveCaretPosition(dot); }
        binding = null;
    }
    private static void edt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Presentation installation requires EDT"); }
}
