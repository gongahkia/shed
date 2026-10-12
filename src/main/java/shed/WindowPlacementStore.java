package shed;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import javax.swing.JFrame;

/** Saves the normal window bounds so a maximized window can also be restored. */
final class WindowPlacementStore {
    private final Path path;
    private Rectangle normalBounds;

    WindowPlacementStore(Path path) {
        this.path = path;
    }

    void restore(JFrame frame) {
        List<Rectangle> screens = screens();
        Rectangle initial = defaultBounds(screens.get(0));
        boolean maximized = false;
        if (Files.isRegularFile(path)) {
            Properties values = new Properties();
            try (var input = Files.newInputStream(path)) {
                values.load(input);
                Rectangle saved = new Rectangle(
                    Integer.parseInt(values.getProperty("x")), Integer.parseInt(values.getProperty("y")),
                    Integer.parseInt(values.getProperty("width")), Integer.parseInt(values.getProperty("height")));
                initial = usableBounds(saved, screens) ? saved : initial;
                maximized = Boolean.parseBoolean(values.getProperty("maximized", "false"));
            } catch (IOException | IllegalArgumentException ignored) {
                // A corrupt or unreadable placement must never prevent the editor opening.
            }
        }
        normalBounds = initial;
        frame.setBounds(initial);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) { captureNormalBounds(frame); }
            @Override public void componentResized(ComponentEvent event) { captureNormalBounds(frame); }
        });
        if (maximized) frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);
    }

    void save(JFrame frame) {
        captureNormalBounds(frame);
        if (normalBounds == null || !usableBounds(normalBounds, screens())) return;
        Properties values = new Properties();
        values.setProperty("x", Integer.toString(normalBounds.x));
        values.setProperty("y", Integer.toString(normalBounds.y));
        values.setProperty("width", Integer.toString(normalBounds.width));
        values.setProperty("height", Integer.toString(normalBounds.height));
        values.setProperty("maximized", Boolean.toString((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0));
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            values.store(bytes, "Shed window placement");
            AtomicFileWriter.write(path, bytes.toByteArray());
        } catch (IOException ignored) {
            // Placement is optional and must not block quitting.
        }
    }

    private void captureNormalBounds(JFrame frame) {
        if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) return;
        Rectangle current = frame.getBounds();
        if (current.width >= 400 && current.height >= 300) normalBounds = current;
    }

    static Rectangle defaultBounds(Rectangle screen) {
        int width = Math.min(1280, Math.max(400, (int) (screen.width * 0.85)));
        int height = Math.min(850, Math.max(300, (int) (screen.height * 0.85)));
        width = Math.min(width, screen.width);
        height = Math.min(height, screen.height);
        return new Rectangle(screen.x + (screen.width - width) / 2,
            screen.y + (screen.height - height) / 2, width, height);
    }

    static boolean usableBounds(Rectangle bounds, List<Rectangle> screens) {
        if (bounds.width < 400 || bounds.height < 300) return false;
        for (Rectangle screen : screens) {
            Rectangle visible = bounds.intersection(screen);
            if (visible.width >= 160 && visible.height >= 120) return true;
        }
        return false;
    }

    private static List<Rectangle> screens() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.ArrayList<Rectangle> bounds = new java.util.ArrayList<>();
        GraphicsConfiguration defaultScreen = environment.getDefaultScreenDevice().getDefaultConfiguration();
        bounds.add(defaultScreen.getBounds());
        for (GraphicsDevice device : environment.getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();
            if (!bounds.contains(screen)) bounds.add(screen);
        }
        return bounds;
    }
}
