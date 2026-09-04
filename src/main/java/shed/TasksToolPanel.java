package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

final class TasksToolPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final DefaultListModel<String> taskNames = new DefaultListModel<>();
    private final JList<String> tasks = new JList<>(taskNames);
    private final Map<String, TaskService.WorkspaceTask> taskByName = new LinkedHashMap<>();
    private final JTextField name = new JTextField();
    private final JTextField command = new JTextField();
    private final DefaultTableModel jobs = new DefaultTableModel(new Object[] {"ID", "Status", "Task", "Duration"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable jobTable = new JTable(jobs);
    private final JTextArea output = new JTextArea();

    TasksToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(toolbar(), BorderLayout.NORTH);
        panel.add(content(), BorderLayout.CENTER);
        output.setEditable(false);
        jobTable.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) showOutput(); });
        tasks.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadTask(); });
    }

    @Override public JPanel component() { return panel; }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new GridLayout(1, 6, 5, 0));
        panel.add(new JLabel("Name")); panel.add(name); panel.add(new JLabel("Command")); panel.add(command);
        panel.add(button("Save Task", this::saveTask));
        panel.add(button("Remove", this::removeTask));
        return panel;
    }

    private java.awt.Component content() {
        JPanel left = new JPanel(new BorderLayout(3, 3));
        left.setBorder(BorderFactory.createTitledBorder("Workspace Tasks"));
        left.add(new JScrollPane(tasks), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(button("Run", this::runTask));
        actions.add(button("Dry Run", this::dryRun));
        actions.add(button("Refresh", this::refresh));
        left.add(actions, BorderLayout.SOUTH);

        JPanel rightTop = new JPanel(new BorderLayout(3, 3));
        rightTop.setBorder(BorderFactory.createTitledBorder("Jobs"));
        rightTop.add(new JScrollPane(jobTable), BorderLayout.CENTER);
        JPanel jobActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        jobActions.add(button("Cancel", this::cancelJob));
        jobActions.add(button("Quickfix", () -> message(editor.openQuickfixList())));
        rightTop.add(jobActions, BorderLayout.SOUTH);
        output.setBorder(BorderFactory.createTitledBorder("Selected Task Output"));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rightTop, new JScrollPane(output));
        right.setResizeWeight(0.48);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.28);
        return split;
    }

    @Override public void refresh() {
        File root = editor.resolveTaskProjectRoot();
        TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(root);
        String selected = tasks.getSelectedValue();
        taskNames.clear(); taskByName.clear();
        if (loaded.isValid()) {
            for (Map.Entry<String, TaskService.WorkspaceTask> entry : editor.jobQuickfixController.effectiveWorkspaceTasks(root, loaded).entrySet()) {
                taskNames.addElement(entry.getKey()); taskByName.put(entry.getKey(), entry.getValue());
            }
        } else {
            output.setText(String.join("\n", loaded.diagnostics()));
        }
        tasks.setSelectedValue(selected, true);
        jobs.setRowCount(0);
        for (AsyncJobService.JobSnapshot job : editor.asyncJobService.list()) {
            if (!job.getDescription().startsWith("task ")) continue;
            Long done = job.getFinishedAtMillis();
            long duration = Math.max(0L, (done == null ? System.currentTimeMillis() : done) - job.getStartedAtMillis());
            jobs.addRow(new Object[] {job.getId(), job.getStatus().name().toLowerCase(), job.getDescription(), duration + " ms"});
        }
        showOutput();
    }

    private void loadTask() {
        TaskService.WorkspaceTask task = taskByName.get(tasks.getSelectedValue());
        if (task == null) return;
        name.setText(task.name()); command.setText(task.command());
        boolean editable = !task.hasDirectArguments();
        name.setEditable(editable); command.setEditable(editable);
    }

    private void saveTask() {
        if (selectedImportedTask()) { message("Imported VS Code tasks are session-only; edit tasks.json or create a separate Shed task."); return; }
        message(editor.saveProjectTask(editor.resolveTaskProjectRoot(), name.getText(), command.getText()));
    }

    private void removeTask() {
        String selected = tasks.getSelectedValue();
        if (selected == null) { message("Select a task."); return; }
        if (selectedImportedTask()) { message("Imported VS Code tasks are session-only and were not removed."); return; }
        if (JOptionPane.showConfirmDialog(panel, "Remove task '" + selected + "'?", "Remove Task", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) message(editor.removeProjectTask(editor.resolveTaskProjectRoot(), selected));
    }

    private void runTask() { run(false); }
    private void dryRun() { run(true); }
    private void run(boolean dry) {
        String selected = tasks.getSelectedValue();
        if (selected == null) { message("Select a task."); return; }
        TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(editor.resolveTaskProjectRoot());
        message(editor.jobQuickfixController.runLoadedTask(selected, editor.resolveTaskProjectRoot(),
            editor.jobQuickfixController.effectiveWorkspaceTasks(editor.resolveTaskProjectRoot(), loaded), dry));
    }

    private void cancelJob() {
        int row = jobTable.getSelectedRow();
        if (row < 0) { message("Select a job."); return; }
        message(editor.cancelJob(String.valueOf(jobs.getValueAt(row, 0))));
    }

    private void showOutput() {
        int row = jobTable.getSelectedRow();
        if (row < 0) return;
        Object value = jobs.getValueAt(row, 0);
        try { output.setText(editor.jobQuickfixController.taskOutputForPanel(Integer.parseInt(String.valueOf(value)))); }
        catch (NumberFormatException ignored) { output.setText(""); }
        output.setCaretPosition(0);
    }

    private void message(String result) { editor.showMessage(result); refresh(); }
    private boolean selectedImportedTask() {
        TaskService.WorkspaceTask task = taskByName.get(tasks.getSelectedValue());
        return task != null && task.hasDirectArguments();
    }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
}
