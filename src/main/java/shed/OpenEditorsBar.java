package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** A document switcher independent of the editor's pane layout. All opens are retained. */
final class OpenEditorsBar extends JPanel {
    private record Entry(FileBuffer buffer, String label, String path, boolean dirty) { }
    private final Texteditor editor;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final JButton close = new JButton("Close");
    private List<Entry> entries = List.of();
    private boolean refreshing;

    OpenEditorsBar(Texteditor editor) {
        super(new BorderLayout(4, 0));
        this.editor = editor;
        setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(1);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> source, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(source, value, index, selected, focused);
                putClientProperty("html.disable", true);
                if (value instanceof Entry entry) {
                    setText((entry.dirty() ? "● " : "") + entry.label());
                    setToolTipText(entry.path() + (entry.dirty() ? " — Unsaved changes" : ""));
                    getAccessibleContext().setAccessibleName(entry.label() + (entry.dirty() ? ", unsaved changes" : "") + ", " + entry.path());
                }
                setBorder(BorderFactory.createCompoundBorder(getBorder(), BorderFactory.createEmptyBorder(0, 10, 0, 10)));
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll); add(close, BorderLayout.EAST);
        list.addListSelectionListener(event -> {
            if (refreshing || event.getValueIsAdjusting()) return;
            Entry entry = list.getSelectedValue();
            if (entry != null && entry.buffer() != editor.getCurrentBuffer()) editor.switchToBuffer(editor.buffers.indexOf(entry.buffer()));
        });
        bind(KeyEvent.VK_ENTER, "edit", () -> editor.writingArea.requestFocusInWindow());
        bind(KeyEvent.VK_DELETE, "close", this::closeSelected);
        KeyboardFocusSupport.installEscape(this, () -> editor.writingArea.requestFocusInWindow());
        close.addActionListener(event -> closeSelected());
        AccessibilitySupport.describe(list, "Open editors", "Left and Right switch documents in the active pane. Enter returns to editing. Delete closes with confirmation for unsaved changes.");
        AccessibilitySupport.describe(close, "Close selected editor", "Close the selected document. Unsaved changes require confirmation; closing the last document keeps Shed open.");
        refreshAppearance();
    }

    private void bind(int key, String name, Runnable action) {
        list.getInputMap().put(KeyStroke.getKeyStroke(key, 0), name);
        list.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        });
    }

    void refreshAppearance() {
        int height = UiZoom.scale(40, editor.configManager.getUiZoom());
        list.setFixedCellHeight(height);
        close.setPreferredSize(new Dimension(UiZoom.scale(80, editor.configManager.getUiZoom()), height));
        setPreferredSize(new Dimension(200, height + UiZoom.scale(14, editor.configManager.getUiZoom())));
        setBackground(editor.configManager.getStatusBarBackground());
        list.setBackground(editor.configManager.getStatusBarBackground());
        list.setForeground(editor.configManager.getStatusBarForeground());
        list.setSelectionBackground(editor.configManager.getSelectionColor());
        list.setSelectionForeground(editor.configManager.getSelectionTextColor());
        setVisible(!editor.goyoModeEnabled && editor.configManager.getOpenEditorsVisible());
        refresh();
    }

    void refresh() {
        if (!isVisible()) return;
        List<Entry> next = new ArrayList<>();
        java.util.Map<String, Integer> names = new java.util.HashMap<>();
        for (FileBuffer buffer : editor.buffers) names.merge(buffer.getDisplayName(), 1, Integer::sum);
        for (FileBuffer buffer : editor.buffers) {
            String name = buffer.getDisplayName();
            String path = buffer.hasFilePath() ? buffer.getFilePath() : name;
            if (names.get(name) > 1) name = buffer.hasFilePath() ? name + " — " + Path.of(path).toAbsolutePath().getParent()
                : name + " " + (editor.buffers.indexOf(buffer) + 1);
            next.add(new Entry(buffer, name, path, buffer.isModified()));
        }
        refreshing = true;
        try {
            if (!entries.equals(next)) { entries = List.copyOf(next); model.clear(); model.addAll(entries); }
            int selected = -1;
            for (int index = 0; index < entries.size(); index++) if (entries.get(index).buffer() == editor.getCurrentBuffer()) selected = index;
            if (list.getSelectedIndex() != selected) { list.setSelectedIndex(selected); if (selected >= 0) list.ensureIndexIsVisible(selected); }
            close.setEnabled(selected >= 0);
        } finally { refreshing = false; }
    }

    void focusEditors() { setVisible(true); refresh(); list.requestFocusInWindow(); }

    void closeSelected() {
        Entry selected = list.getSelectedValue();
        if (selected == null) return;
        boolean discard = !selected.buffer().isModified() || DocumentLifecycle.discardConfirmed(
            editor.confirmDiscardChanges("Discard unsaved changes to " + selected.buffer().getDisplayName() + " and close it?"));
        closeBuffer(selected.buffer(), discard);
    }

    // Keeps the close decision explicit, including in noninteractive lifecycle tests.
    boolean closeBuffer(FileBuffer buffer, boolean discard) {
        if (!editor.buffers.contains(buffer) || buffer.isModified() && !discard) return false;
        editor.switchToBuffer(editor.buffers.indexOf(buffer));
        editor.showMessage(editor.closeActiveBufferWithoutExiting(true));
        refresh(); editor.writingArea.requestFocusInWindow();
        return !editor.buffers.contains(buffer);
    }
}
