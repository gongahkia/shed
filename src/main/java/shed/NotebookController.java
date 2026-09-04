package shed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Opens, saves, and explicitly executes local Jupyter notebooks. */
final class NotebookController {
    private static final long OUTPUT_LIMIT_BYTES = 128 * 1024;
    record KernelSpec(String name, String displayName, String language) {
        KernelSpec {
            name = kernelName(name);
            displayName = displayName == null || displayName.isBlank() ? name : displayName.trim();
            language = language == null ? "" : language.trim();
        }

        @Override public String toString() {
            return displayName.equals(name) ? name + languageLabel(language) : displayName + " (" + name + ")" + languageLabel(language);
        }

        private static String languageLabel(String language) {
            return language == null || language.isBlank() ? "" : " [" + language + "]";
        }
    }
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
                updated -> run(pane, buffer, updated),
                (updated, cellCount) -> runThrough(pane, buffer, updated, cellCount), buffer.getFile(),
                () -> editor.showMessage(chooseKernel(pane, buffer))));
            editor.renderWindowLayout();
            editor.showMessage("Opened Jupyter notebook");
            return true;
        } catch (IllegalArgumentException error) {
            editor.showMessage("Notebook view unavailable: " + concise(error));
            return false;
        }
    }

    String handle(String argument) {
        String raw = argument == null ? "" : argument.trim();
        String operation = raw.toLowerCase(java.util.Locale.ROOT);
        EditorPane pane = editor.getActivePane();
        FileBuffer buffer = editor.getCurrentBuffer();
        if (!isNotebook(buffer)) return "The current buffer is not a .ipynb notebook";
        if (operation.equals("console") || operation.startsWith("console ")) return openConsole(buffer, selectedKernel(buffer, raw.substring("console".length()).trim()));
        if (operation.equals("run") || operation.startsWith("run ")) return runCurrent(pane, buffer, raw.substring("run".length()).trim());
        return switch (operation) {
            case "", "open", "reopen" -> showIfAvailable(pane, buffer) ? "Notebook opened" : "Notebook view unavailable";
            case "runall", "run-all" -> runCurrent(pane, buffer, "");
            case "kernels", "kernelspecs" -> listKernels(buffer);
            case "select", "picker" -> chooseKernel(pane, buffer);
            case "kernel" -> currentKernel(buffer);
            case "raw", "text" -> {
                pane.clearCustomEditorComponent();
                editor.renderWindowLayout();
                yield "Opened notebook JSON source";
            }
            default -> selectNamedKernel(pane, buffer, raw);
        };
    }

    private String currentKernel(FileBuffer buffer) {
        try {
            String selected = selectedKernel(buffer, "");
            return selected.isBlank() ? "Notebook kernel: Jupyter default (none selected)" : "Notebook kernel: " + selected;
        } catch (IllegalArgumentException error) {
            return "Notebook kernelspec metadata is invalid";
        }
    }

    private String selectNamedKernel(EditorPane pane, FileBuffer buffer, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.regionMatches(true, 0, "kernel ", 0, 7)) {
            return "Usage: :notebook [open|run [kernel]|kernels|select|kernel [name]|console [kernel]|raw]";
        }
        String name = value.substring(7).trim();
        try {
            KernelSpec selected = new KernelSpec(name, name, "");
            return persistKernel(pane, buffer, selected);
        } catch (IllegalArgumentException error) {
            return "Jupyter kernel name is invalid";
        }
    }

    private String listKernels(FileBuffer buffer) {
        if (!editor.ensureProjectTrustForFile(buffer.getFile())) return "Jupyter kernel discovery blocked: workspace is untrusted";
        Path directory = buffer.getFile().toPath().toAbsolutePath().normalize().getParent();
        int job = editor.asyncJobService.submit("Discover Jupyter kernels", token -> discoverKernels(directory, token),
            (snapshot, kernels, error) -> {
                if (error != null) {
                    editor.showMessage("Jupyter kernel discovery failed: " + concise(error));
                    return;
                }
                editor.showScratchBuffer("[jupyter kernels]", renderKernels(kernels));
                editor.showMessage("Jupyter kernel discovery completed");
            });
        return "Jupyter kernel discovery started (job " + job + ").";
    }

    private String chooseKernel(EditorPane pane, FileBuffer buffer) {
        if (!editor.ensureProjectTrustForFile(buffer.getFile())) return "Jupyter kernel selection blocked: workspace is untrusted";
        Path directory = buffer.getFile().toPath().toAbsolutePath().normalize().getParent();
        int job = editor.asyncJobService.submit("Discover Jupyter kernels", token -> discoverKernels(directory, token),
            (snapshot, kernels, error) -> {
                if (error != null) {
                    editor.showMessage("Jupyter kernel discovery failed: " + concise(error));
                    return;
                }
                if (kernels == null || kernels.isEmpty()) {
                    editor.showMessage("No installed Jupyter kernels were found");
                    return;
                }
                Map<String, KernelSpec> byLabel = new LinkedHashMap<>();
                for (KernelSpec kernel : kernels) byLabel.put(kernel.toString(), kernel);
                String selected = editor.showPaletteDialog("Jupyter Kernels", new ArrayList<>(byLabel.keySet()));
                KernelSpec kernel = selected == null ? null : byLabel.get(selected);
                if (kernel == null || pane.getBuffer() != buffer) return;
                if (pane.getCustomEditorComponent() instanceof NotebookPanel panel) {
                    panel.selectKernel(kernel);
                    editor.showMessage("Notebook kernel selected: " + kernel.name() + "; save or run to persist it");
                    return;
                }
                editor.showMessage(persistKernel(pane, buffer, kernel));
            });
        return "Jupyter kernel discovery started (job " + job + ").";
    }

    private String persistKernel(EditorPane pane, FileBuffer buffer, KernelSpec kernel) {
        if (!editor.ensureProjectTrustForFile(buffer.getFile())) return "Jupyter kernel selection blocked: workspace is untrusted";
        try {
            NotebookDocument document = NotebookDocument.parse(buffer.getContent())
                .withKernelSpec(kernel.name(), kernel.displayName(), kernel.language());
            write(buffer, document);
            showIfAvailable(pane, buffer);
            return "Notebook kernel selected and saved: " + kernel.name();
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            return "Notebook kernel selection failed: " + concise(error);
        }
    }

    private String openConsole(FileBuffer buffer, String kernel) {
        if (!editor.ensureProjectTrustForFile(buffer.getFile())) return "Jupyter Console blocked: workspace is untrusted";
        List<String> command;
        try {
            command = consoleCommand(kernel);
        } catch (IllegalArgumentException error) {
            return "Jupyter kernel name is invalid";
        }
        return editor.terminalController.openDirect("Jupyter Console", buffer.getFile().getParentFile(), command);
    }

    static List<String> consoleCommand(String kernel) {
        String name = kernel == null ? "" : kernel.trim();
        if (!name.isEmpty() && !name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("kernel name is invalid");
        return name.isEmpty() ? List.of("jupyter", "console") : List.of("jupyter", "console", "--kernel", name);
    }

    static List<String> kernelListCommand() { return List.of("jupyter", "kernelspec", "list", "--json"); }

    static List<KernelSpec> parseKernelSpecs(String output) throws IOException {
        try {
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(output == null ? "" : output));
            Map<String, Object> values = MiniJson.asObject(root == null ? null : root.get("kernelspecs"));
            if (values == null) throw new IllegalArgumentException("Jupyter output does not contain kernelspecs");
            List<KernelSpec> result = new ArrayList<>();
            for (Map.Entry<String, Object> entry : new TreeMap<>(values).entrySet()) {
                Map<String, Object> kernel = MiniJson.asObject(entry.getValue());
                Map<String, Object> spec = MiniJson.asObject(kernel == null ? null : kernel.get("spec"));
                String displayName = MiniJson.asString(spec == null ? null : spec.get("display_name"));
                String language = MiniJson.asString(spec == null ? null : spec.get("language"));
                result.add(new KernelSpec(entry.getKey(), displayName, language));
            }
            return List.copyOf(result);
        } catch (RuntimeException error) {
            throw new IOException("Jupyter kernelspec output is invalid: " + error.getMessage(), error);
        }
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
        runCurrent(pane, buffer, "");
    }

    private void runThrough(EditorPane pane, FileBuffer buffer, NotebookDocument document, int cellCount) {
        try {
            write(buffer, document);
        } catch (IOException | IllegalStateException error) {
            editor.showMessage("Notebook save failed: " + concise(error));
            return;
        }
        if (!editor.ensureProjectTrustForFile(buffer.getFile())) {
            editor.showMessage("Notebook execution blocked: workspace is untrusted");
            return;
        }
        Path source = buffer.getFile().toPath().toAbsolutePath().normalize();
        String kernel;
        try {
            kernel = document.kernelName();
        } catch (IllegalArgumentException error) {
            editor.showMessage("Notebook kernelspec metadata is invalid");
            return;
        }
        int job = editor.asyncJobService.submit("Execute notebook through cell " + cellCount, token -> executeThrough(source, cellCount, kernel, token),
            (snapshot, executed, error) -> {
                if (error != null) {
                    editor.showMessage("Notebook cell execution failed: " + concise(error));
                    return;
                }
                try {
                    write(buffer, executed);
                    showIfAvailable(pane, buffer);
                    editor.showMessage("Notebook executed through cell " + cellCount);
                } catch (IOException | IllegalStateException writeError) {
                    editor.showMessage("Notebook executed but could not save output: " + concise(writeError));
                }
            });
        editor.showMessage("Notebook cell execution started (job " + job + ", fresh " + (kernel.isBlank() ? "default" : kernel) + " kernel)");
    }

    private String runCurrent(EditorPane pane, FileBuffer buffer, String kernel) {
        if (!editor.ensureProjectTrustForFile(buffer.getFile())) return "Notebook execution blocked: workspace is untrusted";
        String selectedKernel;
        try {
            selectedKernel = selectedKernel(buffer, kernel);
        } catch (IllegalArgumentException error) {
            return "Jupyter kernel name is invalid";
        }
        Path source = buffer.getFile().toPath().toAbsolutePath().normalize();
        int job = editor.asyncJobService.submit("Execute notebook " + source.getFileName(), token -> execute(source, selectedKernel, token),
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
        return "Notebook execution started (job " + job + (selectedKernel.isBlank() ? "" : ", kernel " + selectedKernel) + ")";
    }

    static List<String> executeCommand(Path source, String kernel) {
        if (source == null) throw new IllegalArgumentException("notebook path is required");
        String selectedKernel = kernelName(kernel);
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("jupyter", "nbconvert", "--to", "notebook", "--execute", "--inplace"));
        if (!selectedKernel.isBlank()) command.add("--ExecutePreprocessor.kernel_name=" + selectedKernel);
        command.add(source.toAbsolutePath().normalize().toString());
        return List.copyOf(command);
    }

    private static String kernelName(String kernel) {
        String name = kernel == null ? "" : kernel.trim();
        if (!name.isEmpty() && !name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("kernel name is invalid");
        return name;
    }

    static String selectedKernel(NotebookDocument document, String requested) {
        String explicit = kernelName(requested);
        return explicit.isBlank() && document != null ? kernelName(document.kernelName()) : explicit;
    }

    private static String selectedKernel(FileBuffer buffer, String requested) {
        String explicit = kernelName(requested);
        if (!explicit.isBlank()) return explicit;
        if (buffer == null) return "";
        return selectedKernel(NotebookDocument.parse(buffer.getContent()), "");
    }

    private static String execute(Path source, String kernel, AsyncJobService.JobToken token) throws Exception {
        Path output = Files.createTempFile("shed-notebook-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(executeCommand(source, kernel))
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

    private static List<KernelSpec> discoverKernels(Path directory, AsyncJobService.JobToken token) throws Exception {
        Path output = Files.createTempFile("shed-jupyter-kernels-", ".log");
        try {
            Process process = new ProcessBuilder(kernelListCommand()).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            token.onCancel(process::destroyForcibly);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Jupyter kernel discovery timed out after 30 seconds");
            }
            String text = readCapped(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? "Jupyter exited " + process.exitValue() : text.strip());
            return parseKernelSpecs(text);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Jupyter kernel discovery interrupted", error);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static NotebookDocument executeThrough(Path source, int cellCount, String kernel, AsyncJobService.JobToken token) throws Exception {
        NotebookDocument document = NotebookDocument.parse(Files.readString(source, StandardCharsets.UTF_8));
        NotebookDocument prefix = document.through(cellCount);
        Path temporary = Files.createTempFile(source.getParent(), ".shed-notebook-", ".ipynb");
        try {
            Files.writeString(temporary, prefix.serialize(), StandardCharsets.UTF_8);
            execute(temporary, kernel, token);
            return document.withExecutedPrefix(NotebookDocument.parse(Files.readString(temporary, StandardCharsets.UTF_8)), cellCount);
        } finally {
            Files.deleteIfExists(temporary);
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

    private static String renderKernels(List<KernelSpec> kernels) {
        StringBuilder text = new StringBuilder("Jupyter Kernels\n\n");
        if (kernels == null || kernels.isEmpty()) return text.append("(none)\n").toString();
        for (KernelSpec kernel : kernels) {
            text.append(kernel.name()).append("  ").append(kernel.displayName());
            if (!kernel.language().isBlank()) text.append("  [").append(kernel.language()).append("]");
            text.append('\n');
        }
        text.append("\nUse :notebook select for a picker, :notebook kernel <name> to persist a selection, or :notebook run/console <kernel> for a one-shot override.\n");
        return text.toString();
    }

    private static String concise(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class NotebookLoadException extends RuntimeException {
        private NotebookLoadException(IOException cause) { super(cause); }
    }
}
