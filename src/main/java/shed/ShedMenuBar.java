package shed;

import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;

/** Discoverable desktop commands; actions use the same controller paths as keyboard commands. */
final class ShedMenuBar {
    private final Texteditor editor;
    private final JMenuBar bar = new JMenuBar();
    private final int commandMask;
    private final boolean nativeMac;

    static JMenuBar create(Texteditor editor) { return new ShedMenuBar(editor).bar; }

    private ShedMenuBar(Texteditor editor) {
        this.editor = editor;
        commandMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        nativeMac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        build();
    }

    private void build() {
        JMenu file = menu("File");
        item(file, "New File", KeyEvent.VK_N, commandMask, () -> action(PlainKeymap.Action.NEW));
        item(file, "Open File…", KeyEvent.VK_O, commandMask, editor::openFileChooser);
        item(file, "Open Folder…", KeyEvent.VK_O, commandMask | InputEvent.SHIFT_DOWN_MASK, () -> show(editor.openFolder()));
        item(file, "Recent Files…", -1, 0, () -> show(editor.showRecentFiles()));
        file.addSeparator();
        item(file, "Save", KeyEvent.VK_S, commandMask, () -> action(PlainKeymap.Action.SAVE));
        item(file, "Save As…", KeyEvent.VK_S, commandMask | InputEvent.SHIFT_DOWN_MASK, () -> action(PlainKeymap.Action.SAVE_AS));
        item(file, "Close Buffer", KeyEvent.VK_W, commandMask, () -> action(PlainKeymap.Action.CLOSE));
        file.addSeparator();
        item(file, "Settings…", KeyEvent.VK_COMMA, commandMask, () -> show(editor.showSettingsInspector()));
        item(file, "Quit Shed", KeyEvent.VK_Q, commandMask, () -> editor.handleQuit(false));

        JMenu edit = menu("Edit");
        item(edit, "Undo", KeyEvent.VK_Z, commandMask, () -> action(PlainKeymap.Action.UNDO));
        item(edit, "Redo", KeyEvent.VK_Z, commandMask | InputEvent.SHIFT_DOWN_MASK, () -> action(PlainKeymap.Action.REDO));
        edit.addSeparator();
        item(edit, "Cut", KeyEvent.VK_X, commandMask, () -> clipboard(KeyEvent.VK_X));
        item(edit, "Copy", KeyEvent.VK_C, commandMask, () -> clipboard(KeyEvent.VK_C));
        item(edit, "Paste", KeyEvent.VK_V, commandMask, () -> clipboard(KeyEvent.VK_V));
        edit.addSeparator();
        item(edit, "Find…", KeyEvent.VK_F, commandMask, () -> action(PlainKeymap.Action.FIND));
        item(edit, "Find in Files…", KeyEvent.VK_F, commandMask | InputEvent.SHIFT_DOWN_MASK,
            () -> action(PlainKeymap.Action.FIND_IN_FILES));
        item(edit, "Find Next", nativeMac ? KeyEvent.VK_G : KeyEvent.VK_F3,
            nativeMac ? commandMask : 0, () -> action(PlainKeymap.Action.FIND_NEXT));
        item(edit, "Find Previous", nativeMac ? KeyEvent.VK_G : KeyEvent.VK_F3,
            (nativeMac ? commandMask : 0) | InputEvent.SHIFT_DOWN_MASK, () -> action(PlainKeymap.Action.FIND_PREVIOUS));
        item(edit, "Replace…", nativeMac ? KeyEvent.VK_F : KeyEvent.VK_H,
            nativeMac ? commandMask | InputEvent.ALT_DOWN_MASK : commandMask, () -> action(PlainKeymap.Action.REPLACE));

        JMenu view = menu("View");
        item(view, "Command Palette…", KeyEvent.VK_P, commandMask | InputEvent.SHIFT_DOWN_MASK,
            () -> show(editor.showCommandPalette()));
        item(view, "Toggle File Tree", -1, 0, () -> command("tree toggle"));
        item(view, "Toggle Minimap", -1, 0, () -> command("minimap toggle"));
        view.addSeparator();
        item(view, "Problems", -1, 0, () -> editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS));
        item(view, "Git Changes", -1, 0, () -> editor.showToolWindow(ToolWindowHost.Tab.GIT));
        item(view, "Tasks", -1, 0, () -> editor.showToolWindow(ToolWindowHost.Tab.TASKS));
        item(view, "Tests", -1, 0, () -> editor.showToolWindow(ToolWindowHost.Tab.TESTS));

        JMenu navigate = menu("Navigate");
        item(navigate, "Go Back", nativeMac ? KeyEvent.VK_MINUS : KeyEvent.VK_LEFT,
            nativeMac ? InputEvent.CTRL_DOWN_MASK : InputEvent.ALT_DOWN_MASK, () -> action(PlainKeymap.Action.NAVIGATE_BACK));
        item(navigate, "Go Forward", nativeMac ? KeyEvent.VK_MINUS : KeyEvent.VK_RIGHT,
            nativeMac ? InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK : InputEvent.ALT_DOWN_MASK,
            () -> action(PlainKeymap.Action.NAVIGATE_FORWARD));
        navigate.addSeparator();
        item(navigate, "Find File…", KeyEvent.VK_P, commandMask, () -> show(editor.showFileFinder()));
        item(navigate, "Switch Buffer…", KeyEvent.VK_B, commandMask, () -> show(editor.showBufferFinder()));
        item(navigate, "Go to Line…", nativeMac ? -1 : KeyEvent.VK_G,
            nativeMac ? 0 : commandMask, () -> action(PlainKeymap.Action.GO_TO_LINE));
        item(navigate, "Document Symbols…", -1, 0, () -> action(PlainKeymap.Action.SYMBOLS));
        navigate.addSeparator();
        item(navigate, "Next Buffer", -1, 0, () -> action(PlainKeymap.Action.NEXT_BUFFER));
        item(navigate, "Previous Buffer", -1, 0, () -> action(PlainKeymap.Action.PREVIOUS_BUFFER));

        JMenu help = menu("Help");
        item(help, "Shed Help", KeyEvent.VK_F1, 0, () -> editor.showHelp(""));
        item(help, "Performance Diagnostics", -1, 0, () -> command("perf"));
        item(help, "About Shed", -1, 0, () -> command("about"));
    }

    private JMenu menu(String title) {
        JMenu menu = new JMenu(title);
        bar.add(menu);
        return menu;
    }

    private void item(JMenu menu, String label, int key, int modifiers, Runnable run) {
        JMenuItem item = new JMenuItem(label);
        if (key >= 0 && (nativeMac || editor.configManager.getKeymapProfile() == KeymapProfile.PLAIN
            || key == KeyEvent.VK_F1)) item.setAccelerator(KeyStroke.getKeyStroke(key, modifiers));
        item.addActionListener(event -> run.run());
        menu.add(item);
    }

    private void action(PlainKeymap.Action action) { editor.inputController.performMenuAction(action); }

    private void clipboard(int key) {
        if (!editor.extraSelections.isEmpty() && editor.inputController.performPlainMultiSelectionClipboard(key)) return;
        if (key == KeyEvent.VK_X && editor.writingArea.isEditable()) editor.writingArea.cut();
        else if (key == KeyEvent.VK_C) editor.writingArea.copy();
        else if (key == KeyEvent.VK_V && editor.writingArea.isEditable()) editor.writingArea.paste();
    }

    private void command(String value) { show(editor.commandHandler.execute(value)); }

    private void show(String result) {
        if (result != null && !result.isBlank()) editor.showMessage(result);
    }
}
