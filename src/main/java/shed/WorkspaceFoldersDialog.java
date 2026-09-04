package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

final class WorkspaceFoldersDialog extends JDialog {
    private final Texteditor editor;
    private final WorkspaceController controller;
    private final DefaultListModel<String> folders = new DefaultListModel<>();
    private final JList<String> list = new JList<>(folders);

    static void showFor(Texteditor editor, WorkspaceController controller) {
        WorkspaceFoldersDialog dialog = new WorkspaceFoldersDialog(editor, controller);
        dialog.setVisible(true);
    }

    private WorkspaceFoldersDialog(Texteditor editor, WorkspaceController controller) {
        super(editor, "Workspace Folders", false);
        this.editor = editor;
        this.controller = controller;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        AccessibilitySupport.describe(list, "Workspace folders", "Folders in this workspace; the active folder is marked with an asterisk.");
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createTitledBorder("Folders"));
        add(scroll, BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(680, 360));
        pack();
        refresh();
    }

    private JPanel actions() {
        JPanel panel = new JPanel();
        panel.add(button("Add…", this::add));
        panel.add(button("Open", this::open));
        panel.add(button("Remove", this::remove));
        panel.add(button("Refresh", this::refresh));
        panel.add(button("Close", this::dispose));
        return panel;
    }

    private void add() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        message(controller.addDirectory(chooser.getSelectedFile(), false));
        refresh();
    }

    private void open() {
        int index = list.getSelectedIndex();
        if (index < 0) { message("Select a workspace folder."); return; }
        message(controller.activate(Integer.toString(index + 1), true));
        refresh();
    }

    private void remove() {
        int index = list.getSelectedIndex();
        if (index < 0) { message("Select a workspace folder."); return; }
        if (JOptionPane.showConfirmDialog(this, "Remove this folder from the workspace? Files are not deleted.", "Remove Workspace Folder",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        message(controller.remove(Integer.toString(index + 1)));
        refresh();
    }

    private void refresh() {
        int prior = list.getSelectedIndex();
        folders.clear();
        for (java.nio.file.Path root : controller.roots()) {
            folders.addElement((root.equals(controller.activeRoot()) ? "* " : "  ") + controller.displayRoot(root));
        }
        if (!folders.isEmpty()) list.setSelectedIndex(Math.max(0, Math.min(prior, folders.size() - 1)));
    }

    private void message(String value) { editor.showMessage(value); }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
}
