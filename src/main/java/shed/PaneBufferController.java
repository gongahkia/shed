package shed;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

final class PaneBufferController {
    private static final int BACKUP_IDLE_DEBOUNCE_MS = 750;
    private static final int DIFF_GUTTER_DEBOUNCE_MS = 120;
    private static final int LARGE_DIFF_GUTTER_DEBOUNCE_MS = 750;
    private final Texteditor editor;
    private final Map<FileBuffer, Timer> backupTimers = new IdentityHashMap<>();
    private Timer diffGutterTimer;
    private FileBuffer pendingDiffGutterBuffer;
    private int diffGutterJobId = -1;
    private long diffGutterGeneration;
    private FileBuffer diffGutterBuffer;
    private IncrementalDiffGutter incrementalDiffGutter;
    private long openRequestSequence;
    private final Map<EditorPane, Long> openRequests = new WeakHashMap<>();

    PaneBufferController(Texteditor editor) {
        this.editor = editor;
    }

    void handleDocumentChange(DocumentEvent event) {
        if (!editor.suppressDocumentEvents) {
            FileBuffer buffer = getCurrentBuffer();
            FileBuffer.DocumentTextChange textChange = buffer != null && event != null && event.getDocument() == buffer.getDocument()
                ? buffer.applyDocumentChange(event) : null;
            editor.editorUiController.noteEditorDocumentChange();
            markModified(event, textChange);
            editor.updateCurrentLineHighlight();
            editor.scheduleSyntaxHighlighting();
            editor.scheduleCompletionAfterDocumentChange(event, textChange);
            if (editor.inlineFindBar != null) editor.inlineFindBar.onDocumentChanged();
            try {
                int line = editor.writingArea.getLineOfOffset(editor.writingArea.getCaretPosition());
                editor.lineNumberPanel.repaintLines(line);
            } catch (BadLocationException ignored) {
            }
        }
    }


    void withSuppressedDocumentEvents(Runnable action) {
        editor.suppressDocumentEvents = true;
        try {
            action.run();
        } finally {
            editor.suppressDocumentEvents = false;
        }
    }


    void detachActiveDocumentListener() {
        if (editor.bufferDocumentListener != null && editor.writingArea.getDocument() != null) {
            editor.writingArea.getDocument().removeDocumentListener(editor.bufferDocumentListener);
        }
    }


    void attachActiveDocumentListener() {
        if (editor.bufferDocumentListener != null && editor.writingArea.getDocument() != null) {
            editor.writingArea.getDocument().addDocumentListener(editor.bufferDocumentListener);
        }
    }


    void loadBufferIntoEditor(FileBuffer buffer) {
        restoreBufferIntoPane(editor.getActivePane(), buffer);
    }

    private void restoreBufferIntoPane(EditorPane pane, FileBuffer buffer) {
        if (pane == null || buffer == null) return;
        pane.rememberBufferView();
        BufferViewState view = pane.rememberedView(buffer);
        loadBufferIntoPane(pane, buffer, 0);
        if (view == null) return;
        view.restoreSelection(pane.getTextArea());
        int dot = pane.getTextArea().getCaret().getDot();
        int mark = pane.getTextArea().getCaret().getMark();
        SwingUtilities.invokeLater(() -> {
            // An explicit jump or another switch before layout must win over this restoration.
            if (pane.getBuffer() != buffer || pane.getTextArea().getCaret().getDot() != dot
                || pane.getTextArea().getCaret().getMark() != mark) return;
            view.restoreViewport(pane.getScrollPane());
        });
    }

