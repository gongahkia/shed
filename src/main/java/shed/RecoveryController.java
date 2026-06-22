package shed;

import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        if (autoReloaded > 0) {
            editor.showMessage("Auto-reloaded " + autoReloaded + " externally changed buffer" + (autoReloaded == 1 ? "" : "s"));
        }
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
        if (!editor.recoveryStoreDir.exists()) {
            Files.createDirectories(editor.recoveryStoreDir.toPath());
        }

        Set<String> activeSnapshotFiles = new HashSet<>();
        int scratchIndex = 1;
        for (FileBuffer buffer : editor.buffers) {
            if (buffer == null || !buffer.isModified() || buffer == editor.treeBuffer || buffer == editor.quickfixBuffer) {
                continue;
            }
            String snapshotId = buffer.hasFilePath()
                ? "file-" + Integer.toHexString(buffer.getFilePath().hashCode())
                : "scratch-" + (scratchIndex++);
            String snapshotFileName = snapshotId + ".json";
            activeSnapshotFiles.add(snapshotFileName);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", snapshotId);
            payload.put("name", buffer.getDisplayName());
            payload.put("path", buffer.hasFilePath() ? buffer.getFilePath() : null);
            payload.put("modified", true);
            payload.put("content", buffer.getContent());
            payload.put("savedAt", editor.commandLogTimeFormat.format(LocalDateTime.now()));

            Files.writeString(
                new File(editor.recoveryStoreDir, snapshotFileName).toPath(),
                MiniJson.stringify(payload),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        }

        File[] existing = editor.recoveryStoreDir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (existing == null) {
            return;
        }
        for (File file : existing) {
            if (!activeSnapshotFiles.contains(file.getName())) {
                Files.deleteIfExists(file.toPath());
            }
        }
    }


    void clearRecoverySnapshots() {
        if (editor.recoveryStoreDir == null || !editor.recoveryStoreDir.exists()) {
            return;
        }
        File[] snapshots = editor.recoveryStoreDir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (snapshots == null) {
            return;
        }
        for (File snapshot : snapshots) {
            try {
                Files.deleteIfExists(snapshot.toPath());
            } catch (IOException ignored) {
            }
        }
    }


    void promptRecoveryRestoreIfAvailable() {
        if (editor.recoveryStoreDir == null || !editor.recoveryStoreDir.exists()) {
            return;
        }
        File[] snapshots = editor.recoveryStoreDir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (snapshots == null || snapshots.length == 0) {
            return;
        }
        java.util.Arrays.sort(snapshots, Comparator.comparing(File::getName));
        int result = JOptionPane.showConfirmDialog(
            editor,
            snapshots.length + " crash-recovery snapshot(s) were found. Restore now?",
            "Crash Recovery",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) {
            return;
        }

        int restored = 0;
        FileBuffer lastRestored = null;
        for (File snapshot : snapshots) {
            FileBuffer restoredBuffer = restoreRecoverySnapshot(snapshot);
            if (restoredBuffer != null) {
                restored++;
                lastRestored = restoredBuffer;
            }
            try {
                Files.deleteIfExists(snapshot.toPath());
            } catch (IOException ignored) {
            }
        }
        if (lastRestored != null) {
            editor.loadBufferIntoEditor(lastRestored);
        }
        if (restored > 0) {
            editor.showMessage("Recovered " + restored + " buffer" + (restored == 1 ? "" : "s") + " from crash snapshots");
        }
    }


    FileBuffer restoreRecoverySnapshot(File snapshotFile) {
        if (snapshotFile == null || !snapshotFile.isFile()) {
            return null;
        }
        try {
            String json = Files.readString(snapshotFile.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> payload = MiniJson.asObject(MiniJson.parse(json));
            if (payload == null) {
                return null;
            }
            String content = MiniJson.asString(payload.get("content"));
            String path = MiniJson.asString(payload.get("path"));
            String name = MiniJson.asString(payload.get("name"));
            String restoredContent = content == null ? "" : content;

            if (path != null && !path.isBlank()) {
                File file = new File(path);
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

            String scratchName = name == null || name.isBlank() ? "[Recovered Scratch]" : "[Recovered] " + name;
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
