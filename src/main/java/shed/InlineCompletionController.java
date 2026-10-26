package shed;

import java.awt.event.KeyEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.swing.JTextArea;

/** Editor composition for the OpenCode completion client and independently owned pane previews. */
final class InlineCompletionController implements AutoCloseable {
    private final Texteditor editor;
    private final Map<JTextArea, InlineCompletionSession> sessions = new IdentityHashMap<>();
    private InlineCompletionSettings settings;
    private OpenCodeCompletionClient client;
    private String lastError = "";

    InlineCompletionController(Texteditor editor) { this.editor = editor; }

    void install(JTextArea area) {
        if (sessions.containsKey(area)) return;
        var session = new InlineCompletionSession(area, () -> eligible(area), this::delay, this::complete,
            text -> {
                area.replaceSelection(text);
                editor.lastInsertedText += text;
                editor.requestStatusBarRefresh();
            }, message -> { if (!message.equals(lastError)) { lastError = message; editor.showMessage(message); } });
        session.onTiming((stage, nanos) -> {
            if (editor.perfService != null) editor.perfService.recordNanos("ai.complete." + stage, nanos, "chars=" + area.getDocument().getLength());
        });
        sessions.put(area, session);
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
            settings = configured; client = new OpenCodeCompletionClient(settings); lastError = "";
        }
        long started = System.nanoTime(); var request = client.complete(prefix, suffix);
        request.whenComplete((text, error) -> {
            if (editor.perfService != null) editor.perfService.recordDuration("ai.complete.provider", started,
                error == null ? "success" : request.isCancelled() ? "cancelled" : "failed");
        });
        return request;
    }

    boolean keyPressed(KeyEvent event) {
        InlineCompletionSession session = sessions.get(editor.writingArea);
        if (session == null || event.getComponent() != editor.writingArea) return false;
        boolean handled = session.keyPressed(event, !editor.allowsAiCompletion()
            || hasLegacyMapping(editor.configManager, editor.editorState.mode, event));
        if (handled && event.getKeyCode() == KeyEvent.VK_TAB) editor.suppressNextTypedChar = true;
        return handled;
    }

    static boolean hasLegacyMapping(ConfigManager config, EditorMode mode, KeyEvent event) {
        if (!config.getKeymapProfile().usesVimModeHandling() || mode == null) return false;
        String key = switch (event.getKeyCode()) { case KeyEvent.VK_TAB -> "<tab>"; case KeyEvent.VK_ESCAPE -> "<esc>"; default -> null; };
        return key != null && config.getKeybinding(mode.name().toLowerCase(java.util.Locale.ROOT), key) != null;
    }

    String request() {
        if (!Boolean.parseBoolean(editor.configManager.get("ai.completion.enabled", "false")))
            return "Configure OpenCode and enable ai.completion.enabled in user settings first.";
        InlineCompletionSession session = sessions.get(editor.writingArea);
        JTextArea area = editor.writingArea;
        FileBuffer buffer = editor.getCurrentBuffer();
        if (session == null || buffer == null || buffer.isLargeFile() || area.getDocument() != buffer.getDocument()
            || area.getSelectionStart() != area.getSelectionEnd() || !editor.extraSelections.isEmpty())
            return "Inline suggestions require a source buffer without a selection.";
        var document = area.getDocument();
        // Ex commands leave command mode after dispatch. Request on the next EDT turn so that
        // transition cannot cancel the explicit request or leave its target non-editable.
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (editor.writingArea != area || area.getDocument() != document || !sessions.containsKey(area)) return;
            editor.setMode(EditorMode.INSERT); editor.dismissCompletionPopup(); area.requestFocusInWindow();
            javax.swing.SwingUtilities.invokeLater(() -> { if (editor.writingArea == area && area.getDocument() == document) session.request(); });
        });
        return "Requesting inline suggestion… Tab accepts; Escape dismisses.";
    }

    void dismiss() { sessions.values().forEach(InlineCompletionSession::dismiss); }
    boolean visible(JTextArea area) { var session = sessions.get(area); return session != null && session.visible(); }
    void remove(JTextArea area) { var session = sessions.remove(area); if (session != null) session.close(); }
    @Override public void close() {
        sessions.values().forEach(InlineCompletionSession::close); sessions.clear();
        if (client != null) { client.close(); client = null; }
    }
}
