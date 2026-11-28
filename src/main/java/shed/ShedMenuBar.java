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

    static JMenuBar create(Texteditor editor) {
        JMenuBar bar = new ShedMenuBar(editor).bar;
        if (editor.workbenchHeader != null) editor.workbenchHeader.attachTo(bar);
        return bar;
    }

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
        JMenu workspace = new JMenu("Workspace Folders");
        workspace.setName("workbench.workspace");
        workspace.addMenuListener(new javax.swing.event.MenuListener() {
            public void menuSelected(javax.swing.event.MenuEvent event) {
                workspace.removeAll();
                var folders = editor.workspaceController;
                for (var root : folders.roots()) {
                    var entry = new javax.swing.JRadioButtonMenuItem(folders.displayRoot(root), root.equals(folders.activeRoot()));
                    entry.setToolTipText(root.toString());
                    entry.addActionListener(action -> show(folders.activateFolder(root, true))); workspace.add(entry);
                }
                if (workspace.getItemCount() > 0) workspace.addSeparator();
                var manage = new JMenuItem("Manage workspace folders…");
                manage.addActionListener(action -> WorkspaceFoldersDialog.showFor(editor, folders)); workspace.add(manage);
                editor.editorUiController.applyUiTheme(workspace); editor.editorUiController.applyUiFont(workspace);
            }
            public void menuDeselected(javax.swing.event.MenuEvent event) { }
            public void menuCanceled(javax.swing.event.MenuEvent event) { }
        });
        file.add(workspace);
        item(file, "Local History…", -1, 0, () -> LocalHistoryDialog.showFor(editor));
        item(file, "Recent Files…", -1, 0, () -> show(editor.showRecentFiles()));
        file.addSeparator();
        item(file, "Save", KeyEvent.VK_S, commandMask, () -> action(PlainKeymap.Action.SAVE));
        item(file, "Save As…", KeyEvent.VK_S, commandMask | InputEvent.SHIFT_DOWN_MASK, () -> action(PlainKeymap.Action.SAVE_AS));
        item(file, "Close Buffer", KeyEvent.VK_W, commandMask, () -> action(PlainKeymap.Action.CLOSE));
        file.addSeparator();
        item(file, "Settings…", KeyEvent.VK_COMMA, commandMask, () -> show(editor.showSettingsInspector()));
        item(file, "Keyboard Shortcuts…", -1, 0, () -> show(editor.showKeymapInspector()));
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
        item(view, "Focus Open Editors", -1, 0, () -> editor.openEditorsBar.focusEditors());
        item(view, "Toggle Open Editors", -1, 0, () -> show(editor.sessionConfigController.setConfigOptionPersistent(
            "ui.open_editors.visible", Boolean.toString(!editor.configManager.getOpenEditorsVisible()))));
        var explorer = new javax.swing.JCheckBoxMenuItem("File Explorer");
        explorer.setName("workbench.explorer.toggle");
        explorer.setSelected(editor.explorerSidebar != null && editor.explorerSidebar.isOpen());
        explorer.addActionListener(e -> { command("tree toggle"); explorer.setSelected(editor.explorerSidebar.isOpen()); });
        view.add(explorer);
        view.addMenuListener(new javax.swing.event.MenuListener() {
            public void menuSelected(javax.swing.event.MenuEvent e) { explorer.setSelected(editor.explorerSidebar.isOpen()); }
            public void menuDeselected(javax.swing.event.MenuEvent e) { }
            public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        item(view, "Toggle Outline", -1, 0, editor::toggleOutline);
        item(view, "Toggle Minimap", -1, 0, () -> command("minimap toggle"));
        JMenu panes = new JMenu("Editor Panes");
        item(panes, "Split side by side", -1, 0, () -> show(editor.documents.split(true)));
        item(panes, "Split above and below", -1, 0, () -> show(editor.documents.split(false)));
        item(panes, "Focus next pane", -1, 0, () -> show(editor.cycleWindowFocus()));
        item(panes, "Balance splits", -1, 0, () -> show(editor.equalizeWindows()));
        item(panes, "Close current pane", -1, 0, () -> show(editor.documents.closeWindow(false)));
        view.add(panes);
        JMenu tools = new JMenu("Tool Windows");
        for (ToolWindowHost.Tab tab : ToolWindowHost.Tab.values()) {
            item(tools, ToolWindowHost.title(tab), -1, 0, () -> editor.showToolWindow(tab));
        }
        item(tools, "Hide docked tools", -1, 0, () -> { editor.hideToolWindow(); editor.requestActivePaneFocus(); });
        item(tools, "Return detached tools to dock", -1, 0, () -> {
            if (editor.toolWindowHost != null) editor.toolWindowHost.reattachAll();
        });
        view.add(tools);
        view.addSeparator();
        item(view, "Problems", -1, 0, () -> editor.showToolWindow(ToolWindowHost.Tab.PROBLEMS));
        dockToggle(view,"Source Control",ToolWindowHost.Tab.GIT);
        item(view, "Tasks", -1, 0, () -> editor.showToolWindow(ToolWindowHost.Tab.TASKS));
        item(view, "Tests", -1, 0, () -> editor.showToolWindow(ToolWindowHost.Tab.TESTS));
        dockToggle(view,"Agent Chat…",ToolWindowHost.Tab.AGENT);
        var inline=new javax.swing.JCheckBoxMenuItem("Inline Code Suggestions");inline.setName("editor.inlineSuggestions");inline.setSelected(Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled","false")));inline.setToolTipText("Opt in to bounded source context through OpenCode. Tab accepts; Ctrl/Command+Right accepts a word; Escape dismisses.");
        inline.addActionListener(e->{if(!editor.agentOwnsEditor())show(editor.setAiCompletionEnabled(inline.isSelected()));inline.setSelected(Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled","false")));});view.add(inline);
        view.addMenuListener(new javax.swing.event.MenuListener(){public void menuSelected(javax.swing.event.MenuEvent e){inline.setSelected(Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled","false")));}public void menuDeselected(javax.swing.event.MenuEvent e){}public void menuCanceled(javax.swing.event.MenuEvent e){}});

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
        item.setName("menu."+AgentUiTargets.slug(menu.getText())+"."+AgentUiTargets.slug(label));
        AgentUiPermission permission=label.equals("Settings…")||label.equals("Keyboard Shortcuts…")?AgentUiPermission.APPEARANCE:
            java.util.Set.of("Save","Save As…","New File","Undo","Redo","Cut","Paste","Replace…","Close Buffer").contains(label)?AgentUiPermission.EDIT:AgentUiPermission.NAVIGATE;
        Runnable agentAction=run;
        if(label.equals("Open File…"))agentAction=()->show(editor.treeGitController.showAgentFileFinder(editor.workspaceController.activeRoot(),editor::agentOwnsEditor));
        if(label.equals("Recent Files…")||label.equals("Open Folder…")||label.equals("Quit Shed")||label.equals("Cut")||label.equals("Paste")||label.equals("Copy"))item.putClientProperty("shed.agent.disabled",true);
        AgentUiTargets.action(item,permission,agentAction);
        if (key >= 0 && (nativeMac || editor.configManager.getKeymapProfile() == KeymapProfile.PLAIN || key == KeyEvent.VK_F1)) {
            String custom = editor.configManager.actionKeybinding(editor.modeKey(editor.editorState.mode),
                new KeyEvent(item, KeyEvent.KEY_PRESSED, 0, modifiers, key, KeyEvent.CHAR_UNDEFINED));
            if (custom == null) item.setAccelerator(KeyStroke.getKeyStroke(key, modifiers));
            else item.setToolTipText("Editor shortcut customized: " + custom + " — see Keyboard Shortcuts");
        }
        item.addActionListener(event -> {if(!editor.agentOwnsEditor())run.run();});
        menu.add(item);
    }

    private void action(PlainKeymap.Action action) { editor.inputController.performMenuAction(action); }

    private void clipboard(int key) {
        if (!editor.extraSelections.isEmpty() && editor.inputController.performPlainMultiSelectionClipboard(key)) return;
        if (key == KeyEvent.VK_X && editor.writingArea.isEditable()) editor.writingArea.cut();
        else if (key == KeyEvent.VK_C) editor.writingArea.copy();
        else if (key == KeyEvent.VK_V && editor.writingArea.isEditable()) editor.writingArea.paste();
    }

    private void dockToggle(JMenu menu,String title,ToolWindowHost.Tab tab){
        var toggle=new javax.swing.JCheckBoxMenuItem(title);toggle.setSelected(editor.toolWindowHost!=null&&editor.toolWindowHost.isSelected(tab));toggle.addActionListener(e->{if(editor.toolWindowHost!=null)editor.toolWindowHost.toggleRight(tab);else editor.showToolWindow(tab);});
        menu.add(toggle);menu.addMenuListener(new javax.swing.event.MenuListener(){public void menuSelected(javax.swing.event.MenuEvent e){toggle.setSelected(editor.toolWindowHost!=null&&editor.toolWindowHost.isSelected(tab));}public void menuDeselected(javax.swing.event.MenuEvent e){}public void menuCanceled(javax.swing.event.MenuEvent e){}});
    }

    private void command(String value) { show(editor.commandHandler.execute(value)); }

    private void show(String result) {
        if (result != null && !result.isBlank()) editor.showMessage(result);
    }
}
