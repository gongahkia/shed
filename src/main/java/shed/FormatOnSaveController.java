package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class FormatOnSaveController {
    private record Request(FileBuffer buffer, String targetPath, String content) { }
    private record FormatResult(Request request, String uri, Integer version, String content, List<LspClient.TextEdit> edits) { }
    private record ExternalFormatResult(Request request, String content) { }

    private final Texteditor editor;
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private boolean running;
    private boolean quitAfterBatch;
    private boolean quitForce;
    private int completed;

    FormatOnSaveController(Texteditor editor) { this.editor = editor; }

    String requestCurrent(String targetPath, boolean quitAfterSave) {
        FileBuffer buffer = editor.getCurrentBuffer();
        if (buffer == null) return "Error: No file open";
        if (running) return "Save already in progress";
        editor.persistCurrentBufferState();
        enqueue(List.of(new Request(buffer, targetPath, buffer.getContent())), quitAfterSave, true);
        return formatsOnSave(buffer) ? "Formatting before save…" : "Saving…";
    }

    String requestAll(boolean quitAfterSave, boolean force) {
        if (running) return "Save already in progress";
        List<Request> requests = new ArrayList<>();
        for (FileBuffer buffer : editor.buffers) {
            if (buffer != null && buffer.isModified() && buffer.getFile() != null) {
                if (buffer == editor.getCurrentBuffer()) editor.persistCurrentBufferState();
                requests.add(new Request(buffer, null, buffer.getContent()));
            }
        }
        if (requests.isEmpty()) {
            if (quitAfterSave) return editor.quitAll(force);
            return "0 file(s) written";
        }
        enqueue(requests, quitAfterSave, force);
        return "Saving " + requests.size() + " file(s)…";
    }

    private void enqueue(List<Request> requests, boolean quit, boolean force) {
        pending.clear();
        pending.addAll(requests);
        running = true;
        completed = 0;
        quitAfterBatch = quit;
        quitForce = force;
        startNext();
    }

    private void startNext() {
        Request request = pending.peekFirst();
        if (request == null) {
            running = false;
            if (quitAfterBatch) editor.quitAll(quitForce);
            else editor.showMessage(completed + " file(s) written");
            return;
        }
        FormatterPolicy policy = policyFor(request.buffer());
        if (!policy.formatOnSave() || policy.mode() == FormatterPolicy.Mode.DISABLED) {
            finishWrite(request);
            return;
        }
        FileBuffer buffer = request.buffer();
        if (buffer == null || !buffer.hasFilePath() || buffer.isLargeFile()) {
            fail("Formatting requires a file-backed, editable buffer");
            return;
        }
        String current = buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
        if (!Objects.equals(request.content(), current)) { fail("Formatting before save became stale; file was not written"); return; }
        if (policy.mode() == FormatterPolicy.Mode.EXTERNAL) {
            beginExternalFormatting(request, policy);
            return;
        }
        LspClient client = editor.lspController.existingLspClient(buffer);
        if (client == null || !client.isAlive()) {
            editor.asyncJobService.submit("LSP initialize before save", token -> editor.lspController.resolveLspClient(buffer),
                (job, initialized, error) -> completeLspInitialization(job, request, initialized, error));
            return;
        }
        beginFormatting(request, client);
    }

    private void completeLspInitialization(AsyncJobService.JobSnapshot job, Request request, LspClient client, Exception error) {
        if (!running || pending.peekFirst() != request) return;
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) { fail("Formatting before save cancelled"); return; }
        if (error != null || client == null || !client.isAlive()) { fail("LSP formatting is unavailable"); return; }
        beginFormatting(request, client);
    }

    private void beginFormatting(Request request, LspClient client) {
        FileBuffer buffer = request.buffer();
        String current = buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
        if (!Objects.equals(request.content(), current)) { fail("Formatting before save became stale; file was not written"); return; }
        if (!client.supports(LspCapability.FORMATTING)) { fail(client.capabilityUnavailableReason(LspCapability.FORMATTING)); return; }
        editor.lspController.syncLspOpen(buffer);
        editor.lspController.flushPendingLspChange(buffer);
        String uri = editor.bufferUri(buffer);
        Integer version = editor.lspDocumentVersions.get(uri);
        String content = current;
        LspClient formatter = client;
        editor.asyncJobService.submit("LSP format before save", token -> new FormatResult(request, uri, version, content,
            formatter.formattingChecked(uri, editor.writingArea.getTabSize(), editor.effectiveExpandTab())), this::completeFormat);
    }

    private void beginExternalFormatting(Request request, FormatterPolicy policy) {
        FileBuffer buffer = request.buffer();
        try {
            Path file = new File(buffer.getFilePath()).toPath().toAbsolutePath().normalize();
            Path workspace = editor.lspController.resolveWorkspaceRoot(file.getParent());
            editor.asyncJobService.submit("External format before save", token -> {
                ExternalFormatter.Result result = ExternalFormatter.format(policy, workspace, file, request.content(),
                    editor.configManager.getProcessTimeoutMs(), editor.configManager.getProcessOutputMaxBytes(), token);
                if (!result.succeeded()) throw new IOException(result.error());
                return new ExternalFormatResult(request, result.text());
            }, this::completeExternalFormat);
        } catch (IOException error) {
            fail("Formatting before save failed: " + error.getMessage());
        }
    }

    private void completeFormat(AsyncJobService.JobSnapshot job, FormatResult result, Exception error) {
        if (!running || result == null || pending.peekFirst() != result.request()) return;
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) { fail("Formatting before save cancelled"); return; }
        if (error != null) { fail("Formatting before save failed: " + error.getMessage()); return; }
        FileBuffer buffer = result.request().buffer();
        String current = buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
        if (!Objects.equals(result.request().content(), current) || !Objects.equals(result.content(), current)
            || !Objects.equals(result.version(), editor.lspDocumentVersions.get(result.uri()))) {
            fail("Formatting before save became stale; file was not written");
            return;
        }
        if (result.edits() != null && !result.edits().isEmpty()) {
            WorkspaceEditApplyResult applied = editor.applyWorkspaceTextEdits(result.edits());
            if (applied.appliedEditCount <= 0) {
                fail("Formatting before save failed: " + (applied.failureReason == null ? "no applicable edits" : applied.failureReason));
                return;
            }
        }
        finishWrite(result.request());
    }

    private void completeExternalFormat(AsyncJobService.JobSnapshot job, ExternalFormatResult result, Exception error) {
        if (!running || result == null || pending.peekFirst() != result.request()) return;
        if (job.getStatus() == AsyncJobService.Status.CANCELLED) { fail("Formatting before save cancelled"); return; }
        if (error != null) { fail("Formatting before save failed: " + error.getMessage()); return; }
        FileBuffer buffer = result.request().buffer();
        String current = buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
        if (!Objects.equals(result.request().content(), current)) { fail("Formatting before save became stale; file was not written"); return; }
        String formatted = result.content() == null ? "" : result.content();
        editor.paneBufferController.withSuppressedDocumentEvents(() -> buffer.setContent(formatted, true));
        editor.syncLspChange(buffer);
        finishWrite(result.request());
    }

    private FormatterPolicy policyFor(FileBuffer buffer) {
        String extension = buffer == null ? "" : editor.lspController.bufferExtension(buffer);
        return editor.configManager.getFormatterPolicy(extension);
    }

    private boolean formatsOnSave(FileBuffer buffer) {
        FormatterPolicy policy = policyFor(buffer);
        return policy.formatOnSave() && policy.mode() != FormatterPolicy.Mode.DISABLED;
    }

    private void finishWrite(Request request) {
        FileBuffer buffer = request.buffer();
        try {
            if (buffer == editor.getCurrentBuffer()) editor.persistCurrentBufferState();
            String previous = buffer.getContent();
            String updated = buffer == editor.getCurrentBuffer() ? editor.writingArea.getText() : buffer.getContent();
            buffer.setContent(updated);
            editor.backupBeforeSave(buffer);
            if (request.targetPath() != null && !request.targetPath().isBlank()) buffer.saveAs(new File(request.targetPath()));
            else buffer.save();
            editor.notifyBufferSaved(buffer);
            String reload = editor.reloadConfigIfSettingsBuffer(buffer, previous, updated);
            completed++;
            pending.removeFirst();
            if (pending.isEmpty() && reload != null && !reload.isBlank()) editor.showMessage(reload);
            startNext();
        } catch (IOException error) {
            fail("Error saving file: " + error.getMessage());
        }
    }

    private void fail(String message) {
        pending.clear();
        running = false;
        quitAfterBatch = false;
        editor.showMessage(message == null || message.isBlank() ? "Formatting before save failed" : message);
    }
}
