package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class WorkspaceStateRestoreService {
    RestoreResult restore(WorkspaceState state) {
        Objects.requireNonNull(state, "state");
        Map<String, RestoredBuffer> buffersById = new LinkedHashMap<>();
        List<RestoreFailure> failures = new ArrayList<>();
        for (WorkspaceState.BufferState buffer : state.buffers()) {
            RestoredBuffer restored = restoreBuffer(buffer, failures);
            if (restored != null) {
                buffersById.put(restored.id(), restored);
            }
        }
        List<WorkspaceState.PaneState> panes = new ArrayList<>();
        for (WorkspaceState.PaneState pane : state.panes()) {
            if (buffersById.containsKey(pane.bufferId())) {
                panes.add(pane);
            }
        }
        WorkspaceState.ActiveSelection activeSelection = state.activeSelection();
        if (activeSelection != null) {
            boolean activePaneRestored = false;
            for (WorkspaceState.PaneState pane : panes) {
                if (pane.id().equals(activeSelection.paneId())) {
                    activePaneRestored = true;
                    break;
                }
            }
            if (!activePaneRestored) {
                activeSelection = null;
            }
        }
        return new RestoreResult(List.copyOf(buffersById.values()), List.copyOf(panes), activeSelection, List.copyOf(failures));
    }

    private RestoredBuffer restoreBuffer(WorkspaceState.BufferState buffer, List<RestoreFailure> failures) {
        if (buffer.kind() == WorkspaceState.BufferKind.SCRATCH) {
            return new RestoredBuffer(buffer.id(), WorkspaceState.BufferKind.SCRATCH, null, buffer.name(), buffer.modified(), buffer.content(), false, null);
        }
        WorkspaceState.FileSnapshot expected = buffer.fileSnapshot();
        if (expected == null) {
            failures.add(new RestoreFailure(buffer.id(), buffer.path(), FailureKind.UNVERIFIED_FILE,
                "File identity was not recorded; the path was not opened."));
            return recoverDirtyBuffer(buffer);
        }
        try {
            Path path = Path.of(buffer.path());
            WorkspaceState.FileSnapshot current = WorkspaceState.FileSnapshot.capture(path);
            if (!expected.equals(current)) {
                failures.add(new RestoreFailure(buffer.id(), buffer.path(), FailureKind.CHANGED_FILE,
                    "File changed since workspace save; the path was not opened."));
                return recoverDirtyBuffer(buffer);
            }
            String content = buffer.modified() ? buffer.content() : java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
            if (!expected.equals(WorkspaceState.FileSnapshot.capture(path))) {
                failures.add(new RestoreFailure(buffer.id(), buffer.path(), FailureKind.CHANGED_FILE,
                    "File changed during workspace restore; the path was not opened."));
                return recoverDirtyBuffer(buffer);
            }
            return new RestoredBuffer(buffer.id(), WorkspaceState.BufferKind.FILE, buffer.path(), null, buffer.modified(), content, false, null);
        } catch (NoSuchFileException error) {
            failures.add(new RestoreFailure(buffer.id(), buffer.path(), FailureKind.MISSING_FILE,
                "File is missing; the path was not opened."));
            return recoverDirtyBuffer(buffer);
        } catch (IOException | SecurityException error) {
            failures.add(new RestoreFailure(buffer.id(), buffer.path(), FailureKind.UNAVAILABLE_FILE,
                "File could not be verified; the path was not opened."));
            return recoverDirtyBuffer(buffer);
        }
    }

    private RestoredBuffer recoverDirtyBuffer(WorkspaceState.BufferState buffer) {
        if (!buffer.modified()) {
            return null;
        }
        Path source = Path.of(buffer.path());
        Path fileName = source.getFileName();
        String displayName = fileName == null ? "file" : fileName.toString();
        return new RestoredBuffer(buffer.id(), WorkspaceState.BufferKind.SCRATCH, null, "[recovery] " + displayName,
            true, buffer.content(), true, buffer.path());
    }

    record RestoreResult(List<RestoredBuffer> buffers, List<WorkspaceState.PaneState> panes,
                         WorkspaceState.ActiveSelection activeSelection, List<RestoreFailure> failures) {
        boolean complete() {
            return failures.isEmpty();
        }

        boolean hasRecoverableFailures() {
            return !failures.isEmpty();
        }
    }

    record RestoredBuffer(String id, WorkspaceState.BufferKind kind, String path, String name, boolean modified,
                          String content, boolean recoveryOnly, String sourcePath) {
    }

    record RestoreFailure(String bufferId, String path, FailureKind kind, String message) {
        boolean recoverable() {
            return true;
        }
    }

    enum FailureKind {
        UNVERIFIED_FILE,
        MISSING_FILE,
        CHANGED_FILE,
        UNAVAILABLE_FILE
    }
}
