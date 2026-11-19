package shed;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.text.*;

/** Test-only range experiment. The canonical document and JDK view implementations stay unchanged. */
public final class ParagraphRangePrototype extends WorkbenchStyle.TextAreaUI {
    public static ComponentUI createUI(JComponent component) { return new ParagraphRangePrototype(); }

    @Override public View create(Element element) {
        if (Boolean.TRUE.equals(getComponent().getClientProperty(SourcePlainView.SOURCE_PROPERTY))
                && element.getDocument() instanceof PlainDocument
                && Boolean.TRUE.equals(element.getDocument().getProperty("i18n"))
                && element.isLeaf() && AbstractDocument.ContentElementName.equals(element.getName())
                && element.getParentElement() == element.getDocument().getDefaultRootElement()) {
            return super.create(new Leaf(element));
        }
        return super.create(element);
    }

    private record Leaf(Element original) implements Element {
        @Override public Document getDocument() { return original.getDocument(); }
        @Override public Element getParentElement() { return new BreakContext(this); }
        @Override public String getName() { return original.getName(); }
        @Override public AttributeSet getAttributes() { return original.getAttributes(); }
        @Override public int getStartOffset() { return original.getStartOffset(); }
        @Override public int getEndOffset() { return original.getEndOffset(); }
        @Override public int getElementIndex(int offset) { return original.getElementIndex(offset); }
        @Override public int getElementCount() { return original.getElementCount(); }
        @Override public Element getElement(int index) { return original.getElement(index); }
        @Override public boolean isLeaf() { return true; }
    }

    private record BreakContext(Leaf leaf) implements Element {
        @Override public Document getDocument() { return leaf.getDocument(); }
        @Override public Element getParentElement() { return null; }
        @Override public String getName() { return AbstractDocument.ParagraphElementName; }
        @Override public AttributeSet getAttributes() { return leaf.original().getParentElement().getAttributes(); }
        // A preceding LF and following code point preserve paragraph-edge break context.
        // Whether this is sufficient for every supported script is a prototype correctness gate.
        @Override public int getStartOffset() { return Math.max(0, leaf.getStartOffset() - 1); }
        @Override public int getEndOffset() {
            int end = leaf.getEndOffset(), limit = getDocument().getDefaultRootElement().getEndOffset();
            if (end >= limit) return limit;
            Segment next = new Segment();
            try {
                getDocument().getText(end, Math.min(2, limit - end), next);
                int extra = next.count > 1 && Character.isHighSurrogate(next.array[next.offset])
                    && Character.isLowSurrogate(next.array[next.offset + 1]) ? 2 : 1;
                return Math.min(limit, end + extra);
            } catch (BadLocationException stale) {
                return limit;
            } finally { next.array = null; }
        }
        @Override public int getElementIndex(int offset) { return 0; }
        @Override public int getElementCount() { return 1; }
        @Override public Element getElement(int index) { return index == 0 ? leaf : null; }
        @Override public boolean isLeaf() { return false; }
    }
}
