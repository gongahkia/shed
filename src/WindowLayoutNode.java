import java.awt.Component;
import javax.swing.JSplitPane;

public class WindowLayoutNode {
    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    private final EditorPane pane;
    private final Orientation orientation;
    private final double ratio;
    private final WindowLayoutNode first;
    private final WindowLayoutNode second;

    private WindowLayoutNode(EditorPane pane, Orientation orientation, double ratio, WindowLayoutNode first, WindowLayoutNode second) {
        this.pane = pane;
        this.orientation = orientation;
        this.ratio = ratio;
        this.first = first;
        this.second = second;
    }

    public static WindowLayoutNode leaf(EditorPane pane) {
        return new WindowLayoutNode(pane, null, 0.5, null, null);
    }

    public static WindowLayoutNode split(Orientation orientation, double ratio, WindowLayoutNode first, WindowLayoutNode second) {
        return new WindowLayoutNode(null, orientation, ratio, first, second);
    }

    public boolean isLeaf() {
        return pane != null;
    }

    public EditorPane getPane() {
        return pane;
    }

    public WindowLayoutNode getFirst() {
        return first;
    }

    public WindowLayoutNode getSecond() {
        return second;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public Component render() {
        if (isLeaf()) {
            return pane.getScrollPane();
        }

        int splitOrientation = orientation == Orientation.HORIZONTAL ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
        JSplitPane splitPane = new JSplitPane(splitOrientation, first.render(), second.render());
        splitPane.setResizeWeight(ratio);
        splitPane.setContinuousLayout(true);
        return splitPane;
    }
}
