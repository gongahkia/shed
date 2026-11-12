package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

final class ProblemsToolPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final JTextField filter = new JTextField();
    private final JComboBox<String> severity = new JComboBox<>(new String[] {"All", "Errors", "Warnings", "Info", "Hints", "Other"});
    private final DefaultTableModel rows = new DefaultTableModel(new Object[] {"Severity", "Source", "File", "Location", "Message"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(rows);
    private List<ProblemsService.Problem> visible = List.of();
    private final JLabel state = new JLabel();

    ProblemsToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(toolbar(), BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(state, BorderLayout.SOUTH);
        AccessibilitySupport.describe(filter, "Filter problems", "Filter diagnostics by source, path or message.");
        AccessibilitySupport.describe(table, "Problems", "Select a diagnostic, then press Enter to open its source location.");
        table.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "openProblem");
        table.getActionMap().put("openProblem", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent event) { openSelected(); }
        });
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            public java.awt.Component getTableCellRendererComponent(JTable source, Object value, boolean selected, boolean focused, int row, int column) {
                super.getTableCellRendererComponent(source, value, selected, focused, row, column);
                setToolTipText(value == null ? null : value.toString());
                return this;
            }
        });
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(4).setPreferredWidth(520);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) openSelected();
            }
        });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh(); }
        });
        severity.addActionListener(event -> refresh());
    }

    @Override public JPanel component() { return panel; }
    @Override public void focus() { filter.requestFocusInWindow(); }

    @Override public void refresh() {
        List<ProblemsService.Problem> all = editor.problemsController.snapshot();
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        String selectedSeverity = String.valueOf(severity.getSelectedItem()).toLowerCase(Locale.ROOT);
        int selectedRow = table.getSelectedRow();
        String selectedIdentity = selectedRow < 0 || table.convertRowIndexToModel(selectedRow) >= visible.size()
            ? null : visible.get(table.convertRowIndexToModel(selectedRow)).identity();
        visible = new ArrayList<>();
        for (ProblemsService.Problem problem : all) {
            if (!matchesSeverity(problem, selectedSeverity) || !matchesQuery(problem, query)) continue;
            visible.add(problem);
        }
        rows.setRowCount(0);
        for (ProblemsService.Problem problem : visible) {
            rows.addRow(new Object[] {
                problem.severity().name().toLowerCase(Locale.ROOT),
                problem.source(), displayPath(problem.filePath()), problem.line() + ":" + problem.column(), problem.message()
            });
        }
        for (int row = 0; row < visible.size(); row++) if (visible.get(row).identity().equals(selectedIdentity)) {
            int view = table.convertRowIndexToView(row); if (view >= 0) table.setRowSelectionInterval(view, view); break;
        }
        WorkbenchStyle.emptyText(table, all.isEmpty() ? "No problems reported in this workspace" : "No problems match the current filters");
        long errors = all.stream().filter(problem -> problem.severity() == ProblemsService.Severity.ERROR).count();
        WorkbenchStyle.message(state, visible.size() + " of " + all.size() + " problems · " + errors + " errors", errors > 0 ? "error" : null);
    }

    void selectFilter(String value) {
        String normalized = value == null ? "all" : value.trim().toLowerCase(Locale.ROOT);
        String target = switch (normalized) {
            case "errors" -> "Errors";
            case "warnings" -> "Warnings";
            case "info" -> "Info";
            case "hints" -> "Hints";
            case "other" -> "Other";
            default -> "All";
        };
        severity.setSelectedItem(target);
    }

    private JPanel toolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(6, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(new JLabel("Filter")); left.add(filter);
        filter.setColumns(26);
        left.add(severity);
        left.add(button("Refresh", this::refresh));
        left.add(button("Open", this::openSelected));
        toolbar.add(left, BorderLayout.CENTER);
        return toolbar;
    }

    private void openSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) { editor.showMessage("Select a problem"); return; }
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= visible.size()) { editor.showMessage("Problem selection changed; refresh and retry"); return; }
        editor.showMessage(editor.problemsController.open(visible.get(modelRow)));
    }

    private static boolean matchesSeverity(ProblemsService.Problem problem, String selected) {
        return "all".equals(selected) || problem.severity().name().toLowerCase(Locale.ROOT).equals(selected.substring(0, selected.length() - (selected.endsWith("s") ? 1 : 0)));
    }

    private static boolean matchesQuery(ProblemsService.Problem problem, String query) {
        if (query.isEmpty()) return true;
        String haystack = (problem.source() + " " + problem.filePath() + " " + problem.line() + " " + problem.message()).toLowerCase(Locale.ROOT);
        return haystack.contains(query);
    }

    private static String displayPath(String path) {
        if (path == null || path.isBlank()) return "";
        String name = new File(path).getName();
        return name.isBlank() ? path : name;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }
}
