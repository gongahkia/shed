package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;

final class GitConflictResolutionDialog extends JDialog {
    record Load(List<GitConflictResolutionModel.Conflict> conflicts, String detail) {
        Load {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            detail = detail == null ? "" : detail;
        }
    }

    interface Loader {
        Load load();
        String apply(GitConflictResolutionModel.Conflict conflict, String result);
    }

    private final Texteditor editor;
    private final Loader loader;
    private final JLabel state = new JLabel("Loading unresolved Git conflicts…");
    private final DefaultListModel<GitConflictResolutionModel.Conflict> conflicts = new DefaultListModel<>();
    private final JList<GitConflictResolutionModel.Conflict> conflictList = new JList<>(conflicts);
    private final JTextArea base = readOnly();
    private final JTextArea ours = readOnly();
    private final JTextArea theirs = readOnly();
    private final JTextArea result = new JTextArea();
    private final JButton refresh = new JButton("Refresh");
    private final JButton apply = new JButton("Apply Resolution");
    private int loadRequestId;

    static void showFor(Texteditor editor, Loader loader) {
        GitConflictResolutionDialog dialog = new GitConflictResolutionDialog(editor, loader);
        dialog.setVisible(true);
        dialog.refresh();
    }

    private GitConflictResolutionDialog(Texteditor editor, Loader loader) {
        super(editor, "Git Conflict Resolution", false);
        this.editor = editor;
        this.loader = loader;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        KeyboardFocusSupport.installEscape(getRootPane(), this::dispose);
        setLayout(new BorderLayout(8, 8));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        setPreferredSize(new Dimension(1120, 680));
        pack();
        WorkbenchToolWindowPlacement.restore(editor, this, WorkbenchLayout.SurfaceType.GIT, "conflicts");
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(state, BorderLayout.CENTER);
        return panel;
    }

    private JSplitPane content() {
        conflictList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conflictList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showConflict(conflictList.getSelectedValue());
        });
        JScrollPane list = new JScrollPane(conflictList);
        list.setBorder(BorderFactory.createTitledBorder("Unresolved files"));
        JSplitPane sides = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(base, "Base"), scroll(ours, "Ours"));
        sides.setResizeWeight(0.5);
        JSplitPane allSides = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sides, scroll(theirs, "Theirs"));
        allSides.setResizeWeight(0.66);
        JSplitPane details = new JSplitPane(JSplitPane.VERTICAL_SPLIT, allSides, scroll(result, "Result (editable)"));
        details.setResizeWeight(0.5);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, details);
        split.setResizeWeight(0.2);
        return split;
    }

    private JPanel actions() {
        refresh.addActionListener(event -> refresh());
        apply.addActionListener(event -> apply());
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel panel = new JPanel();
        panel.add(refresh);
        panel.add(apply);
        panel.add(close);
        return panel;
    }

    private void refresh() {
        int expected = ++loadRequestId;
        refresh.setEnabled(false);
        state.setText("Loading unresolved Git conflicts…");
        editor.asyncJobService.submit("Git conflict refresh", token -> loader.load(), (job, loaded, error) -> {
            if (!isDisplayable() || expected != loadRequestId) return;
            refresh.setEnabled(true);
            conflicts.clear();
            clearSides();
            if (job.getStatus() != AsyncJobService.Status.SUCCEEDED || loaded == null) {
                state.setText(error == null ? job.getErrorMessage() : error.getMessage());
                return;
            }
            for (GitConflictResolutionModel.Conflict conflict : loaded.conflicts()) conflicts.addElement(conflict);
            state.setText(loaded.detail());
            if (!loaded.conflicts().isEmpty()) conflictList.setSelectedIndex(0);
        });
    }

    private void apply() {
        GitConflictResolutionModel.Conflict conflict = conflictList.getSelectedValue();
        if (conflict == null) {
            state.setText("Select an unresolved file first.");
            return;
        }
        String resolution = result.getText();
        String validation = GitConflictResolutionModel.validateResult(resolution);
        if (validation != null) {
            state.setText(validation);
            return;
        }
        apply.setEnabled(false);
        editor.asyncJobService.submit("Git conflict apply: " + conflict.path(), token -> loader.apply(conflict, resolution), (job, message, error) -> {
            if (!isDisplayable()) return;
            apply.setEnabled(true);
            state.setText(job.getStatus() == AsyncJobService.Status.SUCCEEDED && message != null
                ? message : error == null ? job.getErrorMessage() : error.getMessage());
        });
    }

    private void showConflict(GitConflictResolutionModel.Conflict conflict) {
        if (conflict == null) {
            clearSides();
            return;
        }
        base.setText(conflict.base().display());
        ours.setText(conflict.ours().display());
        theirs.setText(conflict.theirs().display());
        result.setText(conflict.sourceContent());
        result.setCaretPosition(0);
        state.setText("Resolve " + conflict.path() + "; apply writes only after explicit confirmation.");
    }

    private void clearSides() {
        base.setText("");
        ours.setText("");
        theirs.setText("");
        result.setText("");
    }

    private static JTextArea readOnly() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        return area;
    }

    private static JScrollPane scroll(JTextArea area, String title) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        return scroll;
    }
}
