package shed;

import java.awt.*;

/** FlowLayout's wrapping with a preferred height that includes every visible row. */
final class WrappingFlowLayout extends FlowLayout {
    WrappingFlowLayout(FlowLayout source) {
        super(source.getAlignment(), source.getHgap(), source.getVgap());
        setAlignOnBaseline(source.getAlignOnBaseline());
    }

    @Override public Dimension preferredLayoutSize(Container target) { return size(target, false); }
    @Override public Dimension minimumLayoutSize(Container target) { return size(target, true); }

    private Dimension size(Container target, boolean minimum) {
        synchronized (target.getTreeLock()) {
            int available = target.getWidth();
            for (Container parent = target.getParent(); available <= 0 && parent != null; parent = parent.getParent()) available = parent.getWidth();
            Insets insets = target.getInsets();
            int padding = insets.left + insets.right + getHgap() * 2;
            int limit = available > 0 ? Math.max(1, available - padding) : Integer.MAX_VALUE;
            int width = 0, height = 0, rowWidth = 0, rowHeight = 0;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) continue;
                Dimension d = minimum ? child.getMinimumSize() : child.getPreferredSize();
                int gap = rowWidth == 0 ? 0 : getHgap();
                if (rowWidth != 0 && rowWidth + gap + d.width > limit) {
                    width = Math.max(width, rowWidth); height += rowHeight + getVgap(); rowWidth = 0; rowHeight = 0; gap = 0;
                }
                rowWidth += gap + d.width; rowHeight = Math.max(rowHeight, d.height);
            }
            width = Math.max(width, rowWidth); height += rowHeight;
            return new Dimension(width + padding, height + insets.top + insets.bottom + getVgap() * 2);
        }
    }
}
