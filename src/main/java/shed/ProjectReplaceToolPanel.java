package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

final class ProjectReplaceToolPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final JTextField find = new JTextField();
    private final JTextField replacement = new JTextField();
    private final JCheckBox enabled = new JCheckBox("Enabled");
    private final JComboBox<String> scope = new JComboBox<>(new String[] {"workspace", "current-file"});
    private final DefaultTableModel files = new DefaultTableModel(new Object[] {"Apply", "File", "Matches"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
    };
    private final DefaultTableModel matches = new DefaultTableModel(new Object[] {"Apply", "Location", "Preview"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
    };
    private final JTable fileTable = new JTable(files);
    private final JTable matchTable = new JTable(matches);
    private final JTextArea detail = new JTextArea();
    private WorkspaceReplaceService.Plan plan;
    private List<WorkspaceReplaceService.FilePlan> visibleFiles = List.of();
    private List<WorkspaceReplaceService.MatchPlan> visibleMatches = List.of();
    private boolean refreshing;

    ProjectReplaceToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(toolbar(), BorderLayout.NORTH);
        panel.add(content(), BorderLayout.CENTER);
        panel.add(actions(), BorderLayout.SOUTH);
        detail.setEditable(false);
        fileTable.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) refreshMatches(); });
        files.addTableModelListener(event -> { if (!refreshing && event.getColumn() == 0) updateFile(event.getFirstRow()); });
        matches.addTableModelListener(event -> { if (!refreshing && event.getColumn() == 0) updateMatch(event.getFirstRow()); });
        enabled.addActionListener(event -> { if (!refreshing) message(editor.workspaceReplaceCoordinator().setForPanel("project.replace.enabled", Boolean.toString(enabled.isSelected()))); });
        scope.addActionListener(event -> { if (!refreshing) message(editor.workspaceReplaceCoordinator().setForPanel("project.replace.scope", String.valueOf(scope.getSelectedItem()))); });
    }

    @Override public JPanel component() { return panel; }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new GridLayout(1, 7, 5, 0));
        panel.add(new JLabel("Find")); panel.add(find); panel.add(new JLabel("Replace")); panel.add(replacement);
        panel.add(enabled); panel.add(scope); panel.add(button("Preview", this::preview));
        return panel;
    }

    private java.awt.Component content() {
        JScrollPane filePane = new JScrollPane(fileTable); filePane.setBorder(BorderFactory.createTitledBorder("Files"));
        JScrollPane matchPane = new JScrollPane(matchTable); matchPane.setBorder(BorderFactory.createTitledBorder("Matches"));
        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, filePane, matchPane); left.setResizeWeight(0.48);
        JScrollPane result = new JScrollPane(detail); result.setBorder(BorderFactory.createTitledBorder("Preview / Apply Result"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, result); split.setResizeWeight(0.54);
        return split;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        panel.add(button("Discard Preview", this::discard));
        panel.add(button("Apply Selected…", this::apply));
        return panel;
    }

    @Override public void refresh() {
        refreshing = true;
        try {
            WorkspaceReplaceCoordinator controller = editor.workspaceReplaceCoordinator();
            ProjectReplacePolicy policy = controller.policyForPanel();
            enabled.setSelected(policy.enabled()); scope.setSelectedItem(policy.scope());
            plan = controller.planForPanel();
            files.setRowCount(0); visibleFiles = plan == null ? List.of() : plan.files();
            for (WorkspaceReplaceService.FilePlan file : visibleFiles) files.addRow(new Object[] {file.selectedMatchCount() > 0, file.path().toString(), file.selectedMatchCount()});
            renderMatches();
            WorkspaceReplaceService.ApplyResult result = controller.lastApplyResultForPanel();
            if (result != null) detail.setText(renderResult(result));
            else if (plan == null) detail.setText(policy.enabled() ? "Enter a literal find/replacement pair, then Preview." : "Enable project replace before previewing.");
            else detail.setText(renderPlan(plan));
        } finally { refreshing = false; }
    }

    private void refreshMatches() {
        if (refreshing) return;
        refreshing = true;
        try { renderMatches(); } finally { refreshing = false; }
    }

    private void renderMatches() {
        int row = fileTable.getSelectedRow();
        WorkspaceReplaceService.FilePlan file = row < 0 || row >= visibleFiles.size() ? null : visibleFiles.get(row);
        visibleMatches = file == null ? List.of() : file.matches();
        matches.setRowCount(0);
        for (WorkspaceReplaceService.MatchPlan match : visibleMatches) {
            matches.addRow(new Object[] {match.selected(), match.line() + ":" + match.column(), match.preview()});
        }
    }

    private void preview() { message(editor.workspaceReplaceCoordinator().previewForPanel(find.getText(), replacement.getText())); }
    private void discard() { message(editor.workspaceReplaceCoordinator().cancelForPanel()); }

    private void apply() {
        if (plan == null || plan.selectedMatchCount() == 0) { message("Preview and select at least one match first."); return; }
        String warning = "Apply " + plan.selectedMatchCount() + " replacement(s) across " + plan.files().size() + " file(s)?\n"
            + "Files changed since preview will be skipped.";
        if (JOptionPane.showConfirmDialog(panel, warning, "Apply Project Replace", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) message(editor.workspaceReplaceCoordinator().applyForPanel());
    }

    private void updateFile(int row) {
        if (row < 0 || row >= visibleFiles.size()) return;
        boolean selected = Boolean.TRUE.equals(files.getValueAt(row, 0));
        editor.workspaceReplaceCoordinator().selectFileForPanel(visibleFiles.get(row).fileId(), selected);
        refresh();
    }

    private void updateMatch(int row) {
        if (row < 0 || row >= visibleMatches.size()) return;
        boolean selected = Boolean.TRUE.equals(matches.getValueAt(row, 0));
        editor.workspaceReplaceCoordinator().selectMatchForPanel(visibleMatches.get(row).matchId(), selected);
        refresh();
    }

    private void message(String result) { editor.showMessage(result); refresh(); }
    private static JButton button(String text, Runnable action) { JButton button = new JButton(text); button.addActionListener(event -> action.run()); return button; }
    private static String renderPlan(WorkspaceReplaceService.Plan plan) {
        return "Preview ready\n\nFind: " + plan.needle() + "\nReplace: " + plan.replacement() + "\nSelected matches: " + plan.selectedMatchCount()
            + (plan.truncated() ? "\nPreview limit reached; apply is blocked." : "\n\nReview files and matches, then Apply Selected.");
    }
    private static String renderResult(WorkspaceReplaceService.ApplyResult result) {
        StringBuilder text = new StringBuilder(result.message()).append('\n');
        for (WorkspaceReplaceService.FileResult file : result.files()) text.append(file.state().name().toLowerCase()).append("  ")
            .append(file.path()).append("  ").append(file.message()).append('\n');
        return text.toString();
    }
}
