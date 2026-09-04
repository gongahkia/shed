package shed;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import javax.swing.JComponent;
import shed.api.CustomEditorContribution;
import shed.api.CustomEditorDocument;

/** Chooses an installed custom editor without replacing Shed's buffer and save model. */
final class CustomEditorController {
    private final Texteditor editor;
    private final Map<EditorPane, Document> documents = new IdentityHashMap<>();

    CustomEditorController(Texteditor editor) {
        this.editor = editor;
    }

    boolean showIfAvailable(EditorPane pane, FileBuffer buffer) {
        if (pane == null || buffer == null || !buffer.hasFilePath() || editor.extensionManager == null) return false;
        Path file;
        try {
            file = Path.of(buffer.getFilePath()).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return false;
        }
        for (ExtensionRegistry.Owned<CustomEditorContribution> owned : editor.extensionManager.customEditors()) {
            try {
                if (!owned.value().supports(file)) continue;
                Document document = new Document(file, pane, buffer);
                JComponent component = owned.value().createComponent(document);
                if (component == null) {
                    editor.showMessage("Custom editor " + name(owned) + " returned no component");
                    return false;
                }
                install(pane, component, document);
                editor.renderWindowLayout();
                editor.showMessage("Opened with custom editor " + name(owned));
                return true;
            } catch (Exception error) {
                editor.showMessage("Custom editor " + name(owned) + " failed: " + concise(error));
                return false;
            }
        }
        return false;
    }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "list".equalsIgnoreCase(value)) {
            StringBuilder output = new StringBuilder("Custom Editors\n\n");
            List<ExtensionRegistry.Owned<CustomEditorContribution>> editors = editor.extensionManager == null ? List.of() : editor.extensionManager.customEditors();
            if (editors.isEmpty()) output.append("No custom editors installed.\n");
            else for (ExtensionRegistry.Owned<CustomEditorContribution> candidate : editors) {
                output.append("  ").append(name(candidate)).append("  ").append(candidate.value().displayName()).append("\n");
            }
            output.append("\nCustom editors are selected when a file is opened. They receive a byte-backed resource model and may explicitly save that one resource atomically.\n");
            editor.showScratchBuffer("[custom editors]", output.toString());
            return "Showing custom editors";
        }
        if ("reopen".equalsIgnoreCase(value)) {
            return showIfAvailable(editor.getActivePane(), editor.getCurrentBuffer()) ? "Custom editor reopened" : "No installed custom editor supports the current file";
        }
        return "Usage: :customeditor [list|reopen]";
    }

    void dispose(EditorPane pane) {
        if (pane == null) return;
        Document document = documents.remove(pane);
        if (document != null) document.dispose();
    }

    void disposeAll() {
        for (Document document : List.copyOf(documents.values())) document.dispose();
        documents.clear();
    }

    private void install(EditorPane pane, JComponent component, Document document) {
        dispose(pane);
        documents.put(pane, document);
        pane.setCustomEditorComponent(component);
        component.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { editor.activateEditorPane(pane); }
        });
        component.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) { editor.activateEditorPane(pane); }
        });
    }

    private static String name(ExtensionRegistry.Owned<CustomEditorContribution> value) { return value.extensionId() + ":" + value.value().id(); }
    private static String concise(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private final class Document implements CustomEditorDocument {
        private final Path file;
        private final EditorPane pane;
        private final FileBuffer buffer;
        private final CopyOnWriteArrayList<ChangeListener> changeListeners = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Runnable> disposeListeners = new CopyOnWriteArrayList<>();
        private final ByteHistory history = new ByteHistory();
        private volatile long revision;
        private volatile boolean disposed;

        private Document(Path file, EditorPane pane, FileBuffer buffer) {
            this.file = file;
            this.pane = pane;
            this.buffer = buffer;
            this.revision = 0L;
            this.disposed = false;
        }

        @Override public long revision() { return revision; }
        @Override public Path file() { return file; }

        @Override public byte[] bytes() throws IOException {
            return java.nio.file.Files.readAllBytes(file);
        }

        @Override public boolean isBinary() throws IOException {
            return CustomEditorController.isBinary(bytes());
        }

        @Override public void write(byte[] replacement) throws IOException {
            byte[] contents = replacement == null ? new byte[0] : replacement.clone();
            runOnEventThread(() -> writeOnEventThread(contents));
        }

        @Override public boolean canUndo() { return history.canUndo(); }
        @Override public boolean canRedo() { return history.canRedo(); }

        @Override public void undo() throws IOException {
            runOnEventThread(() -> restoreHistoryOnEventThread(true));
        }

        @Override public void redo() throws IOException {
            runOnEventThread(() -> restoreHistoryOnEventThread(false));
        }

        private void runOnEventThread(IoAction action) throws IOException {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
                return;
            }
            final IOException[] failure = new IOException[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try { action.run(); } catch (IOException error) { failure[0] = error; }
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("custom editor save interrupted", error);
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw new IOException("custom editor save failed", error.getCause());
            }
            if (failure[0] != null) throw failure[0];
        }

        @Override public Subscription onDidChange(ChangeListener listener) {
            if (listener == null) throw new IllegalArgumentException("custom editor change listener is required");
            if (disposed) return Subscription.noop();
            changeListeners.add(listener);
            return () -> changeListeners.remove(listener);
        }

        @Override public Subscription onDidDispose(Runnable listener) {
            if (listener == null) throw new IllegalArgumentException("custom editor dispose listener is required");
            if (disposed) {
                if (SwingUtilities.isEventDispatchThread()) listener.run();
                else SwingUtilities.invokeLater(listener);
                return Subscription.noop();
            }
            disposeListeners.add(listener);
            return () -> disposeListeners.remove(listener);
        }

        private void writeOnEventThread(byte[] contents) throws IOException {
            ensureActive();
            byte[] before = java.nio.file.Files.readAllBytes(file);
            if (Arrays.equals(before, contents)) return;
            persist(contents);
            history.recordUndo(before);
            changed("saved");
        }

        private void restoreHistoryOnEventThread(boolean undo) throws IOException {
            ensureActive();
            byte[] target = undo ? history.undoTarget() : history.redoTarget();
            if (target == null) throw new IOException(undo ? "custom editor undo is unavailable" : "custom editor redo is unavailable");
            byte[] before = java.nio.file.Files.readAllBytes(file);
            persist(target);
            if (undo) history.completeUndo(before); else history.completeRedo(before);
            changed(undo ? "undo" : "redo");
        }

        private void ensureActive() throws IOException {
            if (disposed || documents.get(pane) != this || pane.getBuffer() != buffer || !file.equals(Path.of(buffer.getFilePath()).toAbsolutePath().normalize())) {
                throw new IOException("custom editor document is no longer active");
            }
        }

        private void persist(byte[] contents) throws IOException {
            editor.paneBufferController.backupBeforeSave(buffer);
            AtomicFileWriter.write(file, contents);
            try {
                editor.withSuppressedDocumentEvents(() -> {
                    try { buffer.load(editor.configManager); } catch (IOException error) { throw new ReloadFailure(error); }
                });
            } catch (ReloadFailure error) {
                throw (IOException) error.getCause();
            }
            editor.syncLspOpen(buffer);
        }

        private void changed(String action) {
            revision++;
            notifyChanged();
            editor.updateStatusBar();
            editor.showMessage("Custom editor " + action + " " + file.getFileName());
        }

        private void notifyChanged() {
            Change change = new Change(revision);
            for (ChangeListener listener : changeListeners) {
                try { listener.changed(change); } catch (RuntimeException ignored) { }
            }
        }

        private void dispose() {
            if (disposed) return;
            disposed = true;
            changeListeners.clear();
            for (Runnable listener : disposeListeners) {
                try { listener.run(); } catch (RuntimeException ignored) { }
            }
            disposeListeners.clear();
        }
    }

    static boolean isBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        for (byte value : bytes) {
            int unsigned = Byte.toUnsignedInt(value);
            if (unsigned == 0 || unsigned < 0x09 || unsigned > 0x0D && unsigned < 0x20) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** Bounded in-memory history for a single custom document and installed pane. */
    static final class ByteHistory {
        private static final int MAX_ENTRIES = 100;
        private static final int MAX_BYTES = 8 * 1024 * 1024;
        private final ArrayDeque<byte[]> undo = new ArrayDeque<>();
        private final ArrayDeque<byte[]> redo = new ArrayDeque<>();

        synchronized boolean canUndo() { return !undo.isEmpty(); }
        synchronized boolean canRedo() { return !redo.isEmpty(); }

        synchronized void recordUndo(byte[] snapshot) {
            redo.clear();
            add(undo, snapshot);
        }

        synchronized byte[] undoTarget() { return undo.peek(); }
        synchronized byte[] redoTarget() { return redo.peek(); }

        synchronized void completeUndo(byte[] current) {
            undo.pop();
            add(redo, current);
        }

        synchronized void completeRedo(byte[] current) {
            redo.pop();
            add(undo, current);
        }

        private static void add(ArrayDeque<byte[]> values, byte[] snapshot) {
            if (snapshot == null || snapshot.length > MAX_BYTES) return;
            values.push(snapshot.clone());
            int bytes = 0;
            java.util.Iterator<byte[]> iterator = values.iterator();
            while (iterator.hasNext()) bytes += iterator.next().length;
            while (values.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
                byte[] removed = values.removeLast();
                bytes -= removed.length;
            }
        }
    }

    private static final class ReloadFailure extends RuntimeException {
        private ReloadFailure(IOException cause) { super(cause); }
    }
}
