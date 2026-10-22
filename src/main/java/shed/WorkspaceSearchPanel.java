package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.*;

/** Persistent search controls and source locations; results never replace the source document. */
final class WorkspaceSearchPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final JTextField query = new JTextField();
    private final JTextField include = new JTextField();
    private final JTextField exclude = new JTextField();
    private final JCheckBox matchCase = new JCheckBox("Match case", true);
    private final JCheckBox regex = new JCheckBox("Regex");
    private final JCheckBox wholeWord = new JCheckBox("Whole word");
    private final DefaultListModel<WorkspaceTextSearchService.Match> model = new DefaultListModel<>();
    private final JList<WorkspaceTextSearchService.Match> results = new JList<>(model);
    private final JLabel status = new JLabel();
    private final JButton cancel = button("Cancel", this::cancel);
    private final Timer debounce = new Timer(300, event -> search());
    private boolean changing;
    private boolean initialized;
    private List<WorkspaceTextSearchService.Match> displayed = List.of();

    WorkspaceSearchPanel(Texteditor editor) {
        this.editor = editor;
        debounce.setRepeats(false);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel controls = new JPanel(new BorderLayout(6, 4));
        JPanel queryRow = new JPanel(new BorderLayout(6, 0));
        queryRow.add(label("Find", query), BorderLayout.WEST);
        queryRow.add(query, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(button("Search", this::search)); actions.add(cancel);
        actions.add(button("Current File", () -> {
            try { editor.inlineFindBar.showFind(request().pattern()); editor.hideToolWindow(); }
            catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
        }));
        actions.add(button("Replace…", () -> {
            try {
                WorkspaceSearchQuery value = request();
                editor.showToolWindow(ToolWindowHost.Tab.REPLACE);
                editor.toolWindowHost.replacePanel().setSearchQuery(value);
            } catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
        }));
        queryRow.add(actions, BorderLayout.EAST);
        controls.add(queryRow, BorderLayout.NORTH);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        for (JCheckBox option : List.of(matchCase, regex, wholeWord)) {
            options.add(option); option.addActionListener(event -> edited());
        }
        controls.add(options, BorderLayout.CENTER);
        JPanel filters = new JPanel(new GridLayout(1, 2, 8, 0));
        filters.add(field("Include", include)); filters.add(field("Exclude", exclude));
        controls.add(filters, BorderLayout.SOUTH);
        panel.add(controls, BorderLayout.NORTH);
        panel.add(new JScrollPane(results), BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setFixedCellHeight(UiZoom.scale(40, editor.configManager.getUiZoom()));
        results.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                putClientProperty("html.disable", true);
                if (value instanceof WorkspaceTextSearchService.Match match) {
                    java.nio.file.Path file = java.nio.file.Path.of(match.filePath());
                    setText(file.getFileName() + ":" + match.line() + ":" + match.column() + "  " + match.preview());
                    setToolTipText(match.filePath());
                    getAccessibleContext().setAccessibleName(match.filePath() + ", line " + match.line() + ", " + match.preview());
                }
                return this;
            }
        });
        for (JTextField field : List.of(query, include, exclude)) {
            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent event) { edited(); }
                public void removeUpdate(javax.swing.event.DocumentEvent event) { edited(); }
                public void changedUpdate(javax.swing.event.DocumentEvent event) { edited(); }
            });
            field.addActionListener(event -> search());
            field.setPreferredSize(new java.awt.Dimension(180, UiZoom.scale(40, editor.configManager.getUiZoom())));
        }
        query.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "results");
        query.getActionMap().put("results", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!model.isEmpty()) { if (results.getSelectedIndex() < 0) results.setSelectedIndex(0); results.requestFocusInWindow(); }
            }
        });
        results.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        results.getActionMap().put("open", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent event) { openSelected(); }
        });
        results.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = results.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && row >= 0 && results.getCellBounds(row, row).contains(event.getPoint())) openSelected();
            }
        });
        KeyboardFocusSupport.installEscape(panel, () -> { debounce.stop(); coordinator().cancel(); editor.writingArea.requestFocusInWindow(); });
        AccessibilitySupport.describe(query, "Workspace search", "Find text in all workspace folders. Enter starts a search; Down focuses results.");
        AccessibilitySupport.describe(include, "Included files", "Comma-separated globs, such as **/*.{java,kt}. Empty includes all visible files.");
        AccessibilitySupport.describe(exclude, "Excluded files", "Comma-separated globs, such as build/**. Ignore rules also apply.");
        AccessibilitySupport.describe(results, "Workspace search results", "Enter or double-click opens the selected file at its matching location.");
        AccessibilitySupport.describe(status, "Search progress", "Progress, cancellation, errors and result limits.");
        refreshAppearance();
        editor.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) { debounce.stop(); }
        });
    }
    private static JLabel label(String text, JComponent field) { JLabel label = new JLabel(text); label.setLabelFor(field); return label; }
    private static JPanel field(String name, JTextField field) { JPanel row = new JPanel(new BorderLayout(5, 0)); row.add(label(name, field), BorderLayout.WEST); row.add(field); return row; }
    private static JButton button(String title, Runnable action) { JButton button = new JButton(title); button.addActionListener(event -> action.run()); return button; }
    private WorkspaceSearchCoordinator coordinator() { return editor.paletteController.workspaceSearch(); }
    private WorkspaceSearchQuery request() { return new WorkspaceSearchQuery(new SearchPattern(query.getText(), matchCase.isSelected(), regex.isSelected(), wholeWord.isSelected()), include.getText(), exclude.getText()); }
    void focusQuery(SearchPattern initial) {
        changing = true;
        if (initial != null) { query.setText(initial.query()); matchCase.setSelected(initial.caseSensitive()); regex.setSelected(initial.regex()); wholeWord.setSelected(initial.wholeWord()); }
        changing = false;
        query.selectAll(); SwingUtilities.invokeLater(query::requestFocusInWindow);
    }
    private void edited() {
        if (changing) return;
        debounce.stop(); coordinator().invalidate();
        if (!query.getText().isEmpty()) { debounce.restart(); cancel.setEnabled(true); }
    }
    private void cancel() { debounce.stop(); coordinator().cancel(); }
    private void search() {
        debounce.stop();
        if (editor.closingDown || !editor.isDisplayable()) return;
        try { coordinator().search(request()); }
        catch (IllegalArgumentException error) { coordinator().invalidate(); status.setText("Invalid search: " + error.getMessage()); }
    }
    private void openSelected() {
        WorkspaceTextSearchService.Match selected = results.getSelectedValue();
        if (selected != null) editor.openFileAtAsync(new java.io.File(selected.filePath()), selected.line(), selected.column(), "search");
    }
    @Override public void refreshAppearance() {
        SearchControlMetrics.apply(panel, editor);
        results.setFixedCellHeight(UiZoom.scale(40, editor.configManager.getUiZoom()));
        results.setFont(editor.resolveUiFont());
    }
    void setQuery(WorkspaceSearchQuery value) {
        changing = true;
        try {
            if (!query.getText().equals(value.pattern().query())) query.setText(value.pattern().query());
            matchCase.setSelected(value.pattern().caseSensitive());
            regex.setSelected(value.pattern().regex()); wholeWord.setSelected(value.pattern().wholeWord());
            if (!include.getText().equals(value.include())) include.setText(value.include());
            if (!exclude.getText().equals(value.exclude())) exclude.setText(value.exclude());
        } finally { changing = false; }
    }
    @Override public JPanel component() { return panel; }
    @Override public void refresh() {
        WorkspaceSearchCoordinator.Snapshot snapshot = coordinator().snapshot();
        if (!initialized && snapshot.query() != null) {
            changing = true;
            query.setText(snapshot.query().pattern().query());
            matchCase.setSelected(snapshot.query().pattern().caseSensitive());
            regex.setSelected(snapshot.query().pattern().regex()); wholeWord.setSelected(snapshot.query().pattern().wholeWord());
            include.setText(snapshot.query().include()); exclude.setText(snapshot.query().exclude());
            changing = false;
        }
        initialized = true;
        cancel.setEnabled(snapshot.running()); status.setText(snapshot.status());
        if (!displayed.equals(snapshot.matches())) {
            WorkspaceTextSearchService.Match selection = results.getSelectedValue();
            displayed = snapshot.matches(); model.clear(); model.addAll(displayed);
            if (selection != null && displayed.contains(selection)) results.setSelectedValue(selection, false);
        }
    }
}
