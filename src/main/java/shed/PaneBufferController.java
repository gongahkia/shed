package shed;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

final class PaneBufferController {
    private static final int BACKUP_IDLE_DEBOUNCE_MS = 750;
    private static final int DIFF_GUTTER_DEBOUNCE_MS = 120;
    private final Texteditor editor;
    private final Map<FileBuffer, Timer> backupTimers = new IdentityHashMap<>();
    private Timer diffGutterTimer;
    private FileBuffer pendingDiffGutterBuffer;

    PaneBufferController(Texteditor editor) {
        this.editor = editor;
    }

    void handleDocumentChange() {
        if (!editor.suppressDocumentEvents) {
            markModified();
            editor.updateCurrentLineHighlight();
            editor.scheduleSyntaxHighlighting();
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
        loadBufferIntoPane(editor.getActivePane(), buffer, 0);
    }


    void loadBufferIntoPane(EditorPane pane, FileBuffer buffer, int caretPosition) {
        if (pane == null || buffer == null) {
            return;
        }

        boolean activePane = pane == editor.getActivePane();
        if (activePane) {
            detachActiveDocumentListener();
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
        if (replacedTerminalPane) {
            editor.renderWindowLayout();
        }

        if (activePane) {
            editor.bindActivePane(pane);
            attachActiveDocumentListener();
            editor.undoManager = buffer.getUndoManager();
            editor.currentBufferIndex = editor.buffers.indexOf(buffer);
            editor.registerManager.updateFilename(buffer.getFilePath());
            editor.updateCurrentLineHighlight();
            editor.applySyntaxHighlighting();
            editor.refreshLineNumberPanel();
            editor.syncLspOpen(buffer);
            editor.updateStatusBar();
        } else {
            pane.getLineNumberPanel().repaint();
        }
    }


    void persistCurrentBufferState() {
        EditorPane pane = editor.getActivePane();
        if (pane != null) {
            pane.setBuffer(getCurrentBuffer());
        }
    }


    void markModified() {
        FileBuffer buffer = getCurrentBuffer();
        if (buffer != null && buffer.isLargeFile()) {
            return;
        }
        if (buffer != null) {
            buffer.setModified(true);
            editor.invalidateGitBlame(buffer);
            scheduleIdleBackup(buffer);
            editor.recordChangePosition();
            editor.syncLspChange(buffer);
            editor.scheduleDiffGutter(buffer);
            editor.requestStatusBarRefresh();
            editor.scheduleRecoverySnapshotCapture();
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
        long started = System.nanoTime();
        if (editor.lineNumberPanel != null && buffer != null) {
            editor.lineNumberPanel.updateDiffMarkers(buffer.getSavedContent(), editor.writingArea.getText());
        }
        if (editor.perfService != null) {
            editor.perfService.recordDuration("diff.gutter", started, buffer == null ? "no-buffer" : buffer.getDisplayName());
        }
    }

    void scheduleDiffGutter(FileBuffer buffer) {
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
        diffGutterTimer.restart();
    }


    void openFileChooser() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        int result = fileChooser.showOpenDialog(editor);

        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try {
                openFile(file);
            } catch (Exception e) {
                editor.showMessage("Error opening file: " + e.getMessage());
            }
        }
    }


    void openLandingPage() {
        StringBuilder builder = new StringBuilder();
        builder.append("shed ").append(editor.VERSION).append("\n");
        builder.append("swing modal editor\n\n");
        builder.append(":help        view help\n");
        builder.append(":e <file>    open a file\n");
        builder.append(":recent      show recent files\n");
        builder.append(":ls          list open buffers\n\n");
        builder.append("note: this is a scratch buffer and can be edited.\n");

        FileBuffer landing = FileBuffer.createScratch("[landing]", builder.toString());
        if (editor.buffers.isEmpty()) {
            editor.buffers.add(landing);
        } else {
            editor.buffers.set(0, landing);
        }
        loadBufferIntoEditor(landing);
    }


    public void openFile(File file) throws IOException {
        persistCurrentBufferState();
        boolean trustedForLocalExecution = editor.ensureProjectTrustForFile(file);
        String projectConfigMessage = "";
        if (trustedForLocalExecution) {
            projectConfigMessage = editor.configManager.applyProjectConfigForFile(file);
            if (projectConfigMessage != null && !projectConfigMessage.isEmpty()) {
                editor.applyRuntimeConfigFromSettings();
            }
        } else {
            projectConfigMessage = "Project local config/plugins blocked (untrusted project)";
            String cleared = editor.configManager.applyProjectConfigForFile(null);
            if (cleared != null && !cleared.isEmpty()) {
                editor.applyRuntimeConfigFromSettings();
            }
        }

        FileBuffer existing = findBufferByPath(file);
        if (existing != null) {
            loadBufferIntoEditor(existing);
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

        if (shouldReplaceSingleLandingBuffer()) {
            editor.buffers.set(0, buffer);
        } else {
            editor.buffers.add(buffer);
        }
        loadBufferIntoEditor(buffer);
        editor.addToRecentFiles(file.getAbsolutePath());
        editor.registerFileWatch(buffer);
        editor.firePluginEvent("BufOpen");
        editor.refreshGitGutter();
        if (buffer.isLargeFileUnavailable()) {
            editor.showMessage("Large-file unavailable: " + buffer.getLargeFileStatus());
        } else if (buffer.isShowingPreviewOnly()) {
            editor.showMessage("Large-file bounded preview loaded");
        } else if (projectConfigMessage != null && !projectConfigMessage.isEmpty()) {
            editor.showMessage(projectConfigMessage);
        }
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


    public String listBuffers() {
        if (editor.buffers.isEmpty()) {
            return "No buffers open";
        }

        StringBuilder list = new StringBuilder("Buffers:\n");
        for (int i = 0; i < editor.buffers.size(); i++) {
            FileBuffer buf = editor.buffers.get(i);
            list.append(i + 1).append(": ").append(buf.getDisplayName());
            if (buf.isModified()) {
                list.append(" [+]");
            }
            if (i == editor.currentBufferIndex) {
                list.append(" (current)");
            }
            list.append("\n");
        }

        editor.showBufferListDialog(list.toString());
        return "";
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
            openLandingPage();
            return "Buffer deleted";
        }
        FileBuffer replacement = editor.buffers.get(Math.min(editor.currentBufferIndex, editor.buffers.size() - 1));
        for (EditorPane pane : editor.editorPanes) {
            if (pane.getBuffer() == buffer) {
                loadBufferIntoPane(pane, replacement, 0);
            }
        }
        return "Buffer deleted";
    }


    void switchToBuffer(int index) {
        if (index < 0 || index >= editor.buffers.size()) {
            return;
        }

        persistCurrentBufferState();

        FileBuffer newBuffer = editor.buffers.get(index);
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
        double startRatio = editor.dramaticPanelAnimationsEnabled && editor.dramaticMotionAllowed() ? 0.12 : 0.5;
        editor.windowLayoutRoot.splitLeaf(activePane, newPane, orientation, false, startRatio);
        loadBufferIntoPane(newPane, currentBuffer, editor.writingArea.getCaretPosition());
        editor.renderWindowLayout();
        editor.animateSplitForPane(newPane, startRatio, 0.5);
        editor.activateEditorPane(newPane);
        editor.flashPaneJump(newPane);
        editor.animateEditorHostTint(editor.configManager.getCommandColor());
        newPane.getTextArea().requestFocusInWindow();
        return vertical ? "Vertical split created" : "Horizontal split created";
    }


    public String closeActiveWindow() {
        return closePane(editor.getActivePane());
    }


    String closePane(EditorPane paneToClose) {
        if (editor.editorPanes.size() <= 1) {
            return "Cannot close the only window";
        }
        if (paneToClose == null) {
            return "No active window";
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
        } else if (!editor.editorPanes.isEmpty()) {
            nextActive = editor.editorPanes.get(0);
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
        if (editor.editorPanes.size() <= 1) {
            return "Only one window";
        }
        int nextIndex = (editor.activePaneIndex + 1) % editor.editorPanes.size();
        editor.activateEditorPane(editor.editorPanes.get(nextIndex));
        editor.flashPaneJump(editor.getActivePane());
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
        if (editor.editorPanes.size() <= 1) {
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
        editor.flashPaneJump(bestPane);
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


    boolean shouldReplaceSingleLandingBuffer() {
        if (editor.buffers.size() != 1) {
            return false;
        }
        FileBuffer current = editor.buffers.get(0);
        return current.isScratch() && "[landing]".equals(current.getDisplayName()) && !current.isModified();
    }

}
