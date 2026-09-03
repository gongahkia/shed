package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** Shared docked tool area; controllers remain the source of truth. */
final class ToolWindowHost extends JPanel {
    enum Tab { GIT, DEBUG, TASKS, TESTS, PROBLEMS, REPLACE }

    private final Texteditor editor;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<Tab, ToolSurface> surfaces = new EnumMap<>(Tab.class);
    private final Map<String, ToolSurface> extensionSurfaces = new LinkedHashMap<>();
    private final Map<Tab, JDialog> detached = new EnumMap<>(Tab.class);
    private final EnumSet<Tab> hiddenDetachedForFocusMode = EnumSet.noneOf(Tab.class);

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
        addSurface(Tab.TESTS, "Tests", new TestsToolPanel(editor, this));
        addSurface(Tab.PROBLEMS, "Problems", new ProblemsToolPanel(editor, this));
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

    void refreshExtensionViews() {
        Map<String, shed.api.ToolViewContribution> desired = new LinkedHashMap<>();
        if (editor.extensionManager != null) {
            for (ExtensionRegistry.Owned<shed.api.ToolViewContribution> owned : editor.extensionManager.toolViews()) {
                desired.put(extensionName(owned), owned.value());
            }
        }
        for (String name : java.util.List.copyOf(extensionSurfaces.keySet())) {
            if (desired.containsKey(name)) continue;
            ToolSurface surface = extensionSurfaces.remove(name);
            if (surface != null) tabs.remove(surface.component());
        }
        for (Map.Entry<String, shed.api.ToolViewContribution> entry : desired.entrySet()) {
            if (extensionSurfaces.containsKey(entry.getKey())) continue;
            ToolSurface surface = new ExtensionToolSurface(entry.getValue());
            extensionSurfaces.put(entry.getKey(), surface);
            tabs.addTab(extensionTitle(entry.getKey(), entry.getValue()), surface.component());
        }
        tabs.revalidate();
        tabs.repaint();
    }

    String showExtensionView(String requested) {
        refreshExtensionViews();
        String value = requested == null ? "" : requested.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value)) {
            StringBuilder output = new StringBuilder("Extension Views\n\n");
            if (extensionSurfaces.isEmpty()) output.append("No extension views installed.\n");
            else for (String name : extensionSurfaces.keySet()) output.append("  ").append(name).append("\n");
            editor.showScratchBuffer("[extension views]", output.toString());
            return "Showing extension views";
        }
        ToolSurface selected = extensionSurfaces.get(value.toLowerCase(Locale.ROOT));
        if (selected == null) {
            for (Map.Entry<String, ToolSurface> entry : extensionSurfaces.entrySet()) {
                String shortName = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
                if (!shortName.equalsIgnoreCase(value)) continue;
                if (selected != null) return "Extension view is ambiguous: " + value;
                selected = entry.getValue();
            }
        }
        if (selected == null) return "Extension view not found: " + value;
        editor.showToolWindow();
        tabs.setSelectedComponent(selected.component());
        selected.refresh();
        return "Extension view opened";
    }

    ProblemsToolPanel problemsPanel() {
        ToolSurface surface = surfaces.get(Tab.PROBLEMS);
        return surface instanceof ProblemsToolPanel panel ? panel : null;
    }

    void refreshActive() {
        for (Map.Entry<Tab, ToolSurface> entry : surfaces.entrySet()) {
            if (tabs.getSelectedComponent() == entry.getValue().component()) {
                entry.getValue().refresh();
                return;
            }
        }
        for (ToolSurface surface : extensionSurfaces.values()) {
            if (tabs.getSelectedComponent() == surface.component()) {
                surface.refresh();
                return;
            }
        }
    }

    boolean hideForFocusMode() {
        boolean dockedVisible = isVisible();
        hiddenDetachedForFocusMode.clear();
        for (Map.Entry<Tab, JDialog> entry : detached.entrySet()) {
            JDialog dialog = entry.getValue();
            if (dialog != null && dialog.isDisplayable() && dialog.isVisible()) {
                hiddenDetachedForFocusMode.add(entry.getKey());
                dialog.setVisible(false);
            }
        }
        editor.hideToolWindow();
        return dockedVisible;
    }

    void restoreAfterFocusMode(boolean restoreDocked) {
        if (restoreDocked) {
            editor.showToolWindow();
        }
        for (Tab tab : EnumSet.copyOf(hiddenDetachedForFocusMode)) {
            JDialog dialog = detached.get(tab);
            if (dialog != null && dialog.isDisplayable()) {
                dialog.setVisible(true);
            }
        }
        hiddenDetachedForFocusMode.clear();
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
        return switch (tab) { case GIT -> "Git"; case DEBUG -> "Debug"; case TASKS -> "Tasks"; case TESTS -> "Tests"; case PROBLEMS -> "Problems"; case REPLACE -> "Replace"; };
    }

    private static String extensionName(ExtensionRegistry.Owned<shed.api.ToolViewContribution> value) {
        return (value.extensionId() + ":" + value.value().id()).toLowerCase(Locale.ROOT);
    }

    private static String extensionTitle(String name, shed.api.ToolViewContribution view) {
        return view.title() == null || view.title().isBlank() ? name : view.title();
    }

    private static WorkbenchLayout.SurfaceType surfaceType(Tab tab) {
        return switch (tab) {
            case GIT -> WorkbenchLayout.SurfaceType.GIT;
            case DEBUG -> WorkbenchLayout.SurfaceType.DEBUGGER;
            case TASKS -> WorkbenchLayout.SurfaceType.TASKS;
            case TESTS -> WorkbenchLayout.SurfaceType.TESTS;
            case PROBLEMS -> WorkbenchLayout.SurfaceType.PROBLEMS;
            case REPLACE -> WorkbenchLayout.SurfaceType.REPLACE;
        };
    }

    interface ToolSurface {
        JPanel component();
        void refresh();
    }

    private static final class ExtensionToolSurface implements ToolSurface {
        private final shed.api.ToolViewContribution contribution;
        private final JPanel panel = new JPanel(new BorderLayout());
        private boolean created;

        private ExtensionToolSurface(shed.api.ToolViewContribution contribution) {
            this.contribution = contribution;
            panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        }

        @Override public JPanel component() { return panel; }

        @Override public void refresh() {
            if (!created) {
                created = true;
                try {
                    JComponent component = contribution.createComponent();
                    panel.add(component == null ? new JLabel("Extension view returned no component.") : component, BorderLayout.CENTER);
                } catch (RuntimeException error) {
                    panel.add(new JLabel("Extension view failed: " + concise(error)), BorderLayout.CENTER);
                }
            }
            try {
                contribution.refresh();
            } catch (RuntimeException ignored) {
                // The extension panel remains visible; its own UI owns diagnostics.
            }
            panel.revalidate();
            panel.repaint();
        }

        private static String concise(RuntimeException error) {
            String message = error.getMessage();
            return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        }
    }
}
