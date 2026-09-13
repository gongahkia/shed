package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
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

    @Test
    void adjustsTheRatioTowardTheActivePane() {
        EditorPane first = pane();
        EditorPane second = pane();
        WindowLayoutNode root = WindowLayoutNode.split(WindowLayoutNode.Orientation.HORIZONTAL, 0.5,
            WindowLayoutNode.leaf(first), WindowLayoutNode.leaf(second));

        assertTrue(root.adjustRatio(first, 0.2));
        assertEquals(0.7, root.getRatio());

        root = WindowLayoutNode.split(WindowLayoutNode.Orientation.HORIZONTAL, 0.5,
            WindowLayoutNode.leaf(first), WindowLayoutNode.leaf(second));
        assertTrue(root.adjustRatio(second, 0.2));
        assertEquals(0.3, root.getRatio());
    }

    @Test
    void paintsAThemeColoredDividerInsteadOfThePlatformTexture() {
        Color surface = new Color(0x242831);
        Color foreground = new Color(0xB9C3D4);
        WindowLayoutNode root = WindowLayoutNode.split(WindowLayoutNode.Orientation.HORIZONTAL, 0.5,
            WindowLayoutNode.leaf(pane(surface, foreground)), WindowLayoutNode.leaf(pane(surface, foreground)));

        JSplitPane split = (JSplitPane) root.render(null, 800, 600);
        split.setSize(800, 600);
        split.setDividerLocation(400);
        split.doLayout();
        Component divider = Arrays.stream(split.getComponents())
            .filter(component -> component.getX() == 400 && component.getWidth() == split.getDividerSize())
            .findFirst().orElseThrow();
        BufferedImage image = new BufferedImage(divider.getWidth(), divider.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            divider.paint(graphics);
        } finally {
            graphics.dispose();
        }

        Color expected = blend(surface, foreground, 0.16);
        boolean foundThemedDivider = false;
        for (int x = 0; x < divider.getWidth(); x++) {
            if (image.getRGB(x, 300) == expected.getRGB()) {
                foundThemedDivider = true;
                break;
            }
        }
        assertTrue(foundThemedDivider);
    }

    private EditorPane pane() {
        JTextArea text = new JTextArea();
        return new EditorPane(text, new LineNumberPanel(text), new JScrollPane(text), new SearchManager(text));
    }

    private EditorPane pane(Color background, Color foreground) {
        JTextArea text = new JTextArea();
        text.setBackground(background);
        text.setForeground(foreground);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBackground(background);
        scroll.setForeground(foreground);
        scroll.getViewport().setBackground(background);
        return new EditorPane(text, new LineNumberPanel(text), scroll, new SearchManager(text));
    }

    private Color blend(Color surface, Color foreground, double amount) {
        return new Color((int) Math.round(surface.getRed() * (1.0 - amount) + foreground.getRed() * amount),
            (int) Math.round(surface.getGreen() * (1.0 - amount) + foreground.getGreen() * amount),
            (int) Math.round(surface.getBlue() * (1.0 - amount) + foreground.getBlue() * amount));
    }
}
