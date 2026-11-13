package shed;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Shape;
import java.util.Objects;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;

/** Keeps Swing painting/geometry; avoids scanning all lines when undo restores a known widest line. */
final class SourcePlainView extends PlainView {
    static final String SOURCE_PROPERTY = "shed.sourceView";
    private boolean eligible;
    private Font extentFont;
    private FontMetrics extentMetrics;
    private float knownWidth = Float.NaN;
    private Element grownLine;
    private float previousMaximum;

    private SourcePlainView(Element element) { super(element); }

    static View adapt(View stock) {
        return stock != null && stock.getClass() == PlainView.class ? new SourcePlainView(stock.getElement()) : stock;
    }

    @Override public void setParent(View parent) {
        super.setParent(parent);
        clearWitness();
        knownWidth = Float.NaN;
        extentFont = null;
        extentMetrics = null;
        // One attachment scan; no document copy or per-line metadata. Tabs/complex glyphs retain
        // stock behavior, including Swing's font/tab initialization and invalidation semantics.
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

    private boolean sameMetrics() {
        return extentFont == getContainer().getFont() && Objects.equals(extentMetrics, metrics);
    }

    private void remember(float width) {
        knownWidth = width;
        extentFont = getContainer().getFont();
        extentMetrics = metrics;
    }

    private void clearWitness() { grownLine = null; }

    @Override public float getPreferredSpan(int axis) {
        float span = super.getPreferredSpan(axis);
        if (eligible && axis == X_AXIS && (Float.isNaN(knownWidth) || !sameMetrics())) {
            clearWitness();
            remember(span);
        }
        // Another document listener may query size after mutation but before this view's event.
        // Keep the pre-event bound until updateDamage has processed that event.
        return span;
    }

    @SuppressWarnings("deprecation") // Same integer API as PlainView.getLineWidth in Java 21.
    private int width(Element line) throws BadLocationException {
        Segment segment = new Segment();
        int start = line.getStartOffset();
        getDocument().getText(start, line.getEndOffset() - start, segment);
        return Utilities.getTabbedTextWidth(segment, metrics, 0, null, start);
    }

    @Override protected void updateDamage(DocumentEvent event, Shape allocation, ViewFactory factory) {
        if (eligible && event.getType() == DocumentEvent.EventType.INSERT
            && !simpleRange(event.getOffset(), event.getLength())) eligible = false;
        if (!eligible) {
            clearWitness();
            super.updateDamage(event, allocation, factory);
            return;
        }
        updateMetrics();
        // Preferred spans are floats; beyond this bound adjacent integer pixel widths can alias.
        boolean valid = knownWidth >= 0 && knownWidth < (1 << 24) && sameMetrics();
        float before = knownWidth;
        Element root = getElement();
        boolean local = event.getChange(root) == null;
        int index = root.getElementIndex(event.getOffset());
        Element line = root.getElement(index);
        boolean restored = false;
        if (valid && local && event.getType() == DocumentEvent.EventType.REMOVE && grownLine != null && line == grownLine) {
            try { restored = width(line) >= previousMaximum; }
            catch (BadLocationException unavailable) { /* Use stock invalidation. */ }
        }
        if (restored) {
            // Other lines are unchanged and <= previousMaximum. Swing's widest-line reference
            // remains valid; only damage this line and notify horizontal preference.
            damageLineRange(index, index, allocation, getContainer());
            preferenceChanged(null, true, false);
        } else {
            super.updateDamage(event, allocation, factory);
            clearWitness();
        }
        float after = super.getPreferredSpan(X_AXIS);
        remember(after);
        if (valid && local && event.getType() == DocumentEvent.EventType.INSERT && after > before) {
            grownLine = line;
            previousMaximum = before;
        }
    }
}
