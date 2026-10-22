package shed;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Owns a picker's asynchronous query/selection lifecycle, entirely from the EDT. */
final class PaletteFilterSession implements AutoCloseable {
    private final JTextField filter;
    private final JList<String> list;
    private final DefaultListModel<String> model;
    private final JLabel status;
    private final Function<String, List<String>> matcher;
    private final BiConsumer<String, String> accept;
    private final Timer debounce;
    private SwingWorker<List<String>, Void> worker;
    private long generation;
    private boolean pending;
    private boolean acceptWhenReady;
    private boolean closed;

    PaletteFilterSession(JTextField filter, JList<String> list, JLabel status,
                            Function<String, List<String>> matcher, BiConsumer<String, String> accept) {
        this.filter = filter;
        this.list = list;
        this.model = (DefaultListModel<String>) list.getModel();
        this.status = status;
        this.matcher = matcher;
        this.accept = accept;
        debounce = new Timer(75, event -> startFilter());
        debounce.setRepeats(false);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
        filter.addActionListener(event -> acceptSelection());
        bind(list, KeyEvent.VK_ENTER, "accept", this::acceptSelection);
        bind(filter, KeyEvent.VK_DOWN, "next", () -> move(1));
        bind(filter, KeyEvent.VK_UP, "previous", () -> move(-1));
        bind(filter, KeyEvent.VK_PAGE_DOWN, "page-next", () -> move(pageSize()));
        bind(filter, KeyEvent.VK_PAGE_UP, "page-previous", () -> move(-pageSize()));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) return;
                int row = list.locationToIndex(event.getPoint());
                if (row >= 0 && list.getCellBounds(row, row).contains(event.getPoint())) acceptSelection();
            }
        });
        updateCount();
    }

    private static void bind(JComponent component, int key, String name, Runnable action) {
        String id = "shed.palette." + name;
        component.getInputMap().put(KeyStroke.getKeyStroke(key, 0), id);
        component.getActionMap().put(id, new AbstractAction() {
            public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private void changed() {
        if (closed) return;
        generation++;
        if (worker != null) worker.cancel(true);
        pending = true;
        acceptWhenReady = false;
        list.setEnabled(false);
        model.clear();
        status.setText("Searching…");
        debounce.restart();
    }

    private void startFilter() {
        String query = filter.getText();
        long request = generation;
        worker = new SwingWorker<>() {
            @Override protected List<String> doInBackground() { return matcher.apply(query); }
            @Override protected void done() {
                if (closed || isCancelled() || request != generation) return;
                pending = false;
                list.setEnabled(true);
                try {
                    model.clear();
                    model.addAll(get());
                    if (!model.isEmpty()) list.setSelectedIndex(0);
                    updateCount();
                    if (acceptWhenReady) acceptSelection();
                } catch (Exception error) {
                    acceptWhenReady = false;
                    status.setText("Unable to filter results. Edit the query to retry.");
                }
            }
        };
        worker.execute();
    }

    void acceptSelection() {
        if (closed) return;
        if (pending) {
            acceptWhenReady = true;
            status.setText("Searching… will open the first matching result");
            if (debounce.isRunning()) {
                debounce.stop();
                startFilter();
            }
            return;
        }
        acceptWhenReady = false;
        String selected = list.getSelectedValue();
        if (selected != null) accept.accept(selected, filter.getText());
    }

    private void updateCount() {
        status.setText(model.isEmpty() ? "No matches — try a different query"
            : model.size() + (model.size() == 200 ? " results shown" : " results") + " · ↑↓ select · Enter open · Esc close");
    }

    private int pageSize() { return Math.max(1, list.getLastVisibleIndex() - list.getFirstVisibleIndex()); }

    private void move(int delta) {
        if (pending || model.isEmpty()) return;
        int index = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    @Override public void close() {
        closed = true;
        generation++;
        debounce.stop();
        if (worker != null) worker.cancel(true);
    }
}
