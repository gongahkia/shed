package shed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Opens, saves, and explicitly executes local Jupyter notebooks. */
final class NotebookController {
    private static final long OUTPUT_LIMIT_BYTES = 128 * 1024;
    private final Texteditor editor;

    NotebookController(Texteditor editor) {
        this.editor = editor;
    }

    boolean showIfAvailable(EditorPane pane, FileBuffer buffer) {
        if (pane == null || buffer == null || !isNotebook(buffer)) return false;
        try {
            NotebookDocument document = NotebookDocument.parse(buffer.getContent());
            pane.setCustomEditorComponent(new NotebookPanel(document,
                updated -> save(pane, buffer, updated),
                updated -> run(pane, buffer, updated)));
            editor.renderWindowLayout();
            editor.showMessage("Opened Jupyter notebook");
            return true;
        } catch (IllegalArgumentException error) {
            editor.showMessage("Notebook view unavailable: " + concise(error));
            return false;
        }
    }

    String handle(String argument) {
        String operation = argument == null ? "" : argument.trim().toLowerCase(java.util.Locale.ROOT);
        EditorPane pane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        if (!isNotebook(buffer)) return "The current buffer is not a .ipynb notebook";
        return switch (operation) {
            case "", "open", "reopen" -> showIfAvailable(pane, buffer) ? "Notebook opened" : "Notebook view unavailable";
            case "run", "runall", "run-all" -> runCurrent(pane, buffer);
            case "raw", "text" -> {
                pane.clearCustomEditorComponent();
                editor.renderWindowLayout();
                yield "Opened notebook JSON source";
            }
            default -> "Usage: :notebook [open|run|raw]";
        };
    }

    private void save(EditorPane pane, FileBuffer buffer, NotebookDocument document) {
        try {
            write(buffer, document);
            editor.showMessage("Notebook saved");
        } catch (IOException | IllegalStateException error) {
            editor.showMessage("Notebook save failed: " + concise(error));
        }
    }

    private void run(EditorPane pane, FileBuffer buffer, NotebookDocument document) {
        try {
            write(buffer, document);
        } catch (IOException | IllegalStateException error) {
            editor.showMessage("Notebook save failed: " + concise(error));
            return;
        }
        runCurrent(pane, buffer);
    }

    private String runCurrent(EditorPane pane, FileBuffer buffer) {
        if (!editor.ensureProjectTrustForFile(buffer.getFile())) return "Notebook execution blocked: workspace is untrusted";
        Path source = buffer.getFile().toPath().toAbsolutePath().normalize();
        int job = editor.asyncJobService.submit("Execute notebook " + source.getFileName(), token -> execute(source, token),
            (snapshot, output, error) -> {
                if (error != null) {
                    editor.showMessage("Notebook execution failed: " + concise(error));
                    return;
                }
                try {
                    editor.withSuppressedDocumentEvents(() -> {
                        try { buffer.load(editor.configManager); } catch (IOException loadError) { throw new NotebookLoadException(loadError); }
                    });
                    showIfAvailable(pane, buffer);
                    editor.showMessage("Notebook execution completed");
                } catch (NotebookLoadException loadError) {
                    editor.showMessage("Notebook completed but could not reload: " + concise(loadError.getCause()));
                }
            });
        return "Notebook execution started (job " + job + ")";
    }

    private static String execute(Path source, AsyncJobService.JobToken token) throws Exception {
        Path output = Files.createTempFile("shed-notebook-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder("jupyter", "nbconvert", "--to", "notebook", "--execute", "--inplace", source.toString())
                .directory(source.getParent().toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Process running = process;
            token.onCancel(() -> running.destroyForcibly());
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Jupyter execution timed out after 10 minutes");
            }
            String text = readCapped(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? "Jupyter exited " + process.exitValue() : text.strip());
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Jupyter execution interrupted", error);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void write(FileBuffer buffer, NotebookDocument document) throws IOException {
        buffer.setContent(document.serialize(), true);
        buffer.save();
        editor.syncLspOpen(buffer);
        editor.updateStatusBar();
    }

    private static boolean isNotebook(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null) return false;
        return buffer.getFile().getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ipynb");
    }

    private static String readCapped(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes((int) OUTPUT_LIMIT_BYTES + 1);
            String text = new String(bytes, 0, Math.min(bytes.length, (int) OUTPUT_LIMIT_BYTES), StandardCharsets.UTF_8);
            return bytes.length > OUTPUT_LIMIT_BYTES ? text + "\n[shed: output truncated]" : text;
        }
    }

    private static String concise(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class NotebookLoadException extends RuntimeException {
        private NotebookLoadException(IOException cause) { super(cause); }
    }
}
