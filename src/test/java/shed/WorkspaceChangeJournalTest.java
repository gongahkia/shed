package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceChangeJournalTest {
    @TempDir Path root;
    private static final String ID = "proposal-" + "a".repeat(32);

    @Test void retentionReferencesArePrivateBoundedAndReadOnlyInspectionDoesNotCreateDirectories() throws Exception {
        Path storage = root.resolve("journal");
        try (var lease = new WorkspaceChangeJournal(storage).open(true)) {
            Path missing = storage.toRealPath().resolve("retained/resource-missing/original");
            assertEquals(missing, lease.retainedPath(missing.toUri().toString()));
            assertFalse(Files.exists(missing.getParent().getParent()));
            assertNull(lease.retainedPath(root.resolve("outside").toUri().toString()));
            Path retained = lease.retentionDirectory(16);
            assertTrue(lease.list().isEmpty());
            assertEquals(WorkspaceApiException.Code.LIMIT_EXCEEDED,
                assertThrows(WorkspaceApiException.class, () -> lease.retentionDirectory(512L * 1024 * 1024 + 1)).code());
            assertThrows(IOException.class, () -> lease.retainedPath(retained.resolve("foreign/original").toUri().toString()));
            Path outside = Files.createDirectory(root.resolve("outside"));
            try { Files.createSymbolicLink(retained.resolve("resource-link"), outside); }
            catch (UnsupportedOperationException | IOException unavailable) { return; }
            assertThrows(IOException.class, () -> lease.retainedPath(retained.resolve("resource-link/original").toUri().toString()));
            assertFalse(Files.exists(outside.resolve("original")));
        }
    }

    @Test void readOnlyAbsenceHasNoSideEffectsAndHistorySurvivesReopeningWithExactUtf16Preimages() throws Exception {
        Path storage = root.resolve("journal");
        WorkspaceChangeJournal journal = new WorkspaceChangeJournal(storage);
        assertNull(journal.open(false)); assertFalse(Files.exists(storage));
        String text = "draft 👩‍💻\n\ud800";
        String blob;
        try (var lease = journal.open(true)) {
            blob = lease.putText(text);
            var created = lease.append(ID, -1, "PROPOSED", Map.of("text", blob, "actor", "agent-one"));
            assertEquals(0, created.revision());
            lease.append(ID, 0, "APPLYING", Map.of("text", blob, "actor", "agent-one"));
        }
        try (var lease = new WorkspaceChangeJournal(storage).open(false)) {
            assertEquals("APPLYING", lease.read(ID).kind()); assertEquals(text, lease.getText(blob));
            assertEquals(1, lease.list().size());
            assertEquals(WorkspaceApiException.Code.CONFLICT,
                assertThrows(WorkspaceApiException.class, () -> lease.append(ID, 0, "APPLIED", Map.of())).code());
        }
    }

    @Test void corruptHistoryAndBlobsAreRejectedAndCompetingInstancesCannotWriteThroughEachOthersLease() throws Exception {
        Path storage = root.resolve("journal");
        WorkspaceChangeJournal journal = new WorkspaceChangeJournal(storage);
        String blob;
        try (var lease = journal.open(true)) {
            blob = lease.putText("original");
            lease.append(ID, -1, "PROPOSED", Map.of("text", blob));
            lease.append(ID, 0, "APPLYING", Map.of("text", blob));
            assertEquals(WorkspaceApiException.Code.CONFLICT,
                assertThrows(WorkspaceApiException.class, () -> new WorkspaceChangeJournal(storage).open(true)).code());
        }
        Files.writeString(storage.resolve("blobs").resolve(blob + ".bin"), "tampered");
        try (var lease = journal.open(false)) { assertThrows(IOException.class, () -> lease.get(blob)); }
        Files.writeString(storage.resolve(ID).resolve("00000000.json"), "{}");
        try (var lease = journal.open(false)) { assertThrows(IOException.class, () -> lease.read(ID)); }
    }

    @Test void interruptedPendingWritesAreRetainedWithoutReplacingPublishedState() throws Exception {
        Path storage = root.resolve("journal");
        WorkspaceChangeJournal journal = new WorkspaceChangeJournal(storage);
        try (var lease = journal.open(true)) { lease.append(ID, -1, "PROPOSED", Map.of("goal", "keep work")); }
        Path orphan = PrivateWorkspaceFiles.create(storage.resolve(ID), "pending-" + java.util.UUID.randomUUID() + ".json", new byte[]{1,2});
        try (var lease = journal.open(false)) {
            assertEquals("PROPOSED", lease.read(ID).kind());
            lease.append(ID, 0, "APPLYING", Map.of("goal", "keep work"));
        }
        assertTrue(Files.exists(orphan));
    }
}
