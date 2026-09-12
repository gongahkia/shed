package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;

/** The native Explorer surface shown beside editor content. */
final class FileTreePanel extends JPanel {
    private final Texteditor editor;
    private final FileTreeModel model;
    private final JTree tree;
    private final JScrollPane scrollPane;
    private final Consumer<java.io.File> openFile;
    private final Runnable onActivate;
    private Font iconFont;

    FileTreePanel(Texteditor editor, java.io.File root, Function<java.io.File, java.io.File[]> children, Consumer<java.io.File> openFile,
                  Runnable onActivate) {
        super(new BorderLayout());
        this.editor = editor;
        this.model = new FileTreeModel(root, children);
        this.openFile = openFile == null ? file -> { } : openFile;
        this.onActivate = onActivate == null ? () -> { } : onActivate;
        this.tree = new JTree(model) {
            @Override public String getToolTipText(MouseEvent event) {
                TreePath path = getPathForLocation(event.getX(), event.getY());
                if (path == null || !(path.getLastPathComponent() instanceof FileTreeModel.Node node)) return null;
                return node.file().getAbsolutePath();
            }
        };
        this.scrollPane = new JScrollPane(tree);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(2);
        tree.setCellRenderer(new ExplorerRenderer());
        tree.setToolTipText("");
        tree.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
            @Override public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
                Object node = event.getPath().getLastPathComponent();
                if (node instanceof FileTreeModel.Node entry) model.loadChildren(entry);
            }

            @Override public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) {
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                FileTreePanel.this.onActivate.run();
            }

            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) openSelectedFile();
            }
        });
        tree.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                FileTreePanel.this.onActivate.run();
            }
        });
        installOpenActions();
        configureAppearance();
        add(scrollPane, BorderLayout.CENTER);
        tree.expandPath(new TreePath(model.rootNode().getPath()));
        AccessibilitySupport.describe(tree, "File explorer", "Expandable project file tree. Press Enter to open the selected file.");
    }

    @Override public void updateUI() {
        super.updateUI();
        if (tree != null) configureAppearance();
    }

    void refreshAppearance() {
        configureAppearance();
        revalidate();
        repaint();
    }

    void focusTree() {
        tree.requestFocusInWindow();
    }

    java.io.File selectedFile() {
        TreePath selected = tree.getSelectionPath();
        if (selected == null || !(selected.getLastPathComponent() instanceof FileTreeModel.Node node)) return null;
        return node.file();
    }

    private void installOpenActions() {
        tree.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "shed.tree.open");
        tree.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_O, 0), "shed.tree.open");
        tree.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_J, 0), "shed.tree.next");
        tree.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_K, 0), "shed.tree.previous");
        tree.getActionMap().put("shed.tree.open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                openSelectedFile();
            }
        });
        tree.getActionMap().put("shed.tree.next", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                selectRelativeRow(1);
            }
        });
        tree.getActionMap().put("shed.tree.previous", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                selectRelativeRow(-1);
            }
        });
    }

    private void openSelectedFile() {
        java.io.File file = selectedFile();
        if (file != null && file.isFile()) openFile.accept(file);
    }

    private void selectRelativeRow(int direction) {
        int rows = tree.getRowCount();
        if (rows <= 0) return;
        int selected = tree.getLeadSelectionRow();
        int target = selected < 0 ? 0 : Math.max(0, Math.min(rows - 1, selected + direction));
        tree.setSelectionRow(target);
        tree.scrollRowToVisible(target);
    }

    private void configureAppearance() {
        if (editor == null) return;
        Color surface = editor.configManager.getNormalColor();
        Color foreground = editor.configManager.getEditorForeground();
        Font textFont = editor.resolveUiFont();
        iconFont = FileFinderIcons.availableNerdFont(textFont);
        int rowHeight = Math.max(UiZoom.scale(22, editor.configManager.getUiZoom()),
            textFont.getSize() + UiZoom.scale(8, editor.configManager.getUiZoom()));
        setBackground(surface);
        tree.setBackground(surface);
        tree.setForeground(foreground);
        tree.setFont(textFont);
        tree.setRowHeight(rowHeight);
        scrollPane.setBackground(surface);
        scrollPane.getViewport().setBackground(surface);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(rowHeight);
    }

    private final class ExplorerRenderer extends JPanel implements TreeCellRenderer {
        private final JLabel icon = new JLabel();
        private final JLabel label = new JLabel();

        private ExplorerRenderer() {
            super(new BorderLayout());
            icon.setHorizontalAlignment(SwingConstants.CENTER);
            add(icon, BorderLayout.WEST);
            add(label, BorderLayout.CENTER);
        }

        @Override public Component getTreeCellRendererComponent(JTree value, Object nodeValue, boolean selected, boolean expanded,
                                                                 boolean leaf, int row, boolean hasFocus) {
            FileTreeModel.Node node = nodeValue instanceof FileTreeModel.Node entry ? entry : null;
            java.io.File file = node == null ? null : node.file();
            Color surface = selected ? editor.configManager.getSelectionColor() : editor.configManager.getNormalColor();
            Color foreground = selected ? editor.configManager.getSelectionTextColor() : editor.configManager.getEditorForeground();
            Font textFont = editor.resolveUiFont();
            boolean hasNerdIcons = iconFont != null;
            int iconWidth = hasNerdIcons ? UiZoom.scale(24, editor.configManager.getUiZoom()) : 0;

            setOpaque(true);
            setBackground(surface);
            setBorder(BorderFactory.createEmptyBorder(0, UiZoom.scale(2, editor.configManager.getUiZoom()), 0, UiZoom.scale(4, editor.configManager.getUiZoom())));
            icon.setPreferredSize(new Dimension(iconWidth, 1));
            icon.setForeground(selected ? foreground : editor.configManager.getCaretColor());
            icon.setFont(hasNerdIcons ? iconFont.deriveFont((float) Math.max(textFont.getSize(), UiZoom.scale(14, editor.configManager.getUiZoom()))) : textFont);
            icon.setText(!hasNerdIcons || file == null ? "" : file.isDirectory()
                ? FileFinderIcons.iconForDirectory(file, expanded)
                : FileFinderIcons.iconFor(file.getName()));
            label.setForeground(foreground);
            label.setFont(textFont);
            label.setText(node == null ? "" : node.toString());
            return this;
        }
    }
}