    void loadBufferIntoPane(EditorPane pane, FileBuffer buffer, int caretPosition) {
        if (pane == null || buffer == null) {
            return;
        }
        pane.rememberBufferView();
        if (pane.isMarkdownPreview()) {
            editor.detachMarkdownPreview(pane);
            pane.clearMarkdownPreviewComponent();
        } else if (editor.hasMarkdownPreviewForSource(pane)) {
            editor.closeMarkdownPreviewForSource(pane);
        }
        boolean replacedAlternateSurface = pane.getAlternateComponent() != null;
        pane.clearAlternateComponent();

        boolean activePane = pane == editor.getActivePane();
        if (activePane) {
            detachActiveDocumentListener();
            editor.clearLspDecorations();
            editor.clearExtraCursors();
            if (editor.searchManager != null) {
                editor.searchManager.clearHighlights();
            }
        }

        boolean replacedTerminalPane = pane.getTerminalPane() != null;
        if (replacedTerminalPane) {
            FileBuffer previousBuffer = pane.getBuffer();
            if (previousBuffer != null) {
                editor.ptyTerminalPanes.remove(previousBuffer);
            }
            pane.closeTerminalPane();
        }
        pane.setBuffer(buffer);
        pane.getTextArea().setTabSize(editor.effectiveTabSize(buffer));
        pane.setLargeFileProjection(buffer.isLargeFile() && !buffer.isLargeFileUnavailable() ? new LargeFileProjection(buffer) : null);
        if (pane.getLargeFileProjection() != null) {
            try {
                withSuppressedDocumentEvents(() -> renderLargeFileProjection(pane));
            } catch (RuntimeException error) {
                editor.showMessage("Large-file window failed: " + error.getMessage());
            }
        }
        withSuppressedDocumentEvents(() -> pane.getTextArea().setDocument(buffer.getDocument()));
        pane.getTextArea().setEditable(!buffer.isLargeFile() && editor.editorState.mode.isEditable());
        pane.getTextArea().setCaretPosition(Math.min(caretPosition, pane.getTextArea().getDocument().getLength()));
        if (activePane) {
            editor.bindActivePane(pane);
            attachActiveDocumentListener();
            editor.undoManager = buffer.getUndoManager();
            editor.currentBufferIndex = editor.buffers.indexOf(buffer);
            editor.registerManager.updateFilename(buffer.getFilePath());
            editor.updateCurrentLineHighlight();
            editor.applySyntaxHighlighting();
            editor.refreshLineNumberPanel();
            refreshDiffGutterForActiveBuffer(buffer);
            editor.syncLspOpen(buffer);
            SwingUtilities.invokeLater(() -> {
                if (pane != editor.getActivePane() || pane.getBuffer() != buffer) return;
                editor.applySyntaxHighlighting(); // repaint after the new document has entered the viewport
                editor.refreshLspDecorations();
            });
            editor.scheduleOpenBufferCompletionIndex();
            editor.updateStatusBar();
        } else {
            pane.getLineNumberPanel().repaint();
        }
        if (replacedTerminalPane || replacedAlternateSurface) {
            editor.renderWindowLayout();
        }
    }


    void persistCurrentBufferState() {
        EditorPane pane = editor.getActivePane();
        if (pane != null) {
            pane.rememberBufferView();
            pane.setBuffer(getCurrentBuffer());
        }
    }


    void markModified() {
        markModified(null, null);
    }

    private void markModified(DocumentEvent event) {
        markModified(event, null);
    }

