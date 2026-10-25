package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceProposalRecoveryTest {
    @TempDir Path temporary;

    @Test void completeEvidenceCanBeReconciledIdempotentlyWithoutWritingFiles() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Files.writeString(root.resolve("a"), "old\r\n");
        var service = service(root, storage, true);
        String id = interruptedApply(root, storage, service, List.of("a"));
        Files.writeString(root.resolve("a"), "new\r\n");
        var inspection = service.recovery(id);
        assertEquals("APPLIED", inspection.get("verifiedOutcome"));
        String version = queries(root).snapshot(queries(root).workspaceId(), "a").version();
        int revision = revision(MiniJson.asObject(inspection.get("proposal"))); String proof = (String) inspection.get("fingerprint");
        assertEquals("PERMISSION_DENIED", assertThrows(WorkspaceApiException.class, () -> service(root, storage, false).reconcile(id, revision, proof)).code().name());
        var recovered = service.reconcile(id, revision, proof);
        assertEquals("APPLIED", recovered.get("status"));
        assertEquals(version, queries(root).snapshot(queries(root).workspaceId(), "a").version());
        assertEquals(recovered, service.reconcile(id, revision, proof));
        service.undo(id, revision(recovered)); assertEquals("old\r\n", Files.readString(root.resolve("a")));
    }

    @Test void changedEvidenceAndPartialOrMissingFilesNeverReceiveACompleteOutcome() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        for (String name : List.of("a", "b")) Files.writeString(root.resolve(name), "old\r\n");
        var service = service(root, storage, true);
        String id = interruptedApply(root, storage, service, List.of("a", "b"));
        var before = service.recovery(id); assertEquals("FAILED", before.get("verifiedOutcome"));
        Files.writeString(root.resolve("a"), "new\r\n");
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class,
            () -> service.reconcile(id, revision(MiniJson.asObject(before.get("proposal"))), (String) before.get("fingerprint"))).code());
        var mixed = service.recovery(id); assertNull(mixed.get("verifiedOutcome"));
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class,
            () -> service.reconcile(id, revision(MiniJson.asObject(mixed.get("proposal"))), (String) mixed.get("fingerprint"))).code());
        Files.delete(root.resolve("b"));
        var missing = service.recovery(id); assertNull(missing.get("verifiedOutcome"));
        assertEquals("MISSING", MiniJson.asObject(MiniJson.asArray(missing.get("documents")).getLast()).get("state"));
        assertEquals("new\r\n", Files.readString(root.resolve("a"))); assertFalse(Files.exists(root.resolve("b")));
    }

    @Test void rawFormatDifferencesAndCorruptedPreimagesCannotBeReconciledAsExactResults() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Files.writeString(root.resolve("a"), "old\r\n");
        var service = service(root, storage, true);
        String id = interruptedApply(root, storage, service, List.of("a"));
        Files.writeString(root.resolve("a"), "new\n"); // Same normalized text, different raw format.
        assertNull(service.recovery(id).get("verifiedOutcome"));
        Files.writeString(root.resolve("a"), "new\r\n");
        String blob;
        try (var lease = new WorkspaceChangeJournal(storage).open(false)) {
            blob = (String) MiniJson.asObject(MiniJson.asArray(lease.read(id).data().get("operation")).getFirst()).get("diskBefore");
        }
        Files.writeString(storage.resolve("blobs").resolve(blob + ".bin"), "corrupted");
        assertEquals(WorkspaceApiException.Code.RECOVERY_REQUIRED, assertThrows(WorkspaceApiException.class, () -> service.recovery(id)).code());
        assertEquals("new\r\n", Files.readString(root.resolve("a")));
    }

    @Test void anUndoThatNeverTookEffectRestoresTheOriginalApplicationEvidenceForAnotherUndo() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Files.writeString(root.resolve("a"), "old\r\n"); var service = service(root, storage, true);
        String id = interruptedApply(root, storage, service, List.of("a"));
        Files.writeString(root.resolve("a"), "new\r\n"); var inspection = service.recovery(id);
        service.reconcile(id, revision(MiniJson.asObject(inspection.get("proposal"))), (String) inspection.get("fingerprint"));
        try (var lease = new WorkspaceChangeJournal(storage).open(false)) {
            var entry = lease.read(id); var data = new LinkedHashMap<>(entry.data());
            var oldOperation = MiniJson.asArray(data.get("operation")); var inverse = new LinkedHashMap<>(MiniJson.asObject(oldOperation.getFirst()));
            Object before = inverse.get("before"), diskBefore = inverse.get("diskBefore");
            inverse.put("before", inverse.get("after")); inverse.put("after", before);
            inverse.put("diskBefore", inverse.get("diskAfter")); inverse.put("diskAfter", diskBefore);
            data.put("appliedOperation", oldOperation); data.put("operation", List.of(inverse)); data.put("operationKind", "UNDO"); data.put("status", "UNDOING");
            data.put("undoApprovedRevision", entry.revision());
            lease.append(id, entry.revision(), "UNDOING", data);
        }
        var undone = service.recovery(id); assertEquals("APPLIED", undone.get("verifiedOutcome"));
        var restored = service.reconcile(id, revision(MiniJson.asObject(undone.get("proposal"))), (String) undone.get("fingerprint"));
        service.undo(id, revision(restored));
        assertEquals("old\r\n", Files.readString(root.resolve("a")));
    }

    static String interruptedApply(Path root, Path storage, WorkspaceProposalService service, List<String> files) throws Exception {
        var queries = queries(root); List<WorkspaceProposalService.Input> inputs = new ArrayList<>();
        for (String file : files) inputs.add(new WorkspaceProposalService.Input(file, queries.snapshot(queries.workspaceId(), file).version(), "new\n"));
        String id = (String) service.propose("r", "task", "turn", inputs).get("id");
        try (var lease = new WorkspaceChangeJournal(storage).open(false)) {
            var entry = lease.read(id); var data = new LinkedHashMap<>(entry.data()); List<Object> operation = new ArrayList<>();
            for (Object item : MiniJson.asArray(data.get("changes"))) {
                var change = MiniJson.asObject(item); Path file = Path.of(java.net.URI.create((String) change.get("uri")));
                var raw = WorkspaceTextFile.capture(queries.workspaceId(), file, true);
                operation.add(Map.of("document", change.get("document"), "uri", change.get("uri"), "source", "DISK", "version", change.get("version"),
                    "before", change.get("before"), "after", change.get("desired"), "diskBefore", lease.put(raw.bytes()), "diskAfter", lease.put(raw.image().encode("new\n"))));
            }
            data.put("operation", operation); data.put("operationKind", "APPLY"); data.put("resourceSteps", List.of()); data.put("status", "APPLYING"); data.put("approvedRevision", 0);
            lease.append(id, entry.revision(), "APPLYING", data);
        }
        return id;
    }
    private static WorkspaceQueryService queries(Path root) { return new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()); }
    private WorkspaceProposalService service(Path root, Path storage, boolean apply) {
        var caps = apply ? Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY) : Set.of(AgentPolicy.Capability.READ);
        return new WorkspaceProposalService(new WorkspaceQueryService(root, new AgentPolicy(caps, List.of("**/README.md"), List.of(), true), WorkspaceDocumentSource.diskOnly()), null, storage, "human");
    }
    private static int revision(Map<String, Object> value) { return ((Number) value.get("revision")).intValue(); }
}
