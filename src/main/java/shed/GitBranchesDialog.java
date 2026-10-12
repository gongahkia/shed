package shed;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/** Branch browser with the local-branch switch action formerly offered by the scratch buffer. */
final class GitBranchesDialog extends JDialog {
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> branches = new JList<>(model);
    private final JLabel repository = new JLabel();
    private final JLabel status = new JLabel("Select a local branch to switch to it.");
    private final JButton switchBranch = new JButton("Switch to branch");
    private Function<String, String> switchAction;
    private Runnable refreshAction;

    static void showFor(Texteditor editor, String root, String listing,
                        Function<String, String> switchAction, Runnable refreshAction) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showFor(editor, root, listing, switchAction, refreshAction));
            return;
        }
        for (Window window : editor.getOwnedWindows()) {
            if (window instanceof GitBranchesDialog dialog && dialog.isDisplayable()) {
                dialog.load(root, listing, switchAction, refreshAction);
                dialog.setVisible(true);
                dialog.toFront();
                return;
            }
        }
        GitBranchesDialog dialog = new GitBranchesDialog(editor);
        dialog.load(root, listing, switchAction, refreshAction);
        dialog.setVisible(true);
    }

    private GitBranchesDialog(Texteditor editor) {
        super(editor, "Git Branches", false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout());
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setBorder(BorderFactory.createEmptyBorder(20, 24, 14, 24));
        JLabel title = new JLabel("Git Branches");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 7f));
        header.add(title, BorderLayout.NORTH);
        header.add(repository, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        branches.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        branches.setFont(new Font(Font.MONOSPACED, Font.PLAIN, branches.getFont().getSize()));
        branches.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) switchBranch.setEnabled(localSelection() != null);
        });
        AccessibilitySupport.describe(branches, "Git branches", "Local and remote branches in the active repository.");
        JScrollPane scroll = new JScrollPane(branches);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 24));
        add(scroll, BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        editor.editorUiController.prepareDialog(this, 740, 520);
        pack();
        setLocationRelativeTo(editor);
    }

    private JPanel actions() {
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(12, 24, 18, 24));
        footer.add(status, BorderLayout.NORTH);
        JPanel buttons = new JPanel();
        switchBranch.setEnabled(false);
        switchBranch.addActionListener(event -> switchSelection());
        buttons.add(switchBranch);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refreshAction.run());
        buttons.add(refresh);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        buttons.add(close);
        footer.add(buttons, BorderLayout.EAST);
        getRootPane().setDefaultButton(switchBranch);
        return footer;
    }

    private void load(String root, String listing, Function<String, String> switchAction, Runnable refreshAction) {
        this.switchAction = switchAction;
        this.refreshAction = refreshAction;
        repository.setText("Repository: " + root);
        model.clear();
        for (String line : listing.split("\\R")) if (!line.isBlank()) model.addElement(line);
        if (model.isEmpty()) model.addElement("(no branches)");
        switchBranch.setEnabled(false);
        status.setText("Select a local branch to switch to it.");
    }

    private String localSelection() {
        return TreeGitController.localBranchName(branches.getSelectedValue());
    }

    private void switchSelection() {
        String branch = localSelection();
        if (branch == null) return;
        String result = switchAction.apply(branch);
        status.setText(result);
        if ("Switch complete".equals(result)) refreshAction.run();
    }
}
