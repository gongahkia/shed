package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceProposalRestoreTest {
    @TempDir Path temporary;

    @Test void aWorkerCrashDuringApplyOrRecoveryCanBeRestoredWithExactBytesAndIdempotentRetries() throws Exception {
        for (String mode : List.of("apply", "restore")) {
            Path root = Files.createDirectory(temporary.resolve(mode)), storage = temporary.resolve(mode + "-history");
            Path file = root.resolve("source");
            byte[] before = TextFileCodec.encodeRaw("original\r\nmiddle\n", "UTF-16LE", true); Files.write(file, before);
            crash(root, storage, mode);
            assertFalse(Files.exists(file));
            var service = service(root, storage, null);
            var inspection = service.recovery(WorkspaceEditCrashFixture.ID);
            assertNull(inspection.get("verifiedOutcome")); assertEquals(true, inspection.get("restoreAvailable"));
            int revision = revision(inspection); String proof = (String) inspection.get("fingerprint");
            var outcome = service.restore(WorkspaceEditCrashFixture.ID, revision, proof);
            assertEquals("FAILED", outcome.get("status")); assertArrayEquals(before, Files.readAllBytes(file));
            String version = WorkspaceTextFile.read(WorkspacePaths.id(root.toRealPath()), file).version();
            assertEquals(outcome, service.restore(WorkspaceEditCrashFixture.ID, revision, proof));
            assertEquals(version, WorkspaceTextFile.read(WorkspacePaths.id(root.toRealPath()), file).version());
            try (var lease = new WorkspaceChangeJournal(storage).open(false)) {
                var data = lease.read(WorkspaceEditCrashFixture.ID).data();
                assertTrue(MiniJson.asArray(data.get("restoreSteps")).stream().anyMatch(item -> "RENAME".equals(MiniJson.asObject(item).get("kind"))));
            }
        }
    }

    @Test void changedOriginalsStaleEvidenceAndPolicyRestrictionsPreventRestorationWithoutLosingData() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Files.writeString(root.resolve("source"), "original"); crash(root, storage, "apply");
        var service = service(root, storage, null); var inspection = service.recovery(WorkspaceEditCrashFixture.ID);
        var restrictedQueries = new WorkspaceQueryService(root, new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.APPLY),
            List.of("source"), List.of(), true), WorkspaceDocumentSource.diskOnly());
        var restricted = new WorkspaceProposalService(restrictedQueries, null, storage, "human");
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class, () -> restricted.restore(
            WorkspaceEditCrashFixture.ID, revision(inspection), (String) inspection.get("fingerprint"))).code());
        var steps = MiniJson.asArray(inspection.get("resourceSteps"));
        var backup = MiniJson.asObject(steps.stream().filter(item -> "BACKUP".equals(MiniJson.asObject(item).get("kind"))).findFirst().orElseThrow());
        Path original = Path.of(java.net.URI.create((String) backup.get("target"))); Files.writeString(original, "newer human original");
        assertEquals(WorkspaceApiException.Code.STALE_VERSION, assertThrows(WorkspaceApiException.class, () -> service.restore(
            WorkspaceEditCrashFixture.ID, revision(inspection), (String) inspection.get("fingerprint"))).code());
        var changed = service.recovery(WorkspaceEditCrashFixture.ID); assertEquals(false, changed.get("restoreAvailable"));
        assertEquals(WorkspaceApiException.Code.CONFLICT, assertThrows(WorkspaceApiException.class, () -> service.restore(
            WorkspaceEditCrashFixture.ID, revision(changed), (String) changed.get("fingerprint"))).code());
        assertEquals("newer human original", Files.readString(original)); assertFalse(Files.exists(root.resolve("source")));
    }

    @Test void mixedDiskAndOpenDocumentsRestoreAtomicallyAndRetainUsableSaveBindings() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Path disk = Files.writeString(root.resolve("disk"), "old\r\n"), live = Files.writeString(root.resolve("live"), "old\r\n");
        String id = WorkspaceProposalRecoveryTest.interruptedApply(root, storage, service(root, storage, null), List.of("disk", "live"));
        Files.writeString(disk, "new\r\n"); Files.writeString(live, "new\r\n");
        FileBuffer buffer = new FileBuffer(live.toFile());
        try (var documents = new EditorWorkspaceDocuments(() -> List.of(buffer), () -> List.of(root), () -> root, root)) {
            WorkspaceDocumentEditor failing = new WorkspaceDocumentEditor() {
                public List<WorkspaceDocument> snapshots() { return documents.snapshots(); }
                public List<WorkspaceDocument> applyTexts(List<TextChange> changes) { throw new WorkspaceApiException(WorkspaceApiException.Code.IO_ERROR, "fixture rejects batch"); }
            };
            var rejected = service(root, storage, failing); var preview = rejected.recovery(id);
            assertTrue((Boolean) preview.get("restoreAvailable"));
            assertThrows(WorkspaceApiException.class, () -> rejected.restore(id, revision(preview), (String) preview.get("fingerprint")));
            assertEquals("new\r\n", Files.readString(disk)); assertEquals("new\r\n", Files.readString(live)); assertEquals("new\n", buffer.getContent());
            var reviewer = service(root, storage, documents); var retry = reviewer.recovery(id);
            assertTrue((Boolean) retry.get("restoreAvailable"));
            reviewer.restore(id, revision(retry), (String) retry.get("fingerprint"));
            assertEquals("old\r\n", Files.readString(disk)); assertEquals("old\r\n", Files.readString(live));
            assertEquals("old\n", buffer.getContent()); assertFalse(buffer.isModified());
            assertEquals(FileBuffer.ExternalFileState.UNCHANGED, buffer.getExternalFileState());
            SwingUtilities.invokeAndWait(() -> buffer.replaceContentUndoably("human\n")); buffer.save();
            assertEquals("human\r\n", Files.readString(live));
        }
    }

    @Test void conflictingHumanTextIsPreservedWhileTheInspectionExplainsWhyRestoreIsUnavailable() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")), storage = temporary.resolve("history");
        Path file = Files.writeString(root.resolve("source"), "old\r\n"); var service = service(root, storage, null);
        String id = WorkspaceProposalRecoveryTest.interruptedApply(root, storage, service, List.of("source"));
        Files.writeString(file, "human\r\n"); var inspection = service.recovery(id);
        assertEquals(false, inspection.get("restoreAvailable"));
        assertNotNull(MiniJson.asObject(MiniJson.asArray(inspection.get("documents")).getFirst()).get("restoreReason"));
        assertThrows(WorkspaceApiException.class, () -> service.restore(id, revision(inspection), (String) inspection.get("fingerprint")));
        assertEquals("human\r\n", Files.readString(file));
    }

    static void crash(Path root, Path storage, String mode) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            "shed.WorkspaceEditCrashFixture", root.toString(), storage.toString(), mode).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(77, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static int revision(Map<String, Object> inspection) { return ((Number) MiniJson.asObject(inspection.get("proposal")).get("revision")).intValue(); }
    private static WorkspaceProposalService service(Path root, Path storage, WorkspaceDocumentEditor documents) {
        var policy = new AgentPolicy(Set.of(AgentPolicy.Capability.READ, AgentPolicy.Capability.PROPOSE, AgentPolicy.Capability.APPLY), List.of("**/README.md"), List.of(), true);
        return new WorkspaceProposalService(new WorkspaceQueryService(root, policy, documents == null ? WorkspaceDocumentSource.diskOnly() : documents), documents, storage, "human");
    }
}
