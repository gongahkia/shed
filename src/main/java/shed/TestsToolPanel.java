package shed;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

final class TestsToolPanel implements ToolWindowHost.ToolSurface {
    private record Node(String label, TestService.TestCase test) { @Override public String toString() { return label; } }
    private final Texteditor editor;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private final JComboBox<String> roots = new JComboBox<>();
    private final JComboBox<String> status = new JComboBox<>(new String[] {"All", "Unknown", "Passed", "Failed", "Skipped", "Errored"});
    private final JTextField filter = new JTextField(16);
    private final JLabel state = new JLabel("Refresh to discover tests.");
    private final JTree tree = new JTree(new DefaultMutableTreeNode("Tests"));
    private final JTextArea output = new JTextArea();
    private boolean refreshing;

    TestsToolPanel(Texteditor editor, ToolWindowHost host) {
        this.editor = editor;
        panel.setBorder(BorderFactory.createEmptyBorder(5, 7, 7, 7));
        panel.add(toolbar(), BorderLayout.NORTH);
        output.setEditable(false);
        output.setLineWrap(false);
        tree.addTreeSelectionListener(this::selected);
        tree.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) message(editor.testController.open(selectedTest())); } });
        filter.getDocument().addDocumentListener(new DocumentListener() { @Override public void insertUpdate(DocumentEvent event) { refreshTree(); } @Override public void removeUpdate(DocumentEvent event) { refreshTree(); } @Override public void changedUpdate(DocumentEvent event) { refreshTree(); } });
        status.addActionListener(event -> { if (!refreshing) refreshTree(); });
        roots.addActionListener(event -> { if (!refreshing && roots.getSelectedItem() != null) { editor.testController.selectRoot(Path.of(String.valueOf(roots.getSelectedItem()))); refresh(); } });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(output));
        split.setResizeWeight(0.47);
        panel.add(split, BorderLayout.CENTER);
        AccessibilitySupport.describe(tree, "Tests", "Discovered tests grouped by adapter and suite. Double-click to open source.");
    }

    @Override public JPanel component() { return panel; }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        controls.add(new JLabel("Root")); controls.add(roots);
        controls.add(button("Refresh", () -> message(editor.testController.refresh(editor.testController.selectedRoot()).message())));
        controls.add(button("Run All", () -> message(editor.testController.runAll(editor.testController.selectedRoot()))));
        controls.add(button("Run Selection", () -> message(editor.testController.runSelection(editor.testController.selectedRoot(), selectedTest()))));
        controls.add(button("Debug Selection", () -> message(editor.testController.debugSelection(editor.testController.selectedRoot(), selectedTest()))));
        controls.add(button("Rerun Failed", () -> message(editor.testController.rerunFailed(editor.testController.selectedRoot()))));
        controls.add(button("Cancel", () -> message(editor.testController.cancel(editor.testController.selectedRoot()))));
        controls.add(button("Import Coverage…", this::importCoverage));
        controls.add(button("Clear Coverage", () -> message(editor.testController.clearCoverage(editor.testController.selectedRoot()))));
        controls.add(new JLabel("Show")); controls.add(status); controls.add(filter);
        panel.add(controls, BorderLayout.WEST);
        panel.add(state, BorderLayout.CENTER);
        return panel;
    }

    @Override public void refresh() {
        if (refreshing) return;
        refreshing = true;
        try {
            Path selected = editor.testController.selectedRoot();
            String previous = roots.getSelectedItem() == null ? "" : String.valueOf(roots.getSelectedItem());
            roots.removeAllItems();
            for (Path root : editor.testController.rootsForPanel()) roots.addItem(root.toString());
            roots.setSelectedItem(selected.toString());
            if (roots.getSelectedIndex() < 0 && !previous.isBlank()) roots.setSelectedItem(previous);
            TestController.Snapshot snapshot = editor.testController.snapshot(selected);
            state.setText((snapshot.runningJobs() > 0 ? "running " + snapshot.runningJobs() + " — " : "") + (snapshot.diagnostics().isEmpty() ? summary(snapshot) : snapshot.diagnostics().getFirst()));
            output.setText(snapshot.output());
            output.setCaretPosition(0);
            refreshTree(snapshot);
        } finally { refreshing = false; }
    }

    private void refreshTree() { refreshTree(editor.testController.snapshot(editor.testController.selectedRoot())); }
    private void refreshTree(TestController.Snapshot snapshot) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Tests — " + snapshot.tests().size());
        Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
        for (TestService.TestCase test : snapshot.tests()) {
            if (!matches(test)) continue;
            String adapter = test.adapterId().isBlank() ? "unknown" : test.adapterId();
            DefaultMutableTreeNode adapterNode = groups.computeIfAbsent(adapter, key -> { DefaultMutableTreeNode node = new DefaultMutableTreeNode(key); root.add(node); return node; });
            String suite = test.suite().isBlank() ? "tests" : test.suite();
            String key = adapter + "\u0000" + suite;
            DefaultMutableTreeNode suiteNode = groups.computeIfAbsent(key, ignored -> { DefaultMutableTreeNode node = new DefaultMutableTreeNode(suite); adapterNode.add(node); return node; });
            suiteNode.add(new DefaultMutableTreeNode(new Node(marker(test.status()) + " " + test.name(), test)));
        }
        tree.setModel(new DefaultTreeModel(root));
        for (int index = 0; index < tree.getRowCount(); index++) tree.expandRow(index);
    }

    private boolean matches(TestService.TestCase test) {
        String required = String.valueOf(status.getSelectedItem()).toLowerCase(Locale.ROOT);
        if (!"all".equals(required) && !test.status().name().toLowerCase(Locale.ROOT).equals(required)) return false;
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        return query.isBlank() || (test.adapterId() + " " + test.label() + " " + test.id()).toLowerCase(Locale.ROOT).contains(query);
    }

    private TestService.TestCase selectedTest() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof Node node ? node.test() : null;
    }

    private void selected(TreeSelectionEvent event) {
        TestService.TestCase test = selectedTest();
        if (test == null) return;
        String details = test.label() + "\nstatus: " + test.status().name().toLowerCase(Locale.ROOT) + "\nduration: " + test.durationMillis() + "ms\n";
        output.setText(details + (test.output().isBlank() ? "" : "\n" + test.output()));
        output.setCaretPosition(0);
    }

    private void message(String value) { editor.showMessage(value); refresh(); }
    private void importCoverage() {
        JFileChooser chooser = new JFileChooser(editor.testController.selectedRoot().toFile());
        chooser.setDialogTitle("Import coverage report");
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            message(editor.testController.importCoverage(editor.testController.selectedRoot(), chooser.getSelectedFile().toPath()));
        }
    }
    private static JButton button(String label, Runnable action) { JButton button = new JButton(label); button.addActionListener(event -> action.run()); return button; }
    private static String marker(TestService.Status value) { return switch (value) { case PASSED -> "✓"; case FAILED -> "✗"; case ERRORED -> "!"; case SKIPPED -> "–"; case UNKNOWN -> "·"; }; }
    private static String summary(TestController.Snapshot value) {
        long failed = value.tests().stream().filter(test -> test.status().failed()).count();
        String coverage = value.coverage().lines() == 0 ? "" : " — coverage " + value.coverage().display();
        return (value.tests().isEmpty() ? "Refresh to discover tests." : value.tests().size() + " tests" + (failed == 0 ? "" : ", " + failed + " failing")) + coverage;
    }
}
