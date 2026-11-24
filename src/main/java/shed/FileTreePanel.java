package shed;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
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
import javax.swing.TransferHandler;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;

/** The native Explorer surface shown beside editor content. */
final class FileTreePanel extends JPanel {
    static final DataFlavor EXPLORER_FILE_FLAVOR = new DataFlavor(java.io.File.class, "Shed Explorer file");
    private final Texteditor editor;
    private final FileTreeModel model;
    private final JTree tree;
    private final JScrollPane scrollPane;
    private final Consumer<java.io.File> openFile;
    private final Runnable onActivate;
    private Font iconFont;
    private final Function<java.io.File,java.io.File[]> childLoader;
    private boolean refreshingExternal,refreshAgain;
    private volatile java.util.Map<java.nio.file.Path,FileChangeMarks.Mark> changeMarks=java.util.Map.of();
    private ChangePulse changePulse;

    FileTreePanel(Texteditor editor, java.io.File root, Function<java.io.File, java.io.File[]> children, Consumer<java.io.File> openFile,
                  Runnable onActivate) {
        super(new BorderLayout());
        this.editor = editor;this.childLoader=children;
        this.model = new FileTreeModel(root, this::visibleChildren);
        this.openFile = openFile == null ? file -> { } : openFile;
        this.onActivate = onActivate == null ? () -> { } : onActivate;
        this.tree = new JTree(model) {
            @Override public String getToolTipText(MouseEvent event) {
                TreePath path = getPathForLocation(event.getX(), event.getY());
                if (path == null || !(path.getLastPathComponent() instanceof FileTreeModel.Node node)) return null;
                var mark=changeMarks.get(node.file().toPath());return node.file().getAbsolutePath()+(mark==null?"":" · "+mark.detail());
            }
        };
        this.scrollPane = new JScrollPane(tree);
        this.changePulse=new ChangePulse(tree);

        tree.setName("agent.explorer");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(2);
        tree.setCellRenderer(new ExplorerRenderer());
        tree.setDragEnabled(true);
        tree.setTransferHandler(new ExplorerFileTransferHandler());
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
                FileTreePanel.this.onActivate.run();if(editor.toolWindowHost!=null)editor.toolWindowHost.pauseAgentFollowing();
            }

            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) openSelectedFile();
            }
        });
        tree.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                FileTreePanel.this.onActivate.run();if(editor.toolWindowHost!=null)editor.toolWindowHost.pauseAgentFollowing();
            }
        });
        tree.addKeyListener(new java.awt.event.KeyAdapter(){public void keyPressed(KeyEvent event){FileTreePanel.this.onActivate.run();if(editor.toolWindowHost!=null)editor.toolWindowHost.pauseAgentFollowing();}});
        scrollPane.addMouseWheelListener(event->{if(editor.toolWindowHost!=null)editor.toolWindowHost.pauseAgentFollowing();});
        installOpenActions();
        configureAppearance();
        add(scrollPane, BorderLayout.CENTER);
        tree.expandPath(new TreePath(model.rootNode().getPath()));
        AccessibilitySupport.describe(tree, "File explorer", "Expandable project file tree. A added, M modified, D deleted, R moved, R? possible move. Press Enter to open a surviving file.");
        if(editor.toolWindowHost!=null)editor.toolWindowHost.decorateExplorer(this);
    }

    @Override public void updateUI() {
        super.updateUI();
        if (tree != null) configureAppearance();
    }

    void refreshExternalChanges(){
        if(!isDisplayable())return;if(refreshingExternal){refreshAgain=true;return;}refreshingExternal=true;refreshAgain=false;
        var directories=model.loadedDirectories();var expanded=new java.util.ArrayList<TreePath>();
        var paths=tree.getExpandedDescendants(new TreePath(model.rootNode().getPath()));if(paths!=null)while(paths.hasMoreElements())expanded.add(paths.nextElement());
        TreePath selected=tree.getSelectionPath();
        editor.asyncJobService.submit("Refresh observed file tree",token->{
            var listings=new java.util.HashMap<java.io.File,java.io.File[]>();for(var dir:directories){if(token.isCancelled())break;var values=visibleChildren(dir);listings.put(dir,values==null?new java.io.File[0]:values);}return listings;
        },(job,listings,error)->{
            refreshingExternal=false;if(!isDisplayable()||error!=null||listings==null)return;model.refreshListings(listings);for(var path:expanded)if(currentPath(path))tree.expandPath(path);if(selected!=null&&currentPath(selected))tree.setSelectionPath(selected);else tree.clearSelection();tree.repaint();if(refreshAgain)refreshExternalChanges();
        });
    }

    void observeChanges(java.util.Map<java.nio.file.Path,FileChangeMarks.Mark> marks,java.util.Set<String> recent){
        changeMarks=marks;changePulse.flash(recent);refreshExternalChanges();
    }
    private java.io.File[] visibleChildren(java.io.File directory){
        var result=new java.util.LinkedHashMap<java.io.File,java.io.File>();var listed=childLoader.apply(directory);
        if(listed!=null)for(var file:listed)result.put(file.getAbsoluteFile(),file);
        var path=directory.toPath().toAbsolutePath().normalize();
        for(var entry:changeMarks.entrySet())if(java.util.Objects.equals(entry.getKey().getParent(),path))result.putIfAbsent(entry.getKey().toFile(),entry.getKey().toFile());
        return result.values().stream().sorted((a,b)->a.isDirectory()!=b.isDirectory()?(a.isDirectory()?-1:1):a.getName().compareToIgnoreCase(b.getName())).toArray(java.io.File[]::new);
    }
    @Override public void addNotify(){super.addNotify();if(editor!=null&&editor.asyncJobService!=null)refreshExternalChanges();}
    @Override public void removeNotify(){if(changePulse!=null)changePulse.clear();super.removeNotify();}

    private boolean currentPath(TreePath path){
        Object[] parts=path.getPath();if(parts.length==0||parts[0]!=model.rootNode())return false;
        for(int i=1;i<parts.length;i++)if(!(parts[i-1] instanceof javax.swing.tree.TreeNode parent)||parent.getIndex((javax.swing.tree.TreeNode)parts[i])<0)return false;
        return true;
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
        else if(file!=null&&changeMarks.containsKey(file.toPath()))editor.toolWindowHost.showObservedChange(file.toPath());
    }

    private final class ExplorerFileTransferHandler extends TransferHandler {
        @Override protected Transferable createTransferable(javax.swing.JComponent component) {
            java.io.File file = selectedFile();
            return file != null && file.isFile() ? new ExplorerFileTransferable(file) : null;
        }

        @Override public int getSourceActions(javax.swing.JComponent component) {
            return COPY;
        }
    }

    private static final class ExplorerFileTransferable implements Transferable {
        private final java.io.File file;

        private ExplorerFileTransferable(java.io.File file) {
            this.file = file;
        }

        @Override public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {EXPLORER_FILE_FLAVOR};
        }

        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return EXPLORER_FILE_FLAVOR.equals(flavor);
        }

        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return file;
        }
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
        setBackground(surface);
        tree.setBackground(surface);
        tree.setForeground(foreground);
        tree.setFont(textFont);
        scrollPane.setBackground(surface);
        scrollPane.getViewport().setBackground(surface);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        refreshDensity();
    }

    void refreshDensity() {
        int height = editor.configManager.getUiDensity().treeHeight(tree.getFontMetrics(tree.getFont()), editor.configManager.getUiZoom());
        tree.setRowHeight(height);
        scrollPane.getVerticalScrollBar().setUnitIncrement(height);
        revalidate(); repaint();
    }

    private final class ExplorerRenderer extends JPanel implements TreeCellRenderer {
        private final JLabel icon = new JLabel();
        private final JLabel label = new JLabel();
        private final JLabel badge=new JLabel();

        private ExplorerRenderer() {
            super(new BorderLayout());
            icon.setHorizontalAlignment(SwingConstants.CENTER);
            add(icon, BorderLayout.WEST);
            add(label, BorderLayout.CENTER);add(badge,BorderLayout.EAST);
            label.putClientProperty("html.disable",true);badge.putClientProperty("html.disable",true);
        }

        @Override public Component getTreeCellRendererComponent(JTree value, Object nodeValue, boolean selected, boolean expanded,
                                                                 boolean leaf, int row, boolean hasFocus) {
            FileTreeModel.Node node = nodeValue instanceof FileTreeModel.Node entry ? entry : null;
            java.io.File file = node == null ? null : node.file();
            Color surface = selected ? editor.configManager.getSelectionColor() : editor.configManager.getNormalColor();
            Color foreground = selected ? editor.configManager.getSelectionTextColor() : editor.configManager.getEditorForeground();
            var mark=file==null?null:changeMarks.get(file.toPath());Color accent=mark==null?null:ChangePulse.color(mark.code());
            if(mark!=null){surface=ChangePulse.tint(surface,accent,changePulse.strength(file.toPath().toString()));foreground=AccessibilitySupport.readableText(selected||accent==null?foreground:accent,surface);}
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
            label.setFont(mark!=null&&mark.removed()?textFont.deriveFont(java.util.Map.of(java.awt.font.TextAttribute.STRIKETHROUGH,java.awt.font.TextAttribute.STRIKETHROUGH_ON)):textFont);
            label.setText(node == null ? "" : node.toString());
            badge.setFont(textFont);badge.setForeground(foreground);badge.setText(mark==null?"":"  "+mark.code()+(mark.files()>1?" "+mark.files():""));
            label.getAccessibleContext().setAccessibleName((node==null?"":node.toString())+(mark==null?"":" · "+mark.detail()));
            return this;
        }
    }
}
