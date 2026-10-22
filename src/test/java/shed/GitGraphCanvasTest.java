package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitGraphCanvasTest {
    @TempDir Path home;

    @Test
    void zoomedSelectionTracksRowsAndKeyboardSelectionRemainsVisible() throws Exception {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            ConfigManager config = new ConfigManager();
            SwingUtilities.invokeAndWait(() -> {
                AtomicReference<GitGraphModel.Commit> selected = new AtomicReference<>();
                GitGraphDialog.GraphCanvas graph = new GitGraphDialog.GraphCanvas(config,
                    () -> new Font(Font.DIALOG, Font.PLAIN, UiZoom.scale(13, config.getUiZoom())), selected::set);
                List<GitGraphModel.Commit> commits = new ArrayList<>();
                for (int i = 0; i < 30; i++) commits.add(new GitGraphModel.Commit("commit" + i,
                    i < 29 ? List.of("commit" + (i + 1)) : List.of(), "", "Change " + i, "Author", "Today"));
                graph.setSnapshot(new GitGraphModel.Snapshot(GitGraphModel.State.READY, "test", GitGraphModel.graphRows(commits), 1, "30 commits"));
                JScrollPane scroll = new JScrollPane(graph);
                scroll.setSize(500, 240);
                scroll.doLayout();
                graph.setSize(graph.getPreferredSize());
                click(graph, 85);
                assertEquals(commits.get(2), selected.get());

                config.set("ui.zoom", "2.0");
                graph.setSize(graph.getPreferredSize());
                scroll.getViewport().doLayout();
                click(graph, 85);
                assertEquals(commits.get(1), selected.get());
                graph.getActionMap().get("last").actionPerformed(null);
                assertEquals(commits.getLast(), selected.get());
                assertTrue(scroll.getViewport().getViewPosition().y > 0);
                assertTrue(graph.getVisibleRect().intersects(new Rectangle(0, 29 * 80, graph.getWidth(), 80)));

                graph.select(0);
                BufferedImage image = paint(graph);
                int backgroundX = graph.getWidth() - 5;
                assertEquals(config.getNormalColor().getRGB(), image.getRGB(backgroundX, 100));
                config.setTheme("github-light");
                image = paint(graph);
                assertEquals(config.getNormalColor().getRGB(), image.getRGB(backgroundX, 100));
                assertEquals(config.getCurrentLineHighlightColor().getRGB(), image.getRGB(backgroundX, 20));
            });
        } finally {
            if (originalHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", originalHome);
        }
    }

    private static void click(GitGraphDialog.GraphCanvas graph, int y) {
        graph.dispatchEvent(new MouseEvent(graph, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 20, y, 1, false));
    }

    private static BufferedImage paint(GitGraphDialog.GraphCanvas graph) {
        BufferedImage image = new BufferedImage(graph.getWidth(), graph.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graph.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
