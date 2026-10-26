package shed;

import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.swing.JTextArea;

/** Editor composition for the local completion client and independently owned pane previews. */
final class InlineCompletionController implements AutoCloseable {
    private final Texteditor editor;
    private final Map<JTextArea, InlineCompletionSession> sessions = new IdentityHashMap<>();
    private InlineCompletionSettings settings;
    private OllamaCompletionClient client;
    private String lastError = "";

    InlineCompletionController(Texteditor editor) { this.editor = editor; }

    void install(JTextArea area) {
        if (sessions.containsKey(area)) return;
        sessions.put(area, new InlineCompletionSession(area, () -> eligible(area), this::delay, this::complete,
            text -> {
                area.replaceSelection(text);
                editor.lastInsertedText += text;
                editor.requestStatusBarRefresh();
            }, message -> { if (!message.equals(lastError)) { lastError = message; editor.showMessage(message); } }));
    }

    private boolean eligible(JTextArea area) {
        FileBuffer buffer = editor.getCurrentBuffer();
        return Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled", "false"))
            && editor.writingArea == area && area.hasFocus() && buffer != null && !buffer.isLargeFile()
            && area.getDocument() == buffer.getDocument() && editor.extraSelections.isEmpty()
            && editor.editorState.mode == EditorMode.INSERT && editor.allowsAiCompletion();
    }

    private int delay() {
        try { return Integer.parseInt(editor.configManager.get("ai.completion.delay.ms", "500")); }
        catch (NumberFormatException invalid) { return 500; }
    }

    private CompletableFuture<String> complete(String prefix, String suffix) {
        var configured = InlineCompletionSettings.configured(editor.configManager);
        if (client == null || !configured.equals(settings)) {
            if (client != null) client.close();
            settings = configured; client = new OllamaCompletionClient(settings); lastError = "";
        }
        return client.complete(prefix, suffix);
    }

    boolean keyPressed(KeyEvent event) {
        InlineCompletionSession session = sessions.get(editor.writingArea);
        if (session == null || event.getComponent() != editor.writingArea) return false;
        boolean handled = session.keyPressed(event, !editor.allowsAiCompletion());
        if (handled && event.getKeyCode() == KeyEvent.VK_TAB) editor.suppressNextTypedChar = true;
        return handled;
    }

    String request() {
        if (!Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled", "false")))
            return "Configure ai.completion.model and enable ai.completion.enabled in user settings first.";
        InlineCompletionSession session = sessions.get(editor.writingArea);
        if (session == null || !eligible(editor.writingArea)) return "Inline suggestions require an editable focused buffer without a selection or completion popup.";
        session.request(); return "Requesting inline suggestion… Tab accepts; Escape dismisses.";
    }

    void dismiss() { sessions.values().forEach(InlineCompletionSession::dismiss); }
    void paint(Graphics graphics, JTextArea area) { var session = sessions.get(area); if (session != null) session.paint(graphics); }
    void remove(JTextArea area) { var session = sessions.remove(area); if (session != null) session.close(); }
    @Override public void close() {
        sessions.values().forEach(InlineCompletionSession::close); sessions.clear();
        if (client != null) { client.close(); client = null; }
    }
}
