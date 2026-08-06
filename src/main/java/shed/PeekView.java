package shed;

import java.awt.Dimension;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class PeekView {
    record Preview(LspClient.Location location, String label, String text) { }
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Texteditor editor;
    private EditorPane pane;
    private EditorPane source;
    private Preview preview;
    private KeyEventDispatcher dispatcher;

    PeekView(Texteditor editor) { this.editor = editor; }

    Preview load(LspClient.Location location, String label) throws IOException {
        Path path = pathFromFileUri(location == null ? null : location.getUri());
        if (path == null || !Files.isRegularFile(path)) throw new IOException("peek target is not a local readable file");
        long size = Files.size(path);
        if (size > MAX_BYTES) throw new IOException("peek target exceeds " + (MAX_BYTES / (1024 * 1024)) + " MiB");
        return new Preview(location, label == null ? "definition" : label, Files.readString(path, StandardCharsets.UTF_8));
    }

    void show(Preview next) {
        if (next == null || next.location() == null) return;
        close(false);
        source = editor.getActivePane();
        if (source == null) return;
        pane = editor.createEditorPane(new Dimension(Math.max(1, editor.getWidth()), Math.max(1, editor.getHeight())));
        FileBuffer buffer = FileBuffer.createScratch("[peek " + next.label() + "]", next.text());
        editor.editorPanes.add(pane);
        editor.paneBufferController.loadBufferIntoPane(pane, buffer, offsetFor(buffer, next.location().getLine(), next.location().getCharacter()));
        pane.getTextArea().setEditable(false);
        if (editor.windowLayoutRoot == null) editor.windowLayoutRoot = WindowLayoutNode.leaf(source);
        editor.windowLayoutRoot.splitLeaf(source, pane, WindowLayoutNode.Orientation.HORIZONTAL, false, 0.56);
        preview = next;
        installDispatcher();
        editor.renderWindowLayout();
        editor.showMessage("Peek " + next.label() + " — Enter opens, Escape closes");
    }

    void close(boolean openTarget) {
        Preview target = preview;
        removeDispatcher();
        if (pane != null) {
            editor.editorPanes.remove(pane);
            editor.windowLayoutRoot = editor.windowLayoutRoot == null ? null : editor.windowLayoutRoot.removeLeaf(pane);
            if (editor.windowLayoutRoot == null && source != null && editor.editorPanes.contains(source)) editor.windowLayoutRoot = WindowLayoutNode.leaf(source);
            editor.renderWindowLayout();
        }
        pane = null;
        preview = null;
        EditorPane restore = source;
        source = null;
        if (restore != null && editor.editorPanes.contains(restore)) {
            editor.activateEditorPane(restore);
            editor.requestActivePaneFocus();
        }
        if (openTarget && target != null) editor.showMessage(editor.openLspLocation(target.location(), target.label()));
    }

    private static Path pathFromFileUri(String uri) {
        if (uri == null || uri.isBlank()) return null;
        try {
            java.net.URI value = new java.net.URI(uri);
            return "file".equalsIgnoreCase(value.getScheme()) ? Path.of(value).toAbsolutePath().normalize() : null;
        } catch (java.net.URISyntaxException | IllegalArgumentException error) {
            return null;
        }
    }

    private int offsetFor(FileBuffer buffer, int line, int character) {
        try {
            javax.swing.text.Document document = buffer.getDocument();
            String text = document.getText(0, document.getLength());
            int offset = 0;
            for (int current = 0; current < Math.max(0, line) && offset < text.length(); current++) {
                int end = text.indexOf('\n', offset);
                offset = end < 0 ? text.length() : end + 1;
            }
            return Math.min(text.length(), offset + Math.max(0, character));
        } catch (javax.swing.text.BadLocationException error) {
            return 0;
        }
    }

    private void installDispatcher() {
        dispatcher = event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED || preview == null) return false;
            if (editor.editorState.mode != EditorMode.NORMAL || KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() != editor.writingArea) return false;
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) { close(false); return true; }
            if (event.getKeyCode() == KeyEvent.VK_ENTER && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) { close(true); return true; }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
    }

    private void removeDispatcher() {
        if (dispatcher != null) KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        dispatcher = null;
    }
}
