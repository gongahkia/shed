package shed;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Shape;
import java.util.Arrays;
import java.util.Objects;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;

/** PlainView painting/geometry with a bounded summary of widest source lines. EDT-owned, like PlainView. */
final class SourcePlainView extends PlainView {
    static final String SOURCE_PROPERTY = "shed.sourceView";
    static final int MAX_WIDTH_CANDIDATES = 64;
    private boolean eligible, ready;
    private Font extentFont;
    private FontMetrics extentMetrics;
    private final Segment measurement = new Segment();
    private Element[] candidates;
    private int[] widths;
    private int count, minimum, maximumWidth, untrackedUpperBound;

    private SourcePlainView(Element element) { super(element); }

    static View adapt(View stock) {
        return stock != null && stock.getClass() == PlainView.class ? new SourcePlainView(stock.getElement()) : stock;
    }

    @Override public void setParent(View parent) {
        super.setParent(parent);
        reset();
        extentFont = null;
        extentMetrics = null;
        // Partial Segments avoid flattening/copying the document. Complex text and tabs retain the
        // stock view's exact shaping and tab initialization behavior; check inserts incrementally.
        eligible = parent != null && simpleRange(0, getDocument().getLength());
    }

    private boolean simpleRange(int offset, int length) {
        Segment segment = new Segment();
        segment.setPartialReturn(true);
        try {
            for (int end = offset + length; offset < end;) {
                getDocument().getText(offset, Math.min(8192, end - offset), segment);
                if (segment.count == 0) return false;
                for (int i = segment.offset; i < segment.offset + segment.count; i++) {
                    if (segment.array[i] > 127 || segment.array[i] == '\t') return false;
                }
                offset += segment.count;
            }
            return true;
        } catch (BadLocationException unavailable) { return false; }
    }

    private void reset() {
        if (candidates != null) Arrays.fill(candidates, null);
        count = minimum = maximumWidth = untrackedUpperBound = 0;
        ready = false;
    }

    @SuppressWarnings("deprecation") // Same integer API as PlainView.getLineWidth in Java 21.
    private int width(Element line) throws BadLocationException {
        int start = line.getStartOffset();
        int length = line.getEndOffset() - start;
        if (length > 1_000_000) throw new ArithmeticException("Use stock geometry for extreme lines");
        try {
            getDocument().getText(start, length, measurement);
            int value = Utilities.getTabbedTextWidth(measurement, metrics, 0, null, start);
            // Keep float-origin rounding/overflow at extreme pixel coordinates in the stock view.
            if (value < 0 || value >= (1 << 23)) throw new ArithmeticException("Use stock geometry for extreme widths");
            return value;
        } finally { measurement.array = null; }
    }

    private void findMinimum() {
        minimum = 0;
        maximumWidth = 0;
        for (int i = 0; i < count; i++) {
            if (widths[i] < widths[minimum]) minimum = i;
            maximumWidth = Math.max(maximumWidth, widths[i]);
        }
    }

    private void put(Element line) throws BadLocationException {
        int value = width(line);
        for (int i = 0; i < count; i++) {
            if (candidates[i] == line) { widths[i] = value; findMinimum(); return; }
        }
        if (count < MAX_WIDTH_CANDIDATES) {
            candidates[count] = line;
            widths[count++] = value;
            findMinimum();
        } else if (value > widths[minimum]) {
            untrackedUpperBound = Math.max(untrackedUpperBound, widths[minimum]);
            candidates[minimum] = line;
            widths[minimum] = value;
            findMinimum();
        } else untrackedUpperBound = Math.max(untrackedUpperBound, value);
    }

    private void remove(Element line) {
        for (int i = 0; i < count; i++) {
            if (candidates[i] == line) {
                candidates[i] = candidates[--count]; widths[i] = widths[count]; candidates[count] = null;
                findMinimum(); return;
            }
        }
    }

    private int maximum() {
        return maximumWidth;
    }

    private void rebuild() throws BadLocationException {
        reset();
        if (candidates == null) { candidates = new Element[MAX_WIDTH_CANDIDATES]; widths = new int[MAX_WIDTH_CANDIDATES]; }
        Element root = getElement();
        for (int i = 0; i < root.getElementCount(); i++) put(root.getElement(i));
        extentFont = getContainer().getFont(); extentMetrics = metrics; ready = true;
    }

    private void ensureSummary() throws BadLocationException {
        updateMetrics(); // Retain PlainView's font, tab and painting initialization.
        if (!ready || extentFont != getContainer().getFont() || !Objects.equals(extentMetrics, metrics)) rebuild();
    }

    private void useStock() {
        eligible = false; reset(); extentFont = null; extentMetrics = null;
        // Our width policy did not maintain PlainView's private widest-line reference. Its protected
        // metrics invalidation forces a fresh stock calculation before handing control back.
        metrics = null;
    }

    @Override public float getPreferredSpan(int axis) {
        if (!eligible || axis != X_AXIS) return super.getPreferredSpan(axis);
        try { ensureSummary(); return maximum(); }
        catch (BadLocationException | ArithmeticException unavailable) { useStock(); return super.getPreferredSpan(axis); }
    }

    @Override protected void updateDamage(DocumentEvent event, Shape allocation, ViewFactory factory) {
        if (eligible && event.getType() == DocumentEvent.EventType.INSERT
            && !simpleRange(event.getOffset(), event.getLength())) useStock();
        if (!eligible) { super.updateDamage(event, allocation, factory); return; }
        try {
            ensureSummary();
            int before = maximum();
            Element root = getElement();
            DocumentEvent.ElementChange change = event.getChange(root);
            boolean structural = change != null;
            if (structural) {
                Element[] removed = change.getChildrenRemoved(), added = change.getChildrenAdded();
                if (removed.length + added.length > MAX_WIDTH_CANDIDATES) rebuild();
                else {
                    for (Element line : removed) remove(line);
                    for (Element line : added) put(line);
                }
            } else put(root.getElement(root.getElementIndex(event.getOffset())));
            // Every untracked line is <= this conservative bound. Refresh only when the remaining
            // candidates cannot prove the maximum, e.g. many distinct widest lines were removed.
            if (maximum() < untrackedUpperBound) rebuild();
            if (structural || before != maximum()) preferenceChanged(null, true, structural);
            if (structural) getContainer().repaint();
            else {
                int line = root.getElementIndex(event.getOffset());
                damageLineRange(line, line, allocation, getContainer());
            }
        } catch (BadLocationException | ArithmeticException unavailable) {
            useStock(); super.updateDamage(event, allocation, factory);
        }
    }
}
