package shed;

import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

final class RecoveryController {
    private final Texteditor editor;

    RecoveryController(Texteditor editor) {
        this.editor = editor;
    }

    void checkForExternalChanges() {
        if (editor.reloadPromptActive) {
            return;
        }
        int autoReloaded = 0;
        for (FileBuffer buffer : editor.buffers) {
            if (buffer == null || !buffer.hasFilePath() || !buffer.hasExternalChanges()) {
                continue;
            }
            if (!buffer.isModified()) {
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
                continue;
            }
            promptExternalConflictForModifiedBuffer(buffer);
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
        if (buffer == null || buffer.getFile() == null) {
            return;
        }
        editor.reloadPromptActive = true;
        String[] options = {"Keep Mine", "Reload Theirs", "View Both"};
        int result = JOptionPane.showOptionDialog(
            editor,
            "File changed on disk while modified in editor:\n"
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
            showExternalConflictPreview(buffer);
            buffer.refreshExternalTimestamp();
            return;
        }
        buffer.refreshExternalTimestamp();
    }


    void showExternalConflictPreview(FileBuffer buffer) {
        if (buffer == null || buffer.getFile() == null) {
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


    void startRecoverySnapshotTimer() {
        if (editor.recoverySnapshotTimer != null) {
            editor.recoverySnapshotTimer.stop();
        }
        editor.recoverySnapshotTimer = new javax.swing.Timer(5000, e -> persistRecoverySnapshotsSafely());
        editor.recoverySnapshotTimer.setRepeats(true);
        editor.recoverySnapshotTimer.start();
    }


    void persistRecoverySnapshotsSafely() {
        try {
            persistRecoverySnapshots();
        } catch (Exception ignored) {
        }
    }


    void persistRecoverySnapshots() throws IOException {
        if (editor.recoveryStoreDir == null) {
            return;
        }
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
        if (entries.isEmpty()) {
            RecoveryJournal.clear(editor.recoveryStoreDir.toPath());
            return;
        }
        FileBuffer active = editor.getCurrentBuffer();
        int caret = active == null ? 0 : editor.writingArea.getCaretPosition();
        RecoveryJournal.write(editor.recoveryStoreDir.toPath(), new RecoveryJournal.Workspace(
            new File(".").getAbsolutePath(), active != null && active.hasFilePath() ? active.getFilePath() : null, caret
        ), entries);
    }


    void clearRecoverySnapshots() {
        if (editor.recoveryStoreDir == null) {
            return;
        }
        try {
            RecoveryJournal.clear(editor.recoveryStoreDir.toPath());
        } catch (IOException ignored) {
        }
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
        int result = JOptionPane.showConfirmDialog(
            editor,
            journal.entries().size() + " crash-recovery snapshot(s) were found. Restore now?",
            "Crash Recovery",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) {
            return;
        }

        int restored = 0;
        FileBuffer lastRestored = null;
        for (RecoveryJournal.Entry entry : journal.entries()) {
            FileBuffer restoredBuffer = restoreRecoveryEntry(entry);
            if (restoredBuffer != null) {
                restored++;
                lastRestored = restoredBuffer;
            }
        }
        FileBuffer workspaceActive = journal.workspace().activePath() == null ? null
            : editor.findBufferByPath(new File(journal.workspace().activePath()));
        if (workspaceActive != null) {
            editor.loadBufferIntoEditor(workspaceActive);
            editor.writingArea.setCaretPosition(Math.min(journal.workspace().activeCaretPosition(), editor.writingArea.getText().length()));
        } else if (lastRestored != null) {
            editor.loadBufferIntoEditor(lastRestored);
        }
        if (restored == journal.entries().size()) {
            clearRecoverySnapshots();
        }
        if (restored > 0) {
            editor.showMessage("Recovered " + restored + " buffer" + (restored == 1 ? "" : "s") + " from crash snapshots");
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
                    FileBuffer buffer = file.exists() ? new FileBuffer(file, editor.configManager) : new FileBuffer(file.getAbsolutePath());
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
                if (!editor.reloadPromptActive && buffer.getFile() != null && buffer.getFile().equals(file)) {
                    checkForExternalChanges();
                }
            });
        });
    }

}
