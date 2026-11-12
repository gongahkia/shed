package shed;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;

/** Workbench composition: discoverable actions around the original editor, never another document model. */
final class WorkbenchHeader extends JPanel implements AutoCloseable {
    private final Texteditor editor;
    private final DocumentWorkspace documents;
    private final WorkspaceFoldersApplication folders;
    private final JButton workspace = new JButton();
    private final JLabel location = new JLabel();
    private final JLabel focus = new JLabel();
    private final JPanel actions = new JPanel(new GridLayout(1, 0, 0, 0));
    private int rowHeight;
    private final PropertyChangeListener focusListener = event -> refresh();

    WorkbenchHeader(Texteditor editor) {
        super(new BorderLayout(8, 0));
        this.editor = editor;
        documents = editor.documents;
        folders = editor.workspaceController;
        setName("workbench.navigation");
        workspace.setName("workbench.workspace");
        workspace.putClientProperty("html.disable", true);
        workspace.addActionListener(event -> workspaceMenu().show(workspace, 0, workspace.getHeight()));
        WorkbenchStyle.quiet(workspace);
        AccessibilitySupport.describe(workspace, "Workspace folders", "Choose the active folder, open another folder, or manage this workspace.");
        add(workspace, BorderLayout.WEST);
        JPanel context = new JPanel(new BorderLayout(8, 0));
        context.setOpaque(false);
        location.setName("workbench.location"); location.putClientProperty("html.disable", true);
        location.setMinimumSize(new Dimension(0, 0));
        focus.setName("workbench.focus");
        context.add(location, BorderLayout.CENTER); context.add(focus, BorderLayout.EAST);
        add(context, BorderLayout.CENTER);
        action("Find file", WorkbenchIcons.SEARCH, () -> show(editor.showFileFinder()));
        action("Commands", WorkbenchIcons.COMMANDS, () -> show(editor.showCommandPalette()));
        JButton split = action("Split and focus", WorkbenchIcons.SPLIT, null);
        split.addActionListener(event -> splitMenu().show(split, 0, split.getHeight()));
        JButton tools = action("Tools", WorkbenchIcons.TOOLS, null);
        tools.addActionListener(event -> toolMenu().show(tools, 0, tools.getHeight()));
        action("Settings", WorkbenchIcons.SETTINGS, () -> show(editor.showSettingsInspector()));
        add(actions, BorderLayout.EAST);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", focusListener);
        refreshAppearance();
    }

    private JButton action(String name, Icon icon, Runnable action) {
        JButton button = new JButton(icon);
        button.setName("workbench." + name.toLowerCase(java.util.Locale.ROOT).replace(' ', '.'));
        WorkbenchStyle.quiet(button);
        button.setToolTipText(name);
        AccessibilitySupport.describe(button, name, name + " in the current workbench. Existing keyboard shortcuts remain available.");
        if (action != null) button.addActionListener(event -> action.run());
        actions.add(button);
        return button;
    }

