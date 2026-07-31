package shed;

import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JSplitPane;

public class WindowLayoutNode {
    static final int MINIMUM_LEAF_WIDTH = 280;
    static final int MINIMUM_LEAF_HEIGHT = 180;
    private static final int DIVIDER_SIZE = 10;
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

    public double getRatio() {
        return ratio;
    }

    public Component render() {
        return render(null, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    Component render(EditorPane activePane, int availableWidth, int availableHeight) {
        if (isLeaf()) {
            return pane.isHiddenByFocusMode() ? null : pane.getComponent();
        }

        int safeWidth = Math.max(0, availableWidth);
        int safeHeight = Math.max(0, availableHeight);
        if (shouldCollapse(safeWidth, safeHeight)) {
            WindowLayoutNode visible = childContaining(activePane);
            if (visible == null) visible = first == null ? second : first;
            return visible == null ? new javax.swing.JPanel() : visible.render(activePane, safeWidth, safeHeight);
        }

        int splitOrientation = orientation == Orientation.HORIZONTAL ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
        int firstWidth = orientation == Orientation.HORIZONTAL ? (int) Math.round(safeWidth * ratio) : safeWidth;
        int secondWidth = orientation == Orientation.HORIZONTAL ? Math.max(0, safeWidth - firstWidth - DIVIDER_SIZE) : safeWidth;
        int firstHeight = orientation == Orientation.VERTICAL ? (int) Math.round(safeHeight * ratio) : safeHeight;
        int secondHeight = orientation == Orientation.VERTICAL ? Math.max(0, safeHeight - firstHeight - DIVIDER_SIZE) : safeHeight;
        Component firstComponent = first == null ? null : first.render(activePane, firstWidth, firstHeight);
        Component secondComponent = second == null ? null : second.render(activePane, secondWidth, secondHeight);
        if (firstComponent == null) return secondComponent;
        if (secondComponent == null) return firstComponent;
        JSplitPane splitPane = new JSplitPane(splitOrientation, firstComponent, secondComponent);
        splitPane.setResizeWeight(ratio);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(DIVIDER_SIZE);
        splitPane.setMinimumSize(new Dimension(MINIMUM_LEAF_WIDTH, MINIMUM_LEAF_HEIGHT));
        int dividerLocation = orientation == Orientation.HORIZONTAL ? firstWidth : firstHeight;
        javax.swing.SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(Math.max(0, dividerLocation)));
        return splitPane;
    }

    private boolean shouldCollapse(int width, int height) {
        if (orientation == Orientation.HORIZONTAL) return width < minimumWidth();
        return height < minimumHeight();
    }

    private int minimumWidth() {
        if (isLeaf()) return pane.isHiddenByFocusMode() ? 0 : MINIMUM_LEAF_WIDTH;
        if (orientation == Orientation.HORIZONTAL) return childMinimumWidth(first) + childMinimumWidth(second) + DIVIDER_SIZE;
        return Math.max(childMinimumWidth(first), childMinimumWidth(second));
    }

    private int minimumHeight() {
        if (isLeaf()) return pane.isHiddenByFocusMode() ? 0 : MINIMUM_LEAF_HEIGHT;
        if (orientation == Orientation.VERTICAL) return childMinimumHeight(first) + childMinimumHeight(second) + DIVIDER_SIZE;
        return Math.max(childMinimumHeight(first), childMinimumHeight(second));
    }

    private int childMinimumWidth(WindowLayoutNode child) {
        return child == null ? 0 : child.minimumWidth();
    }

    private int childMinimumHeight(WindowLayoutNode child) {
        return child == null ? 0 : child.minimumHeight();
    }

    private WindowLayoutNode childContaining(EditorPane target) {
        if (target == null) return first == null ? second : first;
        if (first != null && first.contains(target)) return first;
        if (second != null && second.contains(target)) return second;
        return first == null ? second : first;
    }

    private boolean contains(EditorPane target) {
        if (isLeaf()) return pane == target;
        return (first != null && first.contains(target)) || (second != null && second.contains(target));
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

    public boolean adjustRatio(EditorPane target, double delta) {
        if (isLeaf()) return false;
        // try deeper splits first (innermost split containing target)
        if (first != null && !first.isLeaf() && first.adjustRatio(target, delta)) return true;
        if (second != null && !second.isLeaf() && second.adjustRatio(target, delta)) return true;
        // if no deeper split handled it, adjust this one
        List<EditorPane> firstLeaves = new ArrayList<>();
        List<EditorPane> secondLeaves = new ArrayList<>();
        if (first != null) first.collectLeaves(firstLeaves);
        if (second != null) second.collectLeaves(secondLeaves);
        boolean inFirst = firstLeaves.contains(target);
        boolean inSecond = secondLeaves.contains(target);
        if (inFirst || inSecond) {
            double newRatio = ratio + delta;
            if (newRatio >= 0.05 && newRatio <= 0.95) {
                ratio = newRatio;
                return true;
            }
        }
        return false;
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
