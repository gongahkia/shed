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
    private final JLabel state = new JLabel("Select a task to run it. Opening this panel does not run commands.");
    private final JButton cancel = button("Cancel", this::cancelJob);
    private final java.util.Set<Integer> cancelling = new java.util.HashSet<>();
    private Integer displayedJob;
    private boolean refreshing;

    TasksToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(toolbar(), BorderLayout.NORTH);
        panel.add(content(), BorderLayout.CENTER);
        panel.add(state, BorderLayout.SOUTH);
        AccessibilitySupport.describe(state, "Task status", "Saved task count, background progress, completion and cancellation.");
        AccessibilitySupport.describe(tasks, "Workspace tasks", "Select a task; Run starts it explicitly. Opening the panel does not execute commands.");
        AccessibilitySupport.describe(jobTable, "Task jobs", "Select a job to read its output. Only a running job can be cancelled.");
        AccessibilitySupport.describe(output, "Task output", "Output of the selected job. Background refresh preserves your reading position.");
        AccessibilitySupport.describe(cancel, "Cancel selected task job", "Stop the selected running task and its tracked child processes.");
        cancel.setEnabled(false);
        output.setEditable(false);
        WorkbenchStyle.emptyText(tasks, "Save a task above to get started");
        WorkbenchStyle.emptyText(jobTable, "Run a task to see its progress");
        jobTable.getSelectionModel().addListSelectionListener(event -> { if (!refreshing && !event.getValueIsAdjusting()) { showOutput(); updateCancellation(); } });
        tasks.addListSelectionListener(event -> { if (!refreshing && !event.getValueIsAdjusting()) loadTask(); });
    }

    @Override public JPanel component() { return panel; }
    @Override public void focus() { tasks.requestFocusInWindow(); }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        JPanel fields = new JPanel(new GridLayout(1, 2, 12, 0));
        fields.add(field("Name", name)); fields.add(field("Command", command));
        panel.add(fields, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(button("Save Task", this::saveTask));
        actions.add(button("Remove", this::removeTask));
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private JPanel field(String title, JTextField input) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JLabel label = new JLabel(title); label.setLabelFor(input);
        panel.add(label, BorderLayout.WEST); panel.add(input, BorderLayout.CENTER);
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
        jobActions.add(cancel);
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
        if (refreshing) return;
        refreshing = true;
        Integer selectedJob = selectedJob();
        try {
            File root = editor.resolveTaskProjectRoot();
            TaskService.TaskLoadResult loaded = editor.taskService.loadWorkspaceTasks(root);
            String selected = tasks.getSelectedValue();
            var available = loaded.isValid() ? editor.jobQuickfixController.effectiveWorkspaceTasks(root, loaded) : Map.<String, TaskService.WorkspaceTask>of();
            List<String> nextNames = List.copyOf(available.keySet());
            taskByName.clear(); taskByName.putAll(available);
            if (!java.util.Collections.list(taskNames.elements()).equals(nextNames)) {
                taskNames.clear(); taskNames.addAll(nextNames); tasks.setSelectedValue(selected, true);
            }
            jobs.setRowCount(0);
            int running = 0, failed = 0;
            for (AsyncJobService.JobSnapshot job : editor.asyncJobService.list()) {
                if (!job.getDescription().startsWith("task ")) continue;
                if (job.getStatus() == AsyncJobService.Status.RUNNING) running++;
                else cancelling.remove(job.getId());
                Integer exitCode = editor.jobQuickfixController.taskExitCodeForPanel(job.getId());
                boolean commandFailed = exitCode != null && exitCode != 0;
                if (job.getStatus() == AsyncJobService.Status.FAILED || commandFailed) failed++;
                Long done = job.getFinishedAtMillis();
                long duration = Math.max(0L, (done == null ? System.currentTimeMillis() : done) - job.getStartedAtMillis());
                String status = cancelling.contains(job.getId()) ? "cancelling" : commandFailed ? "failed · exit " + exitCode : job.getStatus().name().toLowerCase(java.util.Locale.ROOT);
                jobs.addRow(new Object[] {job.getId(), status, job.getDescription(), duration + " ms"});
                if (java.util.Objects.equals(selectedJob, job.getId())) jobTable.setRowSelectionInterval(jobs.getRowCount() - 1, jobs.getRowCount() - 1);
            }
            if (!loaded.isValid()) WorkbenchStyle.message(state, "Task file error: " + String.join("; ", loaded.diagnostics()), "error");
            else WorkbenchStyle.message(state, taskNames.size() + " saved tasks · " + running + " running · " + failed + " failed", failed > 0 ? "error" : null);
        } finally { refreshing = false; }
        showOutput(); updateCancellation();
    }

    private Integer selectedJob() {
        int row = jobTable.getSelectedRow();
        return row < 0 || row >= jobs.getRowCount() ? null : (Integer) jobs.getValueAt(row, 0);
    }

    private void updateCancellation() {
        Integer id = selectedJob();
        var job = id == null ? null : editor.asyncJobService.get(id);
        cancel.setEnabled(job != null && job.getStatus() == AsyncJobService.Status.RUNNING && !cancelling.contains(id));
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
        int previousId = editor.asyncJobService.list().stream().mapToInt(AsyncJobService.JobSnapshot::getId).max().orElse(0);
        message(editor.jobQuickfixController.runTaskForPanel(selected, editor.resolveTaskProjectRoot(),
            editor.jobQuickfixController.effectiveWorkspaceTasks(editor.resolveTaskProjectRoot(), loaded), dry));
        for (int row = 0; row < jobs.getRowCount(); row++) {
            if ((Integer) jobs.getValueAt(row, 0) > previousId) { jobTable.setRowSelectionInterval(row, row); break; }
        }
    }

    private void cancelJob() {
        int row = jobTable.getSelectedRow();
        if (row < 0) { message("Select a job."); return; }
        Integer id = selectedJob();
        String result = editor.cancelJob(String.valueOf(id));
        if (result.startsWith("Cancellation sent")) cancelling.add(id);
        message(result);
    }

    private void showOutput() {
        Integer id = selectedJob();
        if (id == null) { updateCancellation(); return; }
        String text = editor.jobQuickfixController.taskOutputForPanel(id);
        var job = editor.asyncJobService.get(id);
        if (text.isEmpty() && job != null) text = switch (job.getStatus()) {
            case RUNNING -> "Running… output will appear when this task finishes.";
            case CANCELLED -> "Task cancelled. No completed output was retained.";
            case FAILED -> "Task failed: " + job.getErrorMessage();
            case SUCCEEDED -> editor.jobQuickfixController.taskExitCodeForPanel(id) == null
                ? "Finishing task output…" : "Task completed without output.";
        };
        ToolOutput.update(output, text, !java.util.Objects.equals(displayedJob, id));
        displayedJob = id;
    }

    private void message(String result) { editor.showMessage(result); refresh(); }
    private boolean selectedImportedTask() {
        TaskService.WorkspaceTask task = taskByName.get(tasks.getSelectedValue());
        return task != null && task.hasDirectArguments();
    }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.setName("tasks." + text.toLowerCase(java.util.Locale.ROOT).replace(' ', '.')); button.addActionListener(event -> action.run()); return button; }
}
