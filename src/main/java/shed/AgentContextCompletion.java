package shed;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Inline prompt suggestions. No popup windows, focus transfer, or disk work on the EDT. */
final class AgentContextCompletion implements AutoCloseable {
    record Token(int start, int end, char kind, String query) { }
    private final JTextArea prompt;
    private final AsyncJobService jobs;
    private final Supplier<WorkspaceQueryService> workspace;
    private final Supplier<AgentPromptContext.Settings> settings;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final DefaultListModel<AgentPromptContext.Item> items = new DefaultListModel<>();
    private final JList<AgentPromptContext.Item> list = new JList<>(items);
    private final JLabel status = new JLabel();
    private final Timer debounce;
    private int job = -1;
    private long revision;
    private boolean closed, inserting;
    private final DocumentListener listener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { changed(); }
        public void removeUpdate(DocumentEvent e) { changed(); }
        public void changedUpdate(DocumentEvent e) { changed(); }
    };
    AgentContextCompletion(JTextArea prompt, AsyncJobService jobs, Supplier<WorkspaceQueryService> workspace,
                           Supplier<AgentPromptContext.Settings> settings) {
        this.prompt = prompt; this.jobs = jobs; this.workspace = workspace; this.settings = settings;
        panel.setName("agent.session.contextSuggestions"); list.setName("agent.session.contextItems");
        list.setFocusable(false); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            public java.awt.Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(l, value, index, selected, focus);
                label.putClientProperty("html.disable", true); return label;
            }
        });
        var scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(200, WorkbenchStyle.px(90)));
        panel.add(scroll, BorderLayout.CENTER); panel.add(status, BorderLayout.SOUTH); panel.setVisible(false);
        AccessibilitySupport.describe(list, "File and rule suggestions", "Arrow keys select, Tab inserts, Escape dismisses. Quoted paths support spaces.");
        list.addMouseListener(new java.awt.event.MouseAdapter() { public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) accept(); } });
        debounce = new Timer(200, e -> refresh()); debounce.setRepeats(false);
        prompt.getDocument().addDocumentListener(listener);
        prompt.addCaretListener(e -> { if (!inserting && token(prompt.getText(), prompt.getCaretPosition()) == null) dismiss(); });
        bind("TAB", "contextAccept", this::accept);
        bind("ENTER", "contextAcceptEnter", this::accept);
        bind("DOWN", "contextNext", () -> move(1)); bind("UP", "contextPrevious", () -> move(-1));
        bind("ESCAPE", "contextDismiss", () -> { if (!panel.isVisible()) return false; dismiss(); return true; });
        bind("ctrl SPACE", "contextRefresh", () -> { refresh(); return true; });
    }
    JPanel component() { return panel; }
    private void bind(String key, String name, java.util.function.BooleanSupplier handled) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key); Object old = prompt.getInputMap().get(stroke);
        Action previous = old == null ? null : prompt.getActionMap().get(old);
        prompt.getInputMap().put(stroke, name);
        prompt.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) {
            if (!handled.getAsBoolean() && previous != null) previous.actionPerformed(e);
        } });
    }
    private void changed() { if (closed || inserting) return; revision++; if (job >= 0) jobs.cancel(job); items.clear(); panel.setVisible(false); debounce.restart(); }
    private void dismiss() { revision++; debounce.stop(); if (job >= 0) jobs.cancel(job); job = -1; panel.setVisible(false); }
    private void refresh() {
        if (closed) return;
        Token token = token(prompt.getText(), prompt.getCaretPosition()); if (token == null) { dismiss(); return; }
        WorkspaceQueryService queries; AgentPromptContext.Settings config;
        try { queries = workspace.get(); config = settings.get(); } catch (RuntimeException invalid) { return; }
        if (queries == null) return;
        long request = ++revision;
        if (job >= 0) jobs.cancel(job);
        status.setText("Finding context…"); panel.setVisible(true);
        job = jobs.submit("Find agent context", cancellation -> AgentPromptContext.catalog(queries, config, cancellation::isCancelled), (completed, catalog, error) -> {
            if (closed || revision != request) return;
            job = -1; items.clear();
            if (error != null) { status.setText("Context unavailable: " + error.getMessage()); return; }
            if (catalog == null) return;
            for (var item : matches(catalog.items(), token)) items.addElement(item);
            if (!items.isEmpty()) list.setSelectedIndex(0);
            status.setText(items.isEmpty() ? "No matching files or rules" : "↑ ↓ choose · Tab insert · Esc dismiss" + (catalog.complete() ? "" : " · partial list"));
            panel.revalidate();
        });
    }
    static List<AgentPromptContext.Item> matches(List<AgentPromptContext.Item> items, Token token) {
        String query = token.query().toLowerCase(java.util.Locale.ROOT);
        return items.stream().filter(item -> item.kind() == token.kind() && fuzzy(item.name().toLowerCase(java.util.Locale.ROOT), query)).limit(30).toList();
    }
    private static boolean fuzzy(String text, String query) { int at = 0; for (int i = 0; i < text.length() && at < query.length(); i++) if (text.charAt(i) == query.charAt(at)) at++; return at == query.length(); }
    static Token token(String text, int caret) {
        if (caret < 0 || caret > text.length()) return null;
        for (int i = caret - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if ((c == '@' || c == '#') && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                String query = text.substring(i + 1, caret);
                if (query.startsWith("\"")) { if (query.substring(1).contains("\"")) return null; query = query.substring(1); }
                else if (query.chars().anyMatch(Character::isWhitespace)) return null;
                return new Token(i, caret, c, query);
            }
            if (c == '\n') return null;
        }
        return null;
    }
    private boolean accept() {
        Token token = token(prompt.getText(), prompt.getCaretPosition()); var selected = list.getSelectedValue();
        if (!panel.isVisible() || selected == null || token == null) return false;
        inserting = true;
        try { prompt.replaceRange(selected.token() + " ", token.start(), token.end()); prompt.setCaretPosition(token.start() + selected.token().length() + 1); }
        finally { inserting = false; dismiss(); }
        return true;
    }
    private boolean move(int step) { if (!panel.isVisible() || items.isEmpty()) return false; list.setSelectedIndex(Math.floorMod(list.getSelectedIndex() + step, items.size())); list.ensureIndexIsVisible(list.getSelectedIndex()); return true; }
    @Override public void close() { closed = true; dismiss(); prompt.getDocument().removeDocumentListener(listener); }
}
