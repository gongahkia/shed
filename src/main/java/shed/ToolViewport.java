package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.function.IntSupplier;
import javax.swing.*;

/** Keeps a tool's layout usable when the user makes its dock shorter than its controls. */
final class ToolViewport extends JScrollPane {
    static final String PROPERTY = "shed.toolViewport";
    private final class Content extends JPanel implements Scrollable {
        Content(JPanel surface) { super(new BorderLayout()); add(surface); }
        private int requiredHeight() { return Math.max(minimumHeight.getAsInt(), getComponent(0).getMinimumSize().height); }
        @Override public Dimension getPreferredSize() { return new Dimension(1, requiredHeight()); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return WorkbenchStyle.px(24); }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(WorkbenchStyle.px(24), visible.height - WorkbenchStyle.px(24)); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() >= requiredHeight(); }
    }
    private final IntSupplier minimumHeight;

    static void reveal(JComponent component, Rectangle area) {
        component.scrollRectToVisible(area);
        // A nested viewport consumes scrollRectToVisible; reveal through each ancestor too.
        for (java.awt.Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof JViewport viewport) viewport.scrollRectToVisible(SwingUtilities.convertRectangle(component, area, viewport));
        }
    }

    ToolViewport(JPanel surface, IntSupplier minimumHeight) {
        super(VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
        this.minimumHeight = minimumHeight;
        putClientProperty(PROPERTY, true);
        setBorder(BorderFactory.createEmptyBorder());
        setViewportView(new Content(surface));
        getVerticalScrollBar().setUnitIncrement(WorkbenchStyle.px(24));
        setMinimumSize(new Dimension(0, 80));
    }
}
