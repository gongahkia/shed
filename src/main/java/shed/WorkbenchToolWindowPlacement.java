package shed;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import javax.swing.JDialog;

final class WorkbenchToolWindowPlacement {
    private static final String OWNER_WINDOW_ID = "primary";

    private WorkbenchToolWindowPlacement() {
    }

    /** Returns a dock transition bound to the original workspace and placement model. */
    static Runnable restore(Texteditor editor, JDialog dialog, WorkbenchLayout.SurfaceType surface, String instanceId) {
        if (editor == null || dialog == null || surface == null || instanceId == null || instanceId.isBlank()) return () -> { };
        String workspaceId = workspaceId(editor);
        WorkbenchPlacementState state = editor.workbenchPlacementState;
        WorkbenchPlacementState.Entry entry = state.get(workspaceId, surface, instanceId);
        if (entry != null && OWNER_WINDOW_ID.equals(entry.ownerWindowId()) && entry.bounds() != null) applyBounds(dialog, entry.bounds());
        else dialog.setLocationRelativeTo(editor);
        boolean[] docked = {false};
        Runnable capture = () -> {
            if (docked[0]) return;
            Rectangle bounds = dialog.getBounds();
            if (bounds.width < 160 || bounds.height < 120 || bounds.width > 8192 || bounds.height > 8192) return;
            state.put(new WorkbenchPlacementState.Entry(workspaceId, surface, instanceId,
                WorkbenchPlacementState.Mode.DETACHED, OWNER_WINDOW_ID, WorkbenchLayout.Region.DETACHED,
                new WorkbenchPlacementState.Bounds(bounds.x, bounds.y, bounds.width, bounds.height)));
        };
        capture.run();
        dialog.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentMoved(java.awt.event.ComponentEvent event) { capture.run(); }
            @Override public void componentResized(java.awt.event.ComponentEvent event) { capture.run(); }
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { capture.run(); }
        });
        return () -> {
            capture.run(); docked[0] = true;
            var last = state.get(workspaceId, surface, instanceId);
            state.put(new WorkbenchPlacementState.Entry(workspaceId, surface, instanceId,
                WorkbenchPlacementState.Mode.DOCKED, OWNER_WINDOW_ID, WorkbenchLayout.Region.BOTTOM, last == null ? null : last.bounds()));
        };
    }

    private static void applyBounds(Window window, WorkbenchPlacementState.Bounds saved) {
        Rectangle target = new Rectangle(saved.x(), saved.y(), saved.width(), saved.height());
        if (!GraphicsEnvironment.isHeadless()) {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int width = Math.min(target.width, screen.width);
            int height = Math.min(target.height, screen.height);
            int x = Math.max(screen.x, Math.min(target.x, screen.x + screen.width - width));
            int y = Math.max(screen.y, Math.min(target.y, screen.y + screen.height - height));
            target.setBounds(x, y, width, height);
        }
        window.setBounds(target);
    }

    static String workspaceId(Texteditor editor) {
        File root = editor.treeRoot;
        if (root == null) {
            FileBuffer buffer = editor.getCurrentBuffer();
            if (buffer != null && buffer.hasFilePath()) root = new File(buffer.getFilePath()).getParentFile();
        }
        if (root == null) root = new File(".");
        try {
            return root.getCanonicalPath();
        } catch (IOException ignored) {
            return root.getAbsolutePath();
        }
    }
}
