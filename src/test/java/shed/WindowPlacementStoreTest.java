package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowPlacementStoreTest {
    @TempDir Path temporary;

    @Test
    void rejectsOffscreenOrUnusableSavedBounds() {
        List<Rectangle> screens = List.of(new Rectangle(0, 0, 1440, 900));
        assertFalse(WindowPlacementStore.usableBounds(new Rectangle(2000, 2000, 800, 600), screens));
        assertFalse(WindowPlacementStore.usableBounds(new Rectangle(100, 100, 200, 150), screens));
        assertTrue(WindowPlacementStore.usableBounds(new Rectangle(1280, 700, 800, 600), screens));
        assertEquals(new Rectangle(108, 67, 1224, 765), WindowPlacementStore.defaultBounds(screens.getFirst()));
    }

    @Test
    void restoresNormalSizeAndPosition() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path path = temporary.resolve("window-placement.properties");
        JFrame first = new JFrame();
        JFrame second = new JFrame();
        try {
            SwingUtilities.invokeAndWait(() -> {
                WindowPlacementStore store = new WindowPlacementStore(path);
                store.restore(first);
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                first.setBounds(WindowPlacementStore.defaultBounds(screen));
                store.save(first);
            });
            assertTrue(Files.isRegularFile(path));
            SwingUtilities.invokeAndWait(() -> new WindowPlacementStore(path).restore(second));
            assertEquals(first.getBounds(), second.getBounds());
        } finally {
            SwingUtilities.invokeAndWait(() -> { first.dispose(); second.dispose(); });
        }
    }
}
