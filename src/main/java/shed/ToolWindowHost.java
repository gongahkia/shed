package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** Shared docked tool area; controllers remain the source of truth. */
final class ToolWindowHost extends JPanel {
    enum Tab { GIT, DEBUG, TASKS, REPLACE }

    private final Texteditor editor;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<Tab, ToolSurface> surfaces = new EnumMap<>(Tab.class);

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
        panel.add(close, BorderLayout.EAST);
        return panel;
    }

    private void addSurface(Tab tab, String title, ToolSurface surface) {
        surfaces.put(tab, surface);
        tabs.addTab(title, surface.component());
    }

    void showTab(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        if (surface == null) return;
        editor.showToolWindow();
        tabs.setSelectedComponent(surface.component());
        surface.refresh();
    }

    boolean isSelected(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
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

    interface ToolSurface {
        JPanel component();
        void refresh();
    }
}
