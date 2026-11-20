package shed;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import static shed.MarkdownPerformanceProbe.*;

/** Opt-in rendered appearance inventory; does not certify native accessibility or platform focus. */
public final class AppearanceQualificationProbe {
    private static Texteditor editor;

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof Container parent) for (Component child : parent.getComponents()) found.addAll(descendants(child, type));
        return found;
    }

    private static void capture(Path folder, String phase) throws Exception {
        edt(() -> {
            editor.validate();
            JRootPane root = editor.getRootPane();
            root.paintImmediately(root.getVisibleRect());
            var image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try { root.paint(graphics); } finally { graphics.dispose(); }
            ImageIO.write(image, "png", folder.resolve(phase + ".png").toFile());
            List<Map<String, Object>> panes = new ArrayList<>();
            for (EditorPane pane : editor.editorPanes) {
                JTextArea area = pane.getTextArea();
                var model = area.modelToView2D(4);
                int hit = area.viewToModel2D(new java.awt.geom.Point2D.Double(model.getX(), model.getY() + model.getHeight() / 2));
                if (hit != 4) throw new AssertionError("Source hit test differs: " + hit);
                panes.add(Map.of("accessible_name", pane.getScrollPane().getAccessibleContext().getAccessibleName(),
                    "active", Boolean.TRUE.equals(pane.getScrollPane().getClientProperty("shed.activePane")),
                    "font", area.getFont().toString(), "line_height", area.getFontMetrics(area.getFont()).getHeight(),
                    "caret", area.getCaretPosition(), "hit_test", hit,
                    "text_contrast", AccessibilitySupport.contrastRatio(area.getForeground(), area.getBackground()),
                    "selection_contrast", AccessibilitySupport.contrastRatio(area.getSelectedTextColor(), area.getSelectionColor())));
            }
            out("APPEARANCE ", Map.of("phase", phase, "panes", panes,
                "density", editor.configManager.get("ui.density"), "theme", editor.configManager.getThemeId(),
                "device_transform", editor.getGraphicsConfiguration().getDefaultTransform().toString(),
                "status_text_contrast", AccessibilitySupport.contrastRatio(editor.statusBar.getForeground(), editor.statusBar.getBackground()),
                "focus_border_contrast", AccessibilitySupport.contrastRatio(editor.configManager.getFocusColor(), editor.configManager.getNormalColor())));
            return null;
        });
    }

    public static void main(String[] args) throws Exception {
        int exit = 1;
        try {
            Path file = Path.of(args[0]).toAbsolutePath(), folder = Path.of(args[1]);
            Files.createDirectories(folder);
            editor = edt(() -> new Texteditor(new String[]{file.toString()}));
            await(() -> editor.getCurrentBuffer() != null && file.toString().equals(editor.getCurrentBuffer().getFilePath()) && !editor.stagedStartupPending, 30);
            edt(() -> { editor.setSize(1200, 800); editor.splitWindow(true); return null; });
            FileBuffer buffer = edt(editor::getCurrentBuffer);
            var snapshot = edt(buffer::textSnapshot); long revision = edt(buffer::documentRevision);
            boolean dirty = edt(buffer::isModified), undo = edt(() -> buffer.getUndoManager().canUndo());
            var selection = edt(() -> { editor.writingArea.select(4, 12); return editor.getActivePane().surface().selection(); });
            for (String theme : List.of("shed-light", "dracula")) for (String density : List.of("default", "compact", "comfortable")) {
                edt(() -> {
                    editor.setThemeFromCommand(theme);
                    editor.configManager.set("ui.density", density); editor.sessionConfigController.applySetting("ui.density");
                    return null;
                });
                edt(() -> null);
                capture(folder, theme + "-" + density);
            }
            edt(() -> { editor.commandHandler.execute("center on"); editor.commandHandler.execute("focus on"); return null; });
            edt(() -> null); capture(folder, "combined-focus-center");
            edt(() -> {
                editor.commandHandler.execute("focus off"); editor.commandHandler.execute("center off");
                editor.setSize(800, 600); editor.configManager.set("ui.density", "compact");
                editor.sessionConfigController.applySetting("ui.density"); return null;
            });
            edt(() -> null); capture(folder, "narrow-compact");
            edt(() -> {
                ThemeGalleryDialog.showFor(editor);
                ThemeGalleryDialog gallery = Arrays.stream(editor.getOwnedWindows()).filter(w -> w instanceof ThemeGalleryDialog && w.isDisplayable())
                    .map(ThemeGalleryDialog.class::cast).findFirst().orElseThrow();
                try {
                    var controls = descendants(gallery, JButton.class).stream().map(button -> Map.of(
                        "text", button.getText(), "accessible_name", String.valueOf(button.getAccessibleContext().getAccessibleName()),
                        "focusable", button.isFocusable(), "height", button.getHeight())).toList();
                    out("APPEARANCE ", Map.of("phase", "gallery_accessibility", "buttons", controls,
                        "lists", descendants(gallery, JList.class).stream().map(list -> String.valueOf(list.getAccessibleContext().getAccessibleName())).toList()));
                } finally { gallery.dispose(); }
                if (snapshot != buffer.textSnapshot() || revision != buffer.documentRevision() || dirty != buffer.isModified()
                    || undo != buffer.getUndoManager().canUndo() || !selection.equals(editor.getActivePane().surface().selection()))
                    throw new AssertionError("Appearance changed source/history/selection");
                out("APPEARANCE ", Map.of("phase", "source_integrity", "exact", true, "revision", revision));
                return null;
            });
            System.out.println("APPEARANCE_DONE"); exit = 0;
        } catch (Throwable error) { error.printStackTrace(); }
        finally {
            edt(() -> { for (Window window : Window.getWindows()) window.dispose(); return null; });
            System.exit(exit);
        }
    }
}
