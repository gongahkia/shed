package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorWorkspaceDocumentsTest {
    @TempDir Path root;

    @Test void capturesActualUnsavedFileBuffersOnEdtAndKeepsVersionsStableUntilStateChanges() throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), "saved\r\n");
        AtomicReference<FileBuffer> buffer = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { buffer.set(new FileBuffer(file.toFile())); buffer.get().setContent("human draft\n"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        try (EditorWorkspaceDocuments source = new EditorWorkspaceDocuments(() -> {
            assertTrue(SwingUtilities.isEventDispatchThread()); return List.of(buffer.get());
        }, () -> List.of(root), () -> root, root)) {
            var service = new WorkspaceQueryService(root, AgentPolicy.readOnly(), source);
            var first = source.snapshots().getFirst(); var second = source.snapshots().getFirst();
            assertEquals(first.documentId(), second.documentId()); assertEquals(first.version(), second.version());
            assertEquals("CRLF", first.lineEnding()); assertTrue(first.dirty());
            assertEquals("human draft\n", service.read(service.workspaceId(), file.toString(), first.version(), 0, 100).get("content"));
            assertEquals("saved\r\n", Files.readString(file));
            SwingUtilities.invokeAndWait(() -> buffer.get().replaceContentUndoably("newer edit\n"));
            var changed = source.snapshots().getFirst();
            assertEquals(first.documentId(), changed.documentId()); assertNotEquals(first.version(), changed.version());
            assertEquals(WorkspaceApiException.Code.STALE_VERSION,
                assertThrows(WorkspaceApiException.class, () -> service.read(service.workspaceId(), first.documentId(), first.version(), 0, 100)).code());
        }
    }

    @Test void scratchOwnershipDoesNotFollowLaterTabOrWorkspaceSelection() throws Exception {
        Path other = Files.createDirectory(root.resolve("other"));
        AtomicReference<Path> selected = new AtomicReference<>(root);
        List<FileBuffer> buffers = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> buffers.add(FileBuffer.createScratch("Notes", "draft")));
        var source = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root, other), selected::get, root);
        var first = source.snapshots().getFirst(); selected.set(other);
        assertEquals(first.workspaceId(), source.snapshots().getFirst().workspaceId());
        SwingUtilities.invokeAndWait(() -> buffers.add(FileBuffer.createScratch("Other notes", "other draft")));
        var all = source.snapshots(); assertNotEquals(all.get(0).workspaceId(), all.get(1).workspaceId());
        source.close(); assertFalse(source.connected());
        assertEquals(WorkspaceApiException.Code.NOT_CONNECTED, assertThrows(WorkspaceApiException.class, source::snapshots).code());
    }

    @Test void capturesInsideEdtDoNotDeadlockAndFormatChangesAdvanceVersions() throws Exception {
        List<FileBuffer> buffers = new ArrayList<>();
        var source = new EditorWorkspaceDocuments(() -> buffers, () -> List.of(root), () -> root, root);
        SwingUtilities.invokeAndWait(() -> {
            FileBuffer buffer = FileBuffer.createScratch("Notes", "text"); buffers.add(buffer);
            var before = source.snapshots().getFirst();
            buffer.setEncoding("UTF-16");
            var after = source.snapshots().getFirst();
            assertNotEquals(before.version(), after.version()); assertTrue(after.byteOrderMark());
        });
        source.close();
    }
}
