package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceStateTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsVersionedRootsBuffersPanesSelectionAndTools() {
        WorkspaceState state = workspace();

        WorkspaceState restored = WorkspaceState.parse(state.serialize());

        assertEquals(state, restored);
        assertEquals(state, WorkspaceState.fromMap(state.toMap()));
    }

    @Test
    void rejectsUnknownFieldsAndBrokenReferences() {
        WorkspaceState state = workspace();
        Map<String, Object> unknownField = new LinkedHashMap<>(state.toMap());
        unknownField.put("unexpected", true);

        assertThrows(IllegalArgumentException.class, () -> WorkspaceState.fromMap(unknownField));

        Map<String, Object> brokenReference = new LinkedHashMap<>(state.toMap());
        List<Object> panes = new java.util.ArrayList<>(MiniJson.asArray(brokenReference.get("panes")));
        Map<String, Object> firstPane = new LinkedHashMap<>(MiniJson.asObject(panes.getFirst()));
        firstPane.put("bufferId", "missing");
        panes.set(0, firstPane);
        brokenReference.put("panes", panes);

        assertThrows(IllegalArgumentException.class, () -> WorkspaceState.fromMap(brokenReference));
    }

    @Test
    void retainsLastKnownGoodWorkspaceAfterParseFailure() {
        WorkspaceState state = workspace();
        WorkspaceStateCodec codec = new WorkspaceStateCodec();

        WorkspaceStateCodec.LoadResult valid = codec.read(state.serialize());
        WorkspaceStateCodec.LoadResult invalid = codec.read("{\"version\":99}");
        WorkspaceStateCodec.LoadResult firstInvalid = new WorkspaceStateCodec().read("{\"version\":99}");

        assertTrue(valid.accepted());
        assertFalse(invalid.accepted());
        assertTrue(invalid.retainedLastKnownGood());
        assertEquals(state, invalid.state());
        assertEquals(state, codec.lastKnownGood());
        assertFalse(firstInvalid.retainedLastKnownGood());
        assertNull(firstInvalid.state());
    }

    private WorkspaceState workspace() {
        Path root = tempDir.resolve("project").toAbsolutePath();
        Path secondRoot = tempDir.resolve("shared").toAbsolutePath();
        Path file = root.resolve("notes.txt");
        return new WorkspaceState(
            List.of(root.toString(), secondRoot.toString()),
            List.of(
                new WorkspaceState.BufferState("file-1", WorkspaceState.BufferKind.FILE, file.toString(), null, true, "draft"),
                new WorkspaceState.BufferState("scratch-1", WorkspaceState.BufferKind.SCRATCH, null, "Scratch", false, "notes")
            ),
            List.of(
                new WorkspaceState.PaneState("pane-1", "file-1", 3),
                new WorkspaceState.PaneState("pane-2", "scratch-1", 1)
            ),
            new WorkspaceState.ActiveSelection("pane-1", "file-1", 3),
            List.of(new WorkspaceState.ToolState("tree", Map.of("root", root.toString())))
        );
    }
}
