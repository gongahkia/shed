package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** Shared docked tool area; controllers remain the source of truth. */
final class ToolWindowHost extends JPanel {
    enum Tab { GIT, DEBUG, TASKS, REPLACE }

    private final Texteditor editor;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<Tab, ToolSurface> surfaces = new EnumMap<>(Tab.class);
    private final Map<Tab, JDialog> detached = new EnumMap<>(Tab.class);

    ToolWindowHost(Texteditor editor) {
        super(new BorderLayout());
        this.editor = editor;
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, editor.configManager.getStatusBarBackground()));
        setMinimumSize(new Dimension(0, 160));
        add(header(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        addSurface(Tab.GIT, "Git", new GitChangesToolPanel(editor, this));
        addSurface(Tab.DEBUG, "Debug", new DebugToolPanel(editor, this));
        addSurface(Tab.TASKS, "Tasks", new TasksToolPanel(editor, this));
        addSurface(Tab.REPLACE, "Replace", new ProjectReplaceToolPanel(editor, this));
        tabs.addChangeListener(event -> refreshActive());
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        panel.add(new JLabel("Tools"), BorderLayout.WEST);
        JButton close = new JButton("Hide");
        close.addActionListener(event -> editor.hideToolWindow());
        JButton detach = new JButton("Detach");
        detach.addActionListener(event -> detachSelected());
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        actions.add(detach);
        actions.add(close);
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private void addSurface(Tab tab, String title, ToolSurface surface) {
        surfaces.put(tab, surface);
        tabs.addTab(title, surface.component());
    }

    void showTab(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        if (surface == null) return;
        JDialog dialog = detached.get(tab);
        if (dialog != null && dialog.isDisplayable()) {
            dialog.setVisible(true);
            dialog.toFront();
            surface.refresh();
            return;
        }
        editor.showToolWindow();
        tabs.setSelectedComponent(surface.component());
        surface.refresh();
    }

    boolean isSelected(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        JDialog dialog = detached.get(tab);
        if (dialog != null && dialog.isDisplayable() && dialog.isVisible()) return true;
        return surface != null && tabs.getSelectedComponent() == surface.component() && isVisible();
    }

    void refresh(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        if (surface != null) surface.refresh();
    }

    void refreshActive() {
        for (Map.Entry<Tab, ToolSurface> entry : surfaces.entrySet()) {
            if (tabs.getSelectedComponent() == entry.getValue().component()) {
                entry.getValue().refresh();
                return;
            }
        }
    }

    private void detachSelected() {
        for (Map.Entry<Tab, ToolSurface> entry : surfaces.entrySet()) {
            if (tabs.getSelectedComponent() == entry.getValue().component()) {
                detach(entry.getKey(), entry.getValue());
                return;
            }
        }
    }

    private void detach(Tab tab, ToolSurface surface) {
        tabs.remove(surface.component());
        JDialog dialog = new JDialog(editor, title(tab), false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(dialog.getRootPane(), dialog::dispose);
        dialog.setLayout(new BorderLayout());
        dialog.add(surface.component(), BorderLayout.CENTER);
        dialog.setSize(820, 500);
        WorkbenchToolWindowPlacement.restore(editor, dialog, surfaceType(tab), "panel");
        detached.put(tab, dialog);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { reattach(tab, surface, dialog); }
        });
        dialog.setVisible(true);
        surface.refresh();
    }

    private void reattach(Tab tab, ToolSurface surface, Window dialog) {
        if (detached.get(tab) != dialog) return;
        detached.remove(tab);
        if (surface.component().getParent() != null) surface.component().getParent().remove(surface.component());
        tabs.addTab(title(tab), surface.component());
        tabs.setSelectedComponent(surface.component());
        tabs.revalidate();
    }

    private static String title(Tab tab) {
        return switch (tab) { case GIT -> "Git"; case DEBUG -> "Debug"; case TASKS -> "Tasks"; case REPLACE -> "Replace"; };
    }

    private static WorkbenchLayout.SurfaceType surfaceType(Tab tab) {
        return switch (tab) {
            case GIT -> WorkbenchLayout.SurfaceType.GIT;
            case DEBUG -> WorkbenchLayout.SurfaceType.DEBUGGER;
            case TASKS -> WorkbenchLayout.SurfaceType.TASKS;
            case REPLACE -> WorkbenchLayout.SurfaceType.REPLACE;
        };
    }

    interface ToolSurface {
        JPanel component();
        void refresh();
    }
}
