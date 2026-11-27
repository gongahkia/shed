package shed;

import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JSplitPane;

/** Owns Explorer presentation independently of document tabs and editor splits. */
final class FileExplorerSidebar extends JSplitPane {
    private JComponent explorer, outline;
    private boolean outlineOpen;
    private final JSplitPane sections = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private boolean open, focusMode, updating;
    private int width, divider;

    FileExplorerSidebar(JComponent content, int width, int divider) {
        super(HORIZONTAL_SPLIT, null, content);
        setName("workbench.explorerSidebar");
        this.width = width;
        this.divider = divider;
        setResizeWeight(0);
        setContinuousLayout(true);
        setBorder(BorderFactory.createEmptyBorder());
        setDividerSize(0);
        addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY, event -> {
            if (!updating && showingExplorer() && getDividerLocation() > 0) this.width = getDividerLocation();
        });
    }

    void explorer(JComponent next) {
        if (explorer == next) return;
        explorer = next;
        if (next != null) next.setMinimumSize(new Dimension(100, 0));
        sync();
    }
    void outline(JComponent component, boolean visible) { outline=component; outlineOpen=visible; sections.setResizeWeight(.65); sections.setBorder(BorderFactory.createEmptyBorder()); sections.setContinuousLayout(true); sync(); }
    boolean showingOutline() { return outlineOpen && outline != null && !focusMode; }
    void open(boolean value) { open = value; sync(); }
    boolean isOpen() { return open && explorer != null; }
    void focusMode(boolean value) { if (focusMode != value) { focusMode = value; sync(); } }
    boolean showingExplorer() { return isOpen() && !focusMode; }
    void dividerSize(int value) { divider = value; sync(); }

    private void sync() {
        updating = true;
        try {
            JComponent next;
            if (showingExplorer() && showingOutline()) { sections.setTopComponent(explorer); sections.setBottomComponent(outline); next=sections; }
            else { sections.setTopComponent(null); sections.setBottomComponent(null); next=showingExplorer()?explorer:showingOutline()?outline:null; }
            boolean changed = getLeftComponent() != next;
            if (changed) setLeftComponent(next);
            setDividerSize(next == null ? 0 : divider);
            if (next != null && changed) setDividerLocation(width);
            revalidate(); repaint();
        } finally { updating = false; }
    }
}