    private JPopupMenu workspaceMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (Path root : folders.roots()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(folders.displayRoot(root), root.equals(folders.activeRoot()));
            item.setToolTipText(root.toString());
            item.addActionListener(event -> show(folders.activateFolder(root, true))); menu.add(item);
        }
        if (menu.getComponentCount() > 0) menu.addSeparator();
        item(menu, "Open folder…", () -> show(editor.openFolder()));
        item(menu, "Manage workspace folders…", () -> WorkspaceFoldersDialog.showFor(editor, folders));
        prepare(menu); return menu;
    }

    private JPopupMenu splitMenu() {
        JPopupMenu menu = new JPopupMenu();
        item(menu, "Split side by side", () -> show(documents.split(true)));
        item(menu, "Split above and below", () -> show(documents.split(false)));
        menu.addSeparator();
        item(menu, "Focus next pane", () -> show(editor.cycleWindowFocus()));
        item(menu, "Balance splits", () -> show(editor.equalizeWindows()));
        item(menu, "Close current pane", () -> show(documents.closeWindow(false)));
        prepare(menu); return menu;
    }

    private JPopupMenu toolMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (ToolWindowHost.Tab tab : ToolWindowHost.Tab.values()) {
            item(menu, ToolWindowHost.title(tab), () -> {
                editor.showToolWindow(tab);
                if (editor.toolWindowHost != null) editor.toolWindowHost.focus(tab);
            });
        }
        menu.addSeparator();
        item(menu, "Hide docked tools", () -> { editor.hideToolWindow(); editor.requestActivePaneFocus(); });
        item(menu, "Return detached tools to dock", () -> { if (editor.toolWindowHost != null) editor.toolWindowHost.reattachAll(); });
        prepare(menu); return menu;
    }

    private static void item(JPopupMenu menu, String title, Runnable action) {
        JMenuItem item = new JMenuItem(title); item.addActionListener(event -> action.run()); menu.add(item);
    }

    private void prepare(JPopupMenu menu) {
        editor.editorUiController.applyUiTheme(menu); editor.editorUiController.applyUiFont(menu);
    }

    private void show(String message) { if (message != null && !message.isBlank()) editor.showMessage(message); refresh(); }

    void refreshAppearance() {
        Font font = editor.stagedStartupPending ? editor.stagedStartupFont : editor.resolveUiFont();
        int height = Math.max(UiZoom.scale(40, editor.configManager.getUiZoom()), getFontMetrics(font).getHeight() + UiZoom.scale(16, editor.configManager.getUiZoom()));
        rowHeight = height;
        for (Component button : actions.getComponents()) { button.setFont(font); button.setPreferredSize(new Dimension(height, height)); }
        workspace.setFont(font); workspace.setPreferredSize(new Dimension(UiZoom.scale(160, editor.configManager.getUiZoom()), height));
        location.setFont(font); focus.setFont(font);
        focus.setPreferredSize(new Dimension(UiZoom.scale(90, editor.configManager.getUiZoom()), height));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, editor.configManager.getSubtleBorderColor()),
            BorderFactory.createEmptyBorder(0, UiZoom.scale(10, editor.configManager.getUiZoom()), 0, UiZoom.scale(4, editor.configManager.getUiZoom()))));
        setBackground(editor.configManager.getRaisedSurfaceColor()); actions.setOpaque(false);
        location.setForeground(editor.configManager.getMutedForegroundColor());
        refresh();
    }

    void refresh() {
        Path root = documents.activeRoot();
        String rootName = root == null ? "Open workspace ▾" : (root.getFileName() == null ? root.toString() : root.getFileName().toString()) + " ▾";
        if (!rootName.equals(workspace.getText())) workspace.setText(rootName);
        workspace.setToolTipText(root == null ? "Open a workspace folder" : root.toString());
        workspace.getAccessibleContext().setAccessibleDescription(root == null ? "No active workspace folder. Choose a folder to open."
            : "Active workspace folder: " + root + ". Choose another folder or manage this workspace.");
        var document = documents.activeDocument();
        String path = document == null ? "No document" : document.path();
        String context = document == null ? "No document" : document.name();
        if (path != null && !path.isBlank()) {
            try {
                Path file = Path.of(path).toAbsolutePath().normalize();
                Path parent = file.getParent();
                if (root != null && parent != null && parent.startsWith(root)) parent = root.relativize(parent);
                else if (parent != null && parent.getFileName() != null) parent = Path.of("…", parent.getFileName().toString());
                if (parent != null && !parent.toString().isBlank()) context += "  ·  " + parent;
            } catch (java.nio.file.InvalidPathException ignored) { }
        }
        if (!context.equals(location.getText())) location.setText(context);
        location.setToolTipText(path); location.getAccessibleContext().setAccessibleName("Active document: " + context);
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        String area = "Editor";
        if (owner != null) {
            if (editor.openEditorsBar != null && SwingUtilities.isDescendingFrom(owner, editor.openEditorsBar)) area = "Documents";
            else if (editor.toolWindowHost != null && SwingUtilities.isDescendingFrom(owner, editor.toolWindowHost)) area = "Tools";
            else if (SwingUtilities.isDescendingFrom(owner, this)) area = "Navigation";
            else if (SwingUtilities.getWindowAncestor(owner) instanceof JDialog dialog && dialog.getOwner() == editor) area = dialog.getTitle();
        }
        int count = editor.editorPanes.size();
        String cue = area + (count > 1 ? " · " + (editor.activePaneIndex + 1) + "/" + count : "");
        if (!cue.equals(focus.getText())) focus.setText(cue);
        focus.setForeground(editor.configManager.getEditorForeground());
        focus.getAccessibleContext().setAccessibleName("Focus: " + cue);
        focus.setToolTipText("Focus: " + cue);
        setVisible(!editor.goyoModeEnabled);
    }

    @Override public void doLayout() {
        double zoom = editor.configManager.getUiZoom();
        Dimension size = new Dimension(Math.max(UiZoom.scale(80, zoom),
            Math.min(UiZoom.scale(160, zoom), getWidth() / 5)), rowHeight);
        if (!size.equals(workspace.getPreferredSize())) workspace.setPreferredSize(size);
        super.doLayout();
    }

    @Override public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", focusListener);
    }
}
