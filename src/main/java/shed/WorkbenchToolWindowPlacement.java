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

    static void restore(Texteditor editor, JDialog dialog, WorkbenchLayout.SurfaceType surface, String instanceId) {
        if (editor == null || dialog == null || surface == null || instanceId == null || instanceId.isBlank()) return;
        String workspaceId = workspaceId(editor);
        WorkbenchPlacementState.Entry entry = editor.workbenchPlacementState.get(workspaceId, surface, instanceId);
        if (entry != null && entry.mode() == WorkbenchPlacementState.Mode.DETACHED
            && OWNER_WINDOW_ID.equals(entry.ownerWindowId()) && entry.bounds() != null) {
            applyBounds(dialog, entry.bounds());
        } else {
            dialog.setLocationRelativeTo(editor);
        }
        capture(editor, dialog, surface, instanceId);
        dialog.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentMoved(java.awt.event.ComponentEvent event) { capture(editor, dialog, surface, instanceId); }
            @Override public void componentResized(java.awt.event.ComponentEvent event) { capture(editor, dialog, surface, instanceId); }
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { capture(editor, dialog, surface, instanceId); }
        });
    }

    private static void capture(Texteditor editor, Window window, WorkbenchLayout.SurfaceType surface, String instanceId) {
        Rectangle bounds = window.getBounds();
        if (bounds.width < 160 || bounds.height < 120) return;
        String workspaceId = workspaceId(editor);
        editor.workbenchPlacementState.put(new WorkbenchPlacementState.Entry(workspaceId, surface, instanceId,
            WorkbenchPlacementState.Mode.DETACHED, OWNER_WINDOW_ID, WorkbenchLayout.Region.DETACHED,
            new WorkbenchPlacementState.Bounds(bounds.x, bounds.y, bounds.width, bounds.height)));
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

    private static String workspaceId(Texteditor editor) {
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