    private void markModified(DocumentEvent event, FileBuffer.DocumentTextChange textChange) {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer != null && buffer.isLargeFile()) {
            return;
        }
        if (buffer != null) {
            if (event == null && textChange == null) {
                textChange = buffer.reconcileDocumentText();
            }
            buffer.setModified(true);
            editor.invalidateGitBlame(buffer);
            scheduleIdleBackup(buffer);
            editor.recordChangePosition();
            syncLspChange(buffer, event, textChange);
            editor.scheduleDiffGutter(buffer, textChange);
            editor.requestStatusBarRefresh();
            editor.scheduleRecoverySnapshotCapture();
        }
    }

    private void syncLspChange(FileBuffer buffer, DocumentEvent event, FileBuffer.DocumentTextChange textChange) {
        if (textChange != null) {
            editor.syncLspChange(buffer, textChange);
            return;
        }
        if (event == null || event.getType() == DocumentEvent.EventType.CHANGE) {
            editor.syncLspChange(buffer);
            return;
        }
        try {
            String insertedText = event.getType() == DocumentEvent.EventType.INSERT
                ? event.getDocument().getText(event.getOffset(), event.getLength()) : "";
            int removedLength = event.getType() == DocumentEvent.EventType.REMOVE ? event.getLength() : 0;
            editor.syncLspChange(buffer, event.getOffset(), removedLength, insertedText);
        } catch (BadLocationException ignored) {
            editor.syncLspChange(buffer);
        }
    }

    void scheduleIdleBackup(FileBuffer buffer) {
        BackupPolicy policy = editor.configManager.getBackupPolicy();
        if (buffer == null || !policy.enabled() || policy.mode() != BackupPolicy.BackupMode.IDLE) {
            return;
        }
        Timer timer = backupTimers.computeIfAbsent(buffer, ignored -> {
            Timer created = new Timer(BACKUP_IDLE_DEBOUNCE_MS, event -> queueBackup(buffer));
            created.setRepeats(false);
            return created;
        });
        timer.restart();
    }

    void backupBeforeSave(FileBuffer buffer) {
        BackupPolicy policy = editor.configManager.getBackupPolicy();
        if (buffer == null || !policy.enabled() || policy.mode() != BackupPolicy.BackupMode.SAVE_ONLY) {
            return;
        }
        try {
            buffer.createBackup();
        } catch (IOException error) {
            editor.errorReporter.report(error, "backup", "creating a local save-only backup", "docs/BACKUPS.md");
        }
    }

    void flushPendingBackups() {
        for (Timer timer : backupTimers.values()) {
            if (timer.isRunning()) {
                timer.stop();
            }
        }
        for (FileBuffer buffer : new ArrayList<>(backupTimers.keySet())) {
            queueBackup(buffer);
        }
        backupTimers.clear();
    }

    private void queueBackup(FileBuffer buffer) {
        try {
            FileBuffer.BackupSnapshot snapshot = buffer == null ? null : buffer.captureBackupSnapshot();
            if (snapshot != null) {
                editor.backupScheduler.submit(buffer, snapshot, error -> editor.errorReporter.report(error, "backup",
                    "writing an idle backup", "docs/BACKUPS.md"));
            }
        } catch (IOException error) {
            editor.errorReporter.report(error, "backup", "capturing an idle backup", "docs/BACKUPS.md");
        }
    }

    void handleLargeFileScroll(EditorPane pane) {
        if (pane == null || pane.getLargeFileProjection() == null || pane.getLargeFileProjection().rendering()) {
            return;
        }
        JScrollBar bar = pane.getScrollPane().getVerticalScrollBar();
        try {
            boolean changed = bar.getValue() <= 0
                ? pane.getLargeFileProjection().moveBackward(visibleLines(pane))
                : bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum()
                    && pane.getLargeFileProjection().moveForward(visibleLines(pane));
            if (changed) {
                bar.setValue(Math.max(0, (bar.getMaximum() - bar.getVisibleAmount()) / 2));
            }
        } catch (IOException error) {
            editor.showMessage("Large-file window failed: " + error.getMessage());
        }
    }

    void handleLargeFileCaret(EditorPane pane) {
        if (pane == null || pane.getLargeFileProjection() == null || pane.getLargeFileProjection().rendering()) {
            return;
        }
        try {
            JTextArea area = pane.getTextArea();
            int localLine = area.getLineOfOffset(area.getCaretPosition());
            if (pane.getLargeFileProjection().ensureCaretMargin(localLine, area.getLineCount(), visibleLines(pane))) {
                area.setCaretPosition(Math.min(area.getDocument().getLength(), area.getCaretPosition()));
            }
        } catch (BadLocationException | IOException error) {
            editor.showMessage("Large-file window failed: " + error.getMessage());
        }
    }

    void handleLargeFileResize(EditorPane pane) {
        if (pane != null && pane.getLargeFileProjection() != null) {
            try {
                withSuppressedDocumentEvents(() -> renderLargeFileProjection(pane));
            } catch (RuntimeException error) {
                editor.showMessage("Large-file window failed: " + error.getMessage());
            }
        }
    }

    private void renderLargeFileProjection(EditorPane pane) {
        try {
            pane.getLargeFileProjection().render(visibleLines(pane));
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private int visibleLines(EditorPane pane) {
        int lineHeight = Math.max(1, pane.getTextArea().getFontMetrics(pane.getTextArea().getFont()).getHeight());
        return Math.max(1, pane.getScrollPane().getViewport().getExtentSize().height / lineHeight);
    }


    void updateDiffGutter(FileBuffer buffer) {
        if (editor.lineNumberPanel == null || buffer == null || buffer.isLargeFile()) return;
        VersionedTextSnapshot text = buffer.textSnapshot();
        if (buffer == diffGutterBuffer && incrementalDiffGutter != null
            && incrementalDiffGutter.matches(text, buffer.getSavedContent())) return;
        DiffGutterRequest request = new DiffGutterRequest(buffer, text, buffer.getSavedContent(), ++diffGutterGeneration);
        if (diffGutterJobId >= 0) editor.asyncJobService.cancel(diffGutterJobId);
        diffGutterJobId = editor.asyncJobService.submit("Diff gutter", token -> {
            long started = System.nanoTime();
            LineNumberPanel.DiffMarkers markers = LineNumberPanel.diffMarkers(
                request.savedContent(), request.text().text(), token::isCancelled);
            if (token.isCancelled()) return null;
            VersionedTextSnapshot saved = VersionedTextSnapshot.of(request.savedContent());
            if (editor.perfService != null) {
                editor.perfService.recordDuration("diff.gutter", started, request.buffer().getDisplayName());
            }
            return new DiffGutterResult(request, saved, markers);
        }, (snapshot, result, error) -> applyDiffGutter(snapshot, result, error));
    }

    void refreshDiffGutterForActiveBuffer(FileBuffer buffer) {
        if (editor.lineNumberPanel == null || buffer == null) return;
        if (buffer == diffGutterBuffer && incrementalDiffGutter != null
            && incrementalDiffGutter.matches(buffer.textSnapshot(), buffer.getSavedContent())) {
            editor.lineNumberPanel.updateDiffMarkers(incrementalDiffGutter.markers());
            return;
        }
        if (diffGutterTimer != null) diffGutterTimer.stop();
        pendingDiffGutterBuffer = null;
        if (diffGutterJobId >= 0) editor.asyncJobService.cancel(diffGutterJobId);
        diffGutterJobId = -1;
        diffGutterGeneration++;
        diffGutterBuffer = null;
        incrementalDiffGutter = null;
        LineNumberPanel.DiffMarkers empty = new LineNumberPanel.DiffMarkers(Set.of(), Set.of(), Set.of());
        editor.lineNumberPanel.updateDiffMarkers(empty);
        if (buffer.isLargeFile()) return;
        if (!buffer.isModified()) {
            VersionedTextSnapshot text = buffer.textSnapshot();
            diffGutterBuffer = buffer;
            incrementalDiffGutter = new IncrementalDiffGutter(buffer.getSavedContent(), text, text, empty);
        } else {
            scheduleDiffGutter(buffer);
        }
    }

    private void applyDiffGutter(AsyncJobService.JobSnapshot job, DiffGutterResult result, Exception error) {
        if (job == null || job.getStatus() != AsyncJobService.Status.SUCCEEDED || result == null || error != null) return;
        diffGutterJobId = -1;
        DiffGutterRequest request = result.request();
        if (request.generation() != diffGutterGeneration || request.buffer() != editor.getCurrentBuffer()
            || request.buffer().textSnapshot() != request.text() || editor.lineNumberPanel == null
            || request.buffer().getSavedContent() != request.savedContent()) return;
        diffGutterBuffer = request.buffer();
        incrementalDiffGutter = new IncrementalDiffGutter(request.savedContent(), result.saved(), request.text(), result.markers());
        editor.lineNumberPanel.updateDiffMarkers(result.markers());
    }

    void scheduleDiffGutter(FileBuffer buffer) {
        scheduleDiffGutter(buffer, null);
    }

    void scheduleDiffGutter(FileBuffer buffer, FileBuffer.DocumentTextChange change) {
        if (buffer == diffGutterBuffer && incrementalDiffGutter != null) {
            long started = System.nanoTime();
            if (incrementalDiffGutter.apply(change, buffer.getSavedContent())) {
                if (diffGutterTimer != null) diffGutterTimer.stop();
                pendingDiffGutterBuffer = null;
                editor.lineNumberPanel.updateDiffMarkers(incrementalDiffGutter.markers());
                editor.perfService.recordDuration("diff.gutter.incremental", started, buffer.getDisplayName());
                return;
            }
        }
        pendingDiffGutterBuffer = buffer;
        if (diffGutterTimer == null) {
            diffGutterTimer = new Timer(DIFF_GUTTER_DEBOUNCE_MS, event -> {
                FileBuffer pending = pendingDiffGutterBuffer;
                pendingDiffGutterBuffer = null;
                if (pending != null && pending == editor.getCurrentBuffer()) {
                    updateDiffGutter(pending);
                }
            });
            diffGutterTimer.setRepeats(false);
        }
        diffGutterTimer.setInitialDelay(buffer != null && buffer.textSnapshot().length() > 750_000
            ? LARGE_DIFF_GUTTER_DEBOUNCE_MS : DIFF_GUTTER_DEBOUNCE_MS);
        diffGutterTimer.restart();
    }

    private record DiffGutterRequest(FileBuffer buffer, VersionedTextSnapshot text, String savedContent, long generation) { }
    private record DiffGutterResult(DiffGutterRequest request, VersionedTextSnapshot saved,
                                    LineNumberPanel.DiffMarkers markers) { }


    void openFileChooser() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        editor.editorUiController.applyUiTheme(fileChooser);
        int result = fileChooser.showOpenDialog(editor);

        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            openFileAsync(file);
        }
    }


    void openEmptyBuffer() {
        FileBuffer empty = new FileBuffer((String) null, editor.configManager);
        if (editor.buffers.isEmpty()) editor.buffers.add(empty);
        else if (shouldReplaceSingleUntitledBuffer()) empty = editor.buffers.get(0);
        else editor.buffers.add(empty);
        loadBufferIntoEditor(empty);
        editor.writingArea.requestFocusInWindow();
    }


    public void openFile(File file) throws IOException {
        if (findBufferByPath(file) != getCurrentBuffer()) editor.recordJumpPosition();
        EditorPane activePane = editor.getActivePane();
        if (activePane != null) openRequests.put(activePane, ++openRequestSequence);
        persistCurrentBufferState();
        String projectConfigMessage = applyProjectConfigurationForFile(file);

        FileBuffer existing = findBufferByPath(file);
        if (existing != null) {
            loadBufferIntoEditor(existing);
            editor.showAlternateSurfaceIfAvailable(editor.getActivePane(), existing);
            if (projectConfigMessage != null && !projectConfigMessage.isEmpty()) {
                editor.showMessage(projectConfigMessage);
            }
            return;
        }

        FileBuffer buffer;
        if (file.exists()) {
            buffer = new FileBuffer(file, editor.configManager);
        } else {
            buffer = new FileBuffer(file.getAbsolutePath(), editor.configManager);
        }

        installLoadedBuffer(file, buffer, projectConfigMessage, editor.getActivePane());
    }

    void openFileAsync(File file) {
        openFileAsync(file, null);
    }

    void openFileAsync(File file, java.util.function.Consumer<IOException> completion) {
        if (findBufferByPath(file) != getCurrentBuffer()) editor.recordJumpPosition();
        persistCurrentBufferState();
        EditorPane targetPane = editor.getActivePane();
        long request = ++openRequestSequence;
        if (targetPane != null) openRequests.put(targetPane, request);
        String projectConfigMessage = applyProjectConfigurationForFile(file);
        FileBuffer existing = findBufferByPath(file);
        if (existing != null) {
            loadBufferIntoEditor(existing);
            editor.showAlternateSurfaceIfAvailable(editor.getActivePane(), existing);
            if (projectConfigMessage != null && !projectConfigMessage.isEmpty()) editor.showMessage(projectConfigMessage);
            if (completion != null) completion.accept(null);
            return;
        }
        editor.asyncJobService.submit("open file", token -> {
            long started = System.nanoTime();
            try {
                return file.exists() ? new FileBuffer(file, editor.configManager)
                    : new FileBuffer(file.getAbsolutePath(), editor.configManager);
            } finally {
                editor.perfService.recordDuration("file.open.load", started, "bytes=" + file.length());
            }
        }, (job, loaded, error) -> {
                if (editor.closingDown || job.getStatus() == AsyncJobService.Status.CANCELLED) return;
                if (targetPane != null && !java.util.Objects.equals(openRequests.get(targetPane), request)) return;
                if (error != null || loaded == null) {
                    IOException failure = error instanceof IOException io ? io
                        : new IOException(error == null ? "unknown error" : error.getMessage(), error);
                    editor.showMessage("Error opening file: " + failure.getMessage());
                    if (completion != null) completion.accept(failure);
                    return;
                }
                EditorPane pane = targetPane != null && editor.editorPanes.contains(targetPane)
                    ? targetPane : editor.getActivePane();
                long installStarted = System.nanoTime();
                installLoadedBuffer(file, loaded, projectConfigMessage, pane);
                editor.perfService.recordDuration("file.open.install", installStarted,
                    "chars=" + loaded.textSnapshot().length());
                if (completion != null) completion.accept(null);
            });
    }

    private void installLoadedBuffer(File file, FileBuffer buffer, String projectConfigMessage, EditorPane pane) {
        FileBuffer alreadyOpen = findBufferByPath(file);
        if (alreadyOpen != null) buffer = alreadyOpen;
        if (alreadyOpen == null && shouldReplaceSingleUntitledBuffer()) {
            editor.buffers.set(0, buffer);
        } else if (alreadyOpen == null) {
            editor.buffers.add(buffer);
        }
        restoreBufferIntoPane(pane, buffer);
        editor.showAlternateSurfaceIfAvailable(pane, buffer);
        editor.addToRecentFiles(file.getAbsolutePath());
        editor.registerFileWatch(buffer);
        editor.refreshGitGutter();
        if (buffer.isLargeFileUnavailable()) {
            editor.showMessage("Large-file unavailable: " + buffer.getLargeFileStatus());
        } else if (buffer.isShowingPreviewOnly()) {
            editor.showMessage("Large-file bounded preview loaded");
        } else if (projectConfigMessage != null && !projectConfigMessage.isEmpty()) {
            editor.showMessage(projectConfigMessage);
        }
    }


    String openFileInSplit(File file, EditorPane targetPane, WindowLayoutNode.Orientation orientation, boolean newPaneFirst) {
        if (file == null || !file.isFile()) {
            return "Drop a file from the Explorer onto an editor";
        }
        if (targetPane == null || !editor.editorPanes.contains(targetPane) || targetPane == editor.treePane) {
            return "No editor window available for the drop";
        }

        persistCurrentBufferState();
        applyProjectConfigurationForFile(file);
        FileBuffer existing = findBufferByPath(file);
        if (existing != null) return finishSplitOpen(file, targetPane, orientation, newPaneFirst, existing);
        editor.asyncJobService.submit("open file in split", token -> new FileBuffer(file, editor.configManager),
            (job, loaded, error) -> {
                if (editor.closingDown || job.getStatus() == AsyncJobService.Status.CANCELLED) return;
                if (error != null || loaded == null) {
                    editor.showMessage("Error opening file in split: " + (error == null ? "unknown error" : error.getMessage()));
                    return;
                }
                editor.showMessage(finishSplitOpen(file, targetPane, orientation, newPaneFirst, loaded));
            });
        return "Opening in split: " + file.getAbsolutePath();
    }

    private String finishSplitOpen(File file, EditorPane targetPane, WindowLayoutNode.Orientation orientation,
                                   boolean newPaneFirst, FileBuffer loaded) {
        if (!editor.editorPanes.contains(targetPane)) return "Target editor window was closed before the file opened";
        FileBuffer existing = findBufferByPath(file);
        FileBuffer targetBuffer = existing == null ? loaded : existing;
        EditorPane newPane = editor.createEditorPane(editor.getSize());
        editor.editorPanes.add(newPane);
        if (editor.windowLayoutRoot == null) editor.windowLayoutRoot = WindowLayoutNode.leaf(targetPane);
        if (!editor.windowLayoutRoot.splitLeaf(targetPane, newPane, orientation, newPaneFirst, 0.5)) {
            editor.editorPanes.remove(newPane);
            return "Unable to split the target window";
        }
        if (existing == null) {
            if (shouldReplaceSingleUntitledBuffer()) editor.buffers.set(0, targetBuffer);
            else editor.buffers.add(targetBuffer);
        }
        loadBufferIntoPane(newPane, targetBuffer, 0);
        editor.renderWindowLayout();
        editor.activateEditorPane(newPane);
        editor.showAlternateSurfaceIfAvailable(newPane, targetBuffer);
        newPane.getTextArea().requestFocusInWindow();
        editor.addToRecentFiles(file.getAbsolutePath());
        if (existing == null) {
            editor.registerFileWatch(targetBuffer);
            editor.refreshGitGutter();
        }
        return "Opened in split: " + file.getAbsolutePath();
    }


    private String applyProjectConfigurationForFile(File file) {
        boolean trustedForLocalExecution = editor.ensureProjectTrustForFile(file);
        if (trustedForLocalExecution) {
            String message = editor.configManager.applyProjectConfigForFile(file);
            if (message != null && !message.isEmpty()) {
                editor.applyRuntimeConfigFromSettings();
            }
            return message == null ? "" : message;
        }

        String cleared = editor.configManager.applyProjectConfigForFile(null);
        if (cleared != null && !cleared.isEmpty()) {
            editor.applyRuntimeConfigFromSettings();
        }
        return "Project local config blocked (untrusted project)";
    }


    public FileBuffer getCurrentBuffer() {
        EditorPane activePane = editor.getActivePane();
        if (activePane != null && activePane.getBuffer() != null) {
            return activePane.getBuffer();
        }
        if (editor.currentBufferIndex >= 0 && editor.currentBufferIndex < editor.buffers.size()) {
            return editor.buffers.get(editor.currentBufferIndex);
        }
        return null;
    }


    public JTextArea getTextArea() {
        return editor.writingArea;
    }


    public String nextBuffer() {
        if (editor.buffers.isEmpty()) {
            return "No buffers open";
        }

        editor.currentBufferIndex = Math.max(0, editor.buffers.indexOf(getCurrentBuffer()));
        int nextIndex = (editor.currentBufferIndex + 1) % editor.buffers.size();
        switchToBuffer(nextIndex);
        return "Buffer " + (editor.currentBufferIndex + 1) + " of " + editor.buffers.size();
    }


    public String prevBuffer() {
        if (editor.buffers.isEmpty()) {
            return "No buffers open";
        }

        editor.currentBufferIndex = Math.max(0, editor.buffers.indexOf(getCurrentBuffer()));
        int prevIndex = editor.currentBufferIndex - 1;
        if (prevIndex < 0) {
            prevIndex = editor.buffers.size() - 1;
        }
        switchToBuffer(prevIndex);
        return "Buffer " + (editor.currentBufferIndex + 1) + " of " + editor.buffers.size();
    }

    public String deleteBuffer(boolean force) {
        if (editor.buffers.isEmpty()) {
            return "No buffers to delete";
        }

        FileBuffer buffer = getCurrentBuffer();
        if (DocumentLifecycle.needsDiscardConfirmation(buffer, force) && editor.buffers.size() > 1) {
            return "Error: No write since last change (use :bd! to override)";
        }

        if (editor.buffers.size() == 1) {
            if (DocumentLifecycle.needsDiscardConfirmation(buffer, force)) {
                int result = editor.confirmDiscardChanges("Close the last buffer and quit anyway?");
                if (!DocumentLifecycle.discardConfirmed(result)) {
                    return "Buffer close cancelled";
                }
            }
            editor.closeTerminalSession(buffer);
            editor.closeEditor();
            return "Last buffer closed";
        }

        editor.currentBufferIndex = Math.max(0, editor.buffers.indexOf(buffer));
        if (editor.isTreeBuffer(buffer)) {
            editor.treeLineTargets.remove(buffer);
            if (buffer == editor.treeBuffer) {
                editor.treeBuffer = null;
            }
        }
        if (buffer == editor.quickfixBuffer) {
            editor.quickfixBuffer = null;
        }
        editor.closeTerminalSession(buffer);
        editor.buffers.remove(buffer);
        editor.persistRecoverySnapshotsSafely();
        if (editor.buffers.isEmpty()) {
            openEmptyBuffer();
            return "Buffer deleted";
        }
        FileBuffer replacement = editor.buffers.get(Math.min(editor.currentBufferIndex, editor.buffers.size() - 1));
        for (EditorPane pane : new ArrayList<>(editor.editorPanes)) {
            if (pane.getBuffer() == buffer) {
                if (!editor.editorPanes.contains(pane)) continue;
                restoreBufferIntoPane(pane, replacement);
            }
        }
        return "Buffer deleted";
    }


    void switchToBuffer(int index) {
        if (index < 0 || index >= editor.buffers.size()) {
            return;
        }
        EditorPane pane = editor.getActivePane();
        if (pane != null) openRequests.put(pane, ++openRequestSequence);

        FileBuffer newBuffer = editor.buffers.get(index);
        if (newBuffer != getCurrentBuffer()) editor.recordJumpPosition();
        persistCurrentBufferState();
        loadBufferIntoEditor(newBuffer);
    }


    public String splitWindow(boolean vertical) {
        EditorPane activePane = editor.getActivePane();
        FileBuffer currentBuffer = getCurrentBuffer();
        if (activePane == null || currentBuffer == null) {
            return "No active window";
        }

        Dimension size = editor.getSize();
        EditorPane newPane = editor.createEditorPane(size);
        editor.editorPanes.add(newPane);
        WindowLayoutNode.Orientation orientation = vertical ? WindowLayoutNode.Orientation.HORIZONTAL : WindowLayoutNode.Orientation.VERTICAL;
        if (editor.windowLayoutRoot == null) {
            editor.windowLayoutRoot = WindowLayoutNode.leaf(activePane);
        }
        editor.windowLayoutRoot.splitLeaf(activePane, newPane, orientation, false, 0.5);
        loadBufferIntoPane(newPane, currentBuffer, editor.writingArea.getCaretPosition());
        editor.renderWindowLayout();
        editor.activateEditorPane(newPane);
        newPane.getTextArea().requestFocusInWindow();
        return vertical ? "Vertical split created" : "Horizontal split created";
    }


    public String closeActiveWindow() {
        return closePane(editor.getActivePane());
    }


    boolean canCloseActiveWindow() {
        EditorPane pane = editor.getActivePane();
        if (pane == null || editor.hasMarkdownPreviewForSource(pane)) {
            return false;
        }
        return pane.isMarkdownPreview() || pane.isHiddenByFocusMode() || visiblePaneCount() > 1;
    }


    String closeActiveBufferWithoutExiting(boolean force) {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer == null) {
            return "No active buffer";
        }
        if (editor.buffers.size() > 1) {
            return deleteBuffer(force);
        }
        if (DocumentLifecycle.needsDiscardConfirmation(buffer, force)) {
            int result = editor.confirmDiscardChanges("Close the last buffer anyway?");
            if (!DocumentLifecycle.discardConfirmed(result)) {
                return "Buffer close cancelled";
            }
        }

        if (editor.isTreeBuffer(buffer)) {
            editor.treeLineTargets.remove(buffer);
            if (buffer == editor.treeBuffer) {
                editor.treeBuffer = null;
            }
            editor.treePane = null;
        }
        if (buffer == editor.quickfixBuffer) {
            editor.quickfixBuffer = null;
        }
        editor.closeTerminalSession(buffer);
        editor.buffers.remove(buffer);
        editor.currentBufferIndex = 0;
        editor.persistRecoverySnapshotsSafely();
        openEmptyBuffer();
        return "Buffer closed";
    }


    String closePane(EditorPane paneToClose) {
        if (paneToClose == null) {
            return "No active window";
        }
        if (editor.hasMarkdownPreviewForSource(paneToClose)) {
            return "Close Markdown preview first (:markdown close)";
        }
        if (!paneToClose.isMarkdownPreview() && !paneToClose.isHiddenByFocusMode() && visiblePaneCount() <= 1) {
            return "Cannot close the only window";
        }

        EditorPane previouslyActive = editor.getActivePane();
        FileBuffer closingBuffer = paneToClose.getBuffer();
        if (paneToClose == editor.treePane) {
            editor.treePane = null;
        }
        if (paneToClose.getBuffer() == editor.quickfixBuffer) {
            editor.quickfixBuffer = null;
        }
        editor.closeTerminalSession(closingBuffer);
        paneToClose.closeTerminalPane();
        editor.detachMarkdownPreview(paneToClose);
        if (editor.isTreeBuffer(closingBuffer)) {
            editor.treeLineTargets.remove(closingBuffer);
            editor.buffers.remove(closingBuffer);
            if (closingBuffer == editor.treeBuffer) {
                editor.treeBuffer = null;
            }
        }

        detachActiveDocumentListener();
        editor.editorPanes.remove(paneToClose);
        editor.windowLayoutRoot = editor.windowLayoutRoot == null ? null : editor.windowLayoutRoot.removeLeaf(paneToClose);
        if (editor.windowLayoutRoot == null && !editor.editorPanes.isEmpty()) {
            editor.windowLayoutRoot = WindowLayoutNode.leaf(editor.editorPanes.get(0));
        }
        editor.renderWindowLayout();

        EditorPane nextActive = null;
        if (previouslyActive != null && previouslyActive != paneToClose && editor.editorPanes.contains(previouslyActive)) {
            nextActive = previouslyActive;
        } else {
            for (EditorPane pane : editor.editorPanes) {
                if (!pane.isHiddenByFocusMode()) {
                    nextActive = pane;
                    break;
                }
            }
        }

        if (nextActive != null) {
            editor.activePaneIndex = Math.max(0, editor.editorPanes.indexOf(nextActive));
            editor.bindActivePane(nextActive);
            attachActiveDocumentListener();
            editor.updateCurrentLineHighlight();
            editor.refreshLineNumberPanel();
            editor.updateStatusBar();
            editor.requestActivePaneFocus();
        }
        return "Window closed";
    }


    public String cycleWindowFocus() {
        return cycleWindowFocus(1);
    }


    public String cycleWindowFocus(int direction) {
        if (visiblePaneCount() <= 1) {
            return "Only one window";
        }
        int nextIndex = editor.activePaneIndex;
        do {
            nextIndex = Math.floorMod(nextIndex + (direction < 0 ? -1 : 1), editor.editorPanes.size());
        } while (editor.editorPanes.get(nextIndex).isHiddenByFocusMode());
        editor.activateEditorPane(editor.editorPanes.get(nextIndex));
        editor.requestActivePaneFocus();
        return "Window focus changed";
    }


    public String resizeActiveWindow(double delta) {
        if (editor.windowLayoutRoot == null || editor.windowLayoutRoot.isLeaf()) return "Only one window";
        EditorPane activePane = editor.getActivePane();
        if (activePane == null) return "No active window";
        if (editor.windowLayoutRoot.adjustRatio(activePane, delta)) {
            editor.renderWindowLayout();
            return "Window resized";
        }
        return "Cannot resize further";
    }


    public String equalizeWindows() {
        if (editor.windowLayoutRoot == null) {
            return "No windows to equalize";
        }
        editor.windowLayoutRoot.equalize();
        editor.renderWindowLayout();
        return "Windows equalized";
    }


    public String focusWindowDirection(int dx, int dy) {
        if (visiblePaneCount() <= 1) {
            return "Only one window";
        }
        EditorPane activePane = editor.getActivePane();
        if (activePane == null) {
            return "No active window";
        }

        WindowLayoutNode.Direction direction = toLayoutDirection(dx, dy);
        List<EditorPane> candidates = editor.windowLayoutRoot == null ? List.of() : editor.windowLayoutRoot.findNeighborCandidates(activePane, direction);
        if (candidates.isEmpty()) {
            return "No window in that direction";
        }

        Rectangle activeBounds = paneBounds(activePane);
        EditorPane bestPane = null;
        double bestScore = Double.MAX_VALUE;

        for (EditorPane pane : candidates) {
            if (pane.isHiddenByFocusMode()) continue;
            Rectangle candidateBounds = paneBounds(pane);
            double score = directionalAlignmentScore(activeBounds, candidateBounds, direction);
            if (score < bestScore) {
                bestScore = score;
                bestPane = pane;
            }
        }

        if (bestPane == null) {
            return "No window in that direction";
        }

        editor.activateEditorPane(bestPane);
        editor.requestActivePaneFocus();
        return "Window focus changed";
    }


    WindowLayoutNode.Direction toLayoutDirection(int dx, int dy) {
        if (dx < 0) {
            return WindowLayoutNode.Direction.LEFT;
        }
        if (dx > 0) {
            return WindowLayoutNode.Direction.RIGHT;
        }
        if (dy < 0) {
            return WindowLayoutNode.Direction.UP;
        }
        return WindowLayoutNode.Direction.DOWN;
    }

    private int visiblePaneCount() {
        int count = 0;
        for (EditorPane pane : editor.editorPanes) {
            if (!pane.isHiddenByFocusMode()) count++;
        }
        return count;
    }


    double directionalAlignmentScore(Rectangle activeBounds, Rectangle candidateBounds, WindowLayoutNode.Direction direction) {
        double axisDistance;
        double orthogonalDistance;
        switch (direction) {
            case LEFT:
                axisDistance = activeBounds.getX() - candidateBounds.getMaxX();
                orthogonalDistance = Math.abs(activeBounds.getCenterY() - candidateBounds.getCenterY());
                break;
            case RIGHT:
                axisDistance = candidateBounds.getX() - activeBounds.getMaxX();
                orthogonalDistance = Math.abs(activeBounds.getCenterY() - candidateBounds.getCenterY());
                break;
            case UP:
                axisDistance = activeBounds.getY() - candidateBounds.getMaxY();
                orthogonalDistance = Math.abs(activeBounds.getCenterX() - candidateBounds.getCenterX());
                break;
            case DOWN:
            default:
                axisDistance = candidateBounds.getY() - activeBounds.getMaxY();
                orthogonalDistance = Math.abs(activeBounds.getCenterX() - candidateBounds.getCenterX());
                break;
        }
        if (axisDistance < 0) {
            axisDistance = 0;
        }
        return axisDistance * 1000.0 + orthogonalDistance;
    }


    Rectangle paneBounds(EditorPane pane) {
        return SwingUtilities.convertRectangle(
            pane.getScrollPane().getParent(),
            pane.getScrollPane().getBounds(),
            editor.editorHostPanel
        );
    }


    FileBuffer findBufferByPath(File file) {
        if (file == null) {
            return null;
        }
        String targetPath = file.getAbsolutePath();
        for (FileBuffer buffer : editor.buffers) {
            if (buffer.hasFilePath() && targetPath.equals(buffer.getFilePath())) {
                return buffer;
            }
        }
        return null;
    }


    boolean shouldReplaceSingleUntitledBuffer() {
        if (editor.buffers.size() != 1) {
            return false;
        }
        FileBuffer current = editor.buffers.get(0);
        return !current.isScratch() && !current.hasFilePath() && !current.isModified()
            && current.getContent().isEmpty();
    }

}
