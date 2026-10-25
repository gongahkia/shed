package shed;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceQueryServiceTest {
    @TempDir Path root;

    @Test void retainedTransactionContentsCannotBypassSourceExclusionsThroughArtifactNamesOrAliases() throws Exception {
        Path retained = Files.createDirectories(root.resolve(".shed-edit-backup-fixture")).resolve("original");
        Files.writeString(retained, "previous excluded contents");
        Path staged = Files.writeString(root.resolve(".shed-edit-fixture.tmp"), "pending contents");
        String workspace = WorkspacePaths.id(root.toRealPath());
        var service = new WorkspaceQueryService(root, AgentPolicy.readOnly(), () -> List.of(buffer(workspace, "retained-buffer", retained, "v1", "secret")));
        assertEquals(List.of(), service.listDocuments(workspace, 0, 100).get("documents"));
        for (Path path : List.of(retained, staged)) assertEquals(WorkspaceApiException.Code.PROTECTED_PATH,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, path.toString(), null, 0, 100)).code());
        try {
            Path alias = root.resolve("alias.txt"); Files.createSymbolicLink(alias, retained);
            assertEquals(WorkspaceApiException.Code.PROTECTED_PATH,
                assertThrows(WorkspaceApiException.class, () -> service.read(workspace, alias.toString(), null, 0, 100)).code());
        } catch (UnsupportedOperationException | java.io.IOException unavailable) { /* Filesystem link support is optional. */ }
        assertEquals("previous excluded contents", Files.readString(retained));
    }

    @Test void unsavedSnapshotWinsOverDiskAndIsCapturedOnceWithoutDependingOnActiveTab() throws Exception {
        Path file = Files.writeString(root.resolve("source.java"), "saved");
        String workspace = WorkspacePaths.id(root.toRealPath());
        AtomicReference<WorkspaceDocument> buffer = new AtomicReference<>(buffer(workspace, "buffer-1", file, "v1", "unsaved"));
        AtomicInteger captures = new AtomicInteger();
        WorkspaceQueryService service = new WorkspaceQueryService(root, AgentPolicy.readOnly(), () -> {
            captures.incrementAndGet(); return List.of(buffer.get(), buffer("another-workspace", "unrelated", file, "v1", "wrong"));
        });
        var result = service.read(workspace, file.toString(), null, 0, 100);
        assertEquals("unsaved", result.get("content"));
        assertEquals("EDITOR", result.get("source")); assertEquals(true, result.get("dirty"));
        assertEquals(1, captures.get()); assertEquals("saved", Files.readString(file));
        buffer.set(buffer(workspace, "buffer-1", file, "v2", "new human edit"));
        assertEquals(WorkspaceApiException.Code.STALE_VERSION,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, "buffer-1", "v1", 0, 100)).code());
        assertEquals("new human edit", service.read(workspace, "buffer-1", "v2", 0, 100).get("content"));
        assertEquals(1, ((List<?>) service.listDocuments(workspace, 0, 10).get("documents")).size());
    }

    @Test void permissionsAndWorkspaceIdentityFailBeforeReadingEditorState() {
        AtomicInteger captures = new AtomicInteger();
        WorkspaceDocumentSource source = () -> { captures.incrementAndGet(); return List.of(); };
        WorkspaceQueryService denied = new WorkspaceQueryService(root, new AgentPolicy(Set.of(), List.of(), List.of(), true), source);
        assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED,
            assertThrows(WorkspaceApiException.class, denied::info).code());
        WorkspaceQueryService scoped = new WorkspaceQueryService(root, AgentPolicy.readOnly(), source);
        assertEquals(WorkspaceApiException.Code.NOT_FOUND,
            assertThrows(WorkspaceApiException.class, () -> scoped.read("wrong", "file.txt", null, 0, 10)).code());
        assertEquals(0, captures.get());
    }

    @Test void diskReadsPreserveBytesAndReportEncodingNewlinesAndVersion() throws Exception {
        Path file = root.resolve("utf16.txt");
        byte[] text = "first\r\nsecond\r\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] original = new byte[text.length + 2]; original[0] = (byte) 0xff; original[1] = (byte) 0xfe;
        System.arraycopy(text, 0, original, 2, text.length); Files.write(file, original);
        var service = disk();
        var result = service.read(service.workspaceId(), file.toUri().toString(), null, 0, 100);
        assertEquals("first\nsecond\n", result.get("content")); assertEquals("UTF-16LE", result.get("encoding"));
        assertEquals("CRLF", result.get("lineEnding")); assertEquals(true, result.get("byteOrderMark"));
        assertEquals("DISK", result.get("source")); assertEquals(false, service.info().get("editorConnected"));
        assertArrayEquals(original, Files.readAllBytes(file));
        String version = (String) result.get("version"); Files.writeString(file, "changed");
        assertEquals(WorkspaceApiException.Code.STALE_VERSION,
            assertThrows(WorkspaceApiException.class, () -> service.read(service.workspaceId(), file.toString(), version, 0, 100)).code());
    }

    @Test void boundedReadsNeverSplitClustersOrInventCompleteResults() throws Exception {
        Path file = Files.writeString(root.resolve("unicode.txt"), "a👩‍💻b");
        var service = disk(); String workspace = service.workspaceId();
        var first = service.read(workspace, file.toString(), null, 0, 3);
        assertEquals("a", first.get("content")); assertEquals(1, first.get("nextOffset")); assertEquals(true, first.get("truncated"));
        var rest = service.read(workspace, file.toString(), (String) first.get("version"), 1, 100);
        assertEquals("👩‍💻b", rest.get("content")); assertEquals(false, rest.get("truncated"));
        assertEquals(WorkspaceApiException.Code.INVALID_REQUEST,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, file.toString(), null, 2, 10)).code());
        assertEquals(WorkspaceApiException.Code.LIMIT_EXCEEDED,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, file.toString(), null, 1, 1)).code());
    }

    @Test void scratchAndProjectionStatesAreExplicitAndExcludedBuffersAreNotListed() throws Exception {
        String workspace = WorkspacePaths.id(root.toRealPath());
        WorkspaceDocument scratch = buffer(workspace, "scratch-1", null, "v1", "draft");
        WorkspaceDocument projection = new WorkspaceDocument(workspace, "large", root.resolve("huge.txt").toUri().toString(),
            "p1", "preview", false, true, false, "UTF-8", "LF", false, WorkspaceDocument.Source.EDITOR);
        AgentPolicy policy = new AgentPolicy(Set.of(AgentPolicy.Capability.READ), List.of(), List.of("secret.txt"), true);
        var service = new WorkspaceQueryService(root, policy, () -> List.of(scratch, projection,
            buffer(workspace, "secret", root.resolve("secret.txt"), "v1", "sensitive")));
        var page = service.listDocuments(workspace, 0, 1);
        assertEquals(2, page.get("total")); assertEquals(true, page.get("truncated"));
        assertEquals("draft", service.read(workspace, "scratch-1", null, 0, 20).get("content"));
        assertEquals(WorkspaceApiException.Code.UNSUPPORTED,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, "large", null, 0, 20)).code());
        assertFalse(Files.exists(root.resolve("huge.txt")));
    }

    @Test void aliasesCannotEscapeRootOrBypassProtectedFileIdentity() throws Exception {
        Path target = Files.writeString(root.resolve("README.md"), "protected");
        Path alias = root.resolve("alias.txt");
        try { Files.createSymbolicLink(alias, target); }
        catch (UnsupportedOperationException | java.io.IOException denied) { assumeTrue(false, "Symlinks unavailable"); }
        var service = disk();
        assertEquals("protected", service.read(service.workspaceId(), alias.toString(), null, 0, 100).get("content"));
        AgentPolicy writes = new AgentPolicy(Set.of(AgentPolicy.Capability.APPLY), List.of("**/README.md"), List.of(), true);
        Path resolved = WorkspacePaths.resolve(root.toRealPath(), alias.toString());
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH,
            assertThrows(WorkspaceApiException.class, () -> writes.checkPath(root.toRealPath().relativize(resolved), AgentPolicy.Capability.APPLY)).code());
        Path outside = Files.createTempDirectory(root.getParent(), "outside-workspace-");
        try {
            Files.writeString(outside.resolve("secret"), "outside");
            Files.createSymbolicLink(root.resolve("escape"), outside);
            assertEquals(WorkspaceApiException.Code.OUTSIDE_WORKSPACE,
                assertThrows(WorkspaceApiException.class, () -> service.read(service.workspaceId(), "escape/secret", null, 0, 100)).code());
        } finally { Files.deleteIfExists(root.resolve("escape")); Files.deleteIfExists(outside.resolve("secret")); Files.deleteIfExists(outside); }
    }

    @Test void unsupportedAndAdministrativeFilesAreRejected() throws Exception {
        var service = disk(); String workspace = service.workspaceId();
        Files.write(root.resolve("binary"), new byte[]{0, 1, 2});
        Files.write(root.resolve("invalid"), new byte[]{(byte) 0xff, (byte) 0x80});
        for (String file : List.of("binary", "invalid")) assertEquals(WorkspaceApiException.Code.UNSUPPORTED,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, file, null, 0, 100)).code());
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, ".git/config", null, 0, 100)).code());
        assertEquals(WorkspaceApiException.Code.NOT_FOUND,
            assertThrows(WorkspaceApiException.class, () -> service.read(workspace, "missing.txt", null, 0, 100)).code());
    }

    private WorkspaceQueryService disk() { return new WorkspaceQueryService(root, AgentPolicy.readOnly(), WorkspaceDocumentSource.diskOnly()); }
    private static WorkspaceDocument buffer(String workspace, String id, Path path, String version, String text) {
        return new WorkspaceDocument(workspace, id, path == null ? null : path.toUri().toString(), version, text,
            true, false, true, "UTF-8", "LF", false, WorkspaceDocument.Source.EDITOR);
    }
}
