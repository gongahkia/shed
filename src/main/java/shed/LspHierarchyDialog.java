package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

final class LspHierarchyDialog extends JDialog {
    interface ChildrenLoader { List<LspClient.HierarchyItem> load(LspClient.HierarchyItem item) throws Exception; }
    private static final class Node extends DefaultMutableTreeNode {
        private boolean loaded;
        Node(LspClient.HierarchyItem item) { super(item); add(new DefaultMutableTreeNode("Loading…")); }
        LspClient.HierarchyItem item() { return getUserObject() instanceof LspClient.HierarchyItem item ? item : null; }
        @Override public String toString() { return String.valueOf(getUserObject()); }
    }

    private final Texteditor editor;
    private final List<LspClient.HierarchyItem> roots;
    private final ChildrenLoader childrenLoader;
    private final JTree tree = new JTree();
    private final JTextArea details = new JTextArea();
    private final JTextField filter = new JTextField(20);

    static void show(Texteditor editor, String title, List<LspClient.HierarchyItem> roots, ChildrenLoader loader) {
        if (editor == null) return;
        new LspHierarchyDialog(editor, title, roots, loader).setVisible(true);
    }

    private LspHierarchyDialog(Texteditor editor, String title, List<LspClient.HierarchyItem> roots, ChildrenLoader loader) {
        super(editor, title, false);
        this.editor = editor;
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.childrenLoader = loader;
        setLayout(new BorderLayout(6, 6));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
        toolbar.add(new JLabel("Filter")); toolbar.add(filter);
        JButton open = new JButton("Open"); open.addActionListener(event -> openSelected());
        toolbar.add(open);
        add(toolbar, BorderLayout.NORTH);
        details.setEditable(false);
        details.setLineWrap(true);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) { loadChildren(event.getPath()); }
            @Override public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        tree.addTreeSelectionListener(event -> showDetails());
        tree.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) openSelected(); } });
        tree.addKeyListener(new KeyAdapter() { @Override public void keyPressed(KeyEvent event) { if (event.getKeyCode() == KeyEvent.VK_ENTER) openSelected(); } });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { rebuild(); }
            @Override public void removeUpdate(DocumentEvent event) { rebuild(); }
            @Override public void changedUpdate(DocumentEvent event) { rebuild(); }
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), new JScrollPane(details));
        split.setResizeWeight(0.62);
        add(split, BorderLayout.CENTER);
        setMinimumSize(new Dimension(620, 360));
        setSize(820, 520);
        setLocationRelativeTo(editor);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        rebuild();
    }

    private void rebuild() {
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Hierarchy");
        for (LspClient.HierarchyItem item : roots) {
            if (query.isBlank() || (item.getName() + " " + item.getDetail()).toLowerCase(Locale.ROOT).contains(query)) root.add(new Node(item));
        }
        tree.setModel(new DefaultTreeModel(root));
        tree.expandRow(0);
        details.setText(root.getChildCount() == 0 ? "No matching symbols." : "Select a symbol. Children load on expansion.");
    }

    private void loadChildren(TreePath path) {
        if (!(path.getLastPathComponent() instanceof Node node) || node.loaded || childrenLoader == null) return;
        node.loaded = true;
        LspClient.HierarchyItem item = node.item();
        if (item == null) return;
        editor.asyncJobService.submit("LSP hierarchy children", token -> childrenLoader.load(item), (job, children, error) -> {
            if (!isDisplayable()) return;
            node.removeAllChildren();
            if (error != null) node.add(new DefaultMutableTreeNode("Unavailable: " + error.getMessage()));
            else if (children == null || children.isEmpty()) node.add(new DefaultMutableTreeNode("No children"));
            else for (LspClient.HierarchyItem child : children) node.add(new Node(child));
            ((DefaultTreeModel) tree.getModel()).reload(node);
        });
    }

    private void showDetails() {
        Object selected = tree.getLastSelectedPathComponent();
        if (!(selected instanceof Node node) || node.item() == null) return;
        LspClient.HierarchyItem item = node.item();
        details.setText(item.getName() + (item.getDetail().isBlank() ? "" : "\n" + item.getDetail()) + "\n\n" + item.getUri()
            + ":" + (item.getLine() + 1) + ":" + (item.getCharacter() + 1));
        details.setCaretPosition(0);
    }

    private void openSelected() {
        Object selected = tree.getLastSelectedPathComponent();
        if (!(selected instanceof Node node) || node.item() == null) return;
        LspClient.HierarchyItem item = node.item();
        String result = editor.lspController.openLspLocation(new LspClient.Location(item.getUri(), item.getLine(), item.getCharacter()), "hierarchy");
        editor.showMessage(result);
        if (result.startsWith("Opened")) dispose();
    }
}
