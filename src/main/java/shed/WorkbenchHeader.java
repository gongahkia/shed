package shed;

import java.awt.*;
import javax.swing.*;

/** Compact workbench actions in the menu bar; document context lives in the editor tabs. */
final class WorkbenchHeader extends JPanel {
    private final Texteditor editor;
    private final DocumentWorkspace documents;
    private final JPanel actions = new JPanel(new GridLayout(1, 0, 0, 0));

    WorkbenchHeader(Texteditor editor) {
        super(new BorderLayout(8, 0));
        this.editor = editor;
        documents = editor.documents;
        setName("workbench.navigation");
        action("Find file", WorkbenchIcons.SEARCH, () -> show(editor.showFileFinder()));
        action("Commands", WorkbenchIcons.COMMANDS, () -> show(editor.showCommandPalette()));
        JButton split = action("Split and focus", WorkbenchIcons.SPLIT, null);
        split.addActionListener(event -> splitMenu().show(split, 0, split.getHeight()));
        JButton tools = action("Tools", WorkbenchIcons.TOOLS, null);
        tools.addActionListener(event -> toolMenu().show(tools, 0, tools.getHeight()));
        action("Settings", WorkbenchIcons.SETTINGS, () -> show(editor.showSettingsInspector()));
        add(actions, BorderLayout.EAST);
        refreshAppearance();
    }

    void attachTo(JMenuBar menuBar) {
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(this);
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
        setFont(font);
        setOpaque(false); actions.setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, UiZoom.scale(4, editor.configManager.getUiZoom())));
        int padding = UiZoom.scale(2, editor.configManager.getUiZoom());
        for (Component button : actions.getComponents()) {
            button.setFont(font);
            ((JButton) button).setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
        }
        refreshDensity(); refresh();
    }

    void refreshDensity() {
        int height = getFontMetrics(getFont()).getHeight()
            + UiZoom.scale(editor.configManager.getUiDensity().tabVertical, editor.configManager.getUiZoom());
        for (Component button : actions.getComponents()) button.setPreferredSize(new Dimension(height, height));
        actions.invalidate(); invalidate();
        setMaximumSize(getPreferredSize());
        revalidate(); repaint();
    }

    void refresh() { setVisible(!editor.goyoModeEnabled); }
}
