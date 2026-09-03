package shed;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.JComponent;
import shed.api.CustomEditorContribution;
import shed.api.CustomEditorDocument;

/** Chooses an installed custom editor without replacing Shed's buffer and save model. */
final class CustomEditorController {
    private final Texteditor editor;

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
                JComponent component = owned.value().createComponent(new Document(file, pane, buffer));
                if (component == null) {
                    editor.showMessage("Custom editor " + name(owned) + " returned no component");
                    return false;
                }
                install(pane, component);
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

    private void install(EditorPane pane, JComponent component) {
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

        private Document(Path file, EditorPane pane, FileBuffer buffer) {
            this.file = file;
            this.pane = pane;
            this.buffer = buffer;
        }

        @Override public Path file() { return file; }

        @Override public byte[] bytes() throws IOException {
            return java.nio.file.Files.readAllBytes(file);
        }

        @Override public boolean isBinary() throws IOException {
            return CustomEditorController.isBinary(bytes());
        }

        @Override public void write(byte[] replacement) throws IOException {
            byte[] contents = replacement == null ? new byte[0] : replacement.clone();
            if (SwingUtilities.isEventDispatchThread()) {
                writeOnEventThread(contents);
                return;
            }
            final IOException[] failure = new IOException[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try { writeOnEventThread(contents); } catch (IOException error) { failure[0] = error; }
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("custom editor save interrupted", error);
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw new IOException("custom editor save failed", error.getCause());
            }
            if (failure[0] != null) throw failure[0];
        }

        private void writeOnEventThread(byte[] contents) throws IOException {
            if (pane.getBuffer() != buffer || !file.equals(Path.of(buffer.getFilePath()).toAbsolutePath().normalize())) {
                throw new IOException("custom editor document is no longer active");
            }
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
            editor.updateStatusBar();
            editor.showMessage("Custom editor saved " + file.getFileName());
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

    private static final class ReloadFailure extends RuntimeException {
        private ReloadFailure(IOException cause) { super(cause); }
    }
}
