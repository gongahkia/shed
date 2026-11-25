package shed;

import javax.swing.*;
import javax.swing.Timer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

final class RecoveryController {
    private static final int SNAPSHOT_CAPTURE_DEBOUNCE_MS = 750;
    private final Texteditor editor;
    private final RecoveryJournalScheduler recoveryJournalScheduler;
    private boolean recoveryJournalDeferred;
    private boolean recoveryJournalDiscardConfirmed;
    private boolean recoveryJournalLoading;
    private int lastWarnedDroppedEntries = -1;
    private Timer snapshotCaptureTimer;
    private final Map<FileBuffer, FileWatcherService.WatchRegistration> fileWatches = new IdentityHashMap<>();
    // acknowledgment only; this never authorizes a save over the disk version
    private final Map<FileBuffer, ConflictObservation> observedConflicts = new IdentityHashMap<>();
    private record ConflictObservation(FileBuffer.ExternalFileState state,Object key,Object modified,Object created,long size) { }
    private static ConflictObservation observeConflict(FileBuffer buffer,FileBuffer.ExternalFileState state) {
        try {
            var attributes=Files.readAttributes(buffer.getFile().toPath(),java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return new ConflictObservation(state,attributes.fileKey(),attributes.lastModifiedTime(),attributes.creationTime(),attributes.size());
        } catch(java.nio.file.NoSuchFileException missing) { return new ConflictObservation(state,null,null,null,0); }
        catch(IOException unavailable) { return null; }
    }

    RecoveryController(Texteditor editor) {
        this.editor = editor;
        this.recoveryJournalScheduler = new RecoveryJournalScheduler(
            snapshot -> {
                if (snapshot.entries().isEmpty()) {
                    RecoveryJournal.clear(editor.recoveryStoreDir.toPath());
                } else {
                    RecoveryJournal.Journal journal = RecoveryJournal.write(editor.recoveryStoreDir.toPath(), snapshot.workspace(), snapshot.entries(),
                        editor.configManager.getRecoveryRetentionPolicy());
                    int dropped = journal.retention().droppedEntries();
                    if (dropped != lastWarnedDroppedEntries) {
                        lastWarnedDroppedEntries = dropped;
                        if (dropped > 0) {
                            SwingUtilities.invokeLater(() -> editor.showMessage("Recovery warning: " + dropped
                                + " unsaved buffer(s) exceed journal limits. Save them to disk or raise recovery retention limits."));
                        }
                    }
                }
            },
            () -> RecoveryJournal.clear(editor.recoveryStoreDir.toPath()),
            new RecoveryJournalScheduler.Observer() {
                @Override
                public void onWrite(long startedAtNanos, RecoveryJournalScheduler.Snapshot snapshot) {
                    if (editor.perfService != null) {
                        editor.perfService.recordDuration("recovery.journal.write", startedAtNanos,
                            "entries=" + snapshot.entries().size());
                    }
                }

                @Override
                public void onFailure(Exception error) {
                    editor.errorReporter.report(error, "recovery-journal", "writing the local recovery journal",
                        "docs/RECOVERY_JOURNAL.md#read-and-write-semantics");
                }
            }
        );
        this.recoveryJournalDeferred = false;
        this.recoveryJournalDiscardConfirmed = false;
    }

    void checkForExternalChanges() {
        if (editor.reloadPromptActive) {
            return;
        }
        int autoReloaded = 0;
        int retainedAfterDeletion = 0;
        int unsupportedExternalTargets = 0;
        // conflict dialogs run a nested event loop and may open or close buffers
        for (FileBuffer buffer : new ArrayList<>(editor.buffers)) {
            if (buffer == null || !editor.buffers.contains(buffer) || !buffer.hasFilePath()) {
                continue;
            }
            FileBuffer.ExternalFileState state = buffer.getExternalFileState();
            if (state == FileBuffer.ExternalFileState.UNCHANGED) {
                observedConflicts.remove(buffer);
                continue;
            }
            editor.markdownLinksChanged(buffer, true);
            if (!buffer.isModified()) {
                observedConflicts.remove(buffer);
                if (state == FileBuffer.ExternalFileState.EXTERNALLY_CHANGED
                    || state == FileBuffer.ExternalFileState.REPLACED) {
                    try {
                        reloadPreservingViews(buffer);
                        autoReloaded++;
                    } catch (IOException e) {
                        editor.showMessage("Reload failed: " + e.getMessage());
                    }
                } else {
                    buffer.refreshExternalTimestamp();
                    if (state == FileBuffer.ExternalFileState.DELETED) {
                        retainedAfterDeletion++;
                    } else {
                        unsupportedExternalTargets++;
                    }
                }
                continue;
            }
            var observation=observeConflict(buffer,state);
            if(observation!=null&&observation.equals(observedConflicts.get(buffer)))continue;
            if(observation!=null)observedConflicts.put(buffer,observation);
            try { promptExternalConflictForModifiedBuffer(buffer, state); }
            catch(RuntimeException failed) { observedConflicts.remove(buffer);throw failed; }
        }
        if (!hasModifiedSettingsBuffer()) {
            String configReload = editor.reloadConfigIfChanged();
            if (configReload != null) {
                editor.showMessage(configReload);
                return;
            }
        }
        if (autoReloaded > 0) {
            SwingUtilities.invokeLater(() -> {if(!editor.closingDown&&editor.toolWindowHost!=null)editor.toolWindowHost.observedSourceReady();});
            editor.showMessage("Auto-reloaded " + autoReloaded + " externally changed buffer" + (autoReloaded == 1 ? "" : "s"));
        } else if (retainedAfterDeletion > 0) {
            editor.showMessage("Retained " + retainedAfterDeletion + " buffer" + (retainedAfterDeletion == 1 ? "" : "s")
                + " after external deletion");
        } else if (unsupportedExternalTargets > 0) {
            editor.showMessage("Retained " + unsupportedExternalTargets + " buffer" + (unsupportedExternalTargets == 1 ? "" : "s")
                + " with unsupported external target");
        }
    }

    private void reloadPreservingViews(FileBuffer buffer) throws IOException {
        var views = new IdentityHashMap<EditorPane, BufferViewState>();
        for (var pane : editor.editorPanes)
            if (pane.getBuffer() == buffer && !buffer.isLargeFile() && pane.getTextArea().getDocument() == buffer.getDocument())
                views.put(pane, BufferViewState.capture(pane.getTextArea(), pane.getScrollPane()));
        String before = buffer.getContent();
        var followed=editor.toolWindowHost==null?null:editor.toolWindowHost.followedAgentSource(buffer);
        var handoff=followed==null?null:AgentSourceHandoff.capture(followed.getTextArea());
        int caret = buffer == editor.getCurrentBuffer() ? editor.writingArea.getCaretPosition() : 0;
        try {
            editor.withSuppressedDocumentEvents(() -> {
                try {
                    buffer.load(editor.configManager);
                    if (buffer == editor.getCurrentBuffer()) {
                        editor.loadBufferIntoEditor(buffer);
                        editor.writingArea.setCaretPosition(Math.min(caret, editor.writingArea.getText().length()));
                    }
                    for (var entry : views.entrySet()) {
                        var pane = entry.getKey();
                        if (pane.getBuffer() != buffer || pane.getTextArea().getDocument() != buffer.getDocument()) continue;
                        entry.getValue().restoreSelectionAfterChanges(pane.getTextArea(), before, buffer.getContent());
                        entry.getValue().restoreViewport(pane.surface());
                    }
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            if(handoff!=null&&!before.equals(buffer.getContent()))handoff.install();
            else if(handoff!=null)handoff.close();
        } catch (UncheckedIOException failure) { if(handoff!=null)handoff.close();throw failure.getCause(); }
        catch(RuntimeException failure){if(handoff!=null)handoff.close();throw failure;}
    }

    private boolean hasModifiedSettingsBuffer() {
        for (FileBuffer buffer : editor.buffers) {
            if (buffer != null && buffer.isModified() && editor.isSettingsFile(buffer.getFile())) {
                return true;
            }
        }
        return false;
    }


    void promptExternalConflictForModifiedBuffer(FileBuffer buffer) {
        if (buffer == null) {
            return;
        }
        promptExternalConflictForModifiedBuffer(buffer, buffer.getExternalFileState());
    }

    private void promptExternalConflictForModifiedBuffer(FileBuffer buffer, FileBuffer.ExternalFileState state) {
        if (buffer == null || buffer.getFile() == null) {
            return;
        }
        editor.reloadPromptActive = true;
        String[] options = {"Keep Mine", "Reload Theirs", "View Both", "Save Mine As"};
        int result = JOptionPane.showOptionDialog(
            editor,
            externalStateDescription(state) + " while modified in editor:\n"
                + buffer.getDisplayName()
                + "\nChoose how to resolve this conflict.",
            "External Change Conflict",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        );
        editor.reloadPromptActive = false;

        if (result == 1) {
            if (!canReloadFromDisk(state)) {
                editor.showMessage("Cannot reload: " + externalStateDescription(state) + "; dirty buffer retained");
                return;
            }
            try {
                int caret = buffer == editor.getCurrentBuffer() ? editor.writingArea.getCaretPosition() : 0;
                buffer.load(editor.configManager);
                if (buffer == editor.getCurrentBuffer()) {
                    editor.loadBufferIntoEditor(buffer);
                    editor.writingArea.setCaretPosition(Math.min(caret, editor.writingArea.getText().length()));
                }
                editor.showMessage("Reloaded from disk");
            } catch (IOException e) {
                editor.showMessage("Reload failed: " + e.getMessage());
            }
            return;
        }
        if (result == 2) {
            showExternalConflictPreview(buffer, state);
            return;
        }
        if (result == 3) {
            saveConflictAs(buffer);
            return;
        }
        if (result == 0) {
            try {
                buffer.acceptExternalVersionForOverwrite();
                editor.showMessage("Keeping your buffer; the next save will replace the reviewed disk version");
            } catch (IOException error) {
                editor.showMessage("Could not accept external version: " + error.getMessage());
            }
        }
    }

    private void saveConflictAs(FileBuffer buffer) {
        JFileChooser chooser = new JFileChooser();
        File source = buffer.getFile();
        File parent = source == null ? null : source.getAbsoluteFile().getParentFile();
        if (parent != null && parent.isDirectory()) {
            chooser.setCurrentDirectory(parent);
        }
        String name = source == null ? "conflict-copy" : source.getName() + ".conflict";
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), name));
        chooser.setDialogTitle("Save My Conflict Copy");
        editor.editorUiController.applyUiTheme(chooser);
        if (chooser.showSaveDialog(editor) != JFileChooser.APPROVE_OPTION) {
            editor.showMessage("Save As cancelled; dirty buffer retained");
            return;
        }

        File target = chooser.getSelectedFile().getAbsoluteFile();
        if (target.exists() && JOptionPane.showConfirmDialog(editor,
            "Replace the selected file?\n" + target.getAbsolutePath(), "Confirm Save As",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            editor.showMessage("Save As cancelled; dirty buffer retained");
            return;
        }
        try {
            saveConflictAs(buffer, target);
        } catch (IOException error) {
            editor.showMessage("Save As failed; dirty buffer retained: " + error.getMessage());
        }
    }

    void saveConflictAs(FileBuffer buffer, File target) throws IOException {
        if (buffer == null || target == null) {
            throw new IOException("Conflict buffer and save target are required");
        }
        int caret = buffer == editor.getCurrentBuffer() ? editor.writingArea.getCaretPosition() : 0;
        buffer.saveAs(target);
        editor.registerFileWatch(buffer);
        editor.addToRecentFiles(target.getAbsolutePath());
        editor.notifyCurrentBufferSaved();
        if (buffer == editor.getCurrentBuffer()) {
            editor.loadBufferIntoEditor(buffer);
            editor.writingArea.setCaretPosition(Math.min(caret, editor.writingArea.getText().length()));
        }
        editor.refreshGitGutter();
        editor.showMessage("Saved conflict copy as " + target.getAbsolutePath());
    }


    void showExternalConflictPreview(FileBuffer buffer) {
        if (buffer == null) {
            return;
        }
        showExternalConflictPreview(buffer, buffer.getExternalFileState());
    }

    private void showExternalConflictPreview(FileBuffer buffer, FileBuffer.ExternalFileState state) {
        if (buffer == null || buffer.getFile() == null) {
            return;
        }
        if (!canReloadFromDisk(state)) {
            String preview = "External Conflict Preview\nFile: " + buffer.getFilePath() + "\n\n===== YOUR BUFFER =====\n"
                + buffer.getContent() + "\n===== EXTERNAL STATE =====\n" + externalStateDescription(state)
                + "\nNo disk version is available. Your buffer was retained.\n";
            editor.showScratchBuffer("[external conflict] " + buffer.getDisplayName(), preview);
            return;
        }
        try {
            String disk = Files.readString(buffer.getFile().toPath(), StandardCharsets.UTF_8);
            StringBuilder preview = new StringBuilder();
            preview.append("External Conflict Preview\n");
            preview.append("File: ").append(buffer.getFilePath()).append("\n\n");
            preview.append("===== YOUR BUFFER =====\n");
            preview.append(buffer.getContent()).append("\n");
            preview.append("===== DISK VERSION =====\n");
            preview.append(disk).append("\n");
            preview.append("Tip: copy needed parts, then save.\n");
            editor.showScratchBuffer("[external conflict] " + buffer.getDisplayName(), preview.toString());
        } catch (IOException e) {
            editor.showMessage("Conflict preview failed: " + e.getMessage());
        }
    }

    private boolean canReloadFromDisk(FileBuffer.ExternalFileState state) {
        return state == FileBuffer.ExternalFileState.EXTERNALLY_CHANGED || state == FileBuffer.ExternalFileState.REPLACED;
    }

    private String externalStateDescription(FileBuffer.ExternalFileState state) {
        return switch (state) {
            case EXTERNALLY_CHANGED -> "File changed on disk";
            case DELETED -> "File was deleted externally";
            case REPLACED -> "File was replaced externally";
            case UNSUPPORTED -> "File has an unsupported external target";
            case UNCHANGED -> "File is unchanged";
        };
    }


    void startRecoverySnapshotTimer() {
        if (editor.recoverySnapshotTimer != null) {
            editor.recoverySnapshotTimer.stop();
            editor.recoverySnapshotTimer = null;
        }
    }


    void persistRecoverySnapshotsSafely() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::persistRecoverySnapshotsSafely);
            return;
        }
        try {
            persistRecoverySnapshots();
        } catch (Exception error) {
            editor.errorReporter.report(error, "recovery-journal", "capturing recovery journal input",
                "docs/RECOVERY_JOURNAL.md#read-and-write-semantics");
        }
    }

    void scheduleRecoverySnapshotCapture() {
        if (snapshotCaptureTimer == null) {
            snapshotCaptureTimer = new Timer(SNAPSHOT_CAPTURE_DEBOUNCE_MS, event -> persistRecoverySnapshotsSafely());
            snapshotCaptureTimer.setRepeats(false);
        }
        snapshotCaptureTimer.restart();
    }

    void flushScheduledRecoverySnapshotCapture() {
        if (snapshotCaptureTimer != null && snapshotCaptureTimer.isRunning()) {
            snapshotCaptureTimer.stop();
            persistRecoverySnapshotsSafely();
        }
    }


    void persistRecoverySnapshots() throws IOException {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IOException("recovery journal capture must run on the EDT");
        }
        if (editor.recoveryStoreDir == null || recoveryJournalDeferred || recoveryJournalLoading) {
            return;
        }
        List<RecoveryInput> inputs = captureRecoveryJournalInputs();
        recoveryJournalScheduler.requestLazy(captureRecoveryJournalWorkspace(), () -> captureRecoveryJournalEntries(inputs));
    }

    private RecoveryJournal.Workspace captureRecoveryJournalWorkspace() {
        FileBuffer active = editor.getCurrentBuffer();
        int caret = active == null ? 0 : editor.writingArea.getCaretPosition();
        return new RecoveryJournal.Workspace(new File(".").getAbsolutePath(),
            active != null && active.hasFilePath() ? active.getFilePath() : null, caret);
    }

    private List<RecoveryInput> captureRecoveryJournalInputs() {
        List<RecoveryInput> inputs = new ArrayList<>();
        int scratchIndex = 1;
        for (FileBuffer buffer : editor.buffers) {
            if (buffer == null || !buffer.isModified() || buffer.isLargeFile() || buffer == editor.treeBuffer || buffer == editor.quickfixBuffer) {
                continue;
            }
            String snapshotId = buffer.hasFilePath()
                ? "file-" + Integer.toHexString(buffer.getFilePath().hashCode())
                : "scratch-" + (scratchIndex++);
            inputs.add(new RecoveryInput(snapshotId, buffer.getDisplayName(),
                buffer.hasFilePath() ? buffer.getFilePath() : null, buffer));
        }
        return inputs;
    }

    private List<RecoveryJournal.Entry> captureRecoveryJournalEntries(List<RecoveryInput> inputs) {
        List<RecoveryJournal.Entry> entries = new ArrayList<>();
        for (RecoveryInput input : inputs) {
            if (input.buffer().isModified()) {
                entries.add(new RecoveryJournal.Entry(input.id(), input.name(), input.path(), input.buffer().snapshotContent()));
            }
        }
        return entries;
    }

    private record RecoveryInput(String id, String name, String path, FileBuffer buffer) { }


    void clearRecoverySnapshots() {
        if (editor.recoveryStoreDir == null) {
            return;
        }
        recoveryJournalScheduler.clear();
    }

    void shutdownRecoveryJournalScheduling() {
        recoveryJournalScheduler.close(shutdownMode());
    }

    private RecoveryJournalScheduler.ShutdownMode shutdownMode() {
        if (recoveryJournalDiscardConfirmed) {
            return RecoveryJournalScheduler.ShutdownMode.CLEAR;
        }
        if (recoveryJournalDeferred) {
            return RecoveryJournalScheduler.ShutdownMode.PRESERVE;
        }
        if (hasModifiedRecoverableBuffer()) {
            return RecoveryJournalScheduler.ShutdownMode.FLUSH;
        }
        return editor.configManager.getRecoveryCleanupOnCleanExit()
            ? RecoveryJournalScheduler.ShutdownMode.CLEAR
            : RecoveryJournalScheduler.ShutdownMode.PRESERVE;
    }

    private boolean hasModifiedRecoverableBuffer() {
        for (FileBuffer buffer : editor.buffers) {
            if (buffer != null && buffer.isModified() && buffer != editor.treeBuffer && buffer != editor.quickfixBuffer) {
                return true;
            }
        }
        return false;
    }


    void promptRecoveryRestoreIfAvailable() {
        if (editor.recoveryStoreDir == null) {
            return;
        }
        java.nio.file.Path directory = editor.recoveryStoreDir.toPath();
        recoveryJournalLoading = true;
        editor.asyncJobService.submit("read recovery journal", token -> RecoveryJournal.read(directory),
            (job, journal, error) -> {
                if (editor.closingDown || job.getStatus() == AsyncJobService.Status.CANCELLED) return;
                try {
                    if (error != null) {
                        editor.showMessage("Recovery journal rejected: " + error.getMessage());
                    } else {
                        restoreRecoveryJournal(journal);
                    }
                } finally {
                    recoveryJournalLoading = false;
                    if (!recoveryJournalDeferred && hasModifiedRecoverableBuffer()) scheduleRecoverySnapshotCapture();
                }
            });
    }

    private void restoreRecoveryJournal(RecoveryJournal.Journal journal) {
        if (editor.closingDown) {
            return;
        }
        if (journal == null) {
            return;
        }
        if (journal.retention().droppedEntries() > 0) {
            editor.showMessage("Recovery warning: " + journal.retention().droppedEntries()
                + " unsaved buffer(s) were too large to retain in the previous session.");
        }
        if (journal.entries().isEmpty()) {
            return;
        }
        RecoveryWorkspaceDialog.Result result = RecoveryWorkspaceDialog.showFor(editor, journal);
        if (result.decision() == RecoveryWorkspaceDialog.Decision.DEFER) {
            recoveryJournalDeferred = true;
            return;
        }
        if (result.decision() == RecoveryWorkspaceDialog.Decision.DISCARD) {
            recoveryJournalDeferred = false;
            recoveryJournalDiscardConfirmed = true;
            clearRecoverySnapshots();
            editor.showMessage("Recovery snapshots scheduled for discard");
            return;
        }

        int restored = 0;
        FileBuffer lastRestored = null;
        recoveryJournalDeferred = result.entries().size() != journal.entries().size();
        recoveryJournalDiscardConfirmed = false;
        for (RecoveryJournal.Entry entry : result.entries()) {
            FileBuffer restoredBuffer = restoreRecoveryEntry(entry);
            if (restoredBuffer != null) {
                restored++;
                lastRestored = restoredBuffer;
            }
        }
        boolean restoredActive = result.entries().stream().anyMatch(entry -> journal.workspace().activePath() != null
            && journal.workspace().activePath().equals(entry.path()));
        FileBuffer workspaceActive = !restoredActive || journal.workspace().activePath() == null ? null
            : editor.findBufferByPath(new File(journal.workspace().activePath()));
        if (workspaceActive != null) {
            editor.loadBufferIntoEditor(workspaceActive);
            editor.writingArea.setCaretPosition(Math.min(journal.workspace().activeCaretPosition(), editor.writingArea.getText().length()));
        } else if (lastRestored != null) {
            editor.loadBufferIntoEditor(lastRestored);
        }
        if (restored == journal.entries().size()) {
            persistRecoverySnapshotsSafely();
        }
        if (restored > 0) {
            String retained = result.entries().size() == journal.entries().size() ? "" : "; unselected snapshots remain deferred";
            editor.showMessage("Recovered " + restored + " buffer" + (restored == 1 ? "" : "s") + " from crash snapshots" + retained);
        }
    }


    FileBuffer restoreRecoveryEntry(RecoveryJournal.Entry entry) {
        if (entry == null) {
            return null;
        }
        try {
            String restoredContent = entry.content();

            if (entry.path() != null && !entry.path().isBlank()) {
                File file = new File(entry.path());
                FileBuffer existing = editor.findBufferByPath(file);
                if (existing == null) {
                    FileBuffer buffer = file.exists() ? new FileBuffer(file, editor.configManager)
                        : new FileBuffer(file.getAbsolutePath(), editor.configManager);
                    if (editor.shouldReplaceSingleUntitledBuffer()) {
                        editor.releaseClosedBufferResources(editor.buffers.set(0, buffer));
                    } else {
                        editor.buffers.add(buffer);
                    }
                    registerFileWatch(buffer);
                    editor.addToRecentFiles(file.getAbsolutePath());
                    existing = buffer;
                }
                existing.setContent(restoredContent, true);
                return existing;
            }

            String scratchName = entry.name().isBlank() ? "[Recovered Scratch]" : "[Recovered] " + entry.name();
            FileBuffer scratch = FileBuffer.createScratch(scratchName, restoredContent);
            scratch.setModified(true);
            if (editor.shouldReplaceSingleUntitledBuffer()) {
                editor.releaseClosedBufferResources(editor.buffers.set(0, scratch));
            } else {
                editor.buffers.add(scratch);
            }
            return scratch;
        } catch (Exception ignored) {
            return null;
        }
    }


    public void registerFileWatch(FileBuffer buffer) {
        unregisterFileWatch(buffer);
        if (buffer == null || buffer.getFile() == null || buffer.isScratch() || !editor.buffers.contains(buffer)) return;
        var registration = editor.fileWatcherService.watch(buffer.getFile(), file -> {
            SwingUtilities.invokeLater(() -> {
                if (!editor.closingDown && editor.isDisplayable() && editor.buffers.contains(buffer)
                    && !editor.reloadPromptActive && buffer.getFile() != null
                    && buffer.getFile().getAbsoluteFile().equals(file.getAbsoluteFile())) {
                    checkForExternalChanges();
                }
            });
        });
        fileWatches.put(buffer, registration);
    }

    void unregisterFileWatch(FileBuffer buffer) {
        observedConflicts.remove(buffer);
        var registration = fileWatches.remove(buffer);
        if (registration != null) registration.close();
    }

}
