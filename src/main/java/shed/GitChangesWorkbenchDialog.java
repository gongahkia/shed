package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

final class GitChangesWorkbenchDialog extends JDialog {
    private final Texteditor editor;
    private final Supplier<GitChangesWorkbenchModel.Snapshot> loader;
    private final JLabel state = new JLabel("Loading Git status…");
    private final JLabel repository = new JLabel("Repository: resolving…");
    private final DefaultTableModel changes = new DefaultTableModel(new Object[] { "Index", "Worktree", "Path" }, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JButton refresh = new JButton("Refresh");
    private int requestId;

    static void showFor(Texteditor editor, Supplier<GitChangesWorkbenchModel.Snapshot> loader) {
        GitChangesWorkbenchDialog dialog = new GitChangesWorkbenchDialog(editor, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitChangesWorkbenchDialog(Texteditor editor, Supplier<GitChangesWorkbenchModel.Snapshot> loader) {
        super(editor, "Git Changes", false);
        this.editor = editor;
        this.loader = loader;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(720, 460));
        pack();
        setLocationRelativeTo(editor);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(repository, BorderLayout.NORTH);
        state.setHorizontalAlignment(SwingConstants.LEFT);
        panel.add(state, BorderLayout.SOUTH);
        return panel;
    }

    private JScrollPane content() {
        JTable table = new JTable(changes);
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("Changed files"));
        return scroll;
    }

    private JPanel actions() {
        refresh.addActionListener(event -> refresh());
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(refresh);
        panel.add(close);
        return panel;
    }

    private void refresh() {
        int expectedRequest = ++requestId;
        refresh.setEnabled(false);
        state.setText("Refreshing Git status…");
        editor.asyncJobService.submit("Git changes refresh", token -> loader.get(), (job, snapshot, error) -> {
            if (!isDisplayable() || expectedRequest != requestId) return;
            refresh.setEnabled(true);
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || snapshot == null) {
                repository.setText("Repository: unavailable");
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                changes.setRowCount(0);
                return;
            }
            render(snapshot);
        });
    }

    private void render(GitChangesWorkbenchModel.Snapshot snapshot) {
        repository.setText(snapshot.root().isBlank() ? "Repository: unavailable" : "Repository: " + snapshot.root());
        String branch = snapshot.branch().isBlank() ? "" : "  Branch: " + snapshot.branch();
        state.setText(snapshot.detail() + branch);
        changes.setRowCount(0);
        for (GitChangesWorkbenchModel.Change change : snapshot.changes()) {
            changes.addRow(new Object[] { change.indexStatus(), change.worktreeStatus(), change.displayPath() });
        }
    }
}
