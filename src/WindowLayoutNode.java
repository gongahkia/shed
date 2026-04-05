import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JSplitPane;

public class WindowLayoutNode {
    public enum Direction {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    private EditorPane pane;
    private Orientation orientation;
    private double ratio;
    private WindowLayoutNode first;
    private WindowLayoutNode second;

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

    public boolean splitLeaf(EditorPane target, EditorPane newPane, Orientation newOrientation) {
        return splitLeaf(target, newPane, newOrientation, false, 0.5);
    }

    public boolean splitLeaf(EditorPane target, EditorPane newPane, Orientation newOrientation, boolean newPaneFirst, double newRatio) {
        if (isLeaf()) {
            if (pane != target) {
                return false;
            }
            EditorPane originalPane = pane;
            pane = null;
            orientation = newOrientation;
            ratio = Math.max(0.05, Math.min(0.95, newRatio));
            if (newPaneFirst) {
                first = WindowLayoutNode.leaf(newPane);
                second = WindowLayoutNode.leaf(originalPane);
            } else {
                first = WindowLayoutNode.leaf(originalPane);
                second = WindowLayoutNode.leaf(newPane);
            }
            return true;
        }

        return (first != null && first.splitLeaf(target, newPane, newOrientation, newPaneFirst, newRatio))
            || (second != null && second.splitLeaf(target, newPane, newOrientation, newPaneFirst, newRatio));
    }

    public WindowLayoutNode removeLeaf(EditorPane target) {
        if (isLeaf()) {
            return pane == target ? null : this;
        }
        if (first != null) {
            first = first.removeLeaf(target);
        }
        if (second != null) {
            second = second.removeLeaf(target);
        }
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return this;
    }

    public void collectLeaves(java.util.List<EditorPane> leaves) {
        if (isLeaf()) {
            leaves.add(pane);
            return;
        }
        if (first != null) {
            first.collectLeaves(leaves);
        }
        if (second != null) {
            second.collectLeaves(leaves);
        }
    }

    public void equalize() {
        if (isLeaf()) {
            return;
        }
        ratio = 0.5;
        if (first != null) {
            first.equalize();
        }
        if (second != null) {
            second.equalize();
        }
    }

    public List<EditorPane> findNeighborCandidates(EditorPane target, Direction direction) {
        List<PathStep> path = new ArrayList<>();
        if (!buildPath(target, path)) {
            return List.of();
        }

        for (int i = path.size() - 1; i >= 0; i--) {
            PathStep step = path.get(i);
            WindowLayoutNode node = step.node;
            if (node.isLeaf()) {
                continue;
            }

            boolean matchesDirection =
                (direction == Direction.LEFT && node.orientation == Orientation.HORIZONTAL && !step.fromFirst)
                || (direction == Direction.RIGHT && node.orientation == Orientation.HORIZONTAL && step.fromFirst)
                || (direction == Direction.UP && node.orientation == Orientation.VERTICAL && !step.fromFirst)
                || (direction == Direction.DOWN && node.orientation == Orientation.VERTICAL && step.fromFirst);

            if (!matchesDirection) {
                continue;
            }

            WindowLayoutNode siblingSubtree = step.fromFirst ? node.second : node.first;
            List<EditorPane> leaves = new ArrayList<>();
            if (siblingSubtree != null) {
                siblingSubtree.collectLeaves(leaves);
            }
            return leaves;
        }

        return List.of();
    }

    private boolean buildPath(EditorPane target, List<PathStep> path) {
        if (isLeaf()) {
            return pane == target;
        }

        if (first != null && first.buildPath(target, path)) {
            path.add(new PathStep(this, true));
            return true;
        }
        if (second != null && second.buildPath(target, path)) {
            path.add(new PathStep(this, false));
            return true;
        }
        return false;
    }

    private static class PathStep {
        private final WindowLayoutNode node;
        private final boolean fromFirst;

        private PathStep(WindowLayoutNode node, boolean fromFirst) {
            this.node = node;
            this.fromFirst = fromFirst;
        }
    }
}
