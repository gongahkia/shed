package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** A document switcher independent of the editor's pane layout. All opens are retained. */
final class OpenEditorsBar extends JPanel {
    private record Entry(DocumentWorkspace.Id id, String label, String path, boolean dirty) { }
    private final Texteditor editor;
    private final DocumentWorkspace documents;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model) {
        @Override protected void processMouseEvent(java.awt.event.MouseEvent event){
            if(javax.swing.SwingUtilities.isLeftMouseButton(event)){
                int index=locationToIndex(event.getPoint());var bounds=index<0?null:getCellBounds(index,index);
                if(closeHit(bounds,event.getPoint(),controlHeight())){
                    if(event.getID()==java.awt.event.MouseEvent.MOUSE_PRESSED)closeEntry(model.get(index));event.consume();return;
                }
            }
            super.processMouseEvent(event);
        }
    };
    private final JScrollPane scroll;
    private List<Entry> entries = List.of();
    private boolean refreshing;

    OpenEditorsBar(Texteditor editor) {
        super(new BorderLayout(4, 0));
        this.editor = editor;
        this.documents = editor.documents;
        setBorder(BorderFactory.createEmptyBorder());
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(1);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override protected void paintComponent(java.awt.Graphics graphics){
                super.paintComponent(graphics);var icon=WorkbenchIcons.CLOSE;
                icon.paintIcon(this,graphics,getWidth()-controlHeight()+(controlHeight()-icon.getIconWidth())/2,(getHeight()-icon.getIconHeight())/2);
            }
            @Override public java.awt.Component getListCellRendererComponent(JList<?> source, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(source, value, index, selected, focused);
                putClientProperty("html.disable", true);
                if (value instanceof Entry entry) {
                    setText((entry.dirty() ? "● " : "") + entry.label());
                    setToolTipText(entry.path() + (entry.dirty() ? " — Unsaved changes" : ""));
                    getAccessibleContext().setAccessibleName(entry.label() + (entry.dirty() ? ", unsaved changes" : "") + ", " + entry.path());
                }
                var config = editor.configManager;
                setBackground(selected ? config.getNormalColor() : config.getStatusBarBackground());
                setForeground(selected ? config.getEditorForeground() : config.getMutedForegroundColor());
                int zoomLine = UiZoom.scale(2, config.getUiZoom());
                setBorder(BorderFactory.createCompoundBorder(
                    focused ? BorderFactory.createLineBorder(config.getFocusColor(), zoomLine)
                        : BorderFactory.createMatteBorder(0, 0, zoomLine, 0, selected ? config.getFocusColor() : getBackground()),
                    BorderFactory.createEmptyBorder(0, UiZoom.scale(config.getUiDensity().tabHorizontal, config.getUiZoom()),
                        0, controlHeight())));
                return this;
            }
        });
        scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getHorizontalScrollBar().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentShown(java.awt.event.ComponentEvent event) { revalidate(); }
            @Override public void componentHidden(java.awt.event.ComponentEvent event) { revalidate(); }
        });
        list.setName("workbench.openEditors");add(scroll);
        list.addListSelectionListener(event -> {
            if (refreshing || event.getValueIsAdjusting()) return;
            Entry entry = list.getSelectedValue();
            if (entry != null && (documents.activeDocument() == null || !entry.id().equals(documents.activeDocument().id()))) {
                boolean keepNavigationFocus = list.isFocusOwner();
                documents.activate(entry.id());
                if (keepNavigationFocus) list.requestFocusInWindow();
            }
        });
        bind(KeyEvent.VK_ENTER, "edit", () -> focusSurface());
        bind(KeyEvent.VK_DELETE, "close", this::closeSelected);
        KeyboardFocusSupport.installEscape(this, () -> focusSurface());
        AccessibilitySupport.describe(list, "Open editors", "Left and Right switch documents in the active pane. Enter returns to editing. Delete closes with confirmation for unsaved changes.");
        refreshAppearance();
    }

    private void bind(int key, String name, Runnable action) {
        list.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
        list.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        });
    }

    void refreshAppearance() {
        if (editor.stagedStartupPending) {
            // Selection/layout can ask the renderer for metrics before prepareStagedWindow.
            // Keep that first document switch independent of system-font discovery.
            list.setFont(editor.stagedStartupFont.deriveFont((float) list.getFont().getSize()));
        } else {
            list.setFont(editor.resolveUiFont());
        }
        refreshDensity();
        setBackground(editor.configManager.getStatusBarBackground());
        list.setBackground(editor.configManager.getStatusBarBackground());
        list.setForeground(editor.configManager.getStatusBarForeground());
        list.setSelectionBackground(editor.configManager.getSelectionColor());
        list.setSelectionForeground(editor.configManager.getSelectionTextColor());
        setVisible(!editor.goyoModeEnabled && editor.configManager.getOpenEditorsVisible());
        refresh();
    }

    @Override public Dimension getPreferredSize() {
        int height = controlHeight();
        // Reserve scrolling space only when documents overflow the strip.
        if (scroll != null && scroll.getHorizontalScrollBar().isVisible()) height += UiZoom.scale(10, editor.configManager.getUiZoom());
        return new Dimension(200, height);
    }

    void refresh() {
        if (!isVisible()) return;
        List<Entry> next = new ArrayList<>();
        List<DocumentWorkspace.Document> snapshot = documents.documents();
        var labels = DocumentLabels.forDocuments(snapshot);
        for (int index = 0; index < snapshot.size(); index++) {
            var document = snapshot.get(index);
            String name = labels.get(document.id());
            String path = document.path() == null || document.path().isBlank() ? name : document.path();
            next.add(new Entry(document.id(), name, path, document.modified()));
        }
        refreshing = true;
        try {
            if (!entries.equals(next)) { entries = List.copyOf(next); model.clear(); model.addAll(entries); }
            int selected = -1;
            var active = documents.activeDocument();
            for (int index = 0; index < entries.size(); index++) if (active != null && entries.get(index).id().equals(active.id())) selected = index;
            if (list.getSelectedIndex() != selected) { list.setSelectedIndex(selected); if (selected >= 0) list.ensureIndexIsVisible(selected); }
        } finally { refreshing = false; }
    }

    void focusEditors() { setVisible(true); refresh(); list.requestFocusInWindow(); }

    private int controlHeight() {
        return editor.configManager.getUiDensity().controlHeight(list.getFontMetrics(list.getFont()), editor.configManager.getUiZoom());
    }

    void refreshDensity() {
        int height = controlHeight();
        list.setFixedCellHeight(height);
        list.setFixedCellWidth(UiZoom.scale(editor.configManager.getUiDensity().editorTabWidth, editor.configManager.getUiZoom()));
        revalidate(); repaint();
    }

    void closeSelected() {
        Entry selected = list.getSelectedValue();
        closeEntry(selected);
    }

    static boolean closeHit(java.awt.Rectangle cell,java.awt.Point point,int width){return cell!=null&&cell.contains(point)&&point.x>=cell.x+cell.width-Math.min(cell.width,width);}

    private void closeEntry(Entry selected){
        if (selected == null) return;
        var current = documents.documents().stream().filter(document -> document.id().equals(selected.id()))
            .findFirst().orElse(null);
        if (current == null) return;
        boolean discard = !current.modified() || DocumentLifecycle.discardConfirmed(
            editor.confirmDiscardChanges("Discard unsaved changes to " + current.name() + " and close it?"));
        closeDocument(selected.id(), discard);
    }

    // Keeps the close decision explicit, including in noninteractive lifecycle tests.
    boolean closeDocument(DocumentWorkspace.Id id, boolean discard) {
        var active=documents.activeDocument();
        boolean closed = documents.close(id, discard);
        if(closed&&active!=null&&!active.id().equals(id))documents.activate(active.id());
        if (closed) { refresh(); focusSurface(); }
        return closed;
    }

    private void focusSurface() {
        EditorSurface surface = documents.activeSurface();
        if (surface != null) surface.focus();
    }
}
