package shed;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

public class WindowLayoutNodeResponsiveTest {
    @Test
    void collapsesToActiveBranchAndRestoresSplitWhenWidthReturns() {
        EditorPane first = pane();
        EditorPane second = pane();
        WindowLayoutNode root = WindowLayoutNode.split(WindowLayoutNode.Orientation.HORIZONTAL, 0.5,
            WindowLayoutNode.leaf(first), WindowLayoutNode.leaf(second));

        assertSame(second.getComponent(), root.render(second, WindowLayoutNode.MINIMUM_LEAF_WIDTH, 800));
        assertTrue(root.render(second, WindowLayoutNode.MINIMUM_LEAF_WIDTH * 2 + 20, 800) instanceof javax.swing.JSplitPane);
    }

    @Test
    void collapsesVerticalSplitAtMinimumHeightWithoutMutatingTree() {
        EditorPane first = pane();
        EditorPane second = pane();
        WindowLayoutNode root = WindowLayoutNode.split(WindowLayoutNode.Orientation.VERTICAL, 0.25,
            WindowLayoutNode.leaf(first), WindowLayoutNode.leaf(second));

        assertSame(first.getComponent(), root.render(first, 900, WindowLayoutNode.MINIMUM_LEAF_HEIGHT));
        assertTrue(root.getFirst().isLeaf());
        assertTrue(root.getSecond().isLeaf());
    }

    @Test
    void hidesFocusModeLeafWithoutChangingTheSavedSplit() {
        EditorPane first = pane();
        EditorPane second = pane();
        WindowLayoutNode root = WindowLayoutNode.split(WindowLayoutNode.Orientation.HORIZONTAL, 0.25,
            WindowLayoutNode.leaf(first), WindowLayoutNode.leaf(second));

        first.setHiddenByFocusMode(true);
        assertSame(second.getComponent(), root.render(second, 900, 800));
        first.setHiddenByFocusMode(false);
        assertTrue(root.render(second, 900, 800) instanceof javax.swing.JSplitPane);
        assertTrue(root.getFirst().isLeaf());
        assertTrue(root.getSecond().isLeaf());
    }

    private EditorPane pane() {
        JTextArea text = new JTextArea();
        return new EditorPane(text, new LineNumberPanel(text), new JScrollPane(text), new SearchManager(text));
    }
}
