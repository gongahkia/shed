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
    private final JCheckBox matchCase = new JCheckBox("Match case", true);
    private final JCheckBox regex = new JCheckBox("Regex");
    private final JCheckBox wholeWord = new JCheckBox("Whole word");
    private final JTextField include = new JTextField();
    private final JTextField exclude = new JTextField();
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
    private final JTable fileTable = new JTable(files) {
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row >= 0 && row < visibleFiles.size() ? visibleFiles.get(row).path().toString() : null;
        }
    };
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
        refreshAppearance();
        detail.setEditable(false);
        for (JTextField field : List.of(find, replacement, include, exclude)) {
            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent event) { invalidatePreview(); }
                public void removeUpdate(javax.swing.event.DocumentEvent event) { invalidatePreview(); }
                public void changedUpdate(javax.swing.event.DocumentEvent event) { invalidatePreview(); }
            });
        }
        for (JCheckBox option : List.of(matchCase, regex, wholeWord)) option.addActionListener(event -> invalidatePreview());
        matchTable.getSelectionModel().addListSelectionListener(event -> {
            if (!refreshing && !event.getValueIsAdjusting() && matchTable.getSelectedRow() >= 0 && matchTable.getSelectedRow() < visibleMatches.size()) {
                var match = visibleMatches.get(matchTable.getSelectedRow());
                String path = fileTable.getSelectedRow() >= 0 ? visibleFiles.get(fileTable.getSelectedRow()).path() + "\n\n" : "";
                detail.setText(path + "Line " + match.line() + ", column " + match.column() + "\n\n" + match.preview()); detail.setCaretPosition(0);
            }
        });
        fileTable.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) refreshMatches(); });
        files.addTableModelListener(event -> { if (!refreshing && event.getColumn() == 0) updateFile(event.getFirstRow()); });
        matches.addTableModelListener(event -> { if (!refreshing && event.getColumn() == 0) updateMatch(event.getFirstRow()); });
        enabled.addActionListener(event -> { if (!refreshing) message(editor.workspaceReplaceCoordinator().setForPanel("project.replace.enabled", Boolean.toString(enabled.isSelected()))); });
        scope.addActionListener(event -> { if (!refreshing) { invalidatePreview(); message(editor.workspaceReplaceCoordinator().setForPanel("project.replace.scope", String.valueOf(scope.getSelectedItem()))); } });
    }

    @Override public void refreshAppearance() {
        SearchControlMetrics.apply(panel, editor);
        fileTable.setRowHeight(UiZoom.scale(40, editor.configManager.getUiZoom()));
        matchTable.setRowHeight(UiZoom.scale(40, editor.configManager.getUiZoom()));
        for (JTable table : List.of(fileTable, matchTable)) {
            table.setFont(editor.resolveUiFont()); table.getTableHeader().setFont(editor.resolveUiFont());
            table.getColumnModel().getColumn(0).setMaxWidth(UiZoom.scale(60, editor.configManager.getUiZoom()));
        }
        fileTable.getColumnModel().getColumn(2).setMaxWidth(UiZoom.scale(75, editor.configManager.getUiZoom()));
        detail.setFont(editor.resolveUiFont());
        detail.setLineWrap(true); detail.setWrapStyleWord(true);
    }
    @Override public JPanel component() { return panel; }

    private JPanel toolbar() {
        JPanel controls = new JPanel(new GridLayout(3, 1, 4, 4));
        JPanel fields = new JPanel(new GridLayout(1, 2, 8, 0));
        fields.add(field("Find", find)); fields.add(field("Replace", replacement));
        controls.add(fields);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (var option : List.of(matchCase, regex, wholeWord, enabled)) options.add(option);
        options.add(scope); options.add(button("Preview", this::preview)); controls.add(options);
        JPanel filters = new JPanel(new GridLayout(1, 2, 8, 0));
        filters.add(field("Include", include)); filters.add(field("Exclude", exclude)); controls.add(filters);
        return controls;
    }

    private JPanel field(String name, JTextField input) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        JLabel label = new JLabel(name); label.setLabelFor(input); row.add(label, BorderLayout.WEST); row.add(input);
        input.getAccessibleContext().setAccessibleName(name);
        input.setPreferredSize(new java.awt.Dimension(180, UiZoom.scale(40, editor.configManager.getUiZoom())));
        return row;
    }

    void setSearchQuery(WorkspaceSearchQuery query) {
        refreshing = true;
        try {
            find.setText(query.pattern().query()); matchCase.setSelected(query.pattern().caseSensitive());
            regex.setSelected(query.pattern().regex()); wholeWord.setSelected(query.pattern().wholeWord());
            include.setText(query.include()); exclude.setText(query.exclude());
        } finally { refreshing = false; }
        invalidatePreview(); replacement.requestFocusInWindow();
    }

    private void invalidatePreview() {
        if (refreshing) return;
        editor.workspaceReplaceCoordinator().cancelForPanel(); refresh();
    }

    private java.awt.Component content() {
        JScrollPane filePane = new JScrollPane(fileTable); filePane.setBorder(BorderFactory.createTitledBorder("Files"));
        JScrollPane matchPane = new JScrollPane(matchTable); matchPane.setBorder(BorderFactory.createTitledBorder("Matches"));
        JSplitPane left = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filePane, matchPane); left.setResizeWeight(0.40);
        JScrollPane result = new JScrollPane(detail); result.setBorder(BorderFactory.createTitledBorder("Preview / Apply Result"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, result); split.setResizeWeight(0.68);
        return split;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        panel.add(button("Discard Preview", this::discard));
        panel.add(button("Apply Selected…", this::apply));
        return panel;
    }

    @Override public void refresh() {
        int selectedFile = fileTable.getSelectedRow() >= 0 && fileTable.getSelectedRow() < visibleFiles.size()
            ? visibleFiles.get(fileTable.getSelectedRow()).fileId() : -1;
        int selectedMatch = matchTable.getSelectedRow() >= 0 && matchTable.getSelectedRow() < visibleMatches.size()
            ? visibleMatches.get(matchTable.getSelectedRow()).matchId() : -1;
        refreshing = true;
        try {
            WorkspaceReplaceCoordinator controller = editor.workspaceReplaceCoordinator();
            ProjectReplacePolicy policy = controller.policyForPanel();
            enabled.setSelected(policy.enabled()); scope.setSelectedItem(policy.scope());
            plan = controller.planForPanel();
            files.setRowCount(0); visibleFiles = plan == null ? List.of() : plan.files();
            for (WorkspaceReplaceService.FilePlan file : visibleFiles) {
                String label = file.path().getFileName().toString();
                if (visibleFiles.stream().filter(other -> other.path().getFileName().equals(file.path().getFileName())).count() > 1)
                    label += " — " + file.path().getParent();
                files.addRow(new Object[] {file.selectedMatchCount() > 0, label, file.selectedMatchCount()});
            }
            int fileRow = 0;
            for (int index = 0; index < visibleFiles.size(); index++) if (visibleFiles.get(index).fileId() == selectedFile) fileRow = index;
            if (!visibleFiles.isEmpty()) fileTable.setRowSelectionInterval(fileRow, fileRow);
            renderMatches();
            for (int index = 0; index < visibleMatches.size(); index++)
                if (visibleMatches.get(index).matchId() == selectedMatch) matchTable.setRowSelectionInterval(index, index);
            WorkspaceReplaceService.ApplyResult result = controller.lastApplyResultForPanel();
            if (result != null) detail.setText(renderResult(result));
            else if (plan == null) detail.setText(policy.enabled() ? "Enter a find/replacement pair, then Preview. Regex replacements support $1 and ${name}." : "Enable project replace before previewing.");
            else detail.setText((visibleFiles.isEmpty() ? "" : visibleFiles.get(fileRow).path() + "\n\n") + renderPlan(plan));
            detail.setCaretPosition(0);
        } finally { refreshing = false; }
    }

    private void refreshMatches() {
        if (refreshing) return;
        refreshing = true;
        try {
            renderMatches();
            if (plan != null && fileTable.getSelectedRow() >= 0) {
                detail.setText(visibleFiles.get(fileTable.getSelectedRow()).path() + "\n\n" + renderPlan(plan)); detail.setCaretPosition(0);
            }
        } finally { refreshing = false; }
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

    private void preview() {
        try { message(editor.workspaceReplaceCoordinator().previewForPanel(new WorkspaceSearchQuery(
            new SearchPattern(find.getText(), matchCase.isSelected(), regex.isSelected(), wholeWord.isSelected()), include.getText(), exclude.getText()), replacement.getText())); }
        catch (IllegalArgumentException error) { message("Invalid search: " + error.getMessage()); }
    }
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
