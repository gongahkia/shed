package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** Shared docked tool area; controllers remain the source of truth. */
final class ToolWindowHost extends JPanel implements AutoCloseable {
    enum Tab { GIT, DEBUG, TASKS, TESTS, PROBLEMS, REPLACE, SEARCH, AGENT }

    private final Texteditor editor;
    private final JTabbedPane tabs = new JTabbedPane();
    private JPanel headerPanel;
    private final Map<Tab, ToolSurface> surfaces = new EnumMap<>(Tab.class);
    private final Map<Tab, JDialog> detached = new EnumMap<>(Tab.class);
    private final EnumSet<Tab> hiddenDetachedForFocusMode = EnumSet.noneOf(Tab.class);
    private java.awt.Component selectedSurface;
    private int automaticDockHeight = -1;
    private boolean closed;
    private final Map<Tab, Runnable> dockTransitions = new EnumMap<>(Tab.class);
    private int rememberedHeight = -1;
    private boolean rememberedAutomatic;
    private static final java.util.List<Tab> TAB_ORDER = java.util.List.of(Tab.SEARCH, Tab.GIT, Tab.DEBUG, Tab.TASKS, Tab.TESTS, Tab.PROBLEMS, Tab.REPLACE, Tab.AGENT);

    ToolWindowHost(Texteditor editor) {
        super(new BorderLayout());
        this.editor = editor;
        tabs.setName("workbench.tools");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        AccessibilitySupport.describe(tabs, "Tool panels", "Select a tool. Scroll the strip for more tools, or use the workbench Tools menu. Escape returns to the editor.");
        KeyboardFocusSupport.installEscape(this, editor::requestActivePaneFocus);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, editor.configManager.getStatusBarBackground()));
        setMinimumSize(new Dimension(0, 160));
        headerPanel = header();
        // Put dock actions in the tab strip, reserving their space in TabsUI.
        javax.swing.JLayeredPane body = new javax.swing.JLayeredPane() {
            @Override public Dimension getPreferredSize() { return tabs.getPreferredSize(); }
            @Override public void doLayout() {
                tabs.setBounds(0, 0, getWidth(), getHeight());
                Dimension actions = headerPanel.getPreferredSize();
                headerPanel.setBounds(Math.max(0, getWidth() - actions.width), 0, actions.width,
                    UiZoom.scale(40, editor.configManager.getUiZoom()));
            }
        };
        body.add(tabs, javax.swing.JLayeredPane.DEFAULT_LAYER);
        body.add(headerPanel, javax.swing.JLayeredPane.PALETTE_LAYER);
        add(body, BorderLayout.CENTER);
        addSurface(Tab.SEARCH, "Search", new WorkspaceSearchPanel(editor));
        addSurface(Tab.GIT, "Git", new GitChangesToolPanel(editor, this));
        addSurface(Tab.DEBUG, "Debug", new DebugToolPanel(editor, this));
        addSurface(Tab.TASKS, "Tasks", new TasksToolPanel(editor, this));
        addSurface(Tab.TESTS, "Tests", new TestsToolPanel(editor, this));
        addSurface(Tab.PROBLEMS, "Problems", new ProblemsToolPanel(editor, this));
        addSurface(Tab.REPLACE, "Replace", new ProjectReplaceToolPanel(editor, this));
        addSurface(Tab.AGENT, "Agents", new AgentWorkbenchPanel(editor));
        selectedSurface = tabs.getSelectedComponent();
        tabs.addChangeListener(event -> selectedTabChanged());
    }

    private JPanel header() {
        JPanel panel = new JPanel(new java.awt.GridLayout(1, 2, 0, 0));
        JButton close = new JButton(WorkbenchIcons.CLOSE);
        close.addActionListener(event -> { editor.hideToolWindow(); editor.requestActivePaneFocus(); });
        JButton detach = new JButton(WorkbenchIcons.DETACH);
        detach.addActionListener(event -> detachSelected());
        WorkbenchStyle.quiet(close); WorkbenchStyle.quiet(detach);
        close.setToolTipText("Hide tools"); detach.setToolTipText("Detach selected tool");
        AccessibilitySupport.describe(close, "Hide tools", "Hide the docked tool panel.");
        AccessibilitySupport.describe(detach, "Detach selected tool", "Open this tool in its own window. Closing it returns the tool to this dock.");
        panel.add(detach); panel.add(close);
        return panel;
    }

    private void addSurface(Tab tab, String title, ToolSurface surface) {
        surfaces.put(tab, surface);
        tabs.addTab(title, surface.component());
    }

    void showTab(Tab tab) {
        if (closed) return;
        ToolSurface surface = surfaces.get(tab);
        if (surface == null) return;
        JDialog dialog = detached.get(tab);
        if (dialog != null && dialog.isDisplayable()) {
            dialog.setVisible(true);
            dialog.toFront();
            surface.refresh();
            return;
        }
        boolean firstSizing = !isVisible() && rememberedHeight < 0;
        editor.showToolWindow();
        tabs.setSelectedComponent(surface.component());
        if (firstSizing) sizeDockAutomatically(tab);
        surface.refresh();
    }

    void showAgentReview() {
        if (surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents) agents.showReview();
        showTab(Tab.AGENT);
    }

    /** Focus is an explicit user-navigation action, never a side effect of refresh/completion. */
    void focus(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        if (surface != null) javax.swing.SwingUtilities.invokeLater(() -> {
            if (!closed && surface.component().isShowing()) surface.focus();
        });
    }

    void showAgentAction(AgentAction action) {
        if (surfaces.get(Tab.AGENT) instanceof AgentWorkbenchPanel agents) agents.showAction(action);
        showTab(Tab.AGENT);
    }

    private int preferredDividerLocation(Tab tab) {
        int desiredHeight = switch (tab) {
            case DEBUG, REPLACE, AGENT -> 520;
            case GIT, TESTS -> 420;
            default -> 360;
        };
        return Math.max(UiZoom.scale(120, editor.configManager.getUiZoom()),
            editor.editorToolSplit.getHeight() - UiZoom.scale(desiredHeight, editor.configManager.getUiZoom()));
    }

    private int dockHeight() {
        return editor.editorToolSplit.getHeight() - editor.editorToolSplit.getDividerLocation()
            - editor.editorToolSplit.getDividerSize();
    }

    private void sizeDockAutomatically(Tab tab) {
        editor.editorToolSplit.setDividerLocation(preferredDividerLocation(tab));
        automaticDockHeight = dockHeight();
    }

    private void selectedTabChanged() {
        java.awt.Component selected = tabs.getSelectedComponent();
        boolean changed = selected != selectedSurface;
        selectedSurface = selected;
        // Grow a default-sized dock for denser tools, preserving a divider the user moved.
        // Data refreshes do not enter this path, and selecting a smaller tool never shrinks it.
        if (changed && isVisible() && editor.editorToolSplit != null && automaticDockHeight >= 0
            && Math.abs(dockHeight() - automaticDockHeight) <= 2) {
            for (Map.Entry<Tab, ToolSurface> entry : surfaces.entrySet()) {
                if (entry.getValue().component() == selected
                    && preferredDividerLocation(entry.getKey()) < editor.editorToolSplit.getDividerLocation()) {
                    sizeDockAutomatically(entry.getKey());
                    break;
                }
            }
        }
        refreshActive();
    }

    boolean isSelected(Tab tab) {
        ToolSurface surface = surfaces.get(tab);
        JDialog dialog = detached.get(tab);
        if (dialog != null && dialog.isDisplayable() && dialog.isVisible()) return true;
        return surface != null && tabs.getSelectedComponent() == surface.component() && isVisible();
    }

    void refresh(Tab tab) {
        if (closed) return;
        ToolSurface surface = surfaces.get(tab);
        if (surface != null) surface.refresh();
    }

    ProjectReplaceToolPanel replacePanel() { return (ProjectReplaceToolPanel) surfaces.get(Tab.REPLACE); }

    WorkspaceSearchPanel searchPanel() {
        ToolSurface surface = surfaces.get(Tab.SEARCH);
        return surface instanceof WorkspaceSearchPanel panel ? panel : null;
    }

    ProblemsToolPanel problemsPanel() {
        ToolSurface surface = surfaces.get(Tab.PROBLEMS);
        return surface instanceof ProblemsToolPanel panel ? panel : null;
    }

    void refreshAppearance() {
        int size = UiZoom.scale(40, editor.configManager.getUiZoom());
        for (var action : headerPanel.getComponents()) action.setPreferredSize(new Dimension(size, size));
        headerPanel.setBackground(editor.configManager.getStatusBarBackground());
        tabs.setFont(editor.resolveUiFont());
        tabs.setUI(new WorkbenchStyle.TabsUI() {
            @Override protected java.awt.Insets getTabAreaInsets(int placement) {
                return new java.awt.Insets(0, 0, 0, size * 2);
            }
        });
        for (ToolSurface surface : surfaces.values()) surface.refreshAppearance();
    }

    void refreshActive() {
        if (closed) return;
        for (Map.Entry<Tab, ToolSurface> entry : surfaces.entrySet()) {
            if (tabs.getSelectedComponent() == entry.getValue().component()) {
                entry.getValue().refresh();
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

    private void detach(Tab tab, ToolSurface surface) { detach(tab, surface, false); }

    private void detach(Tab tab, ToolSurface surface, boolean restoring) {
        if (closed || detached.containsKey(tab)) return;
        tabs.remove(surface.component());
        JDialog dialog = new JDialog(editor, title(tab), false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(dialog.getRootPane(), dialog::dispose);
        dialog.setLayout(new BorderLayout());
        dialog.add(surface.component(), BorderLayout.CENTER);
        editor.editorUiController.prepareDialog(dialog, 820, 500);
        dialog.pack();
        dockTransitions.put(tab, WorkbenchToolWindowPlacement.restore(editor, dialog, surfaceType(tab), "panel"));
        if (restoring) dialog.setAutoRequestFocus(false);
        detached.put(tab, dialog);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { reattach(tab, surface, dialog); }
        });
        if (!editor.goyoModeEnabled) dialog.setVisible(true);
        else hiddenDetachedForFocusMode.add(tab);
        surface.refresh();
        if (!restoring) focus(tab);
    }

    private void reattach(Tab tab, ToolSurface surface, Window dialog) {
        if (detached.get(tab) != dialog) return;
        detached.remove(tab);
        Runnable dock = dockTransitions.remove(tab);
        if (closed || editor.closingDown || !editor.isDisplayable()) return;
        if (dock != null) dock.run();
        if (surface.component().getParent() != null) surface.component().getParent().remove(surface.component());
        int index = 0;
        for (Tab candidate : TAB_ORDER) {
            if (candidate == tab) break;
            if (!detached.containsKey(candidate)) index++;
        }
        tabs.insertTab(title(tab), null, surface.component(), null, Math.min(index, tabs.getTabCount()));
        tabs.setSelectedComponent(surface.component());
        tabs.revalidate();
        if (!editor.goyoModeEnabled) editor.showToolWindow();
    }

    static String title(Tab tab) {
        return switch (tab) { case GIT -> "Git"; case DEBUG -> "Debug"; case TASKS -> "Tasks"; case TESTS -> "Tests"; case PROBLEMS -> "Problems"; case REPLACE -> "Replace"; case SEARCH -> "Search"; case AGENT -> "Agents"; };
    }

    private static WorkbenchLayout.SurfaceType surfaceType(Tab tab) {
        return switch (tab) {
            case GIT -> WorkbenchLayout.SurfaceType.GIT;
            case DEBUG -> WorkbenchLayout.SurfaceType.DEBUGGER;
            case TASKS -> WorkbenchLayout.SurfaceType.TASKS;
            case TESTS -> WorkbenchLayout.SurfaceType.TESTS;
            case PROBLEMS -> WorkbenchLayout.SurfaceType.PROBLEMS;
            case REPLACE -> WorkbenchLayout.SurfaceType.REPLACE;
            case SEARCH -> WorkbenchLayout.SurfaceType.SEARCH;
            case AGENT -> WorkbenchLayout.SurfaceType.AGENT;
        };
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (ToolSurface surface : surfaces.values()) surface.close();
        for (JDialog dialog : java.util.List.copyOf(detached.values())) dialog.dispose();
        detached.clear(); dockTransitions.clear();
    }

    void reattachAll() {
        for (var entry : java.util.List.copyOf(detached.entrySet())) {
            JDialog dialog = entry.getValue(); reattach(entry.getKey(), surfaces.get(entry.getKey()), dialog); dialog.dispose();
        }
    }

    void rememberDockSize() {
        if (!isVisible()) return;
        rememberedHeight = Math.max(0, dockHeight());
        rememberedAutomatic = automaticDockHeight >= 0 && Math.abs(rememberedHeight - automaticDockHeight) <= 2;
    }

    boolean restoreDockSize() {
        if (rememberedHeight < 0) return false;
        editor.editorToolSplit.setDividerLocation(Math.max(WorkbenchStyle.px(120), editor.editorToolSplit.getHeight() - rememberedHeight - editor.editorToolSplit.getDividerSize()));
        automaticDockHeight = rememberedAutomatic ? dockHeight() : -1;
        return true;
    }

    Map<String, Object> captureDockState() {
        if (isVisible()) rememberDockSize();
        Tab selected = Tab.SEARCH;
        for (var entry : surfaces.entrySet()) if (entry.getValue().component() == tabs.getSelectedComponent()) selected = entry.getKey();
        int total = editor.editorToolSplit.getHeight();
        double ratio = total <= 0 || rememberedHeight < 0 ? .6 : 1.0 - rememberedHeight / (double) total;
        return Map.of("visible", isVisible(), "selected", selected.name(), "ratio", Math.max(.1, Math.min(.9, ratio)));
    }

    void restoreWorkbench(Map<String, Object> dock) {
        if (closed) return;
        String workspace = WorkbenchToolWindowPlacement.workspaceId(editor);
        Map<Tab, WorkbenchPlacementState.Entry> saved = new EnumMap<>(Tab.class);
        for (Tab tab : TAB_ORDER) {
            var placement = editor.workbenchPlacementState.get(workspace, surfaceType(tab), "panel");
            if (placement != null) saved.put(tab, placement);
        }
        reattachAll();
        for (Tab tab : TAB_ORDER) {
            var entry = saved.get(tab);
            if (entry != null && entry.mode() == WorkbenchPlacementState.Mode.DETACHED && "primary".equals(entry.ownerWindowId())) detach(tab, surfaces.get(tab), true);
        }
        if (dock == null) return;
        try {
            Tab selected = Tab.valueOf(String.valueOf(dock.get("selected")));
            if (!detached.containsKey(selected)) tabs.setSelectedComponent(surfaces.get(selected).component());
        } catch (IllegalArgumentException ignored) { }
        if (dock.get("ratio") instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= .1 && number.doubleValue() <= .9) {
            editor.validate(); rememberedHeight = (int) Math.round(editor.editorToolSplit.getHeight() * (1 - number.doubleValue())); rememberedAutomatic = false;
        }
        if (Boolean.TRUE.equals(dock.get("visible"))) { editor.showToolWindow(); restoreDockSize(); }
        else {
            int savedHeight = rememberedHeight; editor.hideToolWindow(); rememberedHeight = savedHeight; rememberedAutomatic = false;
        }
    }

    interface ToolSurface {
        JPanel component();
        void refresh();
        default void refreshAppearance() { }
        default void focus() { }
        default void close() { }
    }


}
