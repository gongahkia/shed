package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceStateRestoreServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void restoresVerifiedFileScratchPanesAndSelection() throws Exception {
        Path file = write("notes.txt", "on disk");
        WorkspaceState state = state(new WorkspaceState.BufferState("file-1", WorkspaceState.BufferKind.FILE, file.toString(), null,
            false, null, WorkspaceState.FileSnapshot.capture(file)), new WorkspaceState.BufferState("scratch-1",
            WorkspaceState.BufferKind.SCRATCH, null, "Scratch", false, "notes", null));

        WorkspaceStateRestoreService.RestoreResult result = new WorkspaceStateRestoreService().restore(state);

        assertTrue(result.complete());
        assertEquals(2, result.buffers().size());
        assertEquals("on disk", result.buffers().getFirst().content());
        assertEquals(2, result.panes().size());
        assertEquals("pane-1", result.activeSelection().paneId());
    }

    @Test
    void reportsChangedCleanFileWithoutOpeningIt() throws Exception {
        Path file = write("notes.txt", "before");
        WorkspaceState state = state(new WorkspaceState.BufferState("file-1", WorkspaceState.BufferKind.FILE, file.toString(), null,
            false, null, WorkspaceState.FileSnapshot.capture(file)));
        Files.writeString(file, "after", StandardCharsets.UTF_8);

        WorkspaceStateRestoreService.RestoreResult result = new WorkspaceStateRestoreService().restore(state);

        assertFalse(result.complete());
        assertTrue(result.hasRecoverableFailures());
        assertTrue(result.buffers().isEmpty());
        assertTrue(result.panes().isEmpty());
        assertNull(result.activeSelection());
        assertEquals(WorkspaceStateRestoreService.FailureKind.CHANGED_FILE, result.failures().getFirst().kind());
        assertTrue(result.failures().getFirst().recoverable());
    }

    @Test
    void detachesDirtyDraftWhenSavedPathIsMissing() throws Exception {
        Path file = write("notes.txt", "before");
        WorkspaceState state = state(new WorkspaceState.BufferState("file-1", WorkspaceState.BufferKind.FILE, file.toString(), null,
            true, "draft", WorkspaceState.FileSnapshot.capture(file)));
        Files.delete(file);

        WorkspaceStateRestoreService.RestoreResult result = new WorkspaceStateRestoreService().restore(state);

        assertEquals(WorkspaceStateRestoreService.FailureKind.MISSING_FILE, result.failures().getFirst().kind());
        WorkspaceStateRestoreService.RestoredBuffer recovered = result.buffers().getFirst();
        assertEquals(WorkspaceState.BufferKind.SCRATCH, recovered.kind());
        assertTrue(recovered.recoveryOnly());
        assertNull(recovered.path());
        assertEquals(file.toString(), recovered.sourcePath());
        assertEquals("draft", recovered.content());
    }

    @Test
    void rejectsLegacyUnverifiedPathWithoutOpeningIt() throws Exception {
        Path file = write("notes.txt", "before");
        WorkspaceState state = new WorkspaceState(WorkspaceState.LEGACY_VERSION, List.of(tempDir.toString()),
            List.of(new WorkspaceState.BufferState("file-1", WorkspaceState.BufferKind.FILE, file.toString(), null, false, null)),
            List.of(new WorkspaceState.PaneState("pane-1", "file-1", 0)),
            new WorkspaceState.ActiveSelection("pane-1", "file-1", 0), List.of());

        WorkspaceStateRestoreService.RestoreResult result = new WorkspaceStateRestoreService().restore(WorkspaceState.parse(state.serialize()));

        assertTrue(result.buffers().isEmpty());
        assertEquals(WorkspaceStateRestoreService.FailureKind.UNVERIFIED_FILE, result.failures().getFirst().kind());
    }

    private WorkspaceState state(WorkspaceState.BufferState... buffers) {
        return new WorkspaceState(List.of(tempDir.toString()), List.of(buffers), panes(buffers),
            buffers.length == 0 ? null : new WorkspaceState.ActiveSelection("pane-1", buffers[0].id(), 0),
            List.of(new WorkspaceState.ToolState("tree", Map.of("root", tempDir.toString()))));
    }

    private List<WorkspaceState.PaneState> panes(WorkspaceState.BufferState[] buffers) {
        java.util.ArrayList<WorkspaceState.PaneState> panes = new java.util.ArrayList<>();
        for (int index = 0; index < buffers.length; index++) {
            panes.add(new WorkspaceState.PaneState("pane-" + (index + 1), buffers[index].id(), 0));
        }
        return panes;
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toAbsolutePath();
    }
}
