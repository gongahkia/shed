package shed;

import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

final class RecoveryController {
    private final Texteditor editor;
    private final RecoveryJournalScheduler recoveryJournalScheduler;
    private boolean recoveryJournalDeferred;
    private boolean recoveryJournalDiscardConfirmed;

    RecoveryController(Texteditor editor) {
        this.editor = editor;
        this.recoveryJournalScheduler = new RecoveryJournalScheduler(
            snapshot -> {
                if (snapshot.entries().isEmpty()) {
                    RecoveryJournal.clear(editor.recoveryStoreDir.toPath());
                } else {
                    RecoveryJournal.write(editor.recoveryStoreDir.toPath(), snapshot.workspace(), snapshot.entries(),
                        editor.configManager.getRecoveryRetentionPolicy());
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
        for (FileBuffer buffer : editor.buffers) {
            if (buffer == null || !buffer.hasFilePath()) {
                continue;
            }
            FileBuffer.ExternalFileState state = buffer.getExternalFileState();
            if (state == FileBuffer.ExternalFileState.UNCHANGED) {
                continue;
            }
            if (!buffer.isModified()) {
                if (state == FileBuffer.ExternalFileState.EXTERNALLY_CHANGED
                    || state == FileBuffer.ExternalFileState.REPLACED) {
                    try {
                        int caret = 0;
                        if (buffer == editor.getCurrentBuffer()) {
                            caret = editor.writingArea.getCaretPosition();
                        }
                        buffer.load(editor.configManager);
                        if (buffer == editor.getCurrentBuffer()) {
                            editor.loadBufferIntoEditor(buffer);
                            editor.writingArea.setCaretPosition(Math.min(caret, editor.writingArea.getText().length()));
                        }
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
            promptExternalConflictForModifiedBuffer(buffer, state);
        }
        if (!hasModifiedSettingsBuffer()) {
            String configReload = editor.reloadConfigIfChanged();
            if (configReload != null) {
                editor.showMessage(configReload);
                return;
            }
        }
        if (autoReloaded > 0) {
            editor.showMessage("Auto-reloaded " + autoReloaded + " externally changed buffer" + (autoReloaded == 1 ? "" : "s"));
        } else if (retainedAfterDeletion > 0) {
            editor.showMessage("Retained " + retainedAfterDeletion + " buffer" + (retainedAfterDeletion == 1 ? "" : "s")
                + " after external deletion");
        } else if (unsupportedExternalTargets > 0) {
            editor.showMessage("Retained " + unsupportedExternalTargets + " buffer" + (unsupportedExternalTargets == 1 ? "" : "s")
                + " with unsupported external target");
        }
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
                buffer.refreshExternalTimestamp();
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
            buffer.refreshExternalTimestamp();
            return;
        }
        if (result == 3) {
            saveConflictAs(buffer);
            return;
        }
        buffer.refreshExternalTimestamp();
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
        if (chooser.showSaveDialog(editor) != JFileChooser.APPROVE_OPTION) {
            buffer.refreshExternalTimestamp();
            editor.showMessage("Save As cancelled; dirty buffer retained");
            return;
        }

        File target = chooser.getSelectedFile().getAbsoluteFile();
        if (target.exists() && JOptionPane.showConfirmDialog(editor,
            "Replace the selected file?\n" + target.getAbsolutePath(), "Confirm Save As",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            buffer.refreshExternalTimestamp();
            editor.showMessage("Save As cancelled; dirty buffer retained");
            return;
        }
        try {
            saveConflictAs(buffer, target);
        } catch (IOException error) {
            buffer.refreshExternalTimestamp();
            editor.showMessage("Save As failed; dirty buffer retained: " + error.getMessage());
        }
    }

    void saveConflictAs(FileBuffer buffer, File target) throws IOException {
        if (buffer == null || target == null) {
            throw new IOException("Conflict buffer and save target are required");
        }
        File previousFile = buffer.getFile();
        int caret = buffer == editor.getCurrentBuffer() ? editor.writingArea.getCaretPosition() : 0;
        buffer.saveAs(target);
        if (previousFile != null && !previousFile.getAbsoluteFile().equals(target.getAbsoluteFile())) {
            editor.fileWatcherService.unwatch(previousFile);
        }
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


    void persistRecoverySnapshots() throws IOException {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IOException("recovery journal capture must run on the EDT");
        }
        if (editor.recoveryStoreDir == null || recoveryJournalDeferred) {
            return;
        }
        recoveryJournalScheduler.request(captureRecoveryJournalWorkspace(), captureRecoveryJournalEntries());
    }

    private RecoveryJournal.Workspace captureRecoveryJournalWorkspace() {
        FileBuffer active = editor.getCurrentBuffer();
        int caret = active == null ? 0 : editor.writingArea.getCaretPosition();
        return new RecoveryJournal.Workspace(new File(".").getAbsolutePath(),
            active != null && active.hasFilePath() ? active.getFilePath() : null, caret);
    }

    private List<RecoveryJournal.Entry> captureRecoveryJournalEntries() {
        List<RecoveryJournal.Entry> entries = new ArrayList<>();
        int scratchIndex = 1;
        for (FileBuffer buffer : editor.buffers) {
            if (buffer == null || !buffer.isModified() || buffer == editor.treeBuffer || buffer == editor.quickfixBuffer) {
                continue;
            }
            String snapshotId = buffer.hasFilePath()
                ? "file-" + Integer.toHexString(buffer.getFilePath().hashCode())
                : "scratch-" + (scratchIndex++);
            entries.add(new RecoveryJournal.Entry(snapshotId, buffer.getDisplayName(),
                buffer.hasFilePath() ? buffer.getFilePath() : null, buffer.getFullContent()));
        }
        return entries;
    }


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
        RecoveryJournal.Journal journal;
        try {
            journal = RecoveryJournal.read(editor.recoveryStoreDir.toPath());
        } catch (IOException error) {
            editor.showMessage("Recovery journal rejected: " + error.getMessage());
            return;
        }
        if (journal == null || journal.entries().isEmpty()) {
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
                    if (editor.shouldReplaceSingleLandingBuffer()) {
                        editor.buffers.set(0, buffer);
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
            if (editor.shouldReplaceSingleLandingBuffer()) {
                editor.buffers.set(0, scratch);
            } else {
                editor.buffers.add(scratch);
            }
            return scratch;
        } catch (Exception ignored) {
            return null;
        }
    }


    public void registerFileWatch(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null || buffer.isScratch()) return;
        editor.fileWatcherService.watch(buffer.getFile(), file -> {
            SwingUtilities.invokeLater(() -> {
                if (!editor.reloadPromptActive && buffer.getFile() != null
                    && buffer.getFile().getAbsoluteFile().equals(file.getAbsoluteFile())) {
                    checkForExternalChanges();
                }
            });
        });
    }

}
